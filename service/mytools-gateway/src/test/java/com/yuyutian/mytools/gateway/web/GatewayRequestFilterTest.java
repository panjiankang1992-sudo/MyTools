package com.yuyutian.mytools.gateway.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.PrincipalValidator;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayRequestFilterTest {

    @Test
    void onlyExactTicketFilesReachThePlaybackControllerWithoutSessionHeaders() throws Exception {
        var config = mock(GatewayProperties.class);
        when(config.mediaRouteEnabled()).thenReturn(true);
        var filter = new GatewayRequestFilter(mock(PrincipalValidator.class), config);
        String root = "/api/app/v1/media/tickets/" + "b".repeat(32) + "/";
        for (String name : List.of("master.m3u8", "v480.m3u8", "v480-00001.ts", "mobile.mp4")) {
            var chain = new MockFilterChain();
            filter.doFilter(new MockHttpServletRequest("GET", root + name), new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isNotNull();
        }
        for (String name : List.of("x.m3u8", "../source", "v480-1.ts", "mobile.mp4/extra")) {
            var response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", root + name), response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(401);
        }
    }

    @Test
    void videoRouteRequiresAuthenticationOnBothDocumentedPrefixes() throws Exception {
        var filter = new GatewayRequestFilter(mock(PrincipalValidator.class), properties(false));
        for (var uri : new String[]{"/api/app/v1/video-generation/models", "/api/video-generation/models"}) {
            var request = new MockHttpServletRequest("GET", uri);
            request.addHeader("X-Owner-Id", "55");
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).as(uri).isEqualTo(401);
            assertThat(chain.getRequest()).as(uri).isNull();
        }
    }

    @Test
    void videoPlaybackTicketIsItsOwnCredential() throws Exception {
        // 原生播放器拿不到登录头，票据随机且绑定所有者，因此这条路径不要求主体。
        var filter = new GatewayRequestFilter(mock(PrincipalValidator.class), properties(false));
        var request = new MockHttpServletRequest("GET", "/api/app/v1/video-generation/tickets/" + "a".repeat(32));
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        // 非 32 位十六进制的票据路径仍然要求认证，不能被当作播放路径放行。
        var malformed = new MockHttpServletRequest("GET", "/api/app/v1/video-generation/tickets/short");
        var malformedResponse = new MockHttpServletResponse();
        filter.doFilter(malformed, malformedResponse, new MockFilterChain());
        assertThat(malformedResponse.getStatus()).isEqualTo(401);
    }

    @Test
    void imageRouteRequiresAuthenticationEvenBeforeModelActivation() throws Exception {
        var filter = new GatewayRequestFilter(mock(PrincipalValidator.class), properties(false));
        var request = new MockHttpServletRequest("GET", "/api/app/v1/image-generation/models");
        request.addHeader("X-Owner-Id", "55");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void shouldNotValidateOrCallDownstreamWhenReaderRouteIsDisabled() throws Exception {
        PrincipalValidator validator = mock(PrincipalValidator.class);
        GatewayRequestFilter filter = new GatewayRequestFilter(validator, properties(false));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/app/v1/reader/shelves");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(validator, never()).validate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldReturnStableUnauthorizedResponseBeforeController() throws Exception {
        GatewayRequestFilter filter = new GatewayRequestFilter(mock(PrincipalValidator.class), properties(true));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/app/v1/reader/shelves");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("GATEWAY_001");
        assertThat(response.getHeader("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void shouldRejectAuthenticatedPrincipalOutsideAllowlist() throws Exception {
        PrincipalValidator validator = mock(PrincipalValidator.class);
        when(validator.validate("token")).thenReturn(new GatewayPrincipal(56L, "user", List.of("USER"), null));
        GatewayRequestFilter filter = new GatewayRequestFilter(validator, properties(true));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/app/v1/reader/shelves");
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("GATEWAY_002");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void shouldPassAllowlistedPrincipalToController() throws Exception {
        PrincipalValidator validator = mock(PrincipalValidator.class);
        when(validator.validate("token")).thenReturn(new GatewayPrincipal(55L, "user", List.of("USER"), null));
        GatewayRequestFilter filter = new GatewayRequestFilter(validator, properties(true));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/app/v1/reader/shelves");
        request.addHeader("Authorization", "Bearer token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE))
                .isEqualTo(new GatewayPrincipal(55L, "user", List.of("USER"), null));
    }

    @Test
    void shouldNotValidateDriveRequestWhenDriveRouteIsDisabled() throws Exception {
        PrincipalValidator validator = mock(PrincipalValidator.class);
        GatewayRequestFilter filter = new GatewayRequestFilter(validator, properties(false, false));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/app/v1/drive/accounts/00000000-0000-0000-0000-000000000001/items");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(validator, never()).validate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldInjectAllowlistedPrincipalForEnabledDriveRoute() throws Exception {
        PrincipalValidator validator = mock(PrincipalValidator.class);
        when(validator.validate("token")).thenReturn(new GatewayPrincipal(55L, "user", List.of("USER"), null));
        GatewayRequestFilter filter = new GatewayRequestFilter(validator, properties(false, true));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/app/v1/drive/accounts/00000000-0000-0000-0000-000000000001/items");
        request.addHeader("Authorization", "Bearer token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE))
                .isEqualTo(new GatewayPrincipal(55L, "user", List.of("USER"), null));
    }

    @Test
    void shouldInjectAllowlistedPrincipalForEnabledDownloadRoute() throws Exception {
        PrincipalValidator validator = mock(PrincipalValidator.class);
        when(validator.validate("token")).thenReturn(new GatewayPrincipal(55L, "user", List.of("USER"), null));
        GatewayRequestFilter filter = new GatewayRequestFilter(validator, properties(false, false, true));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/app/v1/downloads/http");
        request.addHeader("Authorization", "Bearer token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE))
                .isEqualTo(new GatewayPrincipal(55L, "user", List.of("USER"), null));
    }

    @Test
    void shouldAuthenticateIdentityLogoutWithoutApplyingTenantAllowlist() throws Exception {
        PrincipalValidator validator = mock(PrincipalValidator.class);
        java.util.UUID sessionId = java.util.UUID.randomUUID();
        when(validator.validate("token"))
                .thenReturn(new GatewayPrincipal(99L, "user", List.of("USER"), sessionId));
        GatewayProperties properties = new GatewayProperties(GatewayProperties.IdentityMode.DUAL,
                true, false, Set.of(), false, Set.of(), false, Set.of(), "http://mytools", "http://identity",
                "http://reader", "http://drive", "http://download", "gateway-token", "identity-token",
                "reader-token", "drive-token", "download-token", 1000, 3000, false, "", "", false, "", "");
        GatewayRequestFilter filter = new GatewayRequestFilter(validator, properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/app/v1/identity/logout");
        request.addHeader("Authorization", "Bearer token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE))
                .isEqualTo(new GatewayPrincipal(99L, "user", List.of("USER"), sessionId));
    }

    @Test
    void shouldAuthenticateEnabledMediaRouteWithoutTenantAllowlist() throws Exception {
        PrincipalValidator validator = mock(PrincipalValidator.class);
        when(validator.validate("token"))
                .thenReturn(new GatewayPrincipal(77L, "user", List.of("USER"), null));
        GatewayProperties properties = new GatewayProperties(GatewayProperties.IdentityMode.LEGACY,
                false, false, Set.of(), false, Set.of(), false, Set.of(), "http://mytools",
                "http://identity", "http://reader", "http://drive", "http://download",
                "gateway-token", "identity-token", "reader-token", "drive-token",
                "download-token", 1000, 3000, true, "http://media", "media-token", false, "", "");
        GatewayRequestFilter filter = new GatewayRequestFilter(validator, properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/app/v1/media/items");
        request.addHeader("Authorization", "Bearer token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE))
                .isEqualTo(new GatewayPrincipal(77L, "user", List.of("USER"), null));
    }

    @Test
    void shouldNotRequireAuthorizationForAudiobookPlaybackTicket() throws Exception {
        PrincipalValidator validator = mock(PrincipalValidator.class);
        GatewayRequestFilter filter = new GatewayRequestFilter(validator, properties(true));
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/api/app/v1/audiobook-playback/tickets/0123456789abcdef0123456789abcdef");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(validator, never()).validate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldNotRequireAuthorizationForAudiobookExportTicket() throws Exception {
        PrincipalValidator validator = mock(PrincipalValidator.class);
        GatewayRequestFilter filter = new GatewayRequestFilter(validator, properties(true));
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/api/app/v1/audiobook-export/tickets/0123456789abcdef0123456789abcdef");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(validator, never()).validate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldAuthenticateEnabledMessagingRoute() throws Exception {
        PrincipalValidator validator=mock(PrincipalValidator.class);when(validator.validate("token")).thenReturn(new GatewayPrincipal(88L,"user",List.of("USER"),null));GatewayProperties properties=new GatewayProperties(GatewayProperties.IdentityMode.LEGACY,false,false,Set.of(),false,Set.of(),false,Set.of(),"http://mytools","http://identity","http://reader","http://drive","http://download","gateway-token","identity-token","reader-token","drive-token","download-token",1000,3000,false,"","",true,"http://messaging","messaging-token");GatewayRequestFilter filter=new GatewayRequestFilter(validator,properties);MockHttpServletRequest request=new MockHttpServletRequest("GET","/api/app/v1/messages");request.addHeader("Authorization","Bearer token");MockFilterChain chain=new MockFilterChain();filter.doFilter(request,new MockHttpServletResponse(),chain);assertThat(chain.getRequest()).isNotNull();assertThat(request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE)).isEqualTo(new GatewayPrincipal(88L,"user",List.of("USER"),null));
    }

    @Test
    void shouldAuthenticateEnabledCatalogAndDshRoutes() throws Exception {
        PrincipalValidator validator = mock(PrincipalValidator.class);
        when(validator.validate("token"))
                .thenReturn(new GatewayPrincipal(88L, "user", List.of("USER"), null));
        GatewayRequestFilter filter = new GatewayRequestFilter(validator, properties(false),
                new com.yuyutian.mytools.gateway.config.AppCatalogGatewayProperties(true, "", ""),
                new com.yuyutian.mytools.gateway.config.DshGatewayProperties(true, "", ""));

        for (String uri : List.of("/api/app/v1/catalog", "/api/app/v1/dsh/sessions")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
            request.addHeader("Authorization", "Bearer token");
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isNotNull();
            assertThat(request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE))
                    .isEqualTo(new GatewayPrincipal(88L, "user", List.of("USER"), null));
        }
    }

    @Test
    void recordsOnlyFixedModulesAndIndependentServerTrace() throws Exception {
        PrincipalValidator validator = mock(PrincipalValidator.class);
        when(validator.validate("private-access-token"))
                .thenReturn(new GatewayPrincipal(55L, "private-username", List.of("USER"), null));
        GatewayRequestFilter filter = new GatewayRequestFilter(validator, properties(true));
        Logger logger = (Logger) LoggerFactory.getLogger(GatewayRequestFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        String clientCorrelation = "00000000-0000-0000-0000-000000005555";
        MDC.put("gatewayObservationModule", "previous-private-module");
        MDC.put("gatewayObservationTrace", "previous-private-trace");
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/app/v1/reader/private-book-title");
            request.setQueryString("ticket=private-query-ticket");
            request.addHeader("Authorization", "Bearer private-access-token");
            request.addHeader("X-Correlation-Id", clientCorrelation);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (ignoredRequest, result) ->
                    ((jakarta.servlet.http.HttpServletResponse) result).setStatus(202));

            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).hasSize(3);
            assertThat(messages.get(0)).contains("phase=received module=READER");
            assertThat(messages.get(1)).contains("phase=auth-complete", "authenticated=true", "status=200");
            assertThat(messages.get(2)).contains("phase=upstream-complete", "forwarded=true", "completed=true", "status=202");
            String trace = messages.get(0).split("trace=", 2)[1].split(" ", 2)[0];
            assertThat(java.util.UUID.fromString(trace).toString()).isEqualTo(trace);
            assertThat(messages).allSatisfy(message -> assertThat(message).contains("trace=" + trace)
                    .doesNotContain("private-", clientCorrelation, "/api/", "Authorization", "Bearer"));
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
            assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(clientCorrelation);
            assertThat(MDC.get("gatewayObservationModule")).isEqualTo("previous-private-module");
            assertThat(MDC.get("gatewayObservationTrace")).isEqualTo("previous-private-trace");
        } finally {
            MDC.remove("gatewayObservationModule");
            MDC.remove("gatewayObservationTrace");
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void rejectedAuthenticationStopsBeforeUpstreamAndDoesNotLogSensitiveRouteData() throws Exception {
        GatewayRequestFilter filter = new GatewayRequestFilter(mock(PrincipalValidator.class), properties(true));
        Logger logger = (Logger) LoggerFactory.getLogger(GatewayRequestFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/app/v1/connectivity/bootstrap");
            request.setQueryString("private-token=do-not-log");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message).contains("phase=auth-complete module=CONNECTIVITY", "authenticated=false", "status=401"))
                    .anySatisfy(message -> assertThat(message).contains("phase=upstream-complete", "forwarded=false", "status=401"))
                    .allSatisfy(message -> assertThat(message).doesNotContain("private-token", "do-not-log", "/api/"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void escapingUpstreamExceptionIsObservedWithoutLoggingItsMessage() throws Exception {
        GatewayRequestFilter filter = new GatewayRequestFilter(mock(PrincipalValidator.class), properties(false));
        Logger logger = (Logger) LoggerFactory.getLogger(GatewayRequestFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/private-user-resource");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> filter.doFilter(request,
                    new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> {
                        throw new jakarta.servlet.ServletException("private-upstream-token");
                    })).isInstanceOf(jakarta.servlet.ServletException.class);

            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message).contains("phase=upstream-complete module=OTHER", "completed=false", "status=500"))
                    .allSatisfy(message -> assertThat(message).doesNotContain("private-user-resource", "private-upstream-token"));
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private GatewayProperties properties(boolean enabled) {
        return properties(enabled, false);
    }

    private GatewayProperties properties(boolean readerEnabled, boolean driveEnabled) {
        return properties(readerEnabled, driveEnabled, false);
    }

    private GatewayProperties properties(boolean readerEnabled, boolean driveEnabled, boolean downloadEnabled) {
        return new GatewayProperties(GatewayProperties.IdentityMode.LEGACY, false, readerEnabled, Set.of(55L),
                driveEnabled, Set.of(55L), downloadEnabled, Set.of(55L), "http://mytools", "http://identity", "http://reader", "http://drive",
                "http://download", "gateway-token", "identity-token", "reader-token", "drive-token", "download-token", 1000, 3000, false, "", "", false, "", "");
    }
}
