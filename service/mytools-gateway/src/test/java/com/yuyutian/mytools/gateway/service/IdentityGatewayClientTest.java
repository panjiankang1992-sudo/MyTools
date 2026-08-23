package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.IdentityGatewayModels.LoginRequest;
import com.yuyutian.mytools.gateway.model.IdentityGatewayModels.RefreshRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class IdentityGatewayClientTest {
    @Test
    void shouldRebuildLoginPayloadAndForwardCorrelationOnly() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://identity/api/v1/identity/login"))
                .andExpect(header("X-Correlation-Id", "correlation"))
                .andExpect(content().json("""
                        {"username":"user","password":"secret","deviceId":"web"}
                        """))
                .andRespond(withSuccess("""
                        {"accessToken":"access","refreshToken":"refresh","tokenType":"Bearer",
                         "expiresIn":300,"refreshExpiresIn":3600,
                         "sessionId":"00000000-0000-0000-0000-000000000001"}
                        """, MediaType.APPLICATION_JSON));
        IdentityGatewayClient client = new IdentityGatewayClient(restTemplate, properties());

        var result = client.login(new LoginRequest("user", "secret", "web"), "correlation");

        assertThat(result.accessToken()).isEqualTo("access");
        server.verify();
    }

    @Test
    void shouldMapIdentityClientAndAvailabilityFailures() {
        assertFailure(HttpStatus.UNAUTHORIZED, GatewayUnauthorizedException.class);
        assertFailure(HttpStatus.BAD_REQUEST, GatewayBadRequestException.class);
        assertFailure(HttpStatus.SERVICE_UNAVAILABLE, GatewayDownstreamException.class);
    }

    @Test
    void shouldRevokeOnlyTrustedSessionWithInternalToken() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        java.util.UUID sessionId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000009");
        server.expect(requestTo("http://identity/internal/v1/identity/sessions/" + sessionId
                        + "/revoke?reason=USER_LOGOUT"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer identity-token"))
                .andExpect(header("X-Correlation-Id", "correlation"))
                .andRespond(withSuccess());
        IdentityGatewayClient client = new IdentityGatewayClient(restTemplate, properties());

        client.logout(sessionId, "correlation");

        server.verify();
    }

    private void assertFailure(HttpStatus status, Class<? extends RuntimeException> expected) {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://identity/api/v1/identity/refresh"))
                .andRespond(withStatus(status));
        IdentityGatewayClient client = new IdentityGatewayClient(restTemplate, properties());

        assertThatThrownBy(() -> client.refresh(new RefreshRequest("refresh"), "correlation"))
                .isInstanceOf(expected);
        server.verify();
    }

    private GatewayProperties properties() {
        return new GatewayProperties(GatewayProperties.IdentityMode.LEGACY, true, false, Set.of(),
                false, Set.of(), "http://mytools", "http://identity", "http://reader", "http://drive",
                "gateway-token", "identity-token", "reader-token", "drive-token", 1000, 3000);
    }
}
