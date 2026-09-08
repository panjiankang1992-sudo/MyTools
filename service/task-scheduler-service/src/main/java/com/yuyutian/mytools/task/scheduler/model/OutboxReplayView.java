package com.yuyutian.mytools.task.scheduler.model;

/**
 * Outbox 人工重投结果。
 *
 * @param replayedCount 已重置事件数量
 */
public record OutboxReplayView(int replayedCount) {
}
