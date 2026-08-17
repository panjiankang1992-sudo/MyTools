package com.yuyutian.mytools.media.service.analysis;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 受限执行媒体分析命令的接口。
 */
public interface MediaCommandRunner {

    /**
     * 执行命令并返回有界标准输出。
     *
     * @param command 参数化命令，不经过 Shell
     * @param timeout 最大执行时间
     * @param maxOutputBytes 最大输出字节数
     * @return UTF-8 标准输出
     * @throws IOException 命令失败或输出非法
     */
    String runForOutput(List<String> command, Duration timeout, int maxOutputBytes) throws IOException;

    /**
     * 执行生成文件的命令并校验输出。
     *
     * @param command 参数化命令，不经过 Shell
     * @param timeout 最大执行时间
     * @param output 预期输出文件
     * @throws IOException 命令失败或未生成文件
     */
    void runForFile(List<String> command, Duration timeout, Path output) throws IOException;
}
