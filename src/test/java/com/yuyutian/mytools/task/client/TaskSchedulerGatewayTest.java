package com.yuyutian.mytools.task.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TaskSchedulerGatewayTest {

    @Test
    void shouldCreateTaskThroughSharedGateway() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        TaskSchedulerGateway gateway = new TaskSchedulerGateway(restTemplate, "http://scheduler:23210/");
        UUID taskId = UUID.randomUUID();
        server.expect(requestTo("http://scheduler:23210/api/v1/task-instances"))
                .andExpect(jsonPath("$.taskName").value("sample_task"))
                .andExpect(jsonPath("$.parameters.value").value("ok"))
                .andRespond(withSuccess("{\"id\":\"" + taskId + "\"}", MediaType.APPLICATION_JSON));

        UUID created = gateway.create(
                "sample_task", "sample:key", "SAMPLE", "42", 50, Map.of("value", "ok"));

        assertThat(created).isEqualTo(taskId);
        server.verify();
    }
}
