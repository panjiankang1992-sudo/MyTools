package com.yuyutian.mytools.task.scheduler.model;

/**
 * 执行上报处理结果。
 *
 * @param outcome 处理结果
 * @param replayed 是否为幂等重放
 */
public record ExecutionReportView(String outcome, boolean replayed) {

    /**
     * 创建首次接受结果。
     *
     * @return 首次接受结果
     */
    public static ExecutionReportView accepted() {
        return new ExecutionReportView("accepted", false);
    }

    /**
     * 创建幂等重放结果。
     *
     * @return 幂等重放结果
     */
    public static ExecutionReportView replayedResult() {
        return new ExecutionReportView("replayed", true);
    }
}
