package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.AppCatalogGatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AppCatalogGatewayClientTest {
    @Test
    void shouldBindTrustedOwnerWhenListingCatalog() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://catalog/internal/v1/catalog/entries"))
                .andExpect(method(GET)).andExpect(header("Authorization", "Bearer catalog-token"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(new AppCatalogGatewayClient(restTemplate, properties()).list("correlation")).isEmpty();
        server.verify();
    }

    private AppCatalogGatewayProperties properties() {
        return new AppCatalogGatewayProperties(true, "http://catalog", "catalog-token");
    }
}
