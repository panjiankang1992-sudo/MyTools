package com.yuyutian.mytools.news.service.technical;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** 根据实际聚合快照计算净星数，保留冷启动、缺测和负增长。 */
public final class GithubTrendCalculator {
    private GithubTrendCalculator() { }

    /** 计算截至指定时刻可见的近七日净变化，不读取未来快照。 */
    public static GithubTrend calculate(List<GithubSnapshot> history, Instant cutoff) {
        List<GithubSnapshot> visible = history.stream().filter(snapshot -> !snapshot.observedAt().isAfter(cutoff))
                .sorted(Comparator.comparing(GithubSnapshot::observedAt)).toList();
        if (visible.isEmpty()) throw new IllegalArgumentException("At least one visible snapshot is required");
        GithubSnapshot latest = visible.getLast();
        Instant target = latest.observedAt().minus(Duration.ofDays(7));
        GithubSnapshot baseline = visible.stream().filter(snapshot -> !snapshot.observedAt().isAfter(target)
                        && !snapshot.observedAt().isBefore(target.minus(Duration.ofHours(6))))
                .max(Comparator.comparing(GithubSnapshot::observedAt)).orElse(null);
        Instant start = baseline == null ? visible.getFirst().observedAt() : baseline.observedAt();
        List<GithubSnapshot> samples = visible.stream().filter(snapshot -> !snapshot.observedAt().isBefore(start)).toList();
        TrendStatus status = baseline == null
                ? visible.getFirst().observedAt().isAfter(target) ? TrendStatus.WARMING_UP : TrendStatus.MISSING_BASELINE
                : TrendStatus.READY;
        for (int index = 1; baseline != null && index < samples.size(); index++) {
            // 两端差值仍然保留，但连续缺测超过十二小时的样本不能参与周趋势排名。
            if (Duration.between(samples.get(index - 1).observedAt(), samples.get(index).observedAt()).compareTo(Duration.ofHours(12)) > 0) {
                status = TrendStatus.GAPPED_HISTORY;
            }
        }
        if (Duration.between(latest.observedAt(), cutoff).compareTo(Duration.ofHours(12)) > 0) status = TrendStatus.STALE;
        return new GithubTrend(latest.repositoryId(), latest.stars(), latest.forks(), latest.observedAt(),
                baseline == null ? null : baseline.observedAt(), baseline == null ? null : baseline.stars(),
                baseline == null ? null : latest.stars() - baseline.stars(),
                Duration.between(start, latest.observedAt()).toSeconds(), samples.size(), status);
    }
}
