package com.yuyutian.mytools.reader.config;

import com.yuyutian.mytools.reader.model.AudiobookGenerationMode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 有声书生成的低基数运行指标记录器。
 */
@Component
public class AudiobookGenerationMetrics {

    private final MeterRegistry registry;

    /**
     * 创建有声书生成指标记录器。
     *
     * @param registry Micrometer 指标注册表
     */
    public AudiobookGenerationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录一个新接受的生成运行。
     *
     * @param mode 生成模式
     */
    public void recordAccepted(AudiobookGenerationMode mode) {
        counter("reader.audiobook.generation.accepted", "mode", mode.name()).increment();
    }

    /**
     * 记录一份可进入分析和合成阶段的冻结正文规模与投影时长。
     *
     * @param mode 生成模式
     * @param characterCount 冻结正文 Unicode 码点数
     * @param elapsedMilliseconds 从投影任务受理到冻结完成的耗时
     */
    public void recordTextProjected(AudiobookGenerationMode mode, long characterCount,
                                    long elapsedMilliseconds) {
        DistributionSummary.builder("reader.audiobook.text.characters")
                .tag("mode", mode.name())
                .description("Frozen audiobook text Unicode code point count")
                .register(registry)
                .record(characterCount);
        Timer.builder("reader.audiobook.stage.duration")
                .tag("stage", "TEXT_PROJECTION")
                .tag("mode", mode.name())
                .description("Audiobook generation stage duration")
                .register(registry)
                .record(Math.max(0, elapsedMilliseconds), TimeUnit.MILLISECONDS);
    }

    /**
     * 记录一个在调用外部模型和 TTS 前被拒绝的生成运行。
     *
     * @param reason 固定拒绝原因
     */
    public void recordRejected(String reason) {
        counter("reader.audiobook.generation.rejected", "reason", reason).increment();
    }

    /**
     * 记录一个完成且可长期播放的生成版本。
     *
     * @param mode 生成模式
     */
    public void recordCompleted(AudiobookGenerationMode mode) {
        counter("reader.audiobook.generation.completed", "mode", mode.name()).increment();
    }

    /**
     * 记录由调度器终态同步出的生成失败。
     *
     * @param mode 生成模式
     * @param stage 固定生成阶段
     */
    public void recordTaskFailure(AudiobookGenerationMode mode, String stage) {
        Counter.builder("reader.audiobook.generation.failed")
                .tag("mode", mode.name())
                .tag("stage", stage)
                .description("Audiobook generations ended by a failed scheduler task")
                .register(registry)
                .increment();
    }

    private Counter counter(String name, String tagName, String tagValue) {
        return Counter.builder(name)
                .tag(tagName, tagValue)
                .register(registry);
    }
}
