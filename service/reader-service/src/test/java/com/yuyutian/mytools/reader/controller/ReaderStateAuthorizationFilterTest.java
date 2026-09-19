package com.yuyutian.mytools.reader.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderProperties;
import com.yuyutian.mytools.reader.controller.adaptation.ReaderAdaptationResponseFilter;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.service.InternalRequestAuthorizer;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReaderStateAuthorizationFilterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Bearer wrong-token", "Basic wrong-token", "bearer reader-test-token"})
    void authenticationFailureReturns401WithoutEnteringTheController(String authorization) throws Exception {
        var request = request(authorization);
        var response = new MockHttpServletResponse();
        var invoked = new AtomicBoolean();
        var filter = filter("reader-test-token");
        new ReaderAdaptationResponseFilter().doFilter(request, response,
                (req, res) -> filter.doFilter(req, res, (ignoredRequest, ignoredResponse) -> invoked.set(true)));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(mapper.readTree(response.getContentAsByteArray()).path("code").asText())
                .isEqualTo(ErrorCode.INTERNAL_UNAUTHORIZED.code());
        assertThat(response.getContentAsString()).doesNotContain("wrong-token", "reader-test-token", "Exception");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, private");
        assertThat(invoked).isFalse();
    }

    @Test
    void validBearerReachesTheController() throws Exception {
        var invoked = new AtomicBoolean();
        filter("reader-test-token").doFilter(request("Bearer reader-test-token"), new MockHttpServletResponse(),
                (req, res) -> invoked.set(true));
        assertThat(invoked).isTrue();
    }

    @Test
    void missingServerCredentialDoesNotAuthorizeAnEmptyBearer() throws Exception {
        var response = new MockHttpServletResponse();
        filter("").doFilter(request("Bearer "), response, (req, res) -> {
            throw new AssertionError("Unauthorized request reached controller");
        });
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void unrelatedRoutesKeepExistingBehavior() throws Exception {
        var request = new MockHttpServletRequest("GET", "/actuator/health");
        var invoked = new AtomicBoolean();
        filter("").doFilter(request, new MockHttpServletResponse(), (req, res) -> invoked.set(true));
        assertThat(invoked).isTrue();
    }

    @Test
    void downstreamFailuresAreNotMisclassifiedAsAuthenticationFailures() {
        assertThatThrownBy(() -> filter("reader-test-token").doFilter(request("Bearer reader-test-token"),
                new MockHttpServletResponse(), (req, res) -> { throw new ServletException("controller failure"); }))
                .isInstanceOf(ServletException.class).hasMessage("controller failure");
    }

    private ReaderStateAuthorizationFilter filter(String token) {
        var properties = new ReaderProperties("http://127.0.0.1", token, "managed", "http://127.0.0.1", "",
                "http://127.0.0.1", "", 1000, 2000, false, Set.of());
        return new ReaderStateAuthorizationFilter(new InternalRequestAuthorizer(properties), mapper);
    }

    private MockHttpServletRequest request(String authorization) {
        var request = new MockHttpServletRequest("GET", "/api/v1/reader-state/features/reader-adaptation");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }
}
