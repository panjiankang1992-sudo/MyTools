package com.yuyutian.mytools.dsh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.dsh.model.DshModels;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 将 DSH 内部事件投影为不包含系统提示和思维链的 App 模型。
 */
@Component
public class DshEventProjector {

    private static final int MAX_TEXT_LENGTH = 200_000;

    /**
     * 投影会话历史。
     *
     * @param value session.history的result.value
     * @return App会话历史
     */
    public DshModels.History history(JsonNode value) {
        List<DshModels.Message> messages = new ArrayList<>();
        List<DshModels.Step> steps = new ArrayList<>();
        long lastSeq = -1L;
        JsonNode entries = value.path("events");
        if (entries.isArray()) {
            for (JsonNode entry : entries) {
                JsonNode event = entry.path("event");
                long seq = event.path("seq").asLong(-1L);
                lastSeq = Math.max(lastSeq, seq);
                projectMessage(event, messages);
                projectStep(event, steps);
            }
        }
        return new DshModels.History(List.copyOf(messages), List.copyOf(steps),
                value.path("hasMore").asBoolean(false), lastSeq);
    }

    private void projectMessage(JsonNode event, List<DshModels.Message> messages) {
        String type = event.path("type").asText();
        JsonNode data = event.path("data");
        if ("user/message".equals(type)) {
            if (!"user".equals(data.path("source").path("kind").asText())) {
                return;
            }
            addMessage(event, data, "user", messages);
        } else if ("assistant/message".equals(type)) {
            addMessage(event, data.path("message"), "assistant", messages);
        }
    }

    private void addMessage(JsonNode event, JsonNode message, String role, List<DshModels.Message> messages) {
        String text = visibleText(message.path("content"));
        if (text.isBlank()) {
            return;
        }
        String id = safeId(message.path("id").asText(), role + "-" + event.path("seq").asLong());
        messages.add(new DshModels.Message(id, event.path("seq").asLong(), event.path("time").asLong(),
                role, text, "done"));
    }

    private void projectStep(JsonNode event, List<DshModels.Step> steps) {
        String type = event.path("type").asText();
        String label;
        String status = "completed";
        switch (type) {
            case "turn/start" -> {
                label = "Turn started";
                status = "active";
            }
            case "step/start" -> {
                label = "Model started";
                status = "active";
            }
            case "tool/call" -> {
                label = "Tool requested";
                status = "active";
            }
            case "tool/result" -> label = "Tool completed";
            case "step/end" -> label = "Model completed";
            case "turn/end" -> label = "Turn completed";
            default -> {
                return;
            }
        }
        long seq = event.path("seq").asLong();
        steps.add(new DshModels.Step(type + "-" + seq, seq, event.path("time").asLong(), type, label, status));
    }

    private String visibleText(JsonNode content) {
        if (!content.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode block : content) {
            if (!"text".equals(block.path("type").asText())) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(block.path("text").asText());
            if (builder.length() >= MAX_TEXT_LENGTH) {
                return builder.substring(0, MAX_TEXT_LENGTH);
            }
        }
        return builder.toString();
    }

    private String safeId(String value, String fallback) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}") ? value : fallback;
    }
}
