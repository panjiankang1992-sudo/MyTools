package com.yuyutian.mytools.toolbox.service.translation;

import com.yuyutian.mytools.toolbox.common.BusinessException;
import com.yuyutian.mytools.toolbox.common.ErrorCode;
import com.yuyutian.mytools.toolbox.config.TranslationProperties;
import com.yuyutian.mytools.toolbox.model.GuestSession;
import com.yuyutian.mytools.toolbox.model.Subject;
import com.yuyutian.mytools.toolbox.model.TranslationRequest;
import com.yuyutian.mytools.toolbox.model.TranslationResult;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** 翻译服务：本地优先、成功缓存、有界外部并发和逐条失败，不污染用户输入。 */
@Service
public class TranslationService {

    private record Cached(TranslationDictionary.Pair pair, Instant expiresAt, long snapshotVersion) {
    }

    private final TranslationDictionary dictionary;
    private final TranslationProvider provider;
    private final TranslationQuota quota;
    private final TranslationProperties properties;
    private final Clock clock;
    private final ThreadPoolExecutor executor;
    private final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * 创建翻译服务。
     *
     * @param dictionary 精确本地词典
     * @param provider 固定外部服务
     * @param quota 持久化匿名预算
     * @param properties 容量和时间上限
     * @param clock 统一时钟
     */
    public TranslationService(TranslationDictionary dictionary, TranslationProvider provider, TranslationQuota quota,
                              TranslationProperties properties, Clock clock) {
        this.dictionary = dictionary;
        this.provider = provider;
        this.quota = quota;
        this.properties = properties;
        this.clock = clock;
        executor = new ThreadPoolExecutor(properties.concurrency(), properties.concurrency(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(40), runnable -> {
                    Thread thread = new Thread(runnable, "toolbox-translation");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 翻译经过匿名鉴权的请求；输入非法时整批拒绝，上游问题只影响对应条目。
     *
     * @param guest 已验证匿名访客
     * @param request 请求正文
     * @return 保持原身份与顺序的逐条结果
     */
    public TranslationResult translate(GuestSession guest, TranslationRequest request) {
        // 访客用带命名空间的额度主体，与管理员（`admin:…`）**各自独立计额度**。
        return translate("guest:" + guest.guestId(), request);
    }

    /**
     * 翻译一批条目，并**按给定额度主体**扣减额度。
     *
     * 这一层把"额度主体"参数化，是为了让管理端有自己的额度（F06）——
     * 此前管理端只能走公开访客入口，消耗的是访客额度。
     *
     * @param quotaScope 完整额度主体（如 `guest:xxx` / `admin:xxx`）
     * @param request 请求正文
     * @return 保持原身份与顺序的逐条结果
     */
    public TranslationResult translate(String quotaScope, TranslationRequest request) {
        validate(request);
        // 语文只返回教材已校对的读音；未命中由前端本机拼音库继续处理，不消耗外部翻译额度。
        if (request.subject() == Subject.CHINESE) {
            return new TranslationResult(request.items().stream().map(item -> dictionary.findPinyin(item.text())
                    .map(segments -> new TranslationResult.Item(item.id(), item.text(), null, null, "translated", segments))
                    .orElseGet(() -> new TranslationResult.Item(item.id(), item.text(), null, null, "failed"))).toList());
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.batchTimeoutMillis());
        Map<String, Future<TranslationDictionary.Pair>> pending = new LinkedHashMap<>();
        Map<String, TranslationDictionary.Pair> resolved = new LinkedHashMap<>();
        for (var item : request.items()) {
            String key = requestKey(item.text());
            if (resolved.containsKey(key) || pending.containsKey(key)) {
                continue;
            }
            TranslationDictionary.Pair known = dictionary.find(item.text()).orElse(null);
            // 混合双语原文可能已经包含英文答案，不能原样作为中文题面。
            if (containsHan(item.text()) && containsLatin(item.text())
                    && (known == null || containsLatin(known.meaning()) || containsHan(known.englishText()))) {
                resolved.put(key, null);
                continue;
            }
            if (known == null && !containsHan(item.text()) && !containsLatin(item.text())) {
                // 未知数字、表情和其它文字不能伪装成有效的英文词条。
                resolved.put(key, null);
                continue;
            }
            if (known == null) {
                known = cached(key);
            }
            if (known != null) {
                // 本地词库和缓存同样不能让中文题面夹带英文答案。
                resolved.put(key, safePair(known) ? known : null);
                continue;
            }
            try {
                pending.put(key, executor.submit(() -> external(item.text().strip(), key, quotaScope, deadline)));
            } catch (RejectedExecutionException ignored) {
                // 队列已满的条目返回失败，可由用户稍后单独重试。
                resolved.put(key, null);
            }
        }
        try {
            for (var entry : pending.entrySet()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    resolved.put(entry.getKey(), entry.getValue().get(remaining, TimeUnit.NANOSECONDS));
                } catch (ExecutionException | TimeoutException ignored) {
                    // 不返回上游正文、地址或堆栈，其他条目的有效结果仍可返回。
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            // 整批离开后不继续执行队列中的旧输入，取消已发请求并清理取消任务。
            for (var entry : pending.entrySet()) {
                var future = entry.getValue();
                if (!future.isDone()) {
                    future.cancel(true);
                } else if (!resolved.containsKey(entry.getKey()) && !future.isCancelled()) {
                    try {
                        // 前一项耗尽等待时间时，也保留其他已经完成的有效结果。
                        resolved.put(entry.getKey(), future.get());
                    } catch (ExecutionException ignored) {
                        // 对应项仍按失败返回。
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            executor.purge();
        }
        List<TranslationResult.Item> results = new ArrayList<>();
        for (var item : request.items()) {
            var pair = resolved.get(requestKey(item.text()));
            results.add(pair == null
                    ? new TranslationResult.Item(item.id(), item.text(), null, null, "failed")
                    : new TranslationResult.Item(item.id(), item.text(),
                            containsHan(item.text()) || !containsLatin(item.text()) ? pair.englishText() : item.text().strip(),
                            pair.meaning(), "translated"));
        }
        return new TranslationResult(List.copyOf(results));
    }

    /** 关闭受限线程池，取消服务停止时尚未发送或完成的任务。 */
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    private TranslationDictionary.Pair external(String text, String key, String quotaScope, long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
            return null;
        }
        // 排队期间教材可能刚刚发布，再次本地检索，避免已有本地答案仍发送外部请求。
        TranslationDictionary.Pair known = dictionary.find(text).orElse(null);
        if (known == null) {
            known = cached(key);
        }
        if (known != null) {
            return known;
        }
        // 在**发起外部请求之前**记下代际：若请求途中目录换代，这次的结果属于旧代，
        // 必须按旧代写入，否则会把旧内容当成新代结果缓存下来（"迟到的旧请求污染新代"）。
        long generation = dictionary.snapshotVersion();
        try {
            // 在真实发送前扣减；失败不缓存、不伪造结果，也不退还可能已被上游计入的额度。
            if (!quota.tryConsume(quotaScope, text.codePointCount(0, text.length()))) {
                return null;
            }
            // 数据库等待可能跨过整批截止；取得额度后必须再次确认仍允许发送。
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
                return null;
            }
            boolean chineseSource = containsHan(text);
            String translated = provider.translate(text, chineseSource);
            if (translated == null || translated.isBlank() || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
                return null;
            }
            var pair = chineseSource ? new TranslationDictionary.Pair(translated, text)
                    : new TranslationDictionary.Pair(text, translated);
            if (!safePair(pair)) {
                // 上游可能返回双语解释；失败项不能进入缓存或学生卷题面。
                return null;
            }
            remember(key, pair, generation);
            return pair;
        } catch (IOException | DataAccessException ignored) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private synchronized TranslationDictionary.Pair cached(String key) {
        Cached result = cache.get(key);
        if (result == null) {
            return null;
        }
        // 目录换代后旧条目不能再被命中：否则用户改了释义并发布，
        // 翻译仍会返回**换代前**的结果，直到 TTL 到期为止。
        if (result.snapshotVersion() != dictionary.snapshotVersion()) {
            cache.remove(key);
            return null;
        }
        if (!clock.instant().isBefore(result.expiresAt())) {
            cache.remove(key);
            return null;
        }
        return result.pair();
    }

    private synchronized void remember(String key, TranslationDictionary.Pair pair, long snapshotVersion) {
        cache.put(key, new Cached(pair, clock.instant().plusSeconds(properties.cacheTtlSeconds()), snapshotVersion));
        // 即使遇到大量不同输入，缓存也不会无界增长。
        while (cache.size() > properties.cacheEntries()) {
            cache.remove(cache.keySet().iterator().next());
        }
    }

    private static boolean containsHan(String text) {
        return text.codePoints().anyMatch(point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN);
    }

    private static boolean containsLatin(String text) {
        return text.codePoints().anyMatch(point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.LATIN);
    }

    private static boolean safePair(TranslationDictionary.Pair pair) {
        return pair.englishText() != null && pair.meaning() != null
                && containsLatin(pair.englishText()) && !containsHan(pair.englishText())
                && containsHan(pair.meaning()) && !containsLatin(pair.meaning());
    }

    private static String requestKey(String text) {
        // 未知词的大小写可能改变词义，例如 US 与 us，不能共用外部翻译缓存。
        return text.strip().replaceAll("\\s+", " ");
    }

    private static void validate(TranslationRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw BusinessException.invalid("items", "required");
        }
        if (request.items().size() > 20) {
            throw new BusinessException(ErrorCode.TOOLBOX_005, Map.of("limitName", "maxTranslationItems", "limit", 20));
        }
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < request.items().size(); index++) {
            var item = request.items().get(index);
            String field = "items[" + index + "]";
            if (item == null || item.id() == null || item.id().isBlank() || item.id().length() > 128
                    || item.id().codePoints().anyMatch(Character::isISOControl) || !ids.add(item.id())) {
                throw BusinessException.invalid(field + ".id", "invalidOrDuplicate");
            }
            if (item.text() == null || item.text().isBlank() || item.text().codePoints().anyMatch(point ->
                    Character.isISOControl(point) || Character.getType(point) == Character.SURROGATE
                            || Character.getType(point) == Character.FORMAT)) {
                throw BusinessException.invalid(field + ".text", "requiredOrInvalid");
            }
            if (item.text().codePointCount(0, item.text().length()) > 120
                    || item.text().getBytes(StandardCharsets.UTF_8).length > 500) {
                throw new BusinessException(ErrorCode.TOOLBOX_005,
                        Map.of("field", field + ".text", "limitName", "maxTranslationText", "maxCodepoints", 120, "maxBytes", 500));
            }
        }
    }
}
