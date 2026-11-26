package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 工具注册表接口测试：12 个工具、分类映射、限额声明与未知字段拒绝。
 */
class ToolCatalogApiTest extends ToolboxApiTestSupport {

    /** 契约第 2 节的工具标识与分类映射。 */
    private static final Map<String, String> EXPECTED_TOOLS = expectedTools();

    @Test
    @DisplayName("registry returns 12 tools with categories, capabilities and limits")
    void toolsExposeRegistryCapabilitiesAndLimits() {
        ResponseEntity<String> response = rest.getForEntity("/public/v1/tools", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode data = json(response).path("data");
        // 测试档未启用公开 PDF 执行链，因此不能声明服务端处理。
        assertThat(data.path("execution").asText()).isEqualTo("local");
        assertThat(data.path("policyVersion").asText()).isNotBlank();

        JsonNode tools = data.path("tools");
        assertThat(tools.isArray()).isTrue();
        assertThat(tools).hasSize(12);
        for (JsonNode tool : tools) {
            String id = tool.path("id").asText();
            assertThat(EXPECTED_TOOLS).containsKey(id);
            assertThat(tool.path("category").asText()).isEqualTo(EXPECTED_TOOLS.get(id));
            assertThat(tool.path("enabled").asBoolean()).isTrue();
            assertThat(tool.path("labelKey").asText()).isNotBlank();
            assertThat(tool.path("descriptionKey").asText()).isNotBlank();
            assertThat(tool.path("route").asText()).startsWith("/");
            assertThat(tool.path("order").asInt()).isPositive();
        }

        JsonNode capabilities = data.path("capabilities");
        assertThat(capabilities.isArray()).isTrue();
        Map<String, Boolean> capabilityStates = new LinkedHashMap<>();
        Map<String, String> capabilityExecution = new LinkedHashMap<>();
        for (JsonNode capability : capabilities) {
            capabilityStates.put(capability.path("id").asText(), capability.path("enabled").asBoolean());
            capabilityExecution.put(capability.path("id").asText(), capability.path("execution").asText());
        }
        assertThat(capabilityStates).containsKeys("pdf", "copybook", "words", "stats");
        assertThat(capabilityStates.get("copybook")).isTrue();
        assertThat(capabilityStates.get("words")).isTrue();
        // 未启用的 PDF 能力必须声明为浏览器本机处理，不能声明为服务端处理。
        assertThat(capabilityStates.get("pdf")).isFalse();
        assertThat(capabilityExecution.get("pdf")).isEqualTo("local");
        assertThat(capabilityExecution.get("stats")).isEqualTo("server");

        JsonNode limits = data.path("limits");
        assertThat(limits.path("jsonBodyBytes").asLong()).isEqualTo(1_048_576L);
        assertThat(limits.path("eventsBodyBytes").asLong()).isEqualTo(32_768L);
        assertThat(limits.path("textBytes").asLong()).isEqualTo(262_144L);
        assertThat(limits.path("eventsPerBatch").asInt()).isEqualTo(50);
        assertThat(limits.path("entriesPageSizeMax").asInt()).isEqualTo(500);
        assertThat(limits.path("sessionTtlHours").asLong()).isEqualTo(24L);
        // 公开 PDF 执行链尚未启用，必须明确声明而不是给出一组可用的假限额。
        assertThat(limits.path("pdfExecution").path("active").asBoolean()).isFalse();
        assertThat(limits.path("pdfExecution").path("noteKey").asText()).isEqualTo("limits.pdfExecutionInactive");
        assertThat(limits.path("pdfExecution").path("values").path("maxFileBytes").asLong()).isEqualTo(31_457_280L);
    }

    @Test
    @DisplayName("tools response has no internal owner or storage fields")
    void toolsResponseHasNoInternalFields() {
        String body = rest.getForEntity("/public/v1/tools", String.class).getBody();

        assertThat(body).isNotNull();
        assertThat(body).doesNotContain("ownerId");
        assertThat(body).doesNotContain("owner_id");
        assertThat(body).doesNotContain("storageUri");
        assertThat(body).doesNotContain("assetId");
        assertThat(body).doesNotContain("taskName");
        assertThat(body).doesNotContain("token");
    }

    @Test
    @DisplayName("session body with unknown field is rejected")
    void sessionRejectsUnknownFields() {
        ResponseEntity<String> response = postRaw("/public/v1/session", "{\"ownerId\":1}", null);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        JsonNode body = json(response);
        assertThat(body.path("code").asText()).isEqualTo("TOOLBOX_001");
        assertThat(body.path("messageKey").asText()).isEqualTo("request.invalid");
        assertThat(body.path("details").path("field").asText()).isEqualTo("ownerId");
        assertThat(body.path("details").path("reason").asText()).isEqualTo("unknownField");
    }

    @Test
    @DisplayName("event fields ownerId, storageUri, taskName and assetId are rejected")
    void eventsRejectUnknownFields() {
        TestSession session = createSession();
        for (String field : new String[] {"ownerId", "storageUri", "taskName", "assetId"}) {
            String body = "{\"events\":[{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"entry_click\","
                    + "\"category\":\"copybook\",\"toolId\":null,\"occurredAt\":\"" + Instant.now()
                    + "\",\"operationId\":null,\"schemaVersion\":1,\"" + field + "\":\"x\"}]}";

            ResponseEntity<String> response = postRaw("/public/v1/events", body, session);

            assertThat(response.getStatusCode().value()).as(field).isEqualTo(422);
            JsonNode json = json(response);
            assertThat(json.path("code").asText()).isEqualTo("TOOLBOX_001");
            assertThat(json.path("details").path("field").asText()).isEqualTo(field);
            assertThat(json.path("details").path("reason").asText()).isEqualTo("unknownField");
        }
        Long events = jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_event", Long.class);
        assertThat(events).isZero();
    }

    /**
     * 构造契约工具清单。
     *
     * @return 工具标识到分类的映射
     */
    private static Map<String, String> expectedTools() {
        Map<String, String> tools = new LinkedHashMap<>();
        tools.put("pdf.images", "pdf");
        tools.put("pdf.text", "pdf");
        tools.put("pdf.markdown", "pdf");
        tools.put("pdf.merge", "pdf");
        tools.put("pdf.split", "pdf");
        tools.put("pdf.crop", "pdf");
        tools.put("pdf.render", "pdf");
        tools.put("pdf.extract", "pdf");
        tools.put("worksheet.chinese-dictation", "copybook");
        tools.put("worksheet.chinese-pinyin", "copybook");
        tools.put("worksheet.english-dictation", "words");
        tools.put("worksheet.english-meaning", "words");
        return Map.copyOf(tools);
    }
}
