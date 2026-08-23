package com.yuyutian.mytools.auth.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 注册邮件提交成功后的新消息服务旁路发布器。
 */
@Slf4j
@Component
public class RegistrationMailSidecarPublisher {

    private final RegistrationMailSidecarProperties properties;
    private final RestClient restClient;

    /**
     * 创建注册邮件旁路发布器。
     *
     * @param properties 旁路配置
     * @param restClientBuilder HTTP 客户端构建器
     */
    public RegistrationMailSidecarPublisher(RegistrationMailSidecarProperties properties,
                                             RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.getServiceUrl()).build();
    }

    /**
     * 在旧验证码事务提交后异步创建新投递，失败不影响旧链路。
     *
     * @param event 旧邮件成功事件
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(RegistrationMailSent event) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("ownerId", 0L);
            request.put("idempotencyKey", "registration-code:" + event.verificationId());
            request.put("channelType", "EMAIL");
            request.put("recipient", event.email());
            request.put("subject", "MyTools register verification code");
            request.put("body", "Your MyTools verification code is " + event.code()
                    + ". It expires in 1 hour.");
            restClient.post()
                    .uri("/internal/v1/deliveries")
                    .header("Authorization", "Bearer " + properties.getInternalToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Registration mail sidecar delivery created: verificationId={}", event.verificationId());
        } catch (RuntimeException exception) {
            // 禁止记录异常正文，避免下游将请求载荷或鉴权信息包含在异常中。
            log.warn("Registration mail sidecar delivery failed: verificationId={}, errorType={}",
                    event.verificationId(), exception.getClass().getSimpleName());
        }
    }
}
