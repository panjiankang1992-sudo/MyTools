package com.yuyutian.mytools.automation.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 消息附件任务客户端契约测试。
 */
class MessagingClientTest {

    @Test
    void shouldCreateIdempotentCompletionEmail() {
        UUID runId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://messaging.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://messaging.test/internal/v1/deliveries"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer messaging-token"))
                .andExpect(jsonPath("$.ownerId").value(17))
                .andExpect(jsonPath("$.idempotencyKey").value("automation-completion-" + runId))
                .andExpect(jsonPath("$.channelType").value("EMAIL"))
                .andExpect(jsonPath("$.recipient").value("owner@example.test"))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("SUCCEEDED")))
                .andRespond(withSuccess(response(deliveryId, "ACCEPTED"), MediaType.APPLICATION_JSON));
        MessagingClient client = new MessagingClient(builder.build(), "messaging-token");

        assertThat(client.createCompletionEmail(runId, 17L, "owner@example.test", "SUCCEEDED", 2).status())
                .isEqualTo("ACCEPTED");
        server.verify();
    }

    @Test
    void shouldUseOwnerBoundAttachmentRoutes() {
        UUID messageId = UUID.randomUUID();
        UUID partId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://messaging.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String createUrl = "http://messaging.test/internal/v1/inbound-messages/" + messageId
                + "/parts/" + partId + "/download?ownerId=17";
        String jobUrl = "http://messaging.test/internal/v1/attachment-downloads/" + jobId;
        server.expect(requestTo(createUrl)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer messaging-token"))
                .andRespond(withSuccess(response(jobId, "QUEUED"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(jobUrl + "?ownerId=17")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer messaging-token"))
                .andRespond(withSuccess(response(jobId, "RUNNING"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(jobUrl + "/cancel?ownerId=17")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer messaging-token"))
                .andRespond(withSuccess(response(jobId, "CANCELLED"), MediaType.APPLICATION_JSON));
        MessagingClient client = new MessagingClient(builder.build(), "messaging-token");

        assertThat(client.createAttachment(messageId, partId, 17L).status()).isEqualTo("QUEUED");
        assertThat(client.attachment(jobId, 17L).status()).isEqualTo("RUNNING");
        assertThat(client.cancelAttachment(jobId, 17L).status()).isEqualTo("CANCELLED");
        server.verify();
    }

    private String response(UUID id, String status) {
        return "{\"id\":\"" + id + "\",\"status\":\"" + status + "\"}";
    }
}
