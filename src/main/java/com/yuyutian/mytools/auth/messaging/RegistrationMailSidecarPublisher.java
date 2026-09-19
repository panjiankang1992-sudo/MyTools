package com.yuyutian.mytools.auth.messaging;

import com.yuyutian.mytools.auth.mapper.RegistrationMailShadowOutboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 注册邮件影子 Outbox 可靠投递器。
 */
@Slf4j
@Component
public class RegistrationMailSidecarPublisher {

    private static final String PENDING_STATUS = "PENDING";
    private static final String DEAD_STATUS = "DEAD";
    private final RegistrationMailSidecarProperties properties;
    private final RegistrationMailShadowOutboxMapper outboxMapper;
    private final RestClient restClient;

    /**
     * 创建注册邮件影子投递器。
     *
     * @param properties 影子配置
     * @param outboxMapper Outbox 数据访问层
     * @param restClientBuilder HTTP 客户端构建器
     */
    public RegistrationMailSidecarPublisher(RegistrationMailSidecarProperties properties,
                                             RegistrationMailShadowOutboxMapper outboxMapper,
                                             RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.outboxMapper = outboxMapper;
        this.restClient = restClientBuilder.baseUrl(properties.getServiceUrl()).build();
    }

    /**
     * 扫描并投递已提交的影子证据。
     */
    @Scheduled(fixedDelayString = "${migration.messaging.registration-mail.relay-delay-ms:2000}")
    public void relay() {
        if (!properties.isEnabled() && !"SHADOW".equalsIgnoreCase(properties.getMode())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<RegistrationMailShadowOutbox> records = outboxMapper.findReady(now, properties.getBatchSize());
        for (RegistrationMailShadowOutbox record : records) {
            // 条件领取避免多实例重复并发投递，Messaging 端幂等键处理租约边界上的重复请求。
            LocalDateTime claimedUntil = now.plusSeconds(properties.getClaimSeconds());
            if (outboxMapper.claim(record.verificationId(), now, claimedUntil) == 1) {
                deliver(record, claimedUntil);
            }
        }
    }

    private void deliver(RegistrationMailShadowOutbox record, LocalDateTime claimedUntil) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("ownerId", 0L);
            request.put("idempotencyKey", record.idempotencyKey());
            request.put("channelType", "EMAIL");
            request.put("recipientHash", record.recipientHmac());
            request.put("payloadHash", record.payloadHmac());
            request.put("legacyOutcome", record.legacyOutcome());
            restClient.post()
                    .uri("/internal/v1/delivery-shadows")
                    .header("Authorization", "Bearer " + properties.getInternalToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
            outboxMapper.acknowledge(record.verificationId(), claimedUntil, LocalDateTime.now());
            log.info("Registration mail shadow recorded: verificationId={}", record.verificationId());
        } catch (RuntimeException exception) {
            // 禁止记录异常正文，避免下游将鉴权信息包含在异常中。
            handleFailure(record, claimedUntil, exception);
        }
    }

    private void handleFailure(RegistrationMailShadowOutbox record, LocalDateTime claimedUntil,
                               RuntimeException exception) {
        int attemptCount = (record.attemptCount() == null ? 0 : record.attemptCount()) + 1;
        boolean exhausted = attemptCount >= properties.getMaxAttempts();
        String status = exhausted ? DEAD_STATUS : PENDING_STATUS;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime availableAt = exhausted ? now : now.plusSeconds(retryDelaySeconds(attemptCount));
        outboxMapper.fail(record.verificationId(), claimedUntil, status, attemptCount, availableAt,
                exception.getClass().getSimpleName(), now);
        log.warn("Registration mail shadow recording failed: verificationId={}, attemptCount={}, status={}, errorType={}",
                record.verificationId(), attemptCount, status, exception.getClass().getSimpleName());
    }

    private long retryDelaySeconds(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 30);
        long delay = (long) properties.getBaseRetrySeconds() << exponent;
        return Math.min(delay, properties.getMaxRetrySeconds());
    }
}
