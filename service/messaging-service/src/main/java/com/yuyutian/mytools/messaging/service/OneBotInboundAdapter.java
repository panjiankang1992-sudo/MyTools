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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 将 OneBot 11 消息事件转换为消息服务标准模型。
 */
@Service
public class OneBotInboundAdapter {

    private static final Pattern CQ_SEGMENT = Pattern.compile("\\[CQ:[^]]*]", Pattern.CASE_INSENSITIVE);
    private static final int MAX_NODES = 10_000;
    private static final int MAX_PARTS = 500;
    private static final int MAX_FORWARD_EXPANSIONS = 8;
    private static final int MAX_BODY_LENGTH = 10_485_760;
    private static final int MAX_RAW_EVENT_LENGTH = 16_777_215;
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
        ParsedParts parsed = extractParts(event.path("message"), text(event, "raw_message"), request.accountId());
        List<CreateInboundMessagePart> parts = parsed.parts();
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
        CreateInboundMessageRequest inboundRequest = new CreateInboundMessageRequest(
                request.ownerId(), ChannelType.ONEBOT,
                externalId, request.accountId() + ":" + messageType + ":" + conversation,
                sender, null, body, receivedAt, parts);
        if (parsed.forwardIds().isEmpty() && !parsed.truncated()) {
            return deliveryService.receive(inboundRequest);
        }
        String rawEvent = event.toString();
        if (rawEvent.length() > MAX_RAW_EVENT_LENGTH) {
            throw new OneBotPayloadInvalidException();
        }
        // 合并转发先与原始事件在同一事务内落库，再由后台中继访问 OneBot 展开。
        return deliveryService.receiveOneBot(inboundRequest, rawEvent, request.accountId(),
                parsed.forwardIds(), parsed.truncated());
    }

    ParsedParts extractParts(JsonNode message, String rawMessage, String accountKey) {
        List<CreateInboundMessagePart> parts = new ArrayList<>();
        Set<String> attachmentKeys = new HashSet<>();
        Set<String> forwardIds = new java.util.LinkedHashSet<>();
        boolean truncated = false;
        ArrayDeque<JsonNode> stack = new ArrayDeque<>();
        stack.push(message);
        int visited = 0;
        while (!stack.isEmpty()) {
            JsonNode current = stack.pop();
            visited++;
            if (visited > MAX_NODES) {
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
                if (!addText(parts, text(data, "text"))) {
                    truncated = true;
                }
            } else if (List.of("image", "video", "record", "file").contains(type)) {
                String providerFileId = firstText(data, "file_id", "element_id", "id", "file");
                String url = text(data, "url");
                String attachmentKey = attachmentKey(accountKey, providerFileId, url);
                // 合并转发可能同时返回 message 与 content 两棵等价结构，同一附件只允许入库一次。
                if ((!providerFileId.isBlank() || !url.isBlank()) && attachmentKeys.add(attachmentKey)) {
                    if (parts.size() < MAX_PARTS) {
                        parts.add(new CreateInboundMessagePart("ATTACHMENT", null, type.toUpperCase(Locale.ROOT),
                                limit(providerFileId, 512), limit(accountKey, 255), limit(url, 4096),
                                limit(firstText(data, "name", "file_name"), 1024),
                                limit(firstText(data, "mime", "content_type"), 255),
                                positiveLong(data, "file_size", "size")));
                    } else {
                        truncated = true;
                    }
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
            if ("forward".equals(type)) {
                String forwardId = firstText(data, "id", "message_id", "res_id", "file");
                // 入口只提取引用，不得同步访问 OneBot；全局展开预算由持久化中继继续约束。
                if (!forwardId.isBlank()) {
                    String normalizedForwardId = limit(forwardId, 512);
                    if (!forwardIds.contains(normalizedForwardId)) {
                        if (forwardIds.size() < MAX_FORWARD_EXPANSIONS) {
                            forwardIds.add(normalizedForwardId);
                        } else {
                            truncated = true;
                        }
                    }
                }
            }
        }
        if (parts.stream().noneMatch(part -> "TEXT".equals(part.type()))) {
            if (!addText(parts, CQ_SEGMENT.matcher(rawMessage).replaceAll("").trim())) {
                truncated = true;
            }
        }
        if (parts.isEmpty() && !forwardIds.isEmpty()) {
            // 纯合并转发也必须先形成一条可审计消息，展开后再补齐真实分段并发布事件。
            addText(parts, "[forward]");
        }
        if (parts.isEmpty()) {
            throw new OneBotPayloadInvalidException();
        }
        return new ParsedParts(List.copyOf(parts), List.copyOf(forwardIds), truncated);
    }

    private String attachmentKey(String accountKey, String providerFileId, String url) {
        if (!providerFileId.isBlank()) {
            return "provider:" + accountKey + ":" + providerFileId;
        }
        return "url:" + url;
    }

    private boolean addText(List<CreateInboundMessagePart> parts, String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return true;
        }
        if (parts.size() < MAX_PARTS) {
            parts.add(new CreateInboundMessagePart("TEXT", normalized, null, null, null, null, null, null, null));
            return true;
        }
        return false;
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

    /**
     * 本地解析出的消息分段与待展开引用。
     *
     * @param parts 标准消息分段
     * @param forwardIds 合并转发引用
     * @param truncated 是否因安全预算截断
     */
    record ParsedParts(List<CreateInboundMessagePart> parts, List<String> forwardIds, boolean truncated) {
    }
}
