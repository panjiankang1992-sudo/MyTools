package com.yuyutian.mytools.toolbox.service.stats;

import com.yuyutian.mytools.toolbox.common.ToolboxTime;
import com.yuyutian.mytools.toolbox.config.ToolboxProperties;
import com.yuyutian.mytools.toolbox.mapper.stats.EventMapper;
import com.yuyutian.mytools.toolbox.mapper.stats.StatsMapper;
import com.yuyutian.mytools.toolbox.model.EventRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 统计聚合器：消费统计 Outbox，先写已聚合标记再自增日聚合，重复执行不会重复计数。
 * 每个事件在独立事务中处理，进程重启或重复调度都是可重入的。
 */
@Service
public class StatsAggregator {

    private final EventMapper eventMapper;
    private final StatsMapper statsMapper;
    private final ToolboxProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * 构造聚合器。
     *
     * @param eventMapper         事件数据访问层
     * @param statsMapper         统计数据访问层
     * @param properties          门面配置
     * @param transactionTemplate 编程式事务模板
     * @param clock               统一时钟
     */
    public StatsAggregator(EventMapper eventMapper, StatsMapper statsMapper, ToolboxProperties properties,
                           TransactionTemplate transactionTemplate, Clock clock) {
        this.eventMapper = eventMapper;
        this.statsMapper = statsMapper;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 消费一批 Outbox 事件并推进聚合水位。
     *
     * @return 本次处理的批内事件数量
     */
    public int drainOnce() {
        Instant now = clock.instant();
        // 先释放崩溃遗留的处理中租约，再取一批待处理事件。
        eventMapper.releaseExpiredLeases(now);
        List<Long> pending = eventMapper.findPendingEventPks(properties.stats().aggregateBatchSize());
        if (pending.isEmpty()) {
            advanceCursor(now);
            return 0;
        }
        Instant leaseUntil = now.plusSeconds(properties.stats().outboxLeaseSeconds());
        eventMapper.claim(pending, leaseUntil);
        List<EventRow> events = eventMapper.findClaimedEvents(pending);
        for (EventRow row : events) {
            applyOne(row, now);
        }
        advanceCursor(now);
        return events.size();
    }

    /**
     * 在单个事务内标记并累加一个事件。
     *
     * @param row 事件明细
     * @param now 当前时间
     */
    private void applyOne(EventRow row, Instant now) {
        transactionTemplate.executeWithoutResult(status -> {
            try {
                statsMapper.insertApplied(row.id(), now);
            } catch (DuplicateKeyException exception) {
                // 已聚合过：只把 Outbox 标记完成，绝不再次累加。
                eventMapper.markApplied(row.id());
                return;
            }
            LocalDate localDate = ToolboxTime.statDate(row.occurredAt());
            String toolIdKey = row.toolId() == null ? StatsMapper.CATEGORY_ONLY_TOOL_KEY : row.toolId();
            statsMapper.incrementDailyStat(localDate, row.category().value(), toolIdKey, row.eventType());
            eventMapper.markApplied(row.id());
        });
    }

    /**
     * 推进聚合完整水位：存在未完成事件时不越过最早未完成时间。
     *
     * @param now 当前时间
     */
    private void advanceCursor(Instant now) {
        Instant oldestIncomplete = eventMapper.findOldestIncompleteReceivedAt();
        Instant target = oldestIncomplete != null ? oldestIncomplete : eventMapper.findNewestReceivedAt();
        if (target == null) {
            // 没有任何事件时水位推进到当前时间，历史桶可以安全地给出真实零。
            target = now;
        }
        statsMapper.advanceCursor(properties.siteId(), target, now);
    }
}
