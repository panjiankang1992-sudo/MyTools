package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;

/**
 * 时区测试：事件按 Asia/Shanghai 自然日聚合，迟到事件进入原自然日且不回退累计值。
 */
class TimezoneTest extends ToolboxApiTestSupport {

    /** 固定服务端时间，保证跨零点断言可重复。 */
    private static final Instant FIXED_NOW = Instant.parse("2026-03-02T10:00:00Z");

    /**
     * 固定时钟配置，覆盖默认 UTC 系统时钟。
     */
    @TestConfiguration
    static class FixedClockConfig {

        /**
         * 提供固定时钟。
         *
         * @return 固定时钟
         */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @Test
    @DisplayName("UTC 15:59 and 16:01 land on different Shanghai dates")
    void eventsSplitAtShanghaiMidnight() {
        TestSession session = createSession();
        post(session, "2026-03-01T15:59:00Z");
        post(session, "2026-03-01T16:01:00Z");
        aggregator.drainOnce();

        // 存储保持 UTC 字面值，聚合仍按北京时间切日。
        List<Timestamp> stored = jdbc.queryForList(
                "SELECT occurred_at FROM toolbox_event ORDER BY occurred_at", Timestamp.class);
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).toLocalDateTime()).isEqualTo(LocalDateTime.parse("2026-03-01T15:59:00"));
        assertThat(stored.get(1).toLocalDateTime()).isEqualTo(LocalDateTime.parse("2026-03-01T16:01:00"));

        assertThat(clicksOn(LocalDate.parse("2026-03-01"))).isEqualTo(1L);
        assertThat(clicksOn(LocalDate.parse("2026-03-02"))).isEqualTo(1L);
    }

    @Test
    @DisplayName("late event keeps its own date and never resets totals")
    void lateEventKeepsOriginalDateAndDoesNotResetTotals() {
        TestSession session = createSession();
        post(session, "2026-03-01T15:59:00Z");
        post(session, "2026-03-01T16:01:00Z");
        aggregator.drainOnce();
        long before = totalClicks();

        // 迟到事件发生在更早的时间点，聚合后只增加原自然日的桶。
        post(session, "2026-03-01T11:00:00Z");
        aggregator.drainOnce();

        assertThat(clicksOn(LocalDate.parse("2026-03-01"))).isEqualTo(2L);
        assertThat(clicksOn(LocalDate.parse("2026-03-02"))).isEqualTo(1L);
        assertThat(totalClicks()).isEqualTo(before + 1);

        JsonNode data = json(rest.getForEntity("/public/v1/stats?days=7&category=all", String.class)).path("data");
        assertThat(data.path("today").asText()).isEqualTo("2026-03-02");
        JsonNode series = data.path("series");
        assertThat(series.get(5).path("date").asText()).isEqualTo("2026-03-01");
        assertThat(series.get(5).path("clicks").asLong()).isEqualTo(2L);
        assertThat(series.get(6).path("date").asText()).isEqualTo("2026-03-02");
        assertThat(series.get(6).path("clicks").asLong()).isEqualTo(1L);
        assertThat(data.path("totals").path("todayClicks").asLong()).isEqualTo(1L);
    }

    /**
     * 发送一条入口点击事件。
     *
     * @param session    会话
     * @param occurredAt 事件时间
     */
    private void post(TestSession session, String occurredAt) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", "entry_click");
        event.put("category", "copybook");
        event.put("toolId", null);
        event.put("occurredAt", occurredAt);
        event.put("operationId", null);
        event.put("schemaVersion", 1);
        ResponseEntity<String> response = postJson("/public/v1/events", Map.of("events", List.of(event)), session);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(json(response).path("data").path("accepted").asInt()).isEqualTo(1);
    }

    /**
     * 查询指定自然日的入口点击次数。
     *
     * @param localDate 北京时间自然日
     * @return 次数
     */
    private long clicksOn(LocalDate localDate) {
        Long count = jdbc.queryForObject(
                "SELECT COALESCE(SUM(count), 0) FROM toolbox_daily_stat WHERE local_date = ?"
                        + " AND event_type = 'entry_click'", Long.class, java.sql.Date.valueOf(localDate));
        return count == null ? 0L : count;
    }

    /**
     * 查询全部入口点击累计次数。
     *
     * @return 累计次数
     */
    private long totalClicks() {
        Long count = jdbc.queryForObject(
                "SELECT COALESCE(SUM(count), 0) FROM toolbox_daily_stat WHERE event_type = 'entry_click'",
                Long.class);
        return count == null ? 0L : count;
    }
}
