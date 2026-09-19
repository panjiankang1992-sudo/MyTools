package com.yuyutian.mytools.auth.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.auth.mapper.RegistrationMailDeliveryOutboxMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withAccepted;

class RegistrationMailDeliveryPublisherTest {

    @Test
    void shouldNotDeliverPendingRecordsInShadowMode() {
        RegistrationMailSidecarProperties properties = properties();
        properties.setMode("SHADOW");
        RegistrationMailDeliveryOutboxMapper mapper = mock(RegistrationMailDeliveryOutboxMapper.class);
        RegistrationMailDeliveryPayloadCipher cipher = new RegistrationMailDeliveryPayloadCipher(
                properties, new ObjectMapper());

        new RegistrationMailDeliveryPublisher(properties, mapper, cipher, RestClient.builder()).relay();

        verify(mapper, never()).findReady(any(LocalDateTime.class), eq(50));
    }

    @Test
    void shouldSubmitIdempotentDeliveryAndAcknowledge() {
        RegistrationMailSidecarProperties properties = properties();
        RegistrationMailDeliveryPayloadCipher cipher = new RegistrationMailDeliveryPayloadCipher(
                properties, new ObjectMapper());
        RegistrationMailDeliveryOutbox record = cipher.encrypt(
                91L, "user@example.com", "123456", LocalDateTime.now());
        RegistrationMailDeliveryOutboxMapper mapper = mock(RegistrationMailDeliveryOutboxMapper.class);
        when(mapper.findReady(any(LocalDateTime.class), eq(50))).thenReturn(List.of(record));
        when(mapper.claim(eq(91L), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://messaging.test/internal/v1/deliveries"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer internal-token"))
                .andExpect(jsonPath("$.idempotencyKey").value("registration-code:91"))
                .andExpect(jsonPath("$.recipient").value("user@example.com"))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("123456")))
                .andRespond(withAccepted().contentType(MediaType.APPLICATION_JSON));

        new RegistrationMailDeliveryPublisher(properties, mapper, cipher, builder).relay();

        server.verify();
        verify(mapper).acknowledge(eq(91L), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    private RegistrationMailSidecarProperties properties() {
        RegistrationMailSidecarProperties properties = new RegistrationMailSidecarProperties();
        properties.setMode("PRIMARY");
        properties.setServiceUrl("http://messaging.test");
        properties.setInternalToken("internal-token");
        properties.setDeliveryEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        return properties;
    }
}
