package com.yuyutian.mytools.news;

import com.yuyutian.mytools.news.utils.SubscriptionSchedule;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionScheduleTest {
    @Test void dstGapUsesShiftForwardAndOverlapUsesEarlierOffset() {
        assertThat(SubscriptionSchedule.cutoff(LocalDate.of(2026, 3, 8), "02:30", "America/New_York")).isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));
        assertThat(SubscriptionSchedule.cutoff(LocalDate.of(2026, 11, 1), "01:30", "America/New_York")).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
        assertThat(SubscriptionSchedule.latestDate(Instant.parse("2026-11-01T06:30:00Z"), "01:30", "America/New_York")).isEqualTo(LocalDate.of(2026, 11, 1));
    }
    @Test void zoneAndExactCutoffChooseCorrectBusinessDate() {
        Instant now = Instant.parse("2026-10-18T00:00:00Z");
        assertThat(SubscriptionSchedule.firstDate(now, "08:00", "Asia/Shanghai")).isEqualTo(LocalDate.of(2026, 10, 18));
        assertThat(SubscriptionSchedule.firstDate(now.plusMillis(1), "08:00", "Asia/Shanghai")).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(SubscriptionSchedule.latestDate(now, "08:00", "America/Los_Angeles")).isEqualTo(LocalDate.of(2026, 10, 17));
    }
}
