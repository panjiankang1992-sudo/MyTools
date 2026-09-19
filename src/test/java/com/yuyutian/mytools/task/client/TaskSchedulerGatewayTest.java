package com.yuyutian.mytools.task.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TaskSchedulerGatewayTest {

    @Test
    void shouldCreateTaskThroughSharedGateway() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        TaskSchedulerGateway gateway = gateway(restTemplate);
        UUID taskId = UUID.randomUUID();
        server.expect(requestTo("http://scheduler:23210/api/v1/task-instances"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(TaskSchedulerGateway.BUSINESS_TOKEN_HEADER, "business-secret"))
                .andExpect(header(TaskSchedulerGateway.SERVICE_ID_HEADER, "mytools-service"))
                .andExpect(jsonPath("$.taskName").value("sample_task"))
                .andExpect(jsonPath("$.parameters.value").value("ok"))
                .andExpect(jsonPath("$.requiredNodeLabels").isEmpty())
                .andRespond(withSuccess(taskJson(taskId, "QUEUED"), MediaType.APPLICATION_JSON));

        UUID created = gateway.create(
                "sample_task", "sample:key", "SAMPLE", "42", 50, Map.of("value", "ok"));

        assertThat(created).isEqualTo(taskId);
        server.verify();
    }

    @Test
    void shouldQueryCancelAndReadTaskResults() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        TaskSchedulerGateway gateway = gateway(restTemplate);
        UUID taskId = UUID.randomUUID();
        String taskPath = "http://scheduler:23210/api/v1/task-instances/" + taskId;
        server.expect(requestTo(taskPath)).andExpect(method(HttpMethod.GET))
                .andExpect(header(TaskSchedulerGateway.BUSINESS_TOKEN_HEADER, "business-secret"))
                .andRespond(withSuccess(taskJson(taskId, "RUNNING"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(taskPath + "/cancel")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(taskJson(taskId, "CANCELLING"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(taskPath + "/results")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"taskInstanceId":"%s","status":"CANCELLING","steps":[]}
                        """.formatted(taskId), MediaType.APPLICATION_JSON));

        assertThat(gateway.get(taskId).status()).isEqualTo("RUNNING");
        assertThat(gateway.cancel(taskId).status()).isEqualTo("CANCELLING");
        assertThat(gateway.getResults(taskId).steps()).isEmpty();
        server.verify();
    }

    @Test
    void shouldExposeStructuredSchedulerErrorAndRetryability() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        TaskSchedulerGateway gateway = gateway(restTemplate);
        UUID taskId = UUID.randomUUID();
        server.expect(requestTo("http://scheduler:23210/api/v1/task-instances/" + taskId))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"EXECUTION_STATE_CONFLICT\",\"message\":\"state changed\"}"));

        assertThatThrownBy(() -> gateway.get(taskId))
                .isInstanceOfSatisfying(TaskSchedulerGatewayException.class, exception -> {
                    assertThat(exception.statusCode()).isEqualTo(409);
                    assertThat(exception.errorCode()).isEqualTo("EXECUTION_STATE_CONFLICT");
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.getMessage()).isEqualTo("state changed");
                });
        server.verify();
    }

    private TaskSchedulerGateway gateway(RestTemplate restTemplate) {
        return new TaskSchedulerGateway(restTemplate, new ObjectMapper().findAndRegisterModules(),
                "http://scheduler:23210/", "business-secret");
    }

    private String taskJson(UUID taskId, String status) {
        return """
                {"id":"%s","taskName":"sample_task","idempotencyKey":"sample:key",
                 "businessType":"SAMPLE","businessId":"42","priority":50,"parameters":{},
                 "requiredNodeLabels":{},"status":"%s",
                 "createdAt":"2026-09-02T00:00:00Z","updatedAt":"2026-09-02T00:00:00Z"}
                """.formatted(taskId, status);
    }
}
