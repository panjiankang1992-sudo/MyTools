package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.InboundOutboxDeadCount;
import com.yuyutian.mytools.messaging.model.InboundOutboxRedriveView;
import com.yuyutian.mytools.messaging.repository.MessagingRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * 提供入站消息 Outbox 的只读诊断和精确死信重驱能力。
 */
@Service
public class InboundOutboxOperationsService {

    private final MessagingRepository repository;

    /**
     * 创建入站 Outbox 运维服务。
     *
     * @param repository 消息仓储
     */
    public InboundOutboxOperationsService(MessagingRepository repository) {
        this.repository = repository;
    }

    /**
     * 读取各类入站死信数量。
     *
     * @return 死信计数
     */
    public InboundOutboxDeadCount deadCount() {
        int messageReceived = repository.countDeadMessageReceivedEvents();
        int forwardAccepted = repository.countDeadOneBotAcceptanceEvents();
        return new InboundOutboxDeadCount(messageReceived, forwardAccepted,
                messageReceived + forwardAccepted);
    }

    /**
     * 精确重驱一个入站死信；重复调用只返回当前状态，不重复修改事件。
     *
     * @param eventId 事件标识
     * @return 重驱结果；目标不是入站事件时为空
     */
    public Optional<InboundOutboxRedriveView> redrive(UUID eventId) {
        return repository.redriveDeadInboundEvent(eventId).map(result -> new InboundOutboxRedriveView(
                result.eventId(), result.eventType(), result.status(), result.redriven()));
    }
}
