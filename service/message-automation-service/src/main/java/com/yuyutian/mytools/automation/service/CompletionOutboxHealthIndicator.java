package com.yuyutian.mytools.automation.service;

import com.yuyutian.mytools.automation.repository.AutomationRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 暴露自动化完成通知死信数量，避免仅发送开始回执时健康状态仍静默为绿。
 */
@Component("completionOutbox")
public class CompletionOutboxHealthIndicator implements HealthIndicator {

    private final AutomationRepository repository;

    /**
     * 创建完成通知 Outbox 健康检查。
     *
     * @param repository 自动化仓储
     */
    public CompletionOutboxHealthIndicator(AutomationRepository repository) {
        this.repository = repository;
    }

    /**
     * 任一未处理死信都会使就绪健康检查失败，并仅暴露安全计数。
     *
     * @return 完成通知 Outbox 健康状态
     */
    @Override
    public Health health() {
        int deadCount = repository.countDeadCompletions();
        Health.Builder builder = deadCount == 0 ? Health.up() : Health.down();
        return builder.withDetail("deadCount", deadCount).build();
    }
}
