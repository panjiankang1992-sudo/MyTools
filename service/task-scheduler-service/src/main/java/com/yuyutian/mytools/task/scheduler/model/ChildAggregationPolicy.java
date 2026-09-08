package com.yuyutian.mytools.task.scheduler.model;

/**
 * 父任务直接子任务聚合策略配置。
 *
 * @param strategy 聚合策略
 * @param minSuccessCount 最少成功子任务数
 */
public record ChildAggregationPolicy(ChildAggregationStrategy strategy, Integer minSuccessCount) {

    /**
     * 返回兼容既有行为的默认策略。
     *
     * @return 全部成功策略
     */
    public static ChildAggregationPolicy allSuccess() {
        return new ChildAggregationPolicy(ChildAggregationStrategy.ALL_SUCCESS, null);
    }
}
