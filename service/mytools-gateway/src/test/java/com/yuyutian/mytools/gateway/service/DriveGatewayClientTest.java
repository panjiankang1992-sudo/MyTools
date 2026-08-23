package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DriveGatewayClientTest {
    @Test
    void shouldSendOnlyBoundOwnerAndEncodedParentPath() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        UUID accountId = UUID.randomUUID();
        server.expect(requestTo("http://drive/internal/v1/drive/accounts/" + accountId
                        + "/items?ownerId=55&parentPath=books%20%26%202026"))
                .andExpect(method(GET)).andExpect(header("Authorization", "Bearer drive-token"))
                .andExpect(header("X-Correlation-Id", "correlation"))
                .andRespond(withSuccess("[]", org.springframework.http.MediaType.APPLICATION_JSON));
        DriveGatewayClient client = new DriveGatewayClient(restTemplate, properties());

        assertThat(client.items(accountId, 55L, "books & 2026", "correlation")).isEmpty();
        server.verify();
    }

    private GatewayProperties properties() {
        return new GatewayProperties(GatewayProperties.IdentityMode.LEGACY, false, false, Set.of(),
                true, Set.of(55L), false, Set.of(), "http://mytools", "http://identity", "http://reader", "http://drive",
                "http://download", "gateway-token", "identity-token", "reader-token", "drive-token", "download-token", 1000, 3000);
    }
}
