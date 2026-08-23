package com.yuyutian.mytools.automation.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 自动化执行视图。
 */
public record AutomationRunView(UUID id, UUID messageId, UUID ruleId, Integer ruleVersion,
                                String status, int actionCount, List<String> actionRefs,
                                String errorCode, Instant createdAt, Instant updatedAt,
                                List<AutomationActionView> actions) {
}
