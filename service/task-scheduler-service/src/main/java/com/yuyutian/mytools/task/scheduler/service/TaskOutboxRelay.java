package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.config.TaskOutboxProperties;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;

/**
 * 任务事件 Outbox Webhook 投递器。
 */
@Service
public class TaskOutboxRelay {

    private final TaskEventService taskEventService;
    private final TaskOutboxProperties properties;
    private final RestClient restClient;

    /**
     * 创建 Outbox 投递器。
     *
     * @param taskEventService 任务事件服务
     * @param properties 投递配置
     * @param restClientBuilder HTTP 客户端构建器
     */
    public TaskOutboxRelay(TaskEventService taskEventService, TaskOutboxProperties properties,
                           RestClient.Builder restClientBuilder) {
        this.taskEventService = taskEventService;
        this.properties = properties;
        this.restClient = restClientBuilder.build();
    }

    /**
     * 周期投递待处理的任务事件。
     */
    @Scheduled(fixedDelayString = "${task.outbox.relay-delay-ms:2000}")
    public void relay() {
        if (properties.webhookUrl() == null || properties.webhookUrl().isBlank()) {
            return;
        }
        int batchSize = properties.batchSize() > 0 ? properties.batchSize() : 50;
        int maxAttempts = properties.maxAttempts() > 0 ? properties.maxAttempts() : 10;
        for (TaskEventService.TaskOutboxEvent event : taskEventService.claimPending(batchSize, Instant.now())) {
            try {
                RestClient.RequestBodySpec request = restClient.post()
                        .uri(properties.webhookUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Task-Event-Id", event.id().toString())
                        .header("X-Task-Event-Type", event.eventType());
                if (properties.authToken() != null && !properties.authToken().isBlank()) {
                    request.header("Authorization", "Bearer " + properties.authToken());
                }
                request.body(event.payloadJson()).retrieve().toBodilessEntity();
                taskEventService.markPublished(event.id(), Instant.now());
            } catch (RuntimeException exception) {
                // 仅保存异常类型，避免将请求载荷或凭据写入数据库。
                taskEventService.markFailed(event, exception.getClass().getSimpleName(), maxAttempts, Instant.now());
            }
        }
    }
}
