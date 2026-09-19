package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.repository.MessagingRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 暴露入站消息终态与 OneBot 受理回复的死信健康状态。
 */
@Component("inboundOutbox")
public class InboundOutboxHealthIndicator implements HealthIndicator {

    private final MessagingRepository repository;

    /**
     * 创建入站 Outbox 健康检查。
     *
     * @param repository 消息仓储
     */
    public InboundOutboxHealthIndicator(MessagingRepository repository) {
        this.repository = repository;
    }

    /**
     * 根据未发布死信数量返回健康状态。
     *
     * @return 健康详情
     */
    @Override
    public Health health() {
        int messageReceived = repository.countDeadMessageReceivedEvents();
        int forwardAccepted = repository.countDeadOneBotAcceptanceEvents();
        Health.Builder builder = messageReceived + forwardAccepted == 0 ? Health.up() : Health.down();
        return builder.withDetail("deadMessageReceivedEvents", messageReceived)
                .withDetail("deadOneBotForwardAcceptedEvents", forwardAccepted)
                .build();
    }
}
