package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelProviderModels.Result;
import com.yuyutian.mytools.task.executor.common.ErrorCode;

import java.util.Set;

/** 只归一单一完整回答，不保存推理字段、外部错误正文、半截候选或工具调用。 */
public final class NovelProviderResponseParser {
    static final int MAXIMUM_BYTES = 1_048_576;
    private static final Set<String> FINISH_REASONS = Set.of("stop", "length", "content_filter");

    /** 解析有界响应；即使流式回复最终可读，也必须具备终止帧和明确结束原因。 */
    public Result parse(int status, String mediaType, byte[] bytes, String expectedModel, boolean acceptSse) {
        if (bytes == null || bytes.length > MAXIMUM_BYTES || expectedModel == null || status < 100 || status > 599) {
            return failure(ErrorCode.PROTOCOL, status >= 100 && status <= 599 ? status : null, null);
        }
        String digest = NovelProviderJson.sha(bytes);
        if (status < 200 || status >= 300) return httpFailure(status, digest);
        try {
            String raw = NovelProviderJson.utf8(bytes);
            Answer answer;
            if ("application/json".equals(mediaType)) {
                answer = new Answer(expectedModel);
                answer.json(NovelProviderJson.parse(raw));
            } else if (acceptSse && "text/event-stream".equals(mediaType)) {
                answer = stream(raw, expectedModel);
            } else {
                throw invalid();
            }
            if ("content_filter".equals(answer.finish) || answer.refused) {
                return failure(ErrorCode.CONTENT_REJECTED, status, digest);
            }
            if (!"stop".equals(answer.finish)) throw invalid();
            String content = answer.content.toString();
            NovelProviderJson.text(content, 120000);
            return new Result("SUCCEEDED", content, "stop", answer.id, answer.inputTokens,
                    answer.outputTokens, status, null, digest);
        } catch (NovelProviderException exception) {
            return failure(ErrorCode.PROTOCOL, status, digest);
        }
    }

    private static Answer stream(String raw, String model) {
        Answer answer = new Answer(model);
        StringBuilder event = new StringBuilder();
        boolean done = false;
        int frames = 0;
        // split 有界于总响应大小；空行是帧边界，不允许无边界的尾部隐式完成。
        String[] lines = raw.split("\n", -1);
        for (String incoming : lines) {
            String line = incoming.endsWith("\r") ? incoming.substring(0, incoming.length() - 1) : incoming;
            if (line.isEmpty()) {
                if (event.isEmpty()) continue;
                if (++frames > 2048 || done) throw invalid();
                String data = event.substring(0, event.length() - 1);
                event.setLength(0);
                if ("[DONE]".equals(data)) {
                    done = true;
                } else {
                    answer.delta(NovelProviderJson.parse(data));
                }
            } else if (line.startsWith(":")) {
                if (done) throw invalid();
            } else if (line.startsWith("data:")) {
                String data = line.substring(5);
                if (data.startsWith(" ")) data = data.substring(1);
                if (done || event.length() + data.length() + 1 > 65536) throw invalid();
                event.append(data).append('\n');
            } else {
                throw invalid();
            }
        }
        if (!done || !event.isEmpty()) throw invalid();
        return answer;
    }

    private static final class Answer {
        private final String expectedModel;
        private final StringBuilder content = new StringBuilder();
        private String id;
        private String finish;
        private Integer inputTokens;
        private Integer outputTokens;
        private boolean refused;
        private boolean usageSeen;
        private boolean roleSeen;
        private Answer(String model) { expectedModel = model; }

        private JsonNode envelope(JsonNode root) {
            if (root.hasNonNull("error")) throw invalid();
            if (root.has("model") && (!root.get("model").isTextual()
                    || !expectedModel.equals(root.get("model").textValue()))) throw invalid();
            JsonNode requestId = root.get("id");
            if (requestId != null) {
                if (!requestId.isTextual() || !requestId.textValue().matches("[A-Za-z0-9_.:-]{1,128}")) throw invalid();
                if (id != null && !id.equals(requestId.textValue())) throw invalid();
                id = requestId.textValue();
            }
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.size() > 1) throw invalid();
            if (root.hasNonNull("usage")) {
                if (usageSeen || !root.get("usage").isObject()) throw invalid();
                usageSeen = true;
                inputTokens = token(root.get("usage").get("prompt_tokens"));
                outputTokens = token(root.get("usage").get("completion_tokens"));
            }
            return choices;
        }

