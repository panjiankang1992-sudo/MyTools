package com.yuyutian.mytools.task.executor.config;

import org.springframework.stereotype.Component;

/**
 * 执行节点任务轮询间隔。
 */
@Component("executorPollInterval")
public class ExecutorPollInterval {

    private final long milliseconds;

    /**
     * 从执行节点配置解析兼容的新旧轮询间隔。
     *
     * @param properties 执行节点配置
     */
    public ExecutorPollInterval(ExecutorProperties properties) {
        this.milliseconds = properties.effectivePollMilliseconds();
    }

    /**
     * 返回轮询间隔毫秒数。
     *
     * @return 轮询间隔毫秒数
     */
    public long milliseconds() {
        return milliseconds;
    }
}
