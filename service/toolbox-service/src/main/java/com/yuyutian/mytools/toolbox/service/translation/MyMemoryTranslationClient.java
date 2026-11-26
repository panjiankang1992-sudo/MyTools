package com.yuyutian.mytools.toolbox.service.translation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** 固定访问公开翻译服务，并在接收和解析两个边界限制响应。 */
@Component
public final class MyMemoryTranslationClient implements TranslationProvider {

    private static final String ENDPOINT = "https://api.mymemory.translated.net/get";
    private static final int MAXIMUM_QUERY_BYTES = 500;
    private static final int MAXIMUM_RESPONSE_BYTES = 65_536;
    private static final int MAXIMUM_TRANSLATION_CODE_POINTS = 500;
    private static final double MINIMUM_FALLBACK_MATCH = 0.99;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(4);
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    /** 创建固定地址、禁止重定向且连接等待不超过两秒的客户端。 */
    @Autowired
    public MyMemoryTranslationClient(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    MyMemoryTranslationClient(ObjectMapper objectMapper, HttpClient client) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.client = Objects.requireNonNull(client);
    }

    /** 翻译一个短词条；失败只返回固定原因，不携带请求内容或上游错误正文。 */
    @Override
    public String translate(String text, boolean chineseSource) throws IOException, InterruptedException {
        // 请求长度按服务的 UTF-8 字节契约限制，不能用字符数替代。
        if (text == null || text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_QUERY_BYTES) {
            throw new IOException("Invalid translation input");
        }
        String languagePair = chineseSource ? "zh-CN|en" : "en|zh-CN";
        URI uri = URI.create(ENDPOINT + "?q=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
                + "&langpair=" + URLEncoder.encode(languagePair, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(RESPONSE_TIMEOUT)
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .GET()
                .build();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            // 等待的是完整受限响应体，避免只收到响应头便解除总等待上限。
            pending = client.sendAsync(request, response -> new LimitedBodySubscriber());
            HttpResponse<byte[]> response = pending.get(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return parse(response, text, chineseSource);
        } catch (TimeoutException | HttpTimeoutException exception) {
            throw new IOException("Translation request timed out");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof HttpTimeoutException) {
                throw new IOException("Translation request timed out");
            }
            throw new IOException("Translation request failed");
        } catch (InterruptedException exception) {
            // 取消仍在读取的请求，并保留调用方的中断处理能力。
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Translation request failed");
        } finally {
            if (pending != null && !pending.isDone()) {
                pending.cancel(true);
            }
        }
    }

    private String parse(HttpResponse<byte[]> response, String source, boolean chineseSource) throws IOException {
        if (response.statusCode() != 200) {
            throw new IOException("Translation service rejected request");
        }
        List<String> types = response.headers().allValues("Content-Type");
        if (types.size() != 1 || !"application/json".equalsIgnoreCase(types.getFirst().split(";", 2)[0].strip())) {
            throw new IOException("Invalid translation response type");
        }
        byte[] body = response.body();
        // 再检查一次字节上限，注入的传输实现也不能跳过响应大小约束。
        if (body == null || body.length == 0 || body.length > MAXIMUM_RESPONSE_BYTES) {
            throw new IOException("Invalid translation response size");
        }
        JsonNode root;
        try {
            root = objectMapper.readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body);
        } catch (IOException exception) {
            throw new IOException("Invalid translation response");
        }
        if (root == null || !root.isObject() || !"200".equals(root.path("responseStatus").asText())
                || root.path("quotaFinished").asBoolean(false)) {
            throw new IOException("Translation service unavailable");
        }
        JsonNode translated = root.path("responseData").path("translatedText");
        try {
            return decodeAndValidate(translated, source, chineseSource);
        } catch (IOException invalidPrimary) {
            // 主结果可能含拼音或双语解释；只回退到原文和方向严格匹配的独立候选，不裁剪其内容。
            String fallback = selectFallback(root.path("matches"), source, chineseSource);
            if (fallback != null) {
                return fallback;
            }
            throw invalidPrimary;
        }
    }

    private static String decodeAndValidate(JsonNode translated, String source, boolean chineseSource) throws IOException {
        if (!translated.isTextual()) {
            throw new IOException("Invalid translation content");
        }
        String result = translated.textValue();
        // 实体最多解码两次，随后统一校验最终展示文本。
        for (int pass = 0; pass < 2; pass++) {
            String decoded = HtmlUtils.htmlUnescape(result);
            if (decoded.equals(result)) {
                break;
            }
            result = decoded;
        }
        result = result.strip();
        validateTranslation(result, source, chineseSource);
        return result;
    }

