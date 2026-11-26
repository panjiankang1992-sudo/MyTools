package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 统计读取接口测试：小样本事件集合可人工核对、连续自然日序列、排名与幂等重启。
 */
class StatsApiTest extends ToolboxApiTestSupport {

    /** 契约固定的统计时区。 */
    private static final ZoneId STAT_ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    @DisplayName("small event set produces hand-checkable statistics")
    void statsMatchHandComputedSample() {
        TestSession session = createSession();
        postEvents(session, List.of(
                event("entry_click", "copybook", null, null),
                event("entry_click", "copybook", null, null),
                event("worksheet_success", "copybook", "worksheet.chinese-dictation", "op-1"),
                event("worksheet_success", "copybook", "worksheet.chinese-dictation", "op-2"),
                event("worksheet_success", "words", "worksheet.english-dictation", "op-3"),
                event("print_request", "copybook", "worksheet.chinese-dictation", "op-print")));
        aggregator.drainOnce();

        ResponseEntity<String> response = rest.getForEntity("/public/v1/stats?days=7&category=all", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode data = json(response).path("data");
        assertThat(data.path("timezone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(data.path("generatedAt").asText()).endsWith("Z");
        assertThat(data.path("completeThrough").asText()).endsWith("Z");
        assertThat(data.path("partial").asBoolean()).isFalse();
        assertThat(data.path("today").asText()).isEqualTo(LocalDate.now(STAT_ZONE).toString());

        JsonNode totals = data.path("totals");
        assertThat(totals.path("todayClicks").asLong()).isEqualTo(2L);
        assertThat(totals.path("todaySuccess").asLong()).isEqualTo(3L);
        assertThat(totals.path("allTimeSuccess").asLong()).isEqualTo(3L);
        assertThat(totals.path("todayPrintRequests").asLong()).isEqualTo(1L);

        JsonNode series = data.path("series");
        assertThat(series).hasSize(7);
        LocalDate expectedDate = LocalDate.now(STAT_ZONE).minusDays(6);
        for (int index = 0; index < 7; index++) {
            JsonNode point = series.get(index);
            assertThat(point.path("date").asText()).isEqualTo(expectedDate.plusDays(index).toString());
            assertThat(point.path("clicks").isNumber()).isTrue();
            assertThat(point.path("success").isNumber()).isTrue();
            assertThat(point.path("isToday").isBoolean()).isTrue();
            if (index < 6) {
                // 完整聚合的历史桶必须是真实零，而不是缺省或伪造值。
                assertThat(point.path("complete").asBoolean()).isTrue();
                assertThat(point.path("clicks").asLong()).isZero();
                assertThat(point.path("success").asLong()).isZero();
                assertThat(point.path("isToday").asBoolean()).isFalse();
            } else {
                // 当天桶仍可能收到新事件，必须标记未结束，但计数是真实的滚动值。
                assertThat(point.path("complete").asBoolean()).isFalse();
                assertThat(point.path("isToday").asBoolean()).isTrue();
                assertThat(point.path("clicks").asLong()).isEqualTo(2L);
                assertThat(point.path("success").asLong()).isEqualTo(3L);
            }
        }

        JsonNode ranking = data.path("ranking");
        assertThat(ranking).hasSize(2);
        assertThat(ranking.get(0).path("toolId").asText()).isEqualTo("worksheet.chinese-dictation");
        assertThat(ranking.get(0).path("success").asLong()).isEqualTo(2L);
        assertThat(ranking.get(1).path("toolId").asText()).isEqualTo("worksheet.english-dictation");
        assertThat(ranking.get(1).path("success").asLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName("category filter applies to totals and ranking")
    void categoryFilterAppliesToTotalsAndRanking() {
        TestSession session = createSession();
        postEvents(session, List.of(
                event("worksheet_success", "copybook", "worksheet.chinese-dictation", "op-1"),
                event("worksheet_success", "words", "worksheet.english-dictation", "op-2")));
        aggregator.drainOnce();

        JsonNode copybook = json(rest.getForEntity("/public/v1/stats?days=7&category=copybook", String.class))
                .path("data");
        assertThat(copybook.path("totals").path("todaySuccess").asLong()).isEqualTo(1L);
        assertThat(copybook.path("totals").path("allTimeSuccess").asLong()).isEqualTo(1L);
        assertThat(copybook.path("ranking")).hasSize(1);

        JsonNode pdf = json(rest.getForEntity("/public/v1/stats?days=7&category=pdf", String.class)).path("data");
        assertThat(pdf.path("totals").path("todaySuccess").asLong()).isZero();
        assertThat(pdf.path("ranking")).isEmpty();
    }

    @Test
    @DisplayName("30 day window returns 30 contiguous days")
    void thirtyDayWindowIsContiguous() {
        ResponseEntity<String> response = rest.getForEntity("/public/v1/stats?days=30&category=all", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode series = json(response).path("data").path("series");
        assertThat(series).hasSize(30);
        LocalDate first = LocalDate.parse(series.get(0).path("date").asText());
        LocalDate last = LocalDate.parse(series.get(29).path("date").asText());
        assertThat(last.minusDays(29)).isEqualTo(first);
    }

    @Test
    @DisplayName("days only accepts 7 or 30")
    void unsupportedDaysIsRejected() {
        ResponseEntity<String> response = rest.getForEntity("/public/v1/stats?days=14&category=all", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        JsonNode body = json(response);
        assertThat(body.path("code").asText()).isEqualTo("TOOLBOX_001");
        assertThat(body.path("details").path("field").asText()).isEqualTo("days");
    }

    @Test
    @DisplayName("category only accepts all, pdf, copybook or words")
    void unsupportedCategoryIsRejected() {
        ResponseEntity<String> response = rest.getForEntity("/public/v1/stats?days=7&category=video", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(json(response).path("details").path("field").asText()).isEqualTo("category");
    }

    @Test
    @DisplayName("repeated aggregation and restart keep numbers stable")
    void repeatedAggregationIsIdempotent() {
        TestSession session = createSession();
        postEvents(session, List.of(
                event("worksheet_success", "copybook", "worksheet.chinese-dictation", "op-1"),
                event("print_request", "copybook", "worksheet.chinese-dictation", "op-print")));
        aggregator.drainOnce();
        JsonNode first = json(rest.getForEntity("/public/v1/stats?days=7&category=all", String.class)).path("data");

        // 再次运行聚合等价于进程重启后的重放。
        assertThat(aggregator.drainOnce()).isZero();
        assertThat(aggregator.drainOnce()).isZero();
        JsonNode second = json(rest.getForEntity("/public/v1/stats?days=7&category=all", String.class)).path("data");

        assertThat(second.path("totals")).isEqualTo(first.path("totals"));
        assertThat(second.path("series")).isEqualTo(first.path("series"));
        assertThat(second.path("ranking")).isEqualTo(first.path("ranking"));
        assertThat(second.path("totals").path("allTimeSuccess").asLong()).isEqualTo(1L);
    }

    /**
     * 发送事件批次。
     *
     * @param session 会话
     * @param events  事件列表
     */
    private void postEvents(TestSession session, List<Map<String, Object>> events) {
        ResponseEntity<String> response = postJson("/public/v1/events", Map.of("events", events), session);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(json(response).path("data").path("accepted").asInt()).isEqualTo(events.size());
    }

    /**
     * 构造单条事件请求。
     *
     * @param eventType   事件类型
     * @param category    分类
     * @param toolId      工具标识，可为空
     * @param operationId 操作标识，可为空
     * @return 事件请求映射
     */
    private Map<String, Object> event(String eventType, String category, String toolId, String operationId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", eventType);
        event.put("category", category);
        event.put("toolId", toolId);
        event.put("occurredAt", Instant.now().toString());
        event.put("operationId", operationId);
        event.put("schemaVersion", 1);
        return event;
    }

}
