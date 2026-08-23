package com.yuyutian.mytools.automation.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 自动化规则和动作白名单快照。
 */
public record AutomationRuleRecord(UUID id, long ownerId, String name, ChannelType channelType,
                                   String conversationKey, String sender, String commandPrefix,
                                   int priority, boolean enabled, int version, String requestKind,
                                   int maxActions, Instant createdAt, Instant updatedAt) {
}
