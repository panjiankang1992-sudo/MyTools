package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NovelProviderResponseParserTest {
    private final NovelProviderResponseParser parser = new NovelProviderResponseParser();

    @Test
    void acceptsSingleCompleteAnswerAndDropsExtraReasoningFields() throws Exception {
        String body = "\u5c71\u98ce\u5439\u8fc7\u677e\u6797\u3002\ud83c\udf32";
        ObjectNode response = response(body, "stop");
        ((ObjectNode) response.path("choices").get(0).path("message")).put("reasoning_content", "hidden-fixture-reasoning");
        response.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 50);
        var result = parse(response);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.content()).isEqualTo(body);
        assertThat(result.providerRequestId()).isEqualTo("chatcmpl-fixture");
        assertThat(result.inputTokens()).isEqualTo(100);
        assertThat(result.outputTokens()).isEqualTo(50);
        assertThat(result.toString()).doesNotContain(body, "hidden-fixture-reasoning");
        assertThat(NovelProviderJson.MAPPER.writeValueAsString(result)).doesNotContain(body, "reasoning_content");
    }

    @ParameterizedTest
    @CsvSource({"400,FAILED,READER_055", "401,FAILED,READER_040", "403,FAILED,READER_055",
            "404,FAILED,READER_055", "408,CALL_OUTCOME_UNKNOWN,READER_054", "429,FAILED,READER_039",
            "500,CALL_OUTCOME_UNKNOWN,READER_054", "503,CALL_OUTCOME_UNKNOWN,READER_054", "302,FAILED,READER_041"})
    void classifiesWithoutPersistingArbitraryErrorBody(int status, String expectedStatus, String error) {
        byte[] raw = "<html>fixture-secret-and-original-echo</html>".getBytes(StandardCharsets.UTF_8);
        var result = parser.parse(status, "text/html", raw, "fixture-model", true);
        assertThat(result.status()).isEqualTo(expectedStatus);
        assertThat(result.errorCode()).isEqualTo(error);
        assertThat(result.content()).isNull();
        assertThat(result.providerRequestId()).isNull();
        assertThat(result.diagnosticSha256()).isEqualTo(NovelProviderJson.sha(raw));
        assertThat(result.toString()).doesNotContain("fixture-secret", "original-echo");
    }

    @Test
    void rejectsIncompleteMultipleToolAndMalformedAnswers() {
        ObjectNode valid = response("Complete fixture body.", "stop");
        ObjectNode multiple = valid.deepCopy();
        multiple.withArray("choices").add(multiple.path("choices").get(0).deepCopy());
        ObjectNode tool = valid.deepCopy();
        ((ObjectNode) tool.path("choices").get(0).path("message")).putArray("tool_calls");
        ObjectNode model = valid.deepCopy(); model.put("model", "different-model");
        ObjectNode role = valid.deepCopy(); ((ObjectNode) role.path("choices").get(0).path("message")).put("role", "system");
        ObjectNode usage = valid.deepCopy(); usage.putObject("usage").put("prompt_tokens", -1);
        ObjectNode mixed = valid.deepCopy(); mixed.putObject("error").put("message", "untrusted");
        for (ObjectNode response : List.of(multiple, tool, model, role, usage, mixed,
                response("Truncated body", "length"), response("", "stop"))) {
            assertThat(parse(response).errorCode()).isEqualTo("READER_041");
            assertThat(parse(response).content()).isNull();
        }
        for (String raw : List.of("{} {}", "{\"choices\":[],\"choices\":[]}", "<html>error</html>",
                NovelProviderJson.encode(valid).replace("Complete fixture body.", "bad\\ud800"),
                "{\"x\":" + "[".repeat(21) + "0" + "]".repeat(21) + "}")) {
            assertThat(parser.parse(200, "application/json", raw.getBytes(StandardCharsets.UTF_8), "fixture-model", false)
                    .errorCode()).isEqualTo("READER_041");
        }
        assertThat(parser.parse(200, "application/json", new byte[]{(byte) 0xc3, 0x28}, "fixture-model", false).errorCode()).isEqualTo("READER_041");
        assertThat(parser.parse(200, "application/json", new byte[1_048_577], "fixture-model", false).errorCode()).isEqualTo("READER_041");
    }

    @Test
    void refusalAndContentFilterNeverReturnCandidate() {
        var filtered = parse(response("Unusable fixture candidate", "content_filter"));
        assertThat(filtered.errorCode()).isEqualTo("READER_053");
        assertThat(filtered.content()).isNull();
        ObjectNode refusal = response("Unusable fixture candidate", "stop");
        ((ObjectNode) refusal.path("choices").get(0).path("message")).put("refusal", "Refused");
        assertThat(parse(refusal).errorCode()).isEqualTo("READER_053");
        assertThat(parse(refusal).content()).isNull();
    }

    @Test
    void assemblesBoundedSseOnlyWithStopAndDoneIncludingUsageTrailer() {
        String raw = ": fixture keepalive\r\n\r\n" + frame("{\"role\":\"assistant\",\"content\":\"First \"}", null)
                + frame("{\"content\":\"scene.\"}", null) + frame("{}", "stop")
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":25,\"completion_tokens\":10}}\n\n"
                + "data: [DONE]\n\n";
        var result = sse(raw);
        assertThat(result.content()).isEqualTo("First scene.");
        assertThat(result.outputTokens()).isEqualTo(10);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(parser.parse(200, "text/event-stream", raw.getBytes(StandardCharsets.UTF_8), "fixture-model", false)
                .errorCode()).isEqualTo("READER_041");
    }

    @Test
    void rejectsSseTruncationMixedIdentityOversizedFramesAndPostFinishContent() {
        String initial = frame("{\"content\":\"First scene.\"}", null);
        String finish = frame("{}", "stop");
        String done = "data: [DONE]\n\n";
        for (String raw : List.of(initial + finish, initial + done, initial + finish + "data: [DONE]",
                initial + finish + initial + done, initial + finish + done + initial,
                initial + finish.replace("chatcmpl-fixture", "chatcmpl-other") + done,
                initial + "event: arbitrary\n\n" + finish + done,
                "data: " + "x".repeat(65536) + "\n\n" + done,
                frame("{}", null).repeat(2049) + finish + done)) {
            assertThat(sse(raw).errorCode()).isEqualTo("READER_041");
            assertThat(sse(raw).content()).isNull();
        }
    }

    private NovelProviderModels.Result sse(String raw) {
        return parser.parse(200, "text/event-stream", raw.getBytes(StandardCharsets.UTF_8), "fixture-model", true);
    }
    private NovelProviderModels.Result parse(ObjectNode response) {
        return parser.parse(200, "application/json", NovelProviderJson.encode(response).getBytes(StandardCharsets.UTF_8), "fixture-model", false);
    }
    static ObjectNode response(String content, String finish) {
        ObjectNode response = NovelProviderJson.MAPPER.createObjectNode();
        response.put("id", "chatcmpl-fixture").put("model", "fixture-model");
        var choice = response.putArray("choices").addObject().put("index", 0).put("finish_reason", finish);
        choice.putObject("message").put("role", "assistant").put("content", content);
        return response;
    }
    private static String frame(String delta, String finish) {
        ObjectNode root = NovelProviderJson.MAPPER.createObjectNode().put("id", "chatcmpl-fixture").put("model", "fixture-model");
        var choice = root.putArray("choices").addObject().put("index", 0).put("finish_reason", finish);
        choice.set("delta", NovelProviderJson.parse(delta));
        return "data: " + NovelProviderJson.encode(root) + "\n\n";
    }
}
