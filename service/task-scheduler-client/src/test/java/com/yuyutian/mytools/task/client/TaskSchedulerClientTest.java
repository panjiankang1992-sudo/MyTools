package com.yuyutian.mytools.task.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TaskSchedulerClientTest {

    @Test
    void shouldCreateQueryCancelAndReadResultsWithBusinessToken() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://scheduler");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID taskId = UUID.randomUUID();
        String taskJson = "{\"id\":\"" + taskId + "\",\"taskName\":\"message_send\","
                + "\"idempotencyKey\":\"message-1\",\"businessType\":\"MESSAGE\","
                + "\"businessId\":\"delivery-1\",\"priority\":80,\"parameters\":{},"
                + "\"requiredNodeLabels\":{},\"status\":\"QUEUED\"}";
        expect(server, "http://scheduler/api/v1/task-instances", HttpMethod.POST, taskJson);
        expect(server, "http://scheduler/api/v1/task-instances/" + taskId, HttpMethod.GET, taskJson);
        expect(server, "http://scheduler/api/v1/task-instances/" + taskId + "/cancel", HttpMethod.POST,
                taskJson.replace("QUEUED", "CANCELLING"));
        expect(server, "http://scheduler/api/v1/task-instances/" + taskId + "/results", HttpMethod.GET,
                "{\"taskInstanceId\":\"" + taskId + "\",\"status\":\"CANCELLING\",\"steps\":[]}");
        TaskSchedulerClient client = new TaskSchedulerClient(
                builder.build(), new ObjectMapper(), "secret", "messaging-service");

        assertThat(client.create(CreateTaskRequest.create("message_send", "message-1", "MESSAGE",
                "delivery-1", 80, Map.of())).id()).isEqualTo(taskId);
        assertThat(client.get(taskId).status()).isEqualTo("QUEUED");
        assertThat(client.cancel(taskId).status()).isEqualTo("CANCELLING");
        assertThat(client.getResults(taskId).steps()).isEmpty();
        server.verify();
    }

    @Test
    void shouldClassifyStructuredConflictAsNonRetryable() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://scheduler");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://scheduler/api/v1/task-instances"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"IDEMPOTENCY_CONFLICT\",\"message\":\"conflict\"}"));
        TaskSchedulerClient client = new TaskSchedulerClient(builder.build(), new ObjectMapper(), "secret");

        assertThatThrownBy(() -> client.create(CreateTaskRequest.create("message_send", "message-1",
                "MESSAGE", "delivery-1", 80, Map.of())))
                .isInstanceOfSatisfying(TaskSchedulerClientException.class, exception -> {
                    assertThat(exception.statusCode()).isEqualTo(409);
                    assertThat(exception.errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                    assertThat(exception.retryable()).isFalse();
                });
        server.verify();
    }

    private void expect(MockRestServiceServer server, String url, HttpMethod httpMethod, String response) {
        server.expect(requestTo(url)).andExpect(method(httpMethod))
                .andExpect(header(TaskSchedulerClient.BUSINESS_TOKEN_HEADER, "secret"))
                .andExpect(header(TaskSchedulerClient.SERVICE_ID_HEADER, "messaging-service"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }
}
