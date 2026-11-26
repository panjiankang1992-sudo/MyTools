package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 统计开关关闭时的行为测试：必须明确返回 TOOLBOX_008，而不是回落演示数据或伪造零。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "toolbox.stats-enabled=false")
@ActiveProfiles("test")
class StatsDisabledApiTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("stats disabled makes GET /stats return TOOLBOX_008")
    void statsDisabledReturnsServiceUnavailable() throws Exception {
        String body = rest.getForEntity("/public/v1/stats?days=7&category=all", String.class).getBody();
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.path("code").asText()).isEqualTo("TOOLBOX_008");
        assertThat(json.path("messageKey").asText()).isEqualTo("service.unavailable");
        assertThat(json.has("data")).isFalse();
        assertThat(json.path("requestId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("tools endpoint still reports stats capability as disabled")
    void toolsStillAvailableAndReportStatsDisabled() throws Exception {
        JsonNode json = objectMapper.readTree(rest.getForEntity("/public/v1/tools", String.class).getBody());

        assertThat(json.path("code").asText()).isEqualTo("OK");
        JsonNode capabilities = json.path("data").path("capabilities");
        boolean statsDisabled = false;
        for (JsonNode capability : capabilities) {
            if ("stats".equals(capability.path("id").asText())) {
                statsDisabled = !capability.path("enabled").asBoolean();
                assertThat(capability.path("reasonKey").asText()).isEqualTo("capability.statsDisabled");
            }
        }
        assertThat(statsDisabled).isTrue();
    }
}
