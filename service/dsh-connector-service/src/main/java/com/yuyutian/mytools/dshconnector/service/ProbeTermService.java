package com.yuyutian.mytools.dshconnector.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.dshconnector.config.DshConnectorProperties;
import com.yuyutian.mytools.dshconnector.model.ProbeTermModels;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** 在短生命周期 DSH 会话中生成有界书源探测词。 */
@Service
public class ProbeTermService {
    private static final String BEGIN_MARKER = "MYTOOLS_PROBE_TERMS_BEGIN";
    private static final String END_MARKER = "MYTOOLS_PROBE_TERMS_END";
    private static final String PROMPT = "Analyze the user's Chinese or English book clue. Return 1 to 5 "
            + "concise search terms as a JSON string array between MYTOOLS_PROBE_TERMS_BEGIN and "
            + "MYTOOLS_PROBE_TERMS_END. Prefer likely titles, distinctive character names, and short "
            + "plot keywords. Do not call tools. User clue: ";
    private final DshRpcClient rpcClient;
    private final DshConnectorProperties properties;
    private final ObjectMapper objectMapper;

    /** 创建探测词服务。 */
    public ProbeTermService(DshRpcClient rpcClient, DshConnectorProperties properties,
                            ObjectMapper objectMapper) {
        this.rpcClient = rpcClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 同步执行一个由 Executor 限时调用的探测词分析。 */
    public ProbeTermModels.Result analyze(ProbeTermModels.Request request) {
        String sessionId = createSession(request.taskInstanceId());
        try {
            prompt(sessionId, request.clue());
            long timeout = Math.max(1, Math.min(120, properties.getProbeTimeoutSeconds()));
            long deadline = System.nanoTime() + Duration.ofSeconds(timeout).toNanos();
            while (System.nanoTime() < deadline) {
                List<String> terms = extract(history(sessionId));
                if (!terms.isEmpty()) { return new ProbeTermModels.Result(terms); }
                Thread.sleep(750L);
            }
            throw new IllegalStateException("DSH probe term analysis timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DSH probe term analysis was interrupted", exception);
        } finally {
            cancelQuietly(sessionId);
        }
    }

    /** 从 DSH 历史投影中提取最后一个完整的受标记 JSON 词集。 */
    public List<String> extract(JsonNode history) {
        StringBuilder output = new StringBuilder();
        JsonNode entries = history.path("events");
        if (entries.isArray()) {
            for (JsonNode entry : entries) {
                JsonNode event = entry.path("event");
                if (!"assistant/message".equals(event.path("type").asText())) { continue; }
                JsonNode content = event.path("data").path("message").path("content");
                if (!content.isArray()) { continue; }
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText())) {
                        output.append(block.path("text").asText()).append('\n');
                    }
                }
            }
        }
        String value = output.toString();
        int begin = value.lastIndexOf(BEGIN_MARKER);
        int end = begin < 0 ? -1 : value.indexOf(END_MARKER, begin + BEGIN_MARKER.length());
        if (begin < 0 || end < 0) { return List.of(); }
        try {
            JsonNode root = objectMapper.readTree(value.substring(
                    begin + BEGIN_MARKER.length(), end).trim());
            if (!root.isArray()) { return List.of(); }
            LinkedHashSet<String> terms = new LinkedHashSet<>();
            for (JsonNode item : root) {
                String term = item.isTextual() ? item.asText().trim() : "";
                if (term.length() >= 2 && term.length() <= 40) { terms.add(term); }
                if (terms.size() >= 5) { break; }
            }
            return new ArrayList<>(terms);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String createSession(UUID taskInstanceId) {
        String requestedId = "session-task-" + taskInstanceId;
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sessionId", requestedId);
        payload.put("cwd", properties.getWorkspacePath());
        if (!properties.getAgentPreset().isBlank()) { payload.put("agentPreset", properties.getAgentPreset()); }
        String actualId = rpcClient.call("session.create", payload).path("sessionId").asText();
        if (!requestedId.equals(actualId)) { throw new IllegalStateException("DSH session identity mismatch"); }
        return actualId;
    }

    private void prompt(String sessionId, String clue) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sessionId", sessionId);
        payload.put("mode", "queue");
        payload.put("clientTimeZone", "Asia/Shanghai");
        ArrayNode content = payload.putArray("content");
        content.addObject().put("type", "text").put("text", PROMPT + clue);
        if (!rpcClient.call("session.prompt", payload).path("accepted").asBoolean(false)) {
            throw new IllegalStateException("DSH prompt was rejected");
        }
    }

    private JsonNode history(String sessionId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sessionId", sessionId);
        payload.put("maxMessages", 50);
        return rpcClient.call("session.history", payload);
    }

    private void cancelQuietly(String sessionId) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("sessionId", sessionId);
            rpcClient.call("session.cancel", payload);
        } catch (RuntimeException ignored) {
            // 分析会话已结束，清理失败不覆盖成功结果或原始错误。
        }
    }
}
