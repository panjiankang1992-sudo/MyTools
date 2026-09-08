package com.yuyutian.mytools.task.executor.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.test.annotation.DirtiesContext;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "executor.require-non-root=false",
        "executor.work-root=${java.io.tmpdir}/mytools-executor-prometheus-${random.uuid}",
        "spring.task.scheduling.enabled=false",
        "management.endpoints.web.exposure.include=health,info,metrics,prometheus"
})
@AutoConfigureObservability
@ImportAutoConfiguration(PrometheusMetricsExportAutoConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PrometheusEndpointTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldExportJournalReliabilityMetricsWithApplicationLabel() {
        var response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertEquals(200, response.getStatusCode().value());
        String body = response.getBody();
        assertTrue(containsMetric(body, "task_executor_journal_readable", "1.0"));
        assertTrue(containsMetric(body, "task_executor_journal_pending", "0.0"));
        assertTrue(containsMetric(body, "task_executor_journal_diagnostic", "0.0"));
    }

    private boolean containsMetric(String body, String metric, String value) {
        return body != null && Pattern.compile("(?m)^" + Pattern.quote(metric)
                + "\\{[^}]*application=\\\"task-executor-service\\\"[^}]*} "
                + Pattern.quote(value) + "$").matcher(body).find();
    }
}
