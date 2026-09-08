package com.yuyutian.mytools.task.executor.client;

/**
 * 执行终态及补偿结果。
 *
 * @param status 原始执行终态
 * @param compensationStatus 补偿状态
 * @param compensationRequired 补偿失败是否需要人工处理
 * @param compensationErrorCode 补偿失败稳定错误码
 */
public record ExecutionCompletion(String status, String compensationStatus,
                                  boolean compensationRequired, String compensationErrorCode) {

    /**
     * 创建无需补偿的终态。
     *
     * @param status 原始执行终态
     * @return 执行终态
     */
    public static ExecutionCompletion withoutCompensation(String status) {
        return new ExecutionCompletion(status, "NOT_REQUIRED", false, null);
    }
}
