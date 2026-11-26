package com.yuyutian.mytools.gateway.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yuyutian.mytools.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrincipalValidatorTest {

    @Test
    void shouldUseOnlyLegacyValidatorInDefaultMode() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        GatewayProperties properties = properties(GatewayProperties.IdentityMode.LEGACY);
        when(restTemplate.postForObject(eq("http://mytools/internal/v1/gateway/tokens/validate"),
                any(HttpEntity.class), eq(PrincipalValidator.ValidationResponse.class)))
                .thenReturn(new PrincipalValidator.ValidationResponse(true, 7L, "legacy", List.of("USER"), null));

        var principal = new PrincipalValidator(restTemplate, properties).validate("token");

        assertThat(principal.userId()).isEqualTo(7L);
        verify(restTemplate, never()).postForObject(eq("http://identity/internal/v1/identity/tokens/validate"),
                any(HttpEntity.class), eq(PrincipalValidator.ValidationResponse.class));
    }

    @Test
    void shouldFallbackToIdentityOnlyAfterLegacyReturnsInactiveInDualMode() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        GatewayProperties properties = properties(GatewayProperties.IdentityMode.DUAL);
        when(restTemplate.postForObject(eq("http://mytools/internal/v1/gateway/tokens/validate"),
                any(HttpEntity.class), eq(PrincipalValidator.ValidationResponse.class)))
                .thenReturn(new PrincipalValidator.ValidationResponse(false, null, null, List.of(), null));
        when(restTemplate.postForObject(eq("http://identity/internal/v1/identity/tokens/validate"),
                any(HttpEntity.class), eq(PrincipalValidator.ValidationResponse.class)))
                .thenReturn(new PrincipalValidator.ValidationResponse(true, 8L, "identity", List.of("USER"),
                        java.util.UUID.fromString("00000000-0000-0000-0000-000000000008")));

        var principal = new PrincipalValidator(restTemplate, properties).validate("token");

        assertThat(principal.userId()).isEqualTo(8L);
        assertThat(principal.sessionId()).isEqualTo(
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000008"));
    }

    @Test
    void shouldFailClosedWhenValidationResponseIsMissing() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class),
                eq(PrincipalValidator.ValidationResponse.class))).thenReturn(null);

        assertThatThrownBy(() -> new PrincipalValidator(restTemplate,
                properties(GatewayProperties.IdentityMode.IDENTITY)).validate("token"))
                .isInstanceOf(GatewayUnauthorizedException.class);
    }

    @Test
    void observesInternalValidationWithoutTokenPrincipalOrServerAddress() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(PrincipalValidator.ValidationResponse.class)))
                .thenReturn(new PrincipalValidator.ValidationResponse(true, 7L, "private-username", List.of("PRIVATE_ROLE"), null));
        Logger logger = (Logger) LoggerFactory.getLogger(PrincipalValidator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.put("gatewayObservationModule", "MEDIA");
        MDC.put("gatewayObservationTrace", "00000000-0000-4000-8000-000000000007");
        try {
            new PrincipalValidator(restTemplate, properties(GatewayProperties.IdentityMode.LEGACY)).validate("private-access-token");

            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .hasSize(2)
                    .anySatisfy(message -> assertThat(message).contains("phase=rpc-start module=MEDIA", "target=LEGACY"))
                    .anySatisfy(message -> assertThat(message).contains("phase=rpc-complete", "completed=true", "active=true", "status=0", "elapsedMs="))
                    .allSatisfy(message -> assertThat(message).doesNotContain("private-", "PRIVATE_ROLE", "http://", "/internal/", "gateway-token", "identity-token"));
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        } finally {
            MDC.remove("gatewayObservationModule");
            MDC.remove("gatewayObservationTrace");
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void rejectedInternalRpcLogsOnlyFixedOutcomeEvenWhenExceptionContainsSecrets() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(PrincipalValidator.ValidationResponse.class)))
                .thenThrow(new ResourceAccessException("private-token https://private-user-resource/internal/path"));
        Logger logger = (Logger) LoggerFactory.getLogger(PrincipalValidator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.put("gatewayObservationModule", "private-user-module");
        MDC.put("gatewayObservationTrace", "private-token");
        try {
            assertThatThrownBy(() -> new PrincipalValidator(restTemplate,
                    properties(GatewayProperties.IdentityMode.IDENTITY)).validate("private-access-token"))
                    .isInstanceOf(GatewayUnauthorizedException.class);

            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .hasSize(2)
                    .anySatisfy(message -> assertThat(message).contains("phase=rpc-complete module=OTHER trace=none", "target=IDENTITY", "completed=false", "active=false", "status=0"))
                    .allSatisfy(message -> assertThat(message).doesNotContain("private-", "http", "/internal/"));
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        } finally {
            MDC.remove("gatewayObservationModule");
            MDC.remove("gatewayObservationTrace");
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private GatewayProperties properties(GatewayProperties.IdentityMode mode) {
        return new GatewayProperties(mode, false, true, Set.of(7L, 8L), false, Set.of(), false, Set.of(),
                "http://mytools", "http://identity", "http://reader", "http://drive", "http://download",
                "gateway-token", "identity-token", "reader-token", "drive-token", "download-token", 1000, 3000, false, "", "", false, "", "");
    }
}
