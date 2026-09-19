package com.yuyutian.mytools.reader.service.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationAttemptModels.Kind;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationAttemptModels.Terminal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 固定输出边界与终态摘要测试，不把结构验证当作剧情一致性校验。 */
class AdaptationAttemptPayloadTest {
    private final AdaptationAttemptPayload payloads = new AdaptationAttemptPayload(new ObjectMapper());

    @Test
    void shouldCanonicalizeStructuredKeyOrderingAndKeepTextBytesUnchanged() throws Exception {
        String plan = StoryFixtures.plan("The traveler leaves. The gate remains closed.", java.util.List.of("traveler leaves"), "gate remains closed", java.util.List.of());
        var first = payloads.check(Kind.PLAN, success(null, plan));
        var replay = payloads.check(Kind.PLAN, success(null, new ObjectMapper().readTree(first.value().structuredJson()).toPrettyString()));
        assertThat(first.sha256()).isEqualTo(replay.sha256());
        assertThat(first.candidate()).isFalse();
        var text = payloads.check(Kind.GENERATE, success("First line.\r\nSecond line.\n", null));
        assertThat(text.candidate()).isTrue();
        assertThat(text.value().outputText()).isEqualTo("First line.\r\nSecond line.\n");
        assertThat(text.outputSha256()).isNotEqualTo(payloads.check(Kind.GENERATE, success("First line.\nSecond line.\n", null)).outputSha256());
    }

    @Test
    void shouldRejectDuplicateTrailingDeepOversizedAndBrokenUnicodeJson() {
        for (String invalid : new String[]{"{\"x\":1,\"x\":2}", "{} {}", "[]", "{\"x\":\"\\ud800\"}",
                "{\"x\":".repeat(17) + "0" + "}".repeat(17), "{\"x\":\"" + "a".repeat(65536) + "\"}"}) {
            assertThatThrownBy(() -> payloads.check(Kind.CRITIC, success(null, invalid))).isInstanceOf(ChapterAdaptationException.class).hasNoCause();
        }
        for (String invalid : new String[]{"", "   ", "\uD800", "a".repeat(120001), " ```text\nBody\n```", "# Chapter", " <HTML>Body", "<!DOCTYPE html>"}) {
            assertThatThrownBy(() -> payloads.check(Kind.GENERATE, success(invalid, null))).isInstanceOf(ChapterAdaptationException.class);
        }
    }

    @Test
    void shouldRejectUnnormalizedFailureAndUnexpectedMetadataWithoutLeakingPayload() {
        for (Terminal invalid : new Terminal[]{success("text", "{}"),
                new Terminal("SUCCEEDED", "text", null, "length", null, null, null, 200, null, null),
                new Terminal("FAILED", "provider secret", null, "error", null, null, null, 403, "READER_055", null),
                new Terminal("FAILED", null, null, "error", null, null, null, 403, "READER_040", null),
                new Terminal("CALL_OUTCOME_UNKNOWN", null, null, "unknown", null, null, null, 503, "READER_039", null),
                new Terminal("SUCCEEDED", "text", null, "stop", "https://fixture.invalid/private", null, null, 200, null, null),
                new Terminal("SUCCEEDED", "text", null, "stop", null, -1L, null, 200, null, null)}) {
            assertThatThrownBy(() -> payloads.check(Kind.GENERATE, invalid)).isInstanceOf(ChapterAdaptationException.class).hasNoCause();
            assertThat(invalid.toString()).doesNotContain("provider secret", "https://fixture.invalid/private");
        }
    }

    @Test
    void shouldIncludeEveryMetadataChangeInTheTerminalFingerprint() {
        var original = payloads.check(Kind.REPAIR, success("bounded result", null));
        var different = payloads.check(Kind.REPAIR, new Terminal("SUCCEEDED", "bounded result", null, "stop", "changed-request", null, null, 200, null, null));
        assertThat(original.sha256()).isNotEqualTo(different.sha256());
        var unknown = payloads.check(Kind.PLAN, new Terminal("CALL_OUTCOME_UNKNOWN", null, null, "unknown", null, null, null, null, "READER_054", null));
        assertThat(unknown.candidate()).isFalse();
        assertThat(unknown.outputSha256()).isNull();
    }

    private static Terminal success(String text, String json) { return new Terminal("SUCCEEDED", text, json, "stop", null, null, null, 200, null, null); }
}
