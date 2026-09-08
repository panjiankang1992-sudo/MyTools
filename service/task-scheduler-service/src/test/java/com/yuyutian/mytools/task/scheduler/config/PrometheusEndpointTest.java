package com.yuyutian.mytools.task.scheduler.config;

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
        "management.endpoints.web.exposure.include=health,info,metrics,prometheus",
        "management.metrics.tags.application=task-scheduler-service"
})
@AutoConfigureObservability
@ImportAutoConfiguration(PrometheusMetricsExportAutoConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PrometheusEndpointTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldExportSchedulerReliabilityMetricsWithApplicationLabel() {
        var response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertEquals(200, response.getStatusCode().value());
        String body = response.getBody();
        assertTrue(containsMetric(body, "task_outbox_oldest_age_seconds"), body);
        assertTrue(containsMetric(body, "task_executor_clusters_unavailable"), body);
        assertTrue(containsMetric(body, "task_executor_tasks_blocked"), body);
        assertTrue(containsMetric(body, "task_queue_depth"), body);
        assertTrue(containsMetric(body, "task_queue_wait_seconds"), body);
        assertTrue(containsMetric(body, "task_execution_running"), body);
        assertTrue(containsMetric(body, "task_lease_lost_total"), body);
        assertTrue(containsMetric(body, "task_execution_seconds_count"), body);
        assertTrue(containsMetric(body, "task_execution_seconds_sum"), body);
    }

    private boolean containsMetric(String body, String metric) {
        return body != null && Pattern.compile("(?m)^" + Pattern.quote(metric)
                + "\\{[^}]*application=\\\"task-scheduler-service\\\"[^}]*} ").matcher(body).find();
    }
}
