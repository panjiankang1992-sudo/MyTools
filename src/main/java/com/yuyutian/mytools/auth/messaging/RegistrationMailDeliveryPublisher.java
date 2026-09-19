package com.yuyutian.mytools.auth.messaging;

import com.yuyutian.mytools.auth.mapper.RegistrationMailDeliveryOutboxMapper;
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
 * 注册邮件真实投递 Outbox 可靠发布器。
 */
@Slf4j
@Component
public class RegistrationMailDeliveryPublisher {

    private final RegistrationMailSidecarProperties properties;
    private final RegistrationMailDeliveryOutboxMapper outboxMapper;
    private final RegistrationMailDeliveryPayloadCipher payloadCipher;
    private final RestClient restClient;

    /**
     * 创建注册邮件真实投递发布器。
     *
     * @param properties 迁移配置
     * @param outboxMapper Outbox 数据访问层
     * @param payloadCipher 载荷加解密器
     * @param restClientBuilder HTTP 客户端构建器
     */
    public RegistrationMailDeliveryPublisher(RegistrationMailSidecarProperties properties,
                                              RegistrationMailDeliveryOutboxMapper outboxMapper,
                                              RegistrationMailDeliveryPayloadCipher payloadCipher,
                                              RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.outboxMapper = outboxMapper;
        this.payloadCipher = payloadCipher;
        this.restClient = restClientBuilder.baseUrl(properties.getServiceUrl()).build();
    }

    /**
     * 扫描并投递已提交的真实邮件请求。
     */
    @Scheduled(fixedDelayString = "${migration.messaging.registration-mail.relay-delay-ms:2000}")
    public void relay() {
        if (!properties.getMode().equalsIgnoreCase("CANARY")
                && !properties.getMode().equalsIgnoreCase("PRIMARY")) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<RegistrationMailDeliveryOutbox> records = outboxMapper.findReady(now, properties.getBatchSize());
        for (RegistrationMailDeliveryOutbox record : records) {
            LocalDateTime claimedUntil = now.plusSeconds(properties.getClaimSeconds());
            if (outboxMapper.claim(record.verificationId(), now, claimedUntil) == 1) {
                deliver(record, claimedUntil);
            }
        }
    }

    private void deliver(RegistrationMailDeliveryOutbox record, LocalDateTime claimedUntil) {
        try {
            RegistrationMailDeliveryPayload payload = payloadCipher.decrypt(record);
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("ownerId", 0L);
            request.put("idempotencyKey", record.idempotencyKey());
            request.put("channelType", "EMAIL");
            request.put("accountId", null);
            request.put("recipient", payload.recipient());
            request.put("subject", payload.subject());
            request.put("body", payload.body());
            restClient.post().uri("/internal/v1/deliveries")
                    .header("Authorization", "Bearer " + properties.getInternalToken())
                    .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().toBodilessEntity();
            outboxMapper.acknowledge(record.verificationId(), claimedUntil, LocalDateTime.now());
            log.info("Registration mail delivery accepted: verificationId={}", record.verificationId());
        } catch (RuntimeException exception) {
            handleFailure(record, claimedUntil, exception);
        }
    }

    private void handleFailure(RegistrationMailDeliveryOutbox record, LocalDateTime claimedUntil,
                               RuntimeException exception) {
        int attemptCount = (record.attemptCount() == null ? 0 : record.attemptCount()) + 1;
        boolean exhausted = attemptCount >= properties.getMaxAttempts();
        LocalDateTime now = LocalDateTime.now();
        String status = exhausted ? "DEAD" : "PENDING";
        LocalDateTime availableAt = exhausted ? now : now.plusSeconds(retryDelaySeconds(attemptCount));
        outboxMapper.fail(record.verificationId(), claimedUntil, status, attemptCount, availableAt,
                exception.getClass().getSimpleName(), now);
        log.warn("Registration mail delivery failed: verificationId={}, attemptCount={}, status={}, errorType={}",
                record.verificationId(), attemptCount, status, exception.getClass().getSimpleName());
    }

    private long retryDelaySeconds(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 30);
        long delay = (long) properties.getBaseRetrySeconds() << exponent;
        return Math.min(delay, properties.getMaxRetrySeconds());
    }
}
