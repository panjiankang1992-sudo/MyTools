package com.yuyutian.mytools.task.scheduler.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalTokenFilterTest {

    @Test
    void shouldProtectImageDeploymentPath() {
        assertTrue(InternalTokenFilter.isProtectedPath("/internal/v1/image-generation-deployment"));
    }

    @Test
    void shouldProtectVideoDeploymentPath() {
        assertTrue(InternalTokenFilter.isProtectedPath("/internal/v1/video-generation-deployment"));
    }

    @Test
    void shouldRejectMissingTokenForProtectedPath() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(
                new TaskSecurityProperties(true, "scheduler-secret", "scheduler-secret"),
                new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/v1/executions/claim");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertTrue(response.getContentAsString().contains("UNAUTHORIZED"));
    }

    @Test
    void shouldAllowMatchingTokenForProtectedPath() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(
                new TaskSecurityProperties(true, "scheduler-secret", "scheduler-secret"),
                new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/v1/executions/claim");
        request.addHeader(InternalTokenFilter.INTERNAL_TOKEN_HEADER, "scheduler-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertFalse(chain.getRequest() == null);
    }

    @Test
    void shouldLeaveLeaseScopedTaskSdkPathOutsideNodeTokenFilter() {
        assertFalse(InternalTokenFilter.isProtectedPath(
                "/internal/v1/executions/22a0d211-9189-4f13-b23e-741f368de552/tasks/child"));
        assertTrue(InternalTokenFilter.isProtectedPath(
                "/internal/v1/executions/22a0d211-9189-4f13-b23e-741f368de552/steps/report"));
        assertTrue(InternalTokenFilter.isProtectedPath(
                "/internal/v1/outbox/events/22a0d211-9189-4f13-b23e-741f368de552/replay"));
        assertTrue(InternalTokenFilter.isProtectedPath(
                "/api/v1/execution-topology/nodes/22a0d211-9189-4f13-b23e-741f368de552/status"));
        assertTrue(InternalTokenFilter.isProtectedPath("/api/v1/task-instances"));
        assertTrue(InternalTokenFilter.isProtectedPath(
                "/api/v1/task-instances/22a0d211-9189-4f13-b23e-741f368de552/results"));
    }

    @Test
    void shouldUseDedicatedBusinessTokenForTaskInstanceApi() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(
                new TaskSecurityProperties(true, "executor-secret", "business-secret"),
                new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/task-instances/task-id");
        request.addHeader(InternalTokenFilter.BUSINESS_TOKEN_HEADER, "business-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertFalse(chain.getRequest() == null);
    }

    @Test
    void shouldBindBusinessServiceIdentityToItsOwnToken() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(
                new TaskSecurityProperties(true, "legacy-internal", "legacy-business", Map.of(),
                        Map.of("messaging-service", "messaging-secret", "drive-service", "drive-secret")),
                new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/task-instances");
        request.addHeader(InternalTokenFilter.SERVICE_ID_HEADER, "messaging-service");
        request.addHeader(InternalTokenFilter.BUSINESS_TOKEN_HEADER, "messaging-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertEquals("messaging-service",
                request.getAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE));
    }

    @Test
    void shouldRejectTokenBelongingToAnotherBusinessService() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(
                new TaskSecurityProperties(true, "legacy-internal", "legacy-business", Map.of(),
                        Map.of("messaging-service", "messaging-secret", "drive-service", "drive-secret")),
                new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/task-instances");
        request.addHeader(InternalTokenFilter.SERVICE_ID_HEADER, "messaging-service");
        request.addHeader(InternalTokenFilter.BUSINESS_TOKEN_HEADER, "drive-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }

    @Test
    void shouldRejectMissingServiceIdentityWhenIndependentCredentialsAreEnabled() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(
                new TaskSecurityProperties(true, "legacy-internal", "legacy-business", Map.of(),
                        Map.of("messaging-service", "messaging-secret")),
                new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/task-instances");
        request.addHeader(InternalTokenFilter.BUSINESS_TOKEN_HEADER, "legacy-business");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }

    @Test
    void shouldIgnoreBlankIndependentCredentialsAndKeepSharedTokenFallback() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(
                new TaskSecurityProperties(true, "legacy-internal", "legacy-business", Map.of(),
                        Map.of("messaging-service", "")),
                new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/task-instances/task-id");
        request.addHeader(InternalTokenFilter.BUSINESS_TOKEN_HEADER, "legacy-business");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertEquals("legacy-business-client",
                request.getAttribute(InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE));
    }

    @Test
    void shouldRejectInvalidOrSharedIndependentCredentialsAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new TaskSecurityProperties(
                true, "legacy-internal", "legacy-business", Map.of(), Map.of("Invalid", "secret")));
        assertThrows(IllegalArgumentException.class, () -> new TaskSecurityProperties(
                true, "legacy-internal", "legacy-business", Map.of(),
                Map.of("messaging-service", "same-secret", "drive-service", "same-secret")));
    }

    @Test
    void shouldKeepInternalAndBusinessIdentityPartitionsSeparate() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(
                new TaskSecurityProperties(true, "legacy-internal", "legacy-business",
                        Map.of("task-executor-service", "executor-secret"),
                        Map.of("messaging-service", "messaging-secret")),
                new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest allowed = new MockHttpServletRequest("POST", "/internal/v1/executions/claim");
        allowed.addHeader(InternalTokenFilter.SERVICE_ID_HEADER, "task-executor-service");
        allowed.addHeader(InternalTokenFilter.INTERNAL_TOKEN_HEADER, "executor-secret");
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();

        filter.doFilter(allowed, allowedResponse, new MockFilterChain());

        assertEquals(HttpServletResponse.SC_OK, allowedResponse.getStatus());
        MockHttpServletRequest denied = new MockHttpServletRequest("POST", "/internal/v1/executions/claim");
        denied.addHeader(InternalTokenFilter.SERVICE_ID_HEADER, "messaging-service");
        denied.addHeader(InternalTokenFilter.INTERNAL_TOKEN_HEADER, "messaging-secret");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();

        filter.doFilter(denied, deniedResponse, new MockFilterChain());

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, deniedResponse.getStatus());
    }
}
