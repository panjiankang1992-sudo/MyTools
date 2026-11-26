package com.yuyutian.mytools.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.config.RssGatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.*;
import com.yuyutian.mytools.gateway.web.GatewayEnvelopeAdvice;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.web.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RssGatewayControllerTest {
    private static final String TOKEN = "offline-rss-gateway-token-32-characters";
    @Test void bothPrefixesSendTrustedOwnerAndPreserveEnvelope() throws Exception {
        for (String prefix : List.of("/api/v1/rss", "/api/app/v1/rss")) {
            var rest = new RestTemplate(); var server = MockRestServiceServer.createServer(rest);
            server.expect(requestTo("http://rss/internal/v1/rss/subscriptions"))
                    .andExpect(header("X-RSS-Owner", "55")).andExpect(header("Authorization", "Bearer " + TOKEN))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
            mvc(client(rest, true)).perform(get(prefix + "/subscriptions").header("X-RSS-Owner", "99")
                    .requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE, principal()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0000")).andExpect(jsonPath("$.data").isArray());
            server.verify();
        }
    }
    @Test void rejectsForgedQueriesUnknownPathsAndAnonymous() throws Exception {
        var client = mock(RssGatewayClient.class);
        var mvc = mvc(client);
        mvc.perform(get("/api/v1/rss/subscriptions?ownerId=99").requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE, principal())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rss/internal/admin").requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE, principal())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/rss/entries?q=a&q=b").requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE, principal())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rss/subscriptions")).andExpect(status().isUnauthorized());
        verifyNoInteractions(client);
    }
    @Test void queryValuesCannotInjectOwnerOrOtherParameters() throws Exception {
        var rest = new RestTemplate(); var server = MockRestServiceServer.createServer(rest);
        server.expect(requestTo("http://rss/internal/v1/rss/entries?q=a%26ownerId%3D99"))
                .andExpect(header("X-RSS-Owner", "55")).andRespond(withSuccess("{\"items\":[],\"nextCursor\":\"\"}", MediaType.APPLICATION_JSON));
        mvc(client(rest, true)).perform(get("/api/v1/rss/entries").param("q", "a&ownerId=99").requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE, principal())).andExpect(status().isOk());
        server.verify();
    }
    @Test void conflictKeepsStableCodeWithoutDownstreamDetails() throws Exception {
        var rest = new RestTemplate(); var server = MockRestServiceServer.createServer(rest);
        server.expect(requestTo("http://rss/internal/v1/rss/entries/1/state"))
                .andExpect(method(HttpMethod.PUT)).andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"RSS_009\",\"message\":\"private details\"}"));
        mvc(client(rest, true)).perform(put("/api/v1/rss/entries/1/state").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":0,\"read\":true,\"starred\":false}").requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE, principal()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RSS_009")).andExpect(jsonPath("$.message").value("RSS_009")).andExpect(jsonPath("$.data").doesNotExist());
        server.verify();
    }
    @Test void disabledRouteAndFilterCannotSkipAuthentication() throws Exception {
        mvc(client(new RestTemplate(), false)).perform(get("/api/v1/rss/subscriptions").requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE, principal())).andExpect(status().isServiceUnavailable());
        var validator = mock(PrincipalValidator.class);
        var filter = new GatewayRequestFilter(validator, mock(GatewayProperties.class));
        for (String prefix : List.of("/api/v1/rss", "/api/app/v1/rss")) {
            var request = new MockHttpServletRequest("GET", prefix + "/subscriptions");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(401);
        }
        verifyNoInteractions(validator);
    }
    private RssGatewayClient client(RestTemplate rest, boolean enabled) { return new RssGatewayClient(rest, new RssGatewayProperties(enabled, "http://rss", TOKEN), new ObjectMapper()); }
    private MockMvc mvc(RssGatewayClient client) { return MockMvcBuilders.standaloneSetup(new RssGatewayController(client)).setControllerAdvice(new GatewayEnvelopeAdvice(), new GatewayExceptionHandler()).build(); }
    private GatewayPrincipal principal() { return new GatewayPrincipal(55, "rss-test", List.of(), UUID.fromString("00000000-0000-4000-8000-000000000055")); }
}