        private void json(JsonNode root) {
            JsonNode choices = envelope(root);
            if (choices.size() != 1) throw invalid();
            JsonNode choice = choices.get(0);
            checkIndex(choice);
            JsonNode message = choice.get("message");
            if (message == null || !message.isObject() || !"assistant".equals(message.path("role").asText())) throw invalid();
            readContent(message, false);
            readFinish(choice, false);
        }

        private void delta(JsonNode root) {
            boolean previousUsage = usageSeen;
            JsonNode choices = envelope(root);
            if (choices.isEmpty()) {
                // 仅允许结束之后出现一次单独用量帧。
                if (finish == null || previousUsage || !usageSeen) throw invalid();
                return;
            }
            if (finish != null) throw invalid();
            JsonNode choice = choices.get(0);
            checkIndex(choice);
            JsonNode delta = choice.get("delta");
            if (delta == null || !delta.isObject()) throw invalid();
            if (delta.has("role")) {
                if (roleSeen || !"assistant".equals(delta.path("role").asText()) || !content.isEmpty()) throw invalid();
                roleSeen = true;
            }
            readContent(delta, true);
            readFinish(choice, true);
        }

        private void readContent(JsonNode node, boolean delta) {
            if (node.hasNonNull("tool_calls") || node.hasNonNull("function_call")) throw invalid();
            JsonNode refusal = node.get("refusal");
            if (refusal != null && !refusal.isNull()) {
                if (!refusal.isTextual()) throw invalid();
                refused |= !refusal.textValue().isBlank();
            }
            JsonNode text = node.get("content");
            if (text == null || text.isNull()) {
                if (!delta && !refused) throw invalid();
                return;
            }
            if (!text.isTextual()) throw invalid();
            if (!text.textValue().isEmpty()) {
                // 单帧空白合法，整体回答仍须非空；此处检查代理项和总内存上界。
                NovelProviderJson.text("x" + text.textValue(), 120001);
            }
            if (content.length() + text.textValue().length() > 240000) throw invalid();
            content.append(text.textValue());
        }

        private void readFinish(JsonNode choice, boolean delta) {
            JsonNode reason = choice.get("finish_reason");
            if (reason == null || reason.isNull()) {
                if (!delta) throw invalid();
                return;
            }
            if (!reason.isTextual() || !FINISH_REASONS.contains(reason.textValue())) throw invalid();
            finish = reason.textValue();
        }

        private static void checkIndex(JsonNode choice) {
            if (!choice.isObject() || !choice.path("index").isIntegralNumber() || choice.path("index").longValue() != 0) throw invalid();
        }

        private static Integer token(JsonNode token) {
            if (token == null || token.isNull()) return null;
            if (!token.isIntegralNumber() || !token.canConvertToInt() || token.intValue() < 0 || token.intValue() > 10000000) throw invalid();
            return token.intValue();
        }
    }

    static Result httpFailure(int status, String digest) {
        // 5xx/408 无法证明服务端没有生成，不允许自动重发。
        ErrorCode error = status >= 500 || status == 408 ? ErrorCode.UNKNOWN
                : status == 401 ? ErrorCode.UNAUTHORIZED
                : status == 429 ? ErrorCode.UNAVAILABLE
                : status >= 400 && status < 500 ? ErrorCode.REQUEST_REJECTED : ErrorCode.PROTOCOL;
        return failure(error, status, digest);
    }

    static Result failure(ErrorCode error, Integer status, String digest) {
        return new Result(error == ErrorCode.UNKNOWN ? "CALL_OUTCOME_UNKNOWN" : "FAILED",
                null, null, null, null, null, status, error.code(), digest);
    }

    private static NovelProviderException invalid() { return new NovelProviderException(ErrorCode.PROTOCOL); }
}
