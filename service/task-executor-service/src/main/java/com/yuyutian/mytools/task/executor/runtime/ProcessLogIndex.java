package com.yuyutian.mytools.task.executor.runtime;

import java.util.List;

/**
 * 脚本进程日志分段索引。
 *
 * @param stdout 标准输出分段
 * @param stderr 标准错误分段
 */
public record ProcessLogIndex(List<LogSegment> stdout, List<LogSegment> stderr) {

    /**
     * 日志分段元数据。
     *
     * @param path 相对工作目录的文件路径
     * @param sizeBytes 字节数
     * @param sha256 内容摘要
     */
    public record LogSegment(String path, long sizeBytes, String sha256) {
    }

    /**
     * 创建空日志索引。
     *
     * @return 空日志索引
     */
    public static ProcessLogIndex empty() {
        return new ProcessLogIndex(List.of(), List.of());
    }
}
