package com.yuyutian.mytools.automation.service;

import com.yuyutian.mytools.automation.config.AutomationProperties;
import com.yuyutian.mytools.automation.repository.AutomationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 后台推进运行中的消息自动化状态。
 */
@Component
public class AutomationRunReconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AutomationRunReconciler.class);
    private final AutomationRepository repository;
    private final MessageAutomationService service;
    private final AutomationProperties properties;

    /**
     * 创建自动化运行对账器。
     */
    public AutomationRunReconciler(AutomationRepository repository, MessageAutomationService service,
                                   AutomationProperties properties) {
        this.repository = repository;
        this.service = service;
        this.properties = properties;
    }

    /**
     * 分批对账运行中的自动化任务。
     */
    @Scheduled(fixedDelayString = "${automation.reconciliation-delay-ms:2000}")
    public void reconcile() {
        int limit = properties.reconciliationBatchSize() <= 0 ? 100 : properties.reconciliationBatchSize();
        for (UUID messageId : repository.findActiveMessageIds(limit)) {
            try {
                service.get(messageId);
            } catch (RuntimeException exception) {
                LOGGER.warn("Automation reconciliation failed: messageId={}, errorType={}",
                        messageId, exception.getClass().getSimpleName());
            }
        }
    }
}