    private static String selectFallback(JsonNode matches, String source, boolean chineseSource) {
        if (!matches.isArray()) {
            return null;
        }
        String selected = null;
        double selectedMatch = -1;
        double selectedQuality = -1;
        for (JsonNode candidate : matches) {
            JsonNode segment = candidate.path("segment");
            JsonNode matchNode = candidate.path("match");
            // 保留大小写及内部空格差异，防止把其他词形或近似原文的译文当成本词答案。
            if (!segment.isTextual() || !source.strip().equals(segment.textValue().strip())
                    || !matchesLanguage(candidate.path("source"), chineseSource)
                    || !matchesLanguage(candidate.path("target"), !chineseSource) || !matchNode.isNumber()) {
                continue;
            }
            double match = matchNode.doubleValue();
            if (!Double.isFinite(match) || match < MINIMUM_FALLBACK_MATCH || match > 1) {
                continue;
            }
            String result;
            try {
                result = decodeAndValidate(candidate.path("translation"), source, chineseSource);
            } catch (IOException ignored) {
                // 原文回显、答案泄漏、格式控制符和错误语言均不能因高匹配分获得豁免。
                continue;
            }
            double quality = candidate.path("quality").asDouble(0);
            if (!Double.isFinite(quality) || quality < 0 || quality > 100) {
                quality = 0;
            }
            if (match > selectedMatch || match == selectedMatch && quality > selectedQuality) {
                selected = result;
                selectedMatch = match;
                selectedQuality = quality;
            }
        }
        return selected;
    }

    private static boolean matchesLanguage(JsonNode language, boolean chinese) {
        if (!language.isTextual()) {
            return false;
        }
        String tag = language.textValue().toLowerCase(Locale.ROOT);
        // 中文必须明确为简体或中国地区，英文只接受基础标签和两字母地区标签。
        return chinese ? tag.matches("zh-(?:cn|hans(?:-cn)?)") : tag.matches("en(?:-[a-z]{2})?");
    }

    private static void validateTranslation(String result, String source, boolean chineseSource) throws IOException {
        if (result.isEmpty() || result.codePointCount(0, result.length()) > MAXIMUM_TRANSLATION_CODE_POINTS
                || result.indexOf('<') >= 0 || result.indexOf('>') >= 0
                || result.codePoints().anyMatch(MyMemoryTranslationClient::isControl)
                || folded(result).equals(folded(source))) {
            throw new IOException("Invalid translation content");
        }
        boolean hasHan = result.codePoints().anyMatch(value -> Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN);
        boolean hasLatin = result.codePoints().anyMatch(value -> Character.UnicodeScript.of(value) == Character.UnicodeScript.LATIN);
        // 单词字帖只接受方向正确的候选，混入原文汉字的英文候选也不能进入题目。
        if (chineseSource ? !hasLatin || hasHan : !hasHan || hasLatin) {
            throw new IOException("Invalid translation language");
        }
    }

    private static boolean isControl(int value) {
        int type = Character.getType(value);
        return Character.isISOControl(value) || type == Character.FORMAT || type == Character.SURROGATE;
    }

    private static String folded(String text) {
        return text.codePoints()
                .filter(value -> !Character.isWhitespace(value) && !Character.isSpaceChar(value))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString().toLowerCase(Locale.ROOT);
    }

    /** 在流式接收时限制总字节，不依赖可缺失或伪造的长度响应头。 */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        /** 返回完整响应体的等待结果。 */
        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        /** 逐批申请响应数据，拒绝重复订阅。 */
        @Override
        public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) {
                value.cancel();
                return;
            }
            subscription = value;
            subscription.request(1);
        }

        /** 接收当前数据批次，超过上限立即取消上游。 */
        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                int length = buffer.remaining();
                if (length > MAXIMUM_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("Translation response exceeds limit"));
                    return;
                }
                byte[] chunk = new byte[length];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        /** 丢弃传输异常中的原始地址和正文信息。 */
        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(new IOException("Translation response failed"));
        }

        /** 完整响应到达后才向调用方交付有界字节。 */
        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }
}
