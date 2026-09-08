package com.yuyutian.mytools.task.scheduler.model;

/**
 * 父任务直接子任务聚合策略。
 */
public enum ChildAggregationStrategy {
    ALL_SUCCESS,
    ANY_SUCCESS,
    MIN_SUCCESS_COUNT
}
