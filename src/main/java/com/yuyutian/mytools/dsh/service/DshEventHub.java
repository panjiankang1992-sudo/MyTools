package com.yuyutian.mytools.dsh.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.dsh.config.DshProperties;
import com.yuyutian.mytools.dsh.model.DshModels;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 将 DSH 下行 WebSocket 事件转换为按会话隔离的 App SSE 流。
 */
@Slf4j
@Service
public class DshEventHub {

    private static final long INTERACTION_TTL_MILLIS = 10 * 60 * 1000L;
    private final ObjectMapper objectMapper;
    private final DshProperties properties;
    private final DshRpcClient rpcClient;
    private final DshSessionService sessionService;
    private final HttpClient httpClient;
    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, PendingInteraction> pendingInteractions = new ConcurrentHashMap<>();

    /**
     * 创建 DSH 事件桥。
     *
     * @param objectMapper JSON序列化器
     * @param properties DSH配置
     * @param rpcClient DSH RPC客户端
     * @param sessionService DSH会话服务
     */
    public DshEventHub(ObjectMapper objectMapper, DshProperties properties, DshRpcClient rpcClient,
                       DshSessionService sessionService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.rpcClient = rpcClient;
        this.sessionService = sessionService;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds())).build();
    }

    /**
     * 创建当前用户指定会话的 SSE 订阅。
     *
     * @param userId 用户ID
     * @param sessionId DSH会话ID
     * @return SSE发送器
     */
    public SseEmitter subscribe(Long userId, String sessionId) {
        sessionService.assertOwned(userId, sessionId);
        SseEmitter emitter = new SseEmitter(0L);
        CopyOnWriteArrayList<SseEmitter> sessionSubscribers = subscribers.computeIfAbsent(sessionId,
                ignored -> new CopyOnWriteArrayList<>());
        sessionSubscribers.add(emitter);
        Runnable cleanup = () -> removeSubscriber(sessionId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("sessionId", sessionId)));
        } catch (IOException exception) {
            cleanup.run();
            throw new BusinessException(ErrorCode.DSH_003);
        }
        return emitter;
    }

    /**
     * 回复指定会话的待处理授权。
     *
     * @param userId 用户ID
     * @param sessionId DSH会话ID
     * @param interactionId 下行RPC标识
     * @param allow 是否单次允许
     */
    public void replyApproval(Long userId, String sessionId, String interactionId, boolean allow) {
        sessionService.assertOwned(userId, sessionId);
        PendingInteraction pending = pendingInteractions.get(interactionId);
        if (pending == null || !pending.sessionId().equals(sessionId) || !"approval".equals(pending.kind())
                || pending.expiresAt() <= System.currentTimeMillis()) {
            pendingInteractions.remove(interactionId);
            throw new BusinessException(ErrorCode.DSH_007);
        }
        ObjectNode value = objectMapper.createObjectNode();
        value.put("sessionId", sessionId);
        value.put("approvalId", pending.resourceId());
        value.put("outcome", allow ? "allowed-once" : "rejected");
        rpcClient.respond(interactionId, value);
        pendingInteractions.remove(interactionId, pending);
    }

    /**
     * 定期检查 DSH 事件连接并清理过期交互。
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 1000)
    public void reconcile() {
        long now = System.currentTimeMillis();
        pendingInteractions.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        if (properties.isEnabled() && socket.get() == null && connecting.compareAndSet(false, true)) {
            connect();
        }
    }

    /**
     * 定期向 App SSE 连接发送心跳。
     */
    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void heartbeat() {
        subscribers.forEach((sessionId, emitters) -> emitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException exception) {
                removeSubscriber(sessionId, emitter);
            }
        }));
    }

    private void connect() {
        URI httpUri = rpcClient.baseUri().resolve("/api/events.mux");
        String scheme = "https".equals(httpUri.getScheme()) ? "wss" : "ws";
        URI webSocketUri = URI.create(scheme + "://" + httpUri.getRawAuthority() + httpUri.getRawPath());
        httpClient.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .buildAsync(webSocketUri, new DownlinkListener()).whenComplete((connected, error) -> {
                    connecting.set(false);
                    if (error != null) {
                        log.warn("DSH事件连接失败：{}", error.getMessage());
                    }
                });
    }

    private void handleText(String text) {
        try {
            JsonNode envelope = objectMapper.readTree(text);
            if (!"server-request".equals(envelope.path("type").asText())) {
                return;
            }
            String interactionId = envelope.path("rpcId").asText();
            JsonNode payload = envelope.path("payload");
            String type = payload.path("type").asText();
            String sessionId = payload.path("sessionId").asText();
            if (!sessionId.matches("session-[A-Za-z0-9-]{1,96}")) {
                return;
            }
            DshModels.StreamEvent projected = project(interactionId, sessionId, type, payload);
            if (projected != null) {
                publish(sessionId, projected);
            }
        } catch (JsonProcessingException exception) {
            log.warn("DSH事件JSON无效");
        }
    }

    private DshModels.StreamEvent project(String eventId, String sessionId, String type, JsonNode payload) {
        if ("approval/requested".equals(type)) {
            String approvalId = payload.path("approvalId").asText();
            if (!eventId.matches("[0-9a-fA-F-]{36}") || approvalId.length() > 128) {
                return null;
            }
            pendingInteractions.put(eventId, new PendingInteraction(sessionId, "approval", approvalId,
                    System.currentTimeMillis() + INTERACTION_TTL_MILLIS));
            return new DshModels.StreamEvent(eventId, sessionId, -1L, type, "", "waiting",
                    bounded(payload.path("toolName").asText(), 160), eventId, approvalId,
                    bounded(payload.path("reason").asText(), 1000));
        }
        if (!"session/event".equals(type)) {
            return null;
        }
        JsonNode event = payload.path("event");
        String eventType = event.path("type").asText();
        long seq = event.path("seq").asLong(-1L);
        if ("assistant/chunk".equals(eventType)) {
            JsonNode chunk = event.path("data").path("chunk");
            if (!"text-delta".equals(chunk.path("type").asText())) {
                return null;
            }
            return new DshModels.StreamEvent(eventId, sessionId, seq, "assistant/delta",
                    bounded(chunk.path("text").asText(), 16_384), "streaming", "", "", "", "");
        }
        if (eventType.equals("assistant/message") || eventType.equals("user/message")
                || eventType.equals("turn/start") || eventType.equals("turn/end")
                || eventType.equals("step/start") || eventType.equals("step/end")
                || eventType.equals("tool/call") || eventType.equals("tool/result")) {
            return new DshModels.StreamEvent(eventId, sessionId, seq, eventType, "", "changed",
                    "", "", "", "");
        }
        return null;
    }

    private void publish(String sessionId, DshModels.StreamEvent event) {
        CopyOnWriteArrayList<SseEmitter> emitters = subscribers.get(sessionId);
        if (emitters == null) {
            return;
        }
        emitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().id(event.eventId()).name("dsh").data(event));
            } catch (IOException exception) {
                removeSubscriber(sessionId, emitter);
            }
        });
    }

    private void removeSubscriber(String sessionId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = subscribers.get(sessionId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            subscribers.remove(sessionId, emitters);
        }
    }

    private String bounded(String value, int maximum) {
        String safe = value == null ? "" : value.replaceAll("[\\u0000-\\u001F\\u007F]", " ").trim();
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }

    private record PendingInteraction(String sessionId, String kind, String resourceId, long expiresAt) {
    }

    private final class DownlinkListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            // 在连接回调中登记实例，避免异步完成回调覆盖已关闭的连接。
            socket.set(webSocket);
            log.info("DSH事件连接已建立");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                handleText(message);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            socket.compareAndSet(webSocket, null);
            log.info("DSH事件连接已关闭，状态：{}", statusCode);
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            socket.compareAndSet(webSocket, null);
            log.warn("DSH事件连接异常：{}", error.getMessage());
        }
    }
}
