package com.yuyutian.mytools.toolbox.service.translation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.config.TranslationProperties;
import com.yuyutian.mytools.toolbox.model.GuestSession;
import com.yuyutian.mytools.toolbox.model.TranslationRequest;
import com.yuyutian.mytools.toolbox.model.TranslationResult;
import com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** 翻译编排回归：真实本地词典、缓存和受限线程池，仅替换供应商与额度边界。 */
class TranslationServiceTest {

    private static final GuestSession GUEST = new GuestSession(1, "test-guest", Instant.parse("2030-01-01T00:00:00Z"), "csrf");
    private TranslationDictionary dictionary;
    private FakeProvider provider;
    private FakeQuota quota;
    private AdjustableClock clock;
    private TranslationService service;

    @BeforeEach
    void setUp() {
        dictionary = new TranslationDictionary(new CurriculumCatalog(Path.of("target/test-content-empty"), new ObjectMapper()));
        provider = new FakeProvider();
        quota = new FakeQuota();
        clock = new AdjustableClock(Instant.parse("2026-10-04T10:00:00Z"));
        service = createService(2, 60, 25_000);
    }

    @AfterEach
    void closeService() {
        service.close();
    }

    @Test
    void returnsDictionaryMatchWithoutExternalQuota() {
        var result = service.translate(GUEST, request("\u661f\u671f\u4e00"));

        assertThat(result.items()).containsExactly(new TranslationResult.Item(
                "item-0", "\u661f\u671f\u4e00", "Monday", "\u661f\u671f\u4e00", "translated"));
        assertThat(provider.calls).isEmpty();
        assertThat(quota.calls).isEmpty();
    }

    @Test
    void reusesSuccessfulCacheWithoutChargingAgainAndPreservesCallerText() {
        provider.answer = (text, chinese) -> "\u7a00\u6709\u6c34\u679c";

        service.translate(GUEST, request("rare fruit"));
        var result = service.translate(GUEST, request("  rare   fruit  "));

        assertThat(result.items()).containsExactly(new TranslationResult.Item(
                "item-0", "  rare   fruit  ", "rare   fruit", "\u7a00\u6709\u6c34\u679c", "translated"));
        assertThat(provider.calls).containsExactly(new Call("rare fruit", false));
        assertThat(quota.calls).containsExactly(new Debit("guest:" + GUEST.guestId(), 10));
    }

    @Test
    void adminQuotaScopeIsChargedVerbatimAndNeverMixedWithGuestScope() {
        // F06：管理端要有**自己的**额度，不能消耗访客的。
        //
        // 这条测试真正要挡住的是一个很隐蔽的错法：额度类**曾经自己拼前缀**
        // （`"guest:" + guestId`）。如果只把入参改成 `"admin:…"` 而不改那一行，
        // 落库会变成 `guest:admin:…` —— **接口通了、测试绿了，管理员和访客仍然共用额度**。
        // 所以断言"传进去什么，就按什么扣"，而不是"扣费发生了"。
        provider.answer = (text, chinese) -> "\u82f9\u679c";

        service.translate("admin:u-1", request("apple"));

        assertThat(quota.calls).containsExactly(new Debit("admin:u-1", 5));
        // 与访客主体**互不影响**：不能出现任何 guest: 开头的扣费。
        assertThat(quota.calls).noneMatch(call -> call.guestId().startsWith("guest:"));
    }

    @Test
    void deduplicatesEquivalentBatchTextsButRetainsEveryItemIdentity() {
        provider.answer = (text, chinese) -> "\u7a00\u6709\u6c34\u679c";

        var result = service.translate(GUEST, request("rare fruit", "  rare fruit  ", "rare  fruit"));

        assertThat(result.items()).extracting(TranslationResult.Item::id).containsExactly("item-0", "item-1", "item-2");
        assertThat(result.items()).extracting(TranslationResult.Item::sourceText).containsExactly("rare fruit", "  rare fruit  ", "rare  fruit");
        assertThat(result.items()).extracting(TranslationResult.Item::status).containsOnly("translated");
        assertThat(provider.calls).containsExactly(new Call("rare fruit", false));
        assertThat(quota.calls).containsExactly(new Debit("guest:" + GUEST.guestId(), 10));
    }

