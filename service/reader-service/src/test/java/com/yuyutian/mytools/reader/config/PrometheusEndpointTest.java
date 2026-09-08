package com.yuyutian.mytools.reader.config;

import com.yuyutian.mytools.reader.model.AudiobookGenerationMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阅读服务有声书 Prometheus 指标端点测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.endpoints.web.exposure.include=health,info,metrics,prometheus",
        "management.metrics.tags.application=reader-service"
})
@AutoConfigureObservability
@ImportAutoConfiguration(PrometheusMetricsExportAutoConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PrometheusEndpointTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AudiobookGenerationMetrics metrics;

    /**
     * 验证不含用户和正文标签的有声书指标可以经 Prometheus 导出。
     */
    @Test
    void shouldExportAudiobookGovernanceMetricsWithApplicationLabel() {
        metrics.recordAccepted(AudiobookGenerationMode.FULL);
        metrics.recordTextProjected(AudiobookGenerationMode.FULL, 42L, 10L);
        metrics.recordRejected("CHARACTER_LIMIT");
        metrics.recordCompleted(AudiobookGenerationMode.FULL);
        metrics.recordTaskFailure(AudiobookGenerationMode.FULL, "SYNTHESIZING");

        var response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertEquals(200, response.getStatusCode().value());
        String body = response.getBody();
        assertTrue(containsMetric(body, "reader_audiobook_generation_accepted_total"), body);
        assertTrue(containsMetric(body, "reader_audiobook_text_characters_count"), body);
        assertTrue(containsMetric(body, "reader_audiobook_stage_duration_seconds_count"), body);
        assertTrue(containsMetric(body, "reader_audiobook_generation_rejected_total"), body);
        assertTrue(containsMetric(body, "reader_audiobook_generation_completed_total"), body);
        assertTrue(containsMetric(body, "reader_audiobook_generation_failed_total"), body);
    }

    private boolean containsMetric(String body, String metric) {
        return body != null && Pattern.compile("(?m)^" + Pattern.quote(metric)
                + "\\{[^}]*application=\\\"reader-service\\\"[^}]*} ").matcher(body).find();
    }
}
