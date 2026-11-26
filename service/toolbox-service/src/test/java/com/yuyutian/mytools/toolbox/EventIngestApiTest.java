package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 事件上报接口测试：接收/拒绝矩阵、三层去重与批次原子性。
 */
class EventIngestApiTest extends ToolboxApiTestSupport {

    @Test
    @DisplayName("worksheet_success rejects a PDF tool")
    void worksheetSuccessWithPdfToolIsRejected() {
        TestSession session = createSession();
        ResponseEntity<String> response = postEvents(session, List.of(
                event("worksheet_success", "pdf", "pdf.merge", "op-1", now())));

        JsonNode result = firstResult(response);
        assertThat(result.path("status").asText()).isEqualTo("rejected");
        assertThat(result.path("reason").asText()).isEqualTo("worksheet_tool_required");
    }

    @Test
    @DisplayName("unknown tool id and category mismatch are rejected")
    void unknownToolAndCategoryMismatchAreRejected() {
        TestSession session = createSession();
        ResponseEntity<String> response = postEvents(session, List.of(
                event("entry_click", "pdf", "pdf.missing", null, now()),
                event("entry_click", "words", "pdf.merge", null, now())));

        JsonNode body = json(response).path("data");
        assertThat(body.path("rejected").asInt()).isEqualTo(2);
        assertThat(body.path("results").get(0).path("reason").asText()).isEqualTo("unknown_tool");
        assertThat(body.path("results").get(1).path("reason").asText()).isEqualTo("tool_category_mismatch");
    }

    @Test
    @DisplayName("print_request without operationId is rejected")
    void printRequestWithoutOperationIdIsRejected() {
        TestSession session = createSession();
        ResponseEntity<String> response = postEvents(session, List.of(
                event("print_request", "copybook", "worksheet.chinese-dictation", null, now())));

        JsonNode result = firstResult(response);
        assertThat(result.path("status").asText()).isEqualTo("rejected");
        assertThat(result.path("reason").asText()).isEqualTo("missing_operation_id");
    }

    @Test
    @DisplayName("occurredAt 25 hours old is rejected")
    void occurredAtTooOldIsRejected() {
        TestSession session = createSession();
        ResponseEntity<String> response = postEvents(session, List.of(
                event("entry_click", "copybook", "worksheet.chinese-dictation", null,
                        Instant.now().minus(25, ChronoUnit.HOURS).toString())));

        JsonNode result = firstResult(response);
        assertThat(result.path("status").asText()).isEqualTo("rejected");
        assertThat(result.path("reason").asText()).isEqualTo("occurred_at_out_of_range");
    }

    @Test
    @DisplayName("pdf_success is not a public event type and is rejected")
    void pdfSuccessEventTypeIsRejected() {
        TestSession session = createSession();
        ResponseEntity<String> response = postEvents(session, List.of(
                event("pdf_success", "pdf", "pdf.merge", "op-1", now())));

        JsonNode result = firstResult(response);
        assertThat(result.path("status").asText()).isEqualTo("rejected");
        assertThat(result.path("reason").asText()).isEqualTo("invalid_event_type");
    }

    @Test
    @DisplayName("batch above 50 events returns TOOLBOX_005")
    void batchOverFiftyIsRejected() {
        TestSession session = createSession();
        List<Map<String, Object>> events = new ArrayList<>();
        for (int index = 0; index < 51; index++) {
            events.add(event("entry_click", "copybook", null, null, now()));
        }

        ResponseEntity<String> response = postEvents(session, events);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        JsonNode body = json(response);
        assertThat(body.path("code").asText()).isEqualTo("TOOLBOX_005");
        assertThat(body.path("messageKey").asText()).isEqualTo("task.limitExceeded");
        assertThat(body.path("details").path("limitName").asText()).isEqualTo("maxEventsPerBatch");
        Long events1 = jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_event", Long.class);
        assertThat(events1).isZero();
    }

    @Test
    @DisplayName("body above 32 KiB returns TOOLBOX_005")
    void oversizedBodyIsRejected() {
        TestSession session = createSession();
        StringBuilder body = new StringBuilder("{\"events\":[],\"padding\":\"");
        body.append("a".repeat(33_000));
        body.append("\"}");
        assertThat(utf8Length(body.toString())).isGreaterThan(32_768);

        ResponseEntity<String> response = postRaw("/public/v1/events", body.toString(), session);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        JsonNode json = json(response);
        assertThat(json.path("code").asText()).isEqualTo("TOOLBOX_005");
        assertThat(json.path("details").path("limitName").asText()).isEqualTo("maxBodyBytes");
    }

