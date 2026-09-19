package com.yuyutian.mytools.drive.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DriveTaskSchedulerClientTest {

    @Test
    void shouldSendBusinessToken() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://scheduler");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID taskId = UUID.randomUUID();
        server.expect(requestTo("http://scheduler/api/v1/task-instances"))
                .andExpect(header("X-Task-Business-Token", "business-secret"))
                .andRespond(withSuccess("{\"id\":\"" + taskId + "\"}", MediaType.APPLICATION_JSON));

        DriveTaskSchedulerClient client = new DriveTaskSchedulerClient(builder.build(), "business-secret");
        client.createIndexTask(UUID.randomUUID(), UUID.randomUUID(), "request-1");

        server.verify();
    }
}
