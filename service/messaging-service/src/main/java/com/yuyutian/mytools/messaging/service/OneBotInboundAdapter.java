package com.yuyutian.mytools.messaging.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.messaging.config.MessagingProperties;
import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.CreateInboundMessagePart;
import com.yuyutian.mytools.messaging.model.CreateInboundMessageRequest;
import com.yuyutian.mytools.messaging.model.InboundMessageView;
import com.yuyutian.mytools.messaging.model.OneBotInboundRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 将 OneBot 11 消息事件转换为消息服务标准模型。
 */
@Service
public class OneBotInboundAdapter {

    private static final Pattern CQ_SEGMENT = Pattern.compile("\\[CQ:[^]]*]", Pattern.CASE_INSENSITIVE);
    private static final int MAX_NODES = 10_000;
    private static final int MAX_PARTS = 500;
    private static final int MAX_BODY_LENGTH = 10_485_760;
    private final DeliveryService deliveryService;
    private final MessagingProperties properties;

    /**
     * 创建 OneBot 入站适配器。
     */
    public OneBotInboundAdapter(DeliveryService deliveryService, MessagingProperties properties) {
        this.deliveryService = deliveryService;
        this.properties = properties;
    }

    /**
     * 校验并接收一个 OneBot 消息事件。
     */
    public InboundMessageView receive(OneBotInboundRequest request) {
        if (!properties.oneBotIngressEnabled()) {
            throw new OneBotIngressDisabledException();
        }
        JsonNode event = request.event();
        String postType = text(event, "post_type");
        if (!("message".equals(postType) || "message_sent".equals(postType))) {
            throw new OneBotPayloadInvalidException();
        }
        String messageId = firstText(event, "message_id", "id");
        String messageType = defaultValue(text(event, "message_type"), "private");
        String conversation = "group".equals(messageType)
                ? text(event, "group_id") : text(event, "user_id");
        String sender = text(event, "user_id");
        if (messageId.isBlank() || conversation.isBlank() || sender.isBlank()) {
            throw new OneBotPayloadInvalidException();
        }
        List<CreateInboundMessagePart> parts = extractParts(event, request.accountId());
        String body = parts.stream().filter(part -> "TEXT".equals(part.type()))
                .map(CreateInboundMessagePart::text).filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n" + right).orElse("[attachment]");
        if (body.length() > MAX_BODY_LENGTH) {
            throw new OneBotPayloadInvalidException();
        }
        long timestamp = event.path("time").canConvertToLong() ? event.path("time").asLong() : 0L;
        Instant receivedAt = timestamp > 0 ? Instant.ofEpochSecond(timestamp) : Instant.now();
        String externalId = request.accountId() + ":" + text(event, "self_id") + ":message:"
                + messageType + ":" + conversation + ":" + messageId;
        return deliveryService.receive(new CreateInboundMessageRequest(request.ownerId(), ChannelType.ONEBOT,
                externalId, request.accountId() + ":" + messageType + ":" + conversation,
                sender, null, body, receivedAt, parts));
    }

    private List<CreateInboundMessagePart> extractParts(JsonNode event, String accountKey) {
        List<CreateInboundMessagePart> parts = new ArrayList<>();
        ArrayDeque<JsonNode> stack = new ArrayDeque<>();
        stack.push(event.path("message"));
        int visited = 0;
        while (!stack.isEmpty()) {
            JsonNode current = stack.pop();
            visited++;
            if (visited > MAX_NODES || parts.size() > MAX_PARTS) {
                throw new OneBotPayloadInvalidException();
            }
            if (current.isArray()) {
                for (int index = current.size() - 1; index >= 0; index--) {
                    stack.push(current.get(index));
                }
                continue;
            }
            if (!current.isObject()) {
                continue;
            }
            String type = text(current, "type").toLowerCase(Locale.ROOT);
            JsonNode data = current.path("data");
            if ("text".equals(type)) {
                addText(parts, text(data, "text"));
            } else if (List.of("image", "video", "record", "file").contains(type)) {
                String providerFileId = firstText(data, "file_id", "element_id", "id", "file");
                String url = text(data, "url");
                if (!providerFileId.isBlank() || !url.isBlank()) {
                    parts.add(new CreateInboundMessagePart("ATTACHMENT", null, type.toUpperCase(Locale.ROOT),
                            limit(providerFileId, 512), limit(accountKey, 255), limit(url, 4096),
                            limit(firstText(data, "name", "file_name"), 1024),
                            limit(firstText(data, "mime", "content_type"), 255), positiveLong(data, "file_size", "size")));
                }
            }
            current.elements().forEachRemaining(child -> {
                if (child.isContainerNode() && child != data) {
                    stack.push(child);
                }
            });
            if (data.isContainerNode() && "forward".equals(type)) {
                stack.push(data);
            }
        }
        String rawMessage = text(event, "raw_message");
        if (parts.stream().noneMatch(part -> "TEXT".equals(part.type()))) {
            addText(parts, CQ_SEGMENT.matcher(rawMessage).replaceAll("").trim());
        }
        if (parts.isEmpty() || parts.size() > MAX_PARTS) {
            throw new OneBotPayloadInvalidException();
        }
        return List.copyOf(parts);
    }

    private void addText(List<CreateInboundMessagePart> parts, String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank()) {
            parts.add(new CreateInboundMessagePart("TEXT", normalized, null, null, null, null, null, null, null));
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() && !value.isNull() ? value.asText("").trim() : "";
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static Long positiveLong(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.canConvertToLong() && value.asLong() > 0) {
                return value.asLong();
            }
            try {
                long parsed = Long.parseLong(value.asText("0"));
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // 非法声明大小不影响消息接收，下载任务仍需实施真实大小限制。
            }
        }
        return null;
    }

    private static String limit(String value, int maximum) {
        return value == null || value.isBlank() ? null : value.substring(0, Math.min(value.length(), maximum));
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
