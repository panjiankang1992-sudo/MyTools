package com.yuyutian.mytools.news;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.service.technical.GithubTrendCalculator;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class GithubTrendCalculatorTest {
    private static final Instant NOW = Fixtures.CUTOFF;

    @Test
    void firstSnapshotIsColdRegardlessOfTotalStars() {
        GithubTrend trend = GithubTrendCalculator.calculate(List.of(snapshot(NOW, 1000000)), NOW);
        assertThat(trend.status()).isEqualTo(TrendStatus.WARMING_UP);
        assertThat(trend.netStars()).isNull();
        assertThat(trend.baselineAt()).isNull();
        assertThat(trend.coverageSeconds()).isZero();
    }

    @Test
    void continuousSixHourlySamplesProduceActualSignedNetGrowth() {
        List<GithubSnapshot> samples = regular(1000, 10);
        GithubTrend trend = GithubTrendCalculator.calculate(samples, NOW);
        assertThat(trend.status()).isEqualTo(TrendStatus.READY);
        assertThat(trend.netStars()).isEqualTo(280);
        assertThat(trend.coverageSeconds()).isEqualTo(Duration.ofDays(7).toSeconds());
        assertThat(trend.sampleCount()).isEqualTo(29);
        GithubTrend negative = GithubTrendCalculator.calculate(regular(1000, -5), NOW);
        assertThat(negative.netStars()).isEqualTo(-140);
        assertThat(negative.status()).isEqualTo(TrendStatus.READY);
    }

    @Test
    void acceptsAnEarlierEndpointWithinToleranceAndReportsRealSpan() {
        List<GithubSnapshot> samples = regular(100, 2).stream().map(item -> snapshot(item.observedAt().minusSeconds(3600), item.stars())).toList();
        List<GithubSnapshot> withCurrent = new ArrayList<>(samples);
        withCurrent.add(snapshot(NOW, 200));
        GithubTrend trend = GithubTrendCalculator.calculate(withCurrent, NOW);
        assertThat(trend.status()).isEqualTo(TrendStatus.READY);
        assertThat(trend.coverageSeconds()).isEqualTo(Duration.ofHours(169).toSeconds());
        assertThat(trend.baselineAt()).isEqualTo(NOW.minus(Duration.ofHours(169)));
    }

    @Test
    void endpointOutsideToleranceDoesNotInventWeeklyDelta() {
        GithubTrend trend = GithubTrendCalculator.calculate(List.of(snapshot(NOW.minus(Duration.ofHours(175)), 100), snapshot(NOW, 200)), NOW);
        assertThat(trend.status()).isEqualTo(TrendStatus.MISSING_BASELINE);
        assertThat(trend.netStars()).isNull();
    }

    @Test
    void internalGapsAndStaleLatestSampleAreExplicit() {
        GithubTrend gapped = GithubTrendCalculator.calculate(List.of(snapshot(NOW.minus(Duration.ofDays(7)), 100), snapshot(NOW, 200)), NOW);
        assertThat(gapped.status()).isEqualTo(TrendStatus.GAPPED_HISTORY);
        assertThat(gapped.netStars()).isEqualTo(100);
        assertThat(GithubTrendCalculator.calculate(regular(1000, 10), NOW.plus(Duration.ofHours(13))).status()).isEqualTo(TrendStatus.STALE);
    }

    @Test
    void futureSnapshotsCannotChangeHistoricRead() {
        List<GithubSnapshot> samples = new ArrayList<>(regular(1000, 10));
        samples.add(snapshot(NOW.plusSeconds(1), 999999));
        assertThat(GithubTrendCalculator.calculate(samples, NOW).stars()).isEqualTo(1280);
        assertThatThrownBy(() -> GithubTrendCalculator.calculate(List.of(snapshot(NOW.plusSeconds(1), 1)), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<GithubSnapshot> regular(long start, long step) {
        List<GithubSnapshot> result = new ArrayList<>();
        for (int index = 0; index <= 28; index++) result.add(snapshot(NOW.minus(Duration.ofHours((28L - index) * 6)), start + index * step));
        return result;
    }

    private GithubSnapshot snapshot(Instant observed, long stars) {
        return new GithubSnapshot(1, "owner/project", "https://github.com/owner/project", Instant.parse("2015-01-01T00:00:00Z"),
                observed, "", stars, 10, observed);
    }
}
