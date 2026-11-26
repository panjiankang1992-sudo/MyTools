package com.yuyutian.mytools.toolbox.service.stats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 统计聚合定时任务：按配置间隔消费 Outbox，测试环境可关闭并通过同步调用触发。
 */
@Component
@ConditionalOnProperty(name = "toolbox.stats.aggregation-enabled", havingValue = "true", matchIfMissing = true)
public class StatsAggregationJob {

    private static final Logger LOG = LoggerFactory.getLogger(StatsAggregationJob.class);

    private final StatsAggregator aggregator;

    /**
     * 构造定时任务。
     *
     * @param aggregator 统计聚合器
     */
    public StatsAggregationJob(StatsAggregator aggregator) {
        this.aggregator = aggregator;
    }

    /**
     * 周期消费 Outbox；失败只记录日志，不影响后续调度。
     */
    @Scheduled(fixedDelayString = "${toolbox.stats.aggregate-interval-ms:60000}")
    public void drain() {
        try {
            int processed = aggregator.drainOnce();
            if (processed > 0) {
                LOG.debug("stats aggregation applied {} events", processed);
            }
        } catch (RuntimeException exception) {
            LOG.error("stats aggregation failed", exception);
        }
    }
}
