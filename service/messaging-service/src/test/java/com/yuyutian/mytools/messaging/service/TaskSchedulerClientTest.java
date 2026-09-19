package com.yuyutian.mytools.messaging.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TaskSchedulerClientTest {

    @Test
    void shouldSendBusinessToken() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://scheduler");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID taskId = UUID.randomUUID();
        server.expect(requestTo("http://scheduler/api/v1/task-instances"))
                .andExpect(header("X-Task-Business-Token", "business-secret"))
                .andRespond(withSuccess("{\"id\":\"" + taskId + "\"}", MediaType.APPLICATION_JSON));

        TaskSchedulerClient client = new TaskSchedulerClient(builder.build(), "business-secret");
        client.createAttachmentDownloadTask(UUID.randomUUID());

        server.verify();
    }
}
