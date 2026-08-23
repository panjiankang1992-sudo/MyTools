package com.yuyutian.mytools.auth.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withAccepted;

class RegistrationMailSidecarPublisherTest {

    @Test
    void shouldSubmitStableDeliveryAfterLegacySuccess() {
        RegistrationMailSidecarProperties properties = new RegistrationMailSidecarProperties();
        properties.setEnabled(true);
        properties.setServiceUrl("http://messaging.test");
        properties.setInternalToken("internal-token");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://messaging.test/internal/v1/deliveries"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer internal-token"))
                .andExpect(jsonPath("$.idempotencyKey").value("registration-code:91"))
                .andExpect(jsonPath("$.recipient").value("user@example.com"))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("123456")))
                .andRespond(withAccepted().contentType(MediaType.APPLICATION_JSON));

        new RegistrationMailSidecarPublisher(properties, builder)
                .publish(new RegistrationMailSent(91L, "user@example.com", "123456"));

        server.verify();
    }

    @Test
    void shouldDoNothingWhenSidecarIsDisabled() {
        RegistrationMailSidecarProperties properties = new RegistrationMailSidecarProperties();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        new RegistrationMailSidecarPublisher(properties, builder)
                .publish(new RegistrationMailSent(92L, "user@example.com", "654321"));

        server.verify();
    }
}
