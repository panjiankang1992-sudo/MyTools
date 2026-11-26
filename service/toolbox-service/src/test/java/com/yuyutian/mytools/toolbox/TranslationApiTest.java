package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.toolbox.service.translation.TranslationProvider;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** 翻译接口使用真实匿名会话、请求过滤器、词典和数据库，仅替换外部供应商。 */
@Import(TranslationApiTest.ProviderConfiguration.class)
class TranslationApiTest extends ToolboxApiTestSupport {

    @Autowired
    private FakeProvider provider;

    @Autowired
    private com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog catalog;

    @BeforeEach
    void clearTranslationQuota() {
        jdbc.update("DELETE FROM toolbox_translation_quota");
        provider.reset();
    }

    @Test
    void translatesCommonWordsInBothDirectionsWithoutProviderOrQuota() {
        var response = postJson("/public/v1/translations", batch(
                item("cn-day", "\u661f\u671f\u4e00"), item("en-day", "Monday"),
                item("cn-month", "\u4e8c\u6708"), item("en-month", "February"),
                item("cn-number", "\u4e03"), item("en-number", "seven")), createSession());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.path("code").asText()).isEqualTo("OK");
        assertThat(body.path("requestId").asText()).isNotBlank();
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo(body.path("requestId").asText());
        JsonNode items = body.path("data").path("items");
        assertThat(items).hasSize(6);
        assertTranslated(items.get(0), "cn-day", "\u661f\u671f\u4e00", "Monday", "\u661f\u671f\u4e00");
        assertTranslated(items.get(1), "en-day", "Monday", "Monday", "\u661f\u671f\u4e00");
        assertTranslated(items.get(2), "cn-month", "\u4e8c\u6708", "February", "\u4e8c\u6708");
        assertTranslated(items.get(3), "en-month", "February", "February", "\u4e8c\u6708");
        assertTranslated(items.get(4), "cn-number", "\u4e03", "seven", "\u4e03");
        assertTranslated(items.get(5), "en-number", "seven", "seven", "\u4e03");
        assertThat(provider.calls).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_translation_quota", Long.class)).isZero();
        assertThat(items.get(0).has("pinyinSegments")).isFalse();
    }

    @Test
    void acceptsChineseLookupAndLeavesMissesForLocalPinyinWithoutExternalQuota() {
        var response = postJson("/public/v1/translations", Map.of("subject", "chinese", "items",
                List.of(item("pinyin", "\u672a\u6536\u5f55\u8bcd\u8bed"))), createSession());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode result = json(response).path("data").path("items").get(0);
        assertThat(result.path("sourceText").asText()).isEqualTo("\u672a\u6536\u5f55\u8bcd\u8bed");
        assertThat(result.path("status").asText()).isEqualTo("failed");
        assertThat(result.path("englishText").isNull()).isTrue();
        assertThat(result.path("meaning").isNull()).isTrue();
        assertThat(result.has("pinyinSegments")).isFalse();
        verifyNoExternalRequests();
    }

    /** 语文教材查询不能调用翻译供应商或建立额度记录。 */
    private void verifyNoExternalRequests() {
        assertThat(provider.calls).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_translation_quota", Long.class)).isZero();
    }

    @Test
    void returnsFallbackResultsInInputOrderAndPreservesSourceText() throws Exception {
        provider.reply("rare fruit phrase", false, "\u7a00\u6709\u6c34\u679c");
        provider.reply("\u6d4b\u8bd5\u7279\u6b8a\u77ed\u8bed", true, "a special phrase");

        var response = postJson("/public/v1/translations", batch(
                item("english", "  rare fruit phrase  "), item("chinese", "\u6d4b\u8bd5\u7279\u6b8a\u77ed\u8bed")), createSession());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = json(response).path("data").path("items");
        assertTranslated(items.get(0), "english", "  rare fruit phrase  ", "rare fruit phrase", "\u7a00\u6709\u6c34\u679c");
        assertTranslated(items.get(1), "chinese", "\u6d4b\u8bd5\u7279\u6b8a\u77ed\u8bed", "a special phrase", "\u6d4b\u8bd5\u7279\u6b8a\u77ed\u8bed");
        assertThat(provider.calls).containsExactlyInAnyOrder(
                new Call("rare fruit phrase", false), new Call("\u6d4b\u8bd5\u7279\u6b8a\u77ed\u8bed", true));
        assertThat(jdbc.queryForObject(
                "SELECT used_characters FROM toolbox_translation_quota WHERE quota_scope = 'global'", Integer.class))
                .isEqualTo("rare fruit phrase".length() + 6);
    }

    @Test
    @DisplayName("目录换代后翻译缓存失效，不再返回换代前的结果（C06）")
    void translationCacheIsIsolatedByCatalogGeneration() throws Exception {
        provider.reply("generation phrase", false, "\u4e0a\u4e00\u4ee3");
        var session = createSession();

        // 1) 首次翻译：会调用上游并写入缓存。
        var first = postJson("/public/v1/translations", batch(item("en", "generation phrase")), session);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(provider.calls).hasSize(1);

        // 2) 再次翻译同一个词：应命中缓存，**不再调用上游**。
        var second = postJson("/public/v1/translations", batch(item("en2", "generation phrase")), session);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(provider.calls).as("第二次应命中缓存").hasSize(1);

        // 3) 目录换代（模拟发布了新教材）。
        catalog.load();

        // 4) 换代后再翻译：缓存必须失效，**重新调用上游**。
        //    若缓存不带代际，这里仍会返回"上一代"的结果——正是设计禁止的"迟到的旧请求污染新代"。
        var third = postJson("/public/v1/translations", batch(item("en3", "generation phrase")), session);
        assertThat(third.getStatusCode().value()).isEqualTo(200);
        assertThat(provider.calls).as("换代后必须重新向上游取，而不是复用旧代缓存").hasSize(2);
    }

    @Test
    void preservesSuccessfulItemsWhenOneProviderCallFails() throws Exception {
        provider.fail("unknown failed phrase", false);

        var response = postJson("/public/v1/translations", batch(
                item("good", "Tuesday"), item("bad", "unknown failed phrase")), createSession());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = json(response).path("data").path("items");
        assertTranslated(items.get(0), "good", "Tuesday", "Tuesday", "\u661f\u671f\u4e8c");
        assertThat(items.get(1).path("id").asText()).isEqualTo("bad");
        assertThat(items.get(1).path("sourceText").asText()).isEqualTo("unknown failed phrase");
        assertThat(items.get(1).path("status").asText()).isEqualTo("failed");
        assertThat(items.get(1).path("englishText").isNull()).isTrue();
        assertThat(items.get(1).path("meaning").isNull()).isTrue();
        assertThat(response.getBody()).doesNotContain("private upstream diagnostic", "IOException");
    }

    @Test
    void rejectsRequestsWithoutAnonymousSession() {
        assertError(postJson("/public/v1/translations", batch(item("a", "Monday")), null), 401, "TOOLBOX_002");
        assertThat(provider.calls).isEmpty();
    }

    @Test
    void rejectsMissingAndIncorrectCsrfTokens() {
        TestSession session = createSession();
        for (String token : List.of("", "wrong-csrf-token")) {
            HttpHeaders headers = jsonHeaders(null);
            headers.add(HttpHeaders.COOKIE, session.cookie());
            // 缺失与错误的令牌都不能借用已存在的匿名会话调用翻译。
            if (!token.isEmpty()) {
                headers.add("X-Toolbox-Csrf", token);
            }
            var response = rest.exchange("/public/v1/translations", HttpMethod.POST,
                    new HttpEntity<>(batch(item("a", "Monday")), headers), String.class);
            assertError(response, 403, "TOOLBOX_010");
        }
        assertThat(provider.calls).isEmpty();
    }

    @Test
    void rejectsForeignOriginEvenWithValidSessionAndCsrf() {
        HttpHeaders headers = jsonHeaders(createSession());
        headers.add(HttpHeaders.ORIGIN, "https://untrusted.example");
        var response = rest.exchange("/public/v1/translations", HttpMethod.POST,
                new HttpEntity<>(batch(item("a", "Monday")), headers), String.class);

        assertError(response, 403, "TOOLBOX_010");
        assertThat(provider.calls).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsMalformedStructuresWithoutInvokingProvider(String body) {
        assertError(postRaw("/public/v1/translations", body, createSession()), 422, "TOOLBOX_001");
        assertThat(provider.calls).isEmpty();
    }

    private static Stream<String> invalidRequests() {
        return Stream.of(
                "{}", "{\"items\":null}", "{\"items\":[]}", "{\"items\":[null]}",
                "{\"items\":[{\"text\":\"Monday\"}]}",
                "{\"items\":[{\"id\":\" \" ,\"text\":\"Monday\"}]}",
                "{\"items\":[{\"id\":\"a\",\"text\":null}]}",
                "{\"items\":[{\"id\":42,\"text\":\"Monday\"}]}",
                "{\"items\":[{\"id\":\"a\",\"text\":true}]}",
                "{\"items\":[{\"id\":\"a\",\"text\":\" \"}]}",
                "{\"items\":[{\"id\":\"a\",\"text\":\"line\\nbreak\"}]}",
                "{\"items\":[{\"id\":\"a\\nb\",\"text\":\"Monday\"}]}",
                "{\"items\":[{\"id\":\"a\",\"text\":\"Monday\"},{\"id\":\"a\",\"text\":\"Tuesday\"}]}",
                "{\"items\":[{\"id\":\"" + "a".repeat(129) + "\",\"text\":\"Monday\"}]}",
                "{\"items\":[{\"id\":\"a\",\"text\":\"Monday\",\"extra\":true}]}",
                "{\"items\":[{\"id\":\"a\",\"text\":\"Monday\"}],\"extra\":true}",
                "{\"items\":[{\"id\":\"a\",\"text\":\"Monday\"}],\"subject\":null}",
                "{\"items\":[{\"id\":\"a\",\"text\":\"Monday\"}],\"subject\":7}",
                "{\"items\":[{\"id\":\"a\",\"text\":\"Monday\"}],\"subject\":\"math\"}",
                "{\"items\":[{\"id\":\"a\",\"text\":\"Monday\"}],\"subject\":\"chinese\",\"extra\":true}",
                "{\"items\":");
    }

    @Test
    void acceptsExactlyTwentyItemsAndRejectsTwentyOne() {
        TestSession session = createSession();
        var items = IntStream.range(0, 21).mapToObj(index -> item("item-" + index, "Monday")).toList();

        var accepted = postJson("/public/v1/translations", Map.of("items", items.subList(0, 20)), session);
        assertThat(accepted.getStatusCode().value()).isEqualTo(200);
        assertThat(json(accepted).path("data").path("items")).hasSize(20);
        assertError(postJson("/public/v1/translations", Map.of("items", items), session), 413, "TOOLBOX_005");
        assertThat(provider.calls).isEmpty();
    }

    @Test
    void countsSupplementaryCodePointsAtTheTextBoundary() throws Exception {
        String source = "\uD842\uDFB7".repeat(120);
        provider.reply(source, true, "a rare name");
        TestSession session = createSession();

        var accepted = postJson("/public/v1/translations", batch(item("boundary", source)), session);

        assertThat(accepted.getStatusCode().value()).isEqualTo(200);
        assertTranslated(json(accepted).path("data").path("items").get(0), "boundary", source, "a rare name", source);
        assertError(postJson("/public/v1/translations", batch(item("over", source + "\uD842\uDFB7")), session), 413, "TOOLBOX_005");
        assertThat(jdbc.queryForObject(
                "SELECT used_characters FROM toolbox_translation_quota WHERE quota_scope = 'global'", Integer.class))
                .isEqualTo(120);
        assertThat(provider.calls).containsExactly(new Call(source, true));
    }

    @Test
    void rejectsOversizedJsonBeforeTranslation() {
        String body = "{\"items\":[{\"id\":\"a\",\"text\":\"" + "a".repeat(32_768) + "\"}]}";

        var response = postRaw("/public/v1/translations", body, createSession());

        assertError(response, 413, "TOOLBOX_005");
        assertThat(json(response).path("details").path("limitName").asText()).isEqualTo("maxBodyBytes");
        assertThat(provider.calls).isEmpty();
    }

    @Test
    void rejectsMixedLanguageItemWithoutChargingAndResolvesNumericAlias() {
        var response = postJson("/public/v1/translations", batch(
                item("mixed", "\u661f\u671f\u4e00 Monday"), item("number", "1")), createSession());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = json(response).path("data").path("items");
        assertThat(items.get(0).path("status").asText()).isEqualTo("failed");
        assertThat(items.get(0).path("englishText").isNull()).isTrue();
        assertThat(items.get(0).path("meaning").isNull()).isTrue();
        assertTranslated(items.get(1), "number", "1", "one", "\u4e00");
        assertThat(provider.calls).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_translation_quota", Long.class)).isZero();
    }

    private static Map<String, String> item(String id, String text) {
        return Map.of("id", id, "text", text);
    }

    @SafeVarargs
    private static Map<String, Object> batch(Map<String, String>... items) {
        return Map.of("items", List.of(items));
    }

    private void assertError(ResponseEntity<String> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(json(response).path("code").asText()).isEqualTo(code);
        assertThat(json(response).path("requestId").asText()).isNotBlank();
    }

    private static void assertTranslated(JsonNode item, String id, String source, String english, String meaning) {
        assertThat(item.path("id").asText()).isEqualTo(id);
        assertThat(item.path("sourceText").asText()).isEqualTo(source);
        assertThat(item.path("englishText").asText()).isEqualTo(english);
        assertThat(item.path("meaning").asText()).isEqualTo(meaning);
        assertThat(item.path("status").asText()).isEqualTo("translated");
    }

    /** 测试只替换网络边界，不需要动态代理或附加 Java 代理。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @Bean
        @Primary
        FakeProvider fakeTranslationProvider() {
            return new FakeProvider();
        }
    }

    /** 记录被请求的原文与方向。 */
    private record Call(String text, boolean chineseSource) {
    }

    /** 跨 HTTP 工作线程安全的固定响应供应商。 */
    static final class FakeProvider implements TranslationProvider {
        private final Map<Call, String> replies = new ConcurrentHashMap<>();
        private final List<Call> failures = new CopyOnWriteArrayList<>();
        private final List<Call> calls = new CopyOnWriteArrayList<>();

        private void reply(String text, boolean chineseSource, String translated) {
            replies.put(new Call(text, chineseSource), translated);
        }

        private void fail(String text, boolean chineseSource) {
            failures.add(new Call(text, chineseSource));
        }

        private void reset() {
            replies.clear();
            failures.clear();
            calls.clear();
        }

        /** 返回预设结果，未声明的调用明确失败，绝不访问网络。 */
        @Override
        public String translate(String text, boolean chineseSource) throws IOException {
            Call call = new Call(text, chineseSource);
            calls.add(call);
            if (failures.contains(call)) {
                throw new IOException("private upstream diagnostic");
            }
            if (!replies.containsKey(call)) {
                throw new IOException("Unexpected provider request");
            }
            return replies.get(call);
        }
    }
}