    @Test
    @DisplayName("replayed eventId counts once")
    void replayedEventIdIsDuplicate() {
        TestSession session = createSession();
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> event = event("entry_click", "copybook", "worksheet.chinese-dictation", null, now());
        event.put("eventId", eventId);

        ResponseEntity<String> response = postEvents(session, List.of(event, event));

        JsonNode data = json(response).path("data");
        assertThat(data.path("accepted").asInt()).isEqualTo(1);
        assertThat(data.path("duplicate").asInt()).isEqualTo(1);
        assertThat(data.path("results").get(1).path("reason").asText()).isEqualTo("duplicate_event");
        Long events = jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_event", Long.class);
        assertThat(events).isEqualTo(1L);
    }

    @Test
    @DisplayName("success with the same operationId counts once")
    void successDedupedByOperationId() {
        TestSession session = createSession();
        ResponseEntity<String> response = postEvents(session, List.of(
                event("worksheet_success", "copybook", "worksheet.chinese-dictation", "op-success", now()),
                event("worksheet_success", "copybook", "worksheet.chinese-dictation", "op-success", now())));

        JsonNode data = json(response).path("data");
        assertThat(data.path("accepted").asInt()).isEqualTo(1);
        assertThat(data.path("duplicate").asInt()).isEqualTo(1);
        assertThat(data.path("results").get(1).path("reason").asText()).isEqualTo("duplicate_success");

        aggregator.drainOnce();
        assertThat(successCount("worksheet.chinese-dictation")).isEqualTo(1L);
    }

    @Test
    @DisplayName("two print requests with different eventIds count twice")
    void twoPrintRequestsCountTwice() {
        TestSession session = createSession();
        ResponseEntity<String> response = postEvents(session, List.of(
                event("print_request", "copybook", "worksheet.chinese-dictation", "op-print-1", now()),
                event("print_request", "copybook", "worksheet.chinese-dictation", "op-print-2", now())));

        JsonNode data = json(response).path("data");
        assertThat(data.path("accepted").asInt()).isEqualTo(2);

        aggregator.drainOnce();
        Long prints = jdbc.queryForObject(
                "SELECT COALESCE(SUM(count), 0) FROM toolbox_daily_stat WHERE event_type = 'print_request'"
                        + " AND tool_id_key = 'worksheet.chinese-dictation'", Long.class);
        assertThat(prints).isEqualTo(2L);
    }

    @Test
    @DisplayName("one bad item does not discard the rest of the batch")
    void oneBadItemDoesNotDiscardBatch() {
        TestSession session = createSession();
        ResponseEntity<String> response = postEvents(session, List.of(
                event("entry_click", "copybook", "worksheet.chinese-dictation", null, now()),
                event("worksheet_success", "pdf", "pdf.merge", "op-bad", now()),
                event("entry_click", "words", "worksheet.english-dictation", null, now())));

        JsonNode data = json(response).path("data");
        assertThat(data.path("accepted").asInt()).isEqualTo(2);
        assertThat(data.path("rejected").asInt()).isEqualTo(1);
        assertThat(data.path("duplicate").asInt()).isZero();
        Long events = jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_event", Long.class);
        assertThat(events).isEqualTo(2L);
        Long outbox = jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_stat_outbox", Long.class);
        assertThat(outbox).isEqualTo(2L);
    }

    /**
     * 发送事件批次。
     *
     * @param session 会话
     * @param events  事件列表
     * @return 响应
     */
    private ResponseEntity<String> postEvents(TestSession session, List<Map<String, Object>> events) {
        return postJson("/public/v1/events", Map.of("events", events), session);
    }

    /**
     * 构造单条事件请求。
     *
     * @param eventType   事件类型
     * @param category    分类
     * @param toolId      工具标识，可为空
     * @param operationId 操作标识，可为空
     * @param occurredAt  事件时间
     * @return 事件请求映射
     */
    private Map<String, Object> event(String eventType, String category, String toolId, String operationId,
                                      String occurredAt) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", eventType);
        event.put("category", category);
        event.put("toolId", toolId);
        event.put("occurredAt", occurredAt);
        event.put("operationId", operationId);
        event.put("schemaVersion", 1);
        return event;
    }

    /**
     * 当前时间的 ISO 字符串。
     *
     * @return ISO 8601 UTC 字符串
     */
    private String now() {
        return Instant.now().toString();
    }

    /**
     * 取第一条处理结果。
     *
     * @param response 响应
     * @return 结果节点
     */
    private JsonNode firstResult(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode results = json(response).path("data").path("results");
        assertThat(results).hasSize(1);
        return results.get(0);
    }

    /**
     * 查询某工具的成功累计次数。
     *
     * @param toolId 工具标识
     * @return 累计次数
     */
    private long successCount(String toolId) {
        Long count = jdbc.queryForObject(
                "SELECT COALESCE(SUM(count), 0) FROM toolbox_daily_stat WHERE event_type = 'worksheet_success'"
                        + " AND tool_id_key = ?", Long.class, toolId);
        return count == null ? 0L : count;
    }
}
