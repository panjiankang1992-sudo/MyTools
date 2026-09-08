package com.yuyutian.mytools.reader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TaskSchedulerClientHttpTest {

    @Test
    void shouldSendBusinessToken() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://scheduler");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID taskId = UUID.randomUUID();
        server.expect(requestTo("http://scheduler/api/v1/task-instances"))
                .andExpect(header("X-Task-Business-Token", "business-secret"))
                .andRespond(withSuccess("{\"id\":\"" + taskId + "\"}", MediaType.APPLICATION_JSON));

        TaskSchedulerClient client = new TaskSchedulerClient(builder.build(), new ObjectMapper(), "business-secret");
        client.createTask("reader_task", "reader-1", "READER", UUID.randomUUID(), 40, Map.of());

        server.verify();
    }
}