    @Test
    void keepsCaseSensitiveWordsSeparateInCacheAndBatch() {
        provider.answer = (text, chinese) -> text.equals("US") ? "\u7f8e\u56fd" : "\u6211\u4eec";

        var result = service.translate(GUEST, request("US", "us"));
        var cached = service.translate(GUEST, request("US", "us"));

        assertThat(result.items()).extracting(TranslationResult.Item::meaning).containsExactly("\u7f8e\u56fd", "\u6211\u4eec");
        assertThat(cached).isEqualTo(result);
        assertThat(provider.calls).containsExactlyInAnyOrder(new Call("US", false), new Call("us", false));
        assertThat(quota.calls).containsExactly(new Debit("guest:" + GUEST.guestId(), 2), new Debit("guest:" + GUEST.guestId(), 2));
    }

    @Test
    void retriesProviderFailuresWithoutCachingOrRefundingAttemptedQuota() {
        AtomicInteger attempts = new AtomicInteger();
        provider.answer = (text, chinese) -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IOException("private error");
            }
            return "\u91cd\u8bd5\u77ed\u8bed";
        };

        var failed = service.translate(GUEST, request("retry phrase"));
        var recovered = service.translate(GUEST, request("retry phrase"));

        assertFailed(failed.items().get(0), "retry phrase");
        assertThat(recovered.items().get(0).status()).isEqualTo("translated");
        assertThat(recovered.items().get(0).meaning()).isEqualTo("\u91cd\u8bd5\u77ed\u8bed");
        assertThat(provider.calls).containsExactly(new Call("retry phrase", false), new Call("retry phrase", false));
        assertThat(quota.calls).containsExactly(new Debit("guest:" + GUEST.guestId(), 12), new Debit("guest:" + GUEST.guestId(), 12));
    }

    @Test
    void doesNotCacheEmptyProviderResults() {
        AtomicInteger attempts = new AtomicInteger();
        provider.answer = (text, chinese) -> switch (attempts.getAndIncrement()) {
            case 0 -> " ";
            case 1 -> null;
            default -> "\u6709\u6548\u91ca\u4e49";
        };

        assertFailed(service.translate(GUEST, request("empty phrase")).items().get(0), "empty phrase");
        assertFailed(service.translate(GUEST, request("empty phrase")).items().get(0), "empty phrase");
        assertThat(service.translate(GUEST, request("empty phrase")).items().get(0).status()).isEqualTo("translated");
        assertThat(provider.calls).containsExactly(
                new Call("empty phrase", false), new Call("empty phrase", false), new Call("empty phrase", false));
        assertThat(quota.calls).containsExactly(
                new Debit("guest:" + GUEST.guestId(), 12), new Debit("guest:" + GUEST.guestId(), 12), new Debit("guest:" + GUEST.guestId(), 12));
    }

    @Test
    void rejectsAndDoesNotCacheMeaningThatExposesEnglishAnswer() {
        provider.answer = (text, chinese) -> "butterfly\uff08\u8774\u8776\uff09";

        assertFailed(service.translate(GUEST, request("butterfly")).items().get(0), "butterfly");
        assertFailed(service.translate(GUEST, request("butterfly")).items().get(0), "butterfly");

        assertThat(provider.calls).containsExactly(new Call("butterfly", false), new Call("butterfly", false));
        assertThat(quota.calls).containsExactly(new Debit("guest:" + GUEST.guestId(), 9), new Debit("guest:" + GUEST.guestId(), 9));
    }

    @Test
    void doesNotCallProviderOrCacheFailureWhenQuotaIsExhausted() {
        quota.allow = false;
        provider.answer = (text, chinese) -> "\u91cd\u8bd5";

        assertFailed(service.translate(GUEST, request("quota retry")).items().get(0), "quota retry");
        assertThat(provider.calls).isEmpty();
        quota.allow = true;
        assertThat(service.translate(GUEST, request("quota retry")).items().get(0).status()).isEqualTo("translated");
        assertThat(provider.calls).containsExactly(new Call("quota retry", false));
        assertThat(quota.calls).containsExactly(new Debit("guest:" + GUEST.guestId(), 11), new Debit("guest:" + GUEST.guestId(), 11));
    }

    @Test
    void failsClosedWhenPersistentQuotaIsUnavailable() {
        quota.failure = new DataAccessResourceFailureException("private database error");

        assertFailed(service.translate(GUEST, request("database failure")).items().get(0), "database failure");

        assertThat(provider.calls).isEmpty();
    }

    @Test
    void evictsLeastRecentlyUsedSuccessfulEntryAtCapacity() {
        provider.answer = (text, chinese) -> "\u91ca\u4e49";

        service.translate(GUEST, request("first"));
        service.translate(GUEST, request("second"));
        service.translate(GUEST, request("first"));
        service.translate(GUEST, request("third"));
        service.translate(GUEST, request("second"));

        assertThat(provider.calls).containsExactly(
                new Call("first", false), new Call("second", false), new Call("third", false), new Call("second", false));
    }

    @Test
    void expiresCachedResultExactlyAtTtlWithoutRefreshingOnRead() {
        AtomicInteger attempts = new AtomicInteger();
        provider.answer = (text, chinese) -> attempts.getAndIncrement() == 0 ? "\u65e7\u91ca\u4e49" : "\u65b0\u91ca\u4e49";
        service.translate(GUEST, request("timed phrase"));

        clock.advanceSeconds(59);
        assertThat(service.translate(GUEST, request("timed phrase")).items().get(0).meaning()).isEqualTo("\u65e7\u91ca\u4e49");
        clock.advanceSeconds(1);
        assertThat(service.translate(GUEST, request("timed phrase")).items().get(0).meaning()).isEqualTo("\u65b0\u91ca\u4e49");

        assertThat(provider.calls).containsExactly(new Call("timed phrase", false), new Call("timed phrase", false));
        assertThat(quota.calls).containsExactly(new Debit("guest:" + GUEST.guestId(), 12), new Debit("guest:" + GUEST.guestId(), 12));
    }

    @Test
    void retainsChineseSourceAsMeaningAndChargesUnicodeCodePoints() {
        String source = "\uD842\uDFB7\u6c34";
        provider.answer = (text, chinese) -> "rare water";

        var result = service.translate(GUEST, request("  " + source + "  "));

        assertThat(result.items()).containsExactly(new TranslationResult.Item(
                "item-0", "  " + source + "  ", "rare water", source, "translated"));
        assertThat(provider.calls).containsExactly(new Call(source, true));
        assertThat(quota.calls).containsExactly(new Debit("guest:" + GUEST.guestId(), 2));
    }

    @Test
    void blocksUnmatchedMixedLanguageTextButKeepsNumericDictionaryAliases() {
        var result = service.translate(GUEST, request("\u661f\u671f\u4e00 Monday", "1", "2\u6708"));

        assertFailed(result.items().get(0), "\u661f\u671f\u4e00 Monday");
        assertThat(result.items().get(1).englishText()).isEqualTo("one");
        assertThat(result.items().get(1).meaning()).isEqualTo("\u4e00");
        assertThat(result.items().get(2).englishText()).isEqualTo("February");
        assertThat(provider.calls).isEmpty();
        assertThat(quota.calls).isEmpty();
    }

    @Test
    void cancelsTimedOutCallsAndKeepsAlreadyCompletedItems() throws Exception {
        service.close();
        service = createService(2, 60, 500);
        CountDownLatch fastFinished = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        provider.answer = (text, chinese) -> {
            if (text.equals("slow phrase")) {
                try {
                    // 快项完成后继续阻塞，直到整批超时取消，避免依赖固定睡眠。
                    fastFinished.await();
                    new CountDownLatch(1).await();
                    return "unreachable";
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    throw exception;
                }
            }
            fastFinished.countDown();
            return "\u5feb\u901f\u77ed\u8bed";
        };

        var result = service.translate(GUEST, request("slow phrase", "fast phrase"));

        assertFailed(result.items().get(0), "slow phrase");
        assertThat(result.items().get(1).status()).isEqualTo("translated");
        assertThat(result.items().get(1).meaning()).isEqualTo("\u5feb\u901f\u77ed\u8bed");
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void neverCallsProviderAfterQuotaWaitOutlivesCancelledBatch() {
        service.close();
        service = new TranslationService(dictionary, provider, quota,
                new TranslationProperties(4500, 1000, 1, 2, 60, 100), clock);
        CountDownLatch quotaStarted = new CountDownLatch(1);
        CountDownLatch releaseQuota = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        quota.beforeConsume = () -> {
            if (attempts.getAndIncrement() != 0) {
                return;
            }
            quotaStarted.countDown();
            // 模拟 JDBC 已收到取消，但驱动继续等待并吞掉中断的情况。
            while (true) {
                try {
                    releaseQuota.await();
                    return;
                } catch (InterruptedException ignored) {
                    // 有意清除中断，验证 deadline 检查仍能阻止迟到的外部请求。
                }
            }
        };
        provider.answer = (text, chinese) -> "\u6709\u6548\u91ca\u4e49";
        try {
            var timedOut = service.translate(GUEST, request("cancelled phrase"));
            assertThat(quotaStarted.getCount()).isZero();
            assertFailed(timedOut.items().get(0), "cancelled phrase");
            assertThat(provider.calls).isEmpty();

            releaseQuota.countDown();
            // 单工作线程完成下一项，证明迟到的旧任务已经离开外部调用路径。
            var next = service.translate(GUEST, request("after timeout"));
            assertThat(next.items().get(0).status()).isEqualTo("translated");
            assertThat(provider.calls).containsExactly(new Call("after timeout", false));
        } finally {
            releaseQuota.countDown();
        }
    }

    private TranslationService createService(int cacheEntries, long ttlSeconds, long timeoutMillis) {
        return new TranslationService(dictionary, provider, quota,
                new TranslationProperties(4500, 1000, 2, cacheEntries, ttlSeconds, timeoutMillis), clock);
    }

    private static TranslationRequest request(String... texts) {
        var items = java.util.stream.IntStream.range(0, texts.length)
                .mapToObj(index -> new TranslationRequest.Item("item-" + index, texts[index])).toList();
        return new TranslationRequest(items);
    }

    private static void assertFailed(TranslationResult.Item result, String source) {
        assertThat(result.sourceText()).isEqualTo(source);
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.englishText()).isNull();
        assertThat(result.meaning()).isNull();
    }

    /** 测试供应商的可控响应，允许精确模拟失败与中断。 */
    @FunctionalInterface
    private interface Answer {
        String translate(String text, boolean chineseSource) throws IOException, InterruptedException;
    }

    /** 外部请求记录。 */
    private record Call(String text, boolean chineseSource) {
    }

    /** 字符预算申请记录。 */
    private record Debit(String guestId, int characters) {
    }

    /** 无网络、无动态代理的供应商替身。 */
    private static final class FakeProvider implements TranslationProvider {
        private final List<Call> calls = new CopyOnWriteArrayList<>();
        private volatile Answer answer = (text, chinese) -> {
            throw new IOException("Unexpected provider request");
        };

        /** 记录调用后执行当前测试的响应策略。 */
        @Override
        public String translate(String text, boolean chineseSource) throws IOException, InterruptedException {
            calls.add(new Call(text, chineseSource));
            return answer.translate(text, chineseSource);
        }
    }

    /** 用量持久化另有真实 H2 测试，此处只控制允许或拒绝的边界。 */
    private static final class FakeQuota extends TranslationQuota {
        private final List<Debit> calls = new CopyOnWriteArrayList<>();
        private volatile boolean allow = true;
        private volatile RuntimeException failure;
        private volatile Runnable beforeConsume = () -> { };

        private FakeQuota() {
            super(null, null, null, null);
        }

        /** 不触碰数据库，只记录申请并返回测试指定结果。 */
        @Override
        public boolean tryConsume(String guestId, int characters) {
            calls.add(new Debit(guestId, characters));
            beforeConsume.run();
            if (failure != null) {
                throw failure;
            }
            return allow;
        }
    }

    /** 可前进的 UTC 时钟，准确验证过期边界而不等待真实时间。 */
    private static final class AdjustableClock extends Clock {
        private volatile Instant current;

        private AdjustableClock(Instant current) {
            this.current = current;
        }

        private void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }

        /** 返回固定的 UTC 时区。 */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /** 将当前测试时刻投影到要求的时区。 */
        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(current, zone);
        }

        /** 返回当前受控时刻。 */
        @Override
        public Instant instant() {
            return current;
        }
    }
}
