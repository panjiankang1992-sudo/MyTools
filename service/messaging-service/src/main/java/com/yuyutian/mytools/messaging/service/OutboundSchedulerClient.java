package com.yuyutian.mytools.messaging.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.messaging.config.MessagingProperties;
import com.yuyutian.mytools.messaging.model.ErrorCode;
import com.yuyutian.mytools.messaging.model.OutboundModels.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** 新版投递调度和在线租约校验，不复用旧版静态 fence 协议。 */
@Component
public class OutboundSchedulerClient {
    private final RestClient client;
    /** 设置独立业务身份和有界无跳转 HTTP 客户端。 */
    public OutboundSchedulerClient(MessagingProperties p, @Value("${messaging.scheduler-token:}") String token) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(8));
        client = RestClient.builder().baseUrl(p.schedulerUrl()).requestFactory(factory)
                .defaultHeader("X-Task-Service-Id", "messaging-service").defaultHeader("X-Task-Business-Token", token).build();
    }
    /** 同一代投递始终复用同一任务键。 */
    public UUID create(Delivery d) {
        JsonNode result = client.post().uri("/api/v1/task-instances").body(Map.of(
                "taskName", "message_send_channel_message", "businessType", "MESSAGE_OUTBOUND", "businessId", d.id().toString(),
                "idempotencyKey", key(d), "priority", 0, "parameters", Map.of("deliveryId", d.id().toString(), "generation", d.generation())))
                .retrieve().body(JsonNode.class);
        return validate(result, d);
    }
    /** 查询调度终态，识别从未开始发送就失败的执行。 */
    public String status(Delivery d) {
        JsonNode result = client.get().uri("/api/v1/task-instances/{id}", d.taskId()).retrieve().body(JsonNode.class);
        if (!validate(result, d).equals(d.taskId())) throw new OutboundException(ErrorCode.OUTBOUND_LEASE_INVALID);
        String state = result.path("status").asText();
        if (!java.util.Set.of("CREATED", "QUEUED", "RUNNING", "WAITING_CHILDREN", "SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED", "CANCELLING").contains(state))
            throw new OutboundException(ErrorCode.OUTBOUND_LEASE_INVALID);
        return state;
    }
    private UUID validate(JsonNode result, Delivery d) {
        if (result == null || !key(d).equals(result.path("idempotencyKey").asText())
                || !"message_send_channel_message".equals(result.path("taskName").asText())
                || !d.id().toString().equals(result.path("businessId").asText())
                || !"MESSAGE_OUTBOUND".equals(result.path("businessType").asText())
                || result.path("parameters").size() != 2 || !d.id().toString().equals(result.path("parameters").path("deliveryId").asText())
                || result.path("parameters").path("generation").asInt(-1) != d.generation()) {
            throw new OutboundException(ErrorCode.OUTBOUND_LEASE_INVALID);
        }
        return UUID.fromString(result.path("id").asText());
    }
    /** 每次真正发送前读取 Scheduler 的实时执行状态。 */
    public Grant authorize(Proof proof) {
        try {
            Grant grant = client.post().uri("/api/v1/task-instances/messaging/executions/{id}/authorize", proof.executionId())
                    .header("X-Task-Lease-Token", proof.leaseToken().toString()).body(Map.of()).retrieve().body(Grant.class);
            if (grant == null || grant.taskId() == null || grant.leaseUntil() == null) throw new OutboundException(ErrorCode.OUTBOUND_LEASE_INVALID);
            return grant;
        } catch (RuntimeException exception) { throw new OutboundException(ErrorCode.OUTBOUND_LEASE_INVALID); }
    }
    /** 返回版本化任务身份。 */
    public static String key(Delivery d) { return "message-outbound:" + d.id() + ":" + d.generation(); }
}
