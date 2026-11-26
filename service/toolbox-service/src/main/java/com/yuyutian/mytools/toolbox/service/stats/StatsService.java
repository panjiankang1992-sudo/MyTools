package com.yuyutian.mytools.toolbox.service.stats;

import com.yuyutian.mytools.toolbox.common.BusinessException;
import com.yuyutian.mytools.toolbox.common.ToolboxTime;
import com.yuyutian.mytools.toolbox.config.ToolboxProperties;
import com.yuyutian.mytools.toolbox.mapper.stats.EventMapper;
import com.yuyutian.mytools.toolbox.mapper.stats.StatsMapper;
import com.yuyutian.mytools.toolbox.model.DailyStatBucket;
import com.yuyutian.mytools.toolbox.model.StatsRankingEntry;
import com.yuyutian.mytools.toolbox.model.StatsResponse;
import com.yuyutian.mytools.toolbox.model.StatsSeriesPoint;
import com.yuyutian.mytools.toolbox.model.StatsTotals;
import com.yuyutian.mytools.toolbox.model.ToolRankingRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 统计读取服务：只从长期日聚合行计算，未完整聚合的自然日返回 null 而不是伪造的零。
 * 响应按 (days, category) 做短时内存缓存，可通过配置关闭。
 */
@Service
public class StatsService {

    /** 入口点击事件类型。 */
    private static final String TYPE_ENTRY_CLICK = "entry_click";

    /** 题单成功事件类型。 */
    private static final String TYPE_WORKSHEET_SUCCESS = "worksheet_success";

    /** 打印发起事件类型。 */
    private static final String TYPE_PRINT_REQUEST = "print_request";

    /** 允许的统计窗口天数。 */
    private static final List<Integer> ALLOWED_DAYS = List.of(7, 30);

    private final StatsMapper statsMapper;
    private final EventMapper eventMapper;
    private final ToolboxProperties properties;
    private final Clock clock;
    private final Map<String, CachedStats> cache = new ConcurrentHashMap<>();

    /**
     * 构造统计读取服务。
     *
     * @param statsMapper 统计数据访问层
     * @param eventMapper 事件数据访问层，用于判断聚合是否追平
     * @param properties  门面配置
     * @param clock       统一时钟
     */
    public StatsService(StatsMapper statsMapper, EventMapper eventMapper, ToolboxProperties properties, Clock clock) {
        this.statsMapper = statsMapper;
        this.eventMapper = eventMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 返回统计响应。
     *
     * @param days     统计窗口天数，只允许 7 或 30
     * @param category 工具分类，null 表示全部分类
     * @return 统计响应
     */
    public StatsResponse read(int days, String category) {
        if (!ALLOWED_DAYS.contains(days)) {
            throw BusinessException.invalid("days", "unsupportedValue",
                    Map.of("allowed", ALLOWED_DAYS));
        }
        long ttl = properties.stats().cacheTtlSeconds();
        if (ttl <= 0) {
            return compute(days, category);
        }
        String key = days + ":" + (category == null ? "all" : category);
        Instant now = clock.instant();
        CachedStats cached = cache.get(key);
        if (cached != null && cached.createdAt().plusSeconds(ttl).isAfter(now)) {
            return cached.response();
        }
        StatsResponse response = compute(days, category);
        cache.put(key, new CachedStats(now, response));
        return response;
    }

    /**
     * 实际计算统计响应。
     *
     * @param days     统计窗口天数
     * @param category 工具分类，null 表示全部分类
     * @return 统计响应
     */
    private StatsResponse compute(int days, String category) {
        Instant now = clock.instant();
        LocalDate today = ToolboxTime.statDate(now);
        LocalDate from = today.minusDays(days - 1L);
        Instant completeThrough = statsMapper.findCompleteThrough(properties.siteId()).orElse(null);
        boolean partial = eventMapper.hasIncomplete();

        StatsTotals totals = new StatsTotals(
                statsMapper.sumCount(today, today, category, TYPE_ENTRY_CLICK),
                statsMapper.sumCount(today, today, category, TYPE_WORKSHEET_SUCCESS),
                statsMapper.sumAllTime(category, TYPE_WORKSHEET_SUCCESS),
                statsMapper.sumCount(today, today, category, TYPE_PRINT_REQUEST));

        Map<LocalDate, Map<String, Long>> buckets = new HashMap<>();
        for (DailyStatBucket bucket : statsMapper.findSeries(from, today, category)) {
            buckets.computeIfAbsent(bucket.localDate(), key -> new HashMap<>())
                    .put(bucket.eventType(), bucket.count());
        }
        List<StatsSeriesPoint> series = new ArrayList<>(days);
        for (int offset = 0; offset < days; offset++) {
            LocalDate date = from.plusDays(offset);
            boolean isToday = date.equals(today);
            // 桶是否已经不可能再收到新事件：水位必须越过该自然日的结束时刻。
            boolean complete = completeThrough != null
                    && !ToolboxTime.endOfDayExclusive(date).isAfter(completeThrough);
            // 水位是否已经覆盖该自然日的开始：未覆盖时不能把缺失桶写成真实零。
            boolean covered = completeThrough != null
                    && !ToolboxTime.startOfDay(date).isAfter(completeThrough);
            Map<String, Long> bucket = buckets.getOrDefault(date, Map.of());
            // 已覆盖但尚未结束的桶给出真实的滚动值，并用 complete=false 标明仍会变化。
            Long clicks = covered ? bucket.getOrDefault(TYPE_ENTRY_CLICK, 0L) : null;
            Long success = covered ? bucket.getOrDefault(TYPE_WORKSHEET_SUCCESS, 0L) : null;
            series.add(new StatsSeriesPoint(date.toString(), clicks, success, isToday, complete));
        }
        List<StatsRankingEntry> ranking = new ArrayList<>();
        for (ToolRankingRow row : statsMapper.findRanking(from, today, category)) {
            ranking.add(new StatsRankingEntry(row.toolId(), row.success()));
        }
        return new StatsResponse(
                ToolboxTime.STAT_ZONE_ID,
                ToolboxTime.isoOrNull(now),
                ToolboxTime.isoOrNull(completeThrough),
                partial,
                today.toString(),
                totals,
                List.copyOf(series),
                List.copyOf(ranking));
    }

    /**
     * 缓存条目。
     *
     * @param createdAt 生成时间
     * @param response  统计响应
     */
    private record CachedStats(Instant createdAt, StatsResponse response) {
    }
}
