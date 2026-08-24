package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.DshGatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DshGatewayClientTest {
    @Test
    void shouldBindTrustedOwnerForSessionLifecycle() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://dsh/internal/v1/dsh/sessions?ownerId=55"))
                .andExpect(method(GET)).andExpect(header("Authorization", "Bearer dsh-token"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://dsh/internal/v1/dsh/sessions/session-1?ownerId=55"))
                .andExpect(method(DELETE)).andRespond(withSuccess());
        DshGatewayClient client = new DshGatewayClient(restTemplate, properties());

        assertThat(client.list(55L, "correlation")).isEmpty();
        client.archive("session-1", 55L, "correlation");
        server.verify();
    }

    private DshGatewayProperties properties() {
        return new DshGatewayProperties(true, "http://dsh", "dsh-token");
    }
}
