package com.yuyutian.mytools.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.config.NewsGatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.*;
import com.yuyutian.mytools.gateway.web.GatewayEnvelopeAdvice;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NewsGatewayControllerTest {
    private final NewsGatewayProperties properties = new NewsGatewayProperties(true, "http://news", "internal-news-test-token");

    @Test void weeklyAcceptancePreserves202AndTrustedOwner() throws Exception {
        RestTemplate template=new RestTemplate();
        MockRestServiceServer server=MockRestServiceServer.createServer(template);
        server.expect(requestTo("http://news/internal/v1/news/weekly-jobs?ownerId=55"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().json("{\"idempotencyKey\":\"request-123\"}"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"job\",\"status\":\"QUEUED\",\"reused\":false}"));
        mvc(new NewsGatewayClient(template,properties),true).perform(post("/api/app/v1/news/weekly-jobs?ownerId=999")
                        .header("X-Owner-Id","999").requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,principal())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"idempotencyKey\":\"request-123\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
        server.verify();
        NewsGatewayClient client=mock(NewsGatewayClient.class);
        mvc(client,true).perform(post("/api/v1/news/weekly-jobs").requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,principal())
                .contentType(MediaType.APPLICATION_JSON).content("{\"idempotencyKey\":\"request-123\",\"windowEnd\":\"2026-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }

    @Test void weeklyImageRequiresAuthenticationAndUsesPrivateResponse() throws Exception {
        RestTemplate template=new RestTemplate(); MockRestServiceServer server=MockRestServiceServer.createServer(template);
        String id="00000000-0000-4000-8000-000000000001", asset="a".repeat(64);
        server.expect(requestTo("http://news/internal/v1/news/weekly-reports/"+id+"/assets/"+asset+"?ownerId=55"))
                .andRespond(withSuccess("{\"assetId\":\""+asset+"\",\"mime\":\"image/png\",\"base64\":\"AA==\"}",MediaType.APPLICATION_JSON));
        mvc(new NewsGatewayClient(template,properties),true).perform(get("/api/app/v1/news/weekly-reports/"+id+"/assets/"+asset)
                        .requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,principal()))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.mime").value("image/png"));
        server.verify();
    }

    @Test
    void evidenceUsesTrustedOwnerOnBothRoutesAndRejectsAnonymousRequests() throws Exception {
        String id="00000000-0000-4000-8000-000000000001";
        for(String prefix:List.of("/api/v1/news","/api/app/v1/news")) {
            RestTemplate template=new RestTemplate();MockRestServiceServer server=MockRestServiceServer.createServer(template);
            server.expect(requestTo("http://news/internal/v1/news/editions/"+id+"/evidence?ownerId=55"))
                    .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization","Bearer internal-news-test-token"))
                    .andRespond(withSuccess("{\"editionId\":\""+id+"\",\"items\":[]}",MediaType.APPLICATION_JSON));
            mvc(new NewsGatewayClient(template,properties),true)
                    .perform(get(prefix+"/editions/"+id+"/evidence?ownerId=99&articleId=forged").header("X-Owner-Id","99")
                            .requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,principal()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.editionId").value(id));
            server.verify();
        }
        NewsGatewayClient client=mock(NewsGatewayClient.class);
        mvc(client,true).perform(get("/api/v1/news/editions/"+id+"/evidence")).andExpect(status().isUnauthorized());
        verifyNoInteractions(client);
    }

    @Test
    void expiredEditionKeeps410AndDoesNotExposeDownstreamDetails() throws Exception {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(template);
        String id="00000000-0000-4000-8000-000000000001";
        server.expect(requestTo("http://news/internal/v1/news/editions/"+id+"?ownerId=55"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.GONE).body("private archive details"));
        mvc(new NewsGatewayClient(template, properties), true)
                .perform(get("/api/v1/news/editions/"+id).requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE, principal()))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("NEWS_026"))
                .andExpect(jsonPath("$.data").doesNotExist());
        server.verify();
    }

    @Test
    void onlyAuthenticatedOwnerIsSentDownstreamEvenIfHeaderAndQueryAreForged() throws Exception {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(template);
        server.expect(requestTo("http://news/internal/v1/news/editions?ownerId=55&limit=30"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer internal-news-test-token"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        mvc(new NewsGatewayClient(template, properties), true)
                .perform(get("/api/app/v1/news/editions?ownerId=99").header("X-Owner-Id", "99")
                        .requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE, principal()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data").isArray());
        server.verify();
    }

    @Test
    void conflictingPreviewRetains409AndDoesNotExposeDownstreamBody() throws Exception {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(template);
        server.expect(requestTo("http://news/internal/v1/news/previews?ownerId=55"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                        .json("{\"idempotencyKey\":\"key\",\"cutoff\":\"2026-10-18T00:00:00Z\"}"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT).body("sensitive downstream context"));
        mvc(new NewsGatewayClient(template, properties), true)
                .perform(post("/api/v1/news/previews").requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE, principal())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"idempotencyKey\":\"key\",\"cutoff\":\"2026-10-18T00:00:00Z\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NEWS_004"))
                .andExpect(jsonPath("$.data").doesNotExist());
        server.verify();
    }

    @Test
    void localRegionIgnoresClientNodeAndCityAndStillRequiresAuthentication() throws Exception {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(template);
        server.expect(requestTo("http://news/internal/v1/news/local-region"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer internal-news-test-token"))
                .andRespond(withSuccess("{\"status\":\"SOURCE_PACK_MISSING\",\"regionCode\":\"GEONAME-123\"}", MediaType.APPLICATION_JSON));
        mvc(new NewsGatewayClient(template, properties), true)
                .perform(get("/api/app/v1/news/local-region?nodeId=forged&city=forged")
                        .requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE, principal()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.regionCode").value("GEONAME-123"));
        server.verify();
        NewsGatewayClient client = mock(NewsGatewayClient.class);
        mvc(client, true).perform(get("/api/v1/news/local-region")).andExpect(status().isUnauthorized());
        verifyNoInteractions(client);
    }

    @Test
    void ownerInBodyIsRejectedBeforeDownstreamAndDisabledRouteIsClosed() throws Exception {
        NewsGatewayClient client = mock(NewsGatewayClient.class);
        mvc(client, true).perform(post("/api/app/v1/news/previews").requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE, principal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":99,\"idempotencyKey\":\"key\",\"cutoff\":\"2026-10-18T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        mvc(client, false).perform(get("/api/app/v1/news/capabilities")
                        .requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE, principal()))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(client);
    }

    @Test
    void bothNewsPrefixesRequireAuthenticationBeforeRouteActivation() throws Exception {
        var gateway = new GatewayProperties(GatewayProperties.IdentityMode.LEGACY, false, false, Set.of(), false, Set.of(),
                false, Set.of(), "http://mytools", "http://identity", "http://reader", "http://drive", "http://download",
                "gateway", "identity", "reader", "drive", "download", 1000, 3000, false, "", "", false, "", "");
        GatewayRequestFilter filter = new GatewayRequestFilter(mock(PrincipalValidator.class), gateway);
        for (String prefix : List.of("/api/app/v1/news", "/api/v1/news")) {
            var request = new MockHttpServletRequest("GET", prefix + "/editions");
            request.addHeader("X-Owner-Id", "55");
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Test
    void subscriptionAndRunRoutesUseOnlyAuthenticatedOwner() throws Exception {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(template);
        server.expect(requestTo("http://news/internal/v1/news/subscription?ownerId=55")).andExpect(method(HttpMethod.PUT))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().json("{\"expectedRevision\":0,\"enabled\":true,\"zoneId\":\"Asia/Shanghai\",\"cutoffTime\":\"07:30\"}"))
                .andRespond(withSuccess("{\"revision\":1}", MediaType.APPLICATION_JSON));
        var mock = mvc(new NewsGatewayClient(template, properties), true);
        mock.perform(put("/api/v1/news/subscription?ownerId=99").header("X-Owner-Id", "99")
                        .requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE, principal()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"enabled\":true,\"zoneId\":\"Asia/Shanghai\",\"cutoffTime\":\"07:30\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1));
        server.verify(); server.reset();
        var id = java.util.UUID.randomUUID();
        server.expect(requestTo("http://news/internal/v1/news/runs/" + id + "/cancel?ownerId=55"))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess("{\"status\":\"CANCELLED\"}", MediaType.APPLICATION_JSON));
        mock.perform(post("/api/app/v1/news/runs/" + id + "/cancel?ownerId=99")
                        .requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE, principal()))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        server.verify();
    }

    @Test
    void subscriptionRejectsOwnerPayloadAndPreservesRevisionConflictCode() throws Exception {
        var client = mock(NewsGatewayClient.class);
        mvc(client, true).perform(put("/api/v1/news/subscription").requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE, principal())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":0,\"enabled\":true,\"zoneId\":\"UTC\",\"cutoffTime\":\"08:00\",\"ownerId\":99}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client);
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(template);
        server.expect(requestTo("http://news/internal/v1/news/subscription?ownerId=55")).andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT));
        mvc(new NewsGatewayClient(template, properties), true).perform(put("/api/v1/news/subscription")
                        .requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE, principal()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"enabled\":false,\"zoneId\":\"UTC\",\"cutoffTime\":\"08:00\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NEWS_014"));
        server.verify();
    }

    @Test
    void deliveryRoutesUseAuthenticatedOwnerAndRetainRetryGeneration() throws Exception {
        RestTemplate template=new RestTemplate();
        MockRestServiceServer server=MockRestServiceServer.createServer(template);
        var id=java.util.UUID.randomUUID();var part=java.util.UUID.randomUUID();
        server.expect(requestTo("http://news/internal/v1/news/destinations?ownerId=55"))
                .andRespond(withSuccess("[]",MediaType.APPLICATION_JSON));
        var mock=mvc(new NewsGatewayClient(template,properties),true);
        mock.perform(get("/api/v1/news/destinations?ownerId=99").header("X-Owner-Id","99")
                .requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,principal())).andExpect(status().isOk());
        server.verify();server.reset();
        server.expect(requestTo("http://news/internal/v1/news/deliveries/"+id+"/parts/"+part+"/retry?ownerId=55"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().json("{\"expectedGeneration\":2,\"acknowledgeDuplicateRisk\":true}"))
                .andRespond(withSuccess("{\"status\":\"UNKNOWN\"}",MediaType.APPLICATION_JSON));
        mock.perform(post("/api/v1/news/deliveries/"+id+"/parts/"+part+"/retry?ownerId=99")
                .requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,principal()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedGeneration\":2,\"acknowledgeDuplicateRisk\":true}")).andExpect(status().isOk());
        server.verify();
    }
    @Test
    void deliveryTargetPayloadRejectsRawAddressAndForeignOwner() throws Exception {
        var client=mock(NewsGatewayClient.class);var mock=mvc(client,true);
        for(String payload:List.of(
                "{\"expectedRevision\":0,\"targets\":[],\"ownerId\":99}",
                "{\"expectedRevision\":0,\"targets\":[{\"destinationId\":\""+java.util.UUID.randomUUID()+"\",\"mode\":\"FULL\",\"enabled\":true,\"address\":\"forbidden@example.com\"}]}")) {
            mock.perform(put("/api/v1/news/delivery-targets").requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,principal())
                    .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(client);
    }
    @Test
    void targetRevisionConflictKeepsAStableDistinctCode() throws Exception {
        RestTemplate template=new RestTemplate();MockRestServiceServer server=MockRestServiceServer.createServer(template);
        server.expect(requestTo("http://news/internal/v1/news/delivery-targets?ownerId=55"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT).body("private downstream data"));
        mvc(new NewsGatewayClient(template,properties),true).perform(put("/api/v1/news/delivery-targets")
                .requestAttr(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,principal()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":0,\"targets\":[]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NEWS_022"));
        server.verify();
    }

    private GatewayPrincipal principal() { return new GatewayPrincipal(55, "user", List.of("USER"), null); }

    private MockMvc mvc(NewsGatewayClient client, boolean enabled) {
        ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
        return MockMvcBuilders.standaloneSetup(new NewsGatewayController(new NewsGatewayProperties(enabled, "http://news", "token"), client))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .setControllerAdvice(new NewsGatewayExceptionHandler(), new GatewayExceptionHandler(), new GatewayEnvelopeAdvice()).build();
    }
}
