package com.yuyutian.mytools.messaging.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 渠道文件解析客户端契约测试。
 */
class ProviderFileResolverClientTest {

    @Test
    void shouldKeepProviderReferenceAndTokenInsideMessagingBoundary() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://resolver.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://resolver.test/internal/v1/provider-files/resolve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer resolver-token"))
                .andExpect(jsonPath("$.channelType").value("ONEBOT"))
                .andExpect(jsonPath("$.accountKey").value("napcat-main"))
                .andExpect(jsonPath("$.providerFileId").value("opaque-file"))
                .andRespond(withSuccess("{\"mode\":\"PUBLIC_URL\",\"downloadUrl\":\"https://cdn.example.test/a.bin\"}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        ProviderFileResolverClient client = new ProviderFileResolverClient(builder.build(), "resolver-token");

        assertThat(client.resolve("napcat-main", "FILE", "opaque-file").downloadUrl())
                .isEqualTo("https://cdn.example.test/a.bin");
        server.verify();
    }

    @Test
    void shouldRejectCredentialBearingResolvedUrl() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://resolver.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://resolver.test/internal/v1/provider-files/resolve"))
                .andRespond(withSuccess("{\"mode\":\"PUBLIC_URL\",\"downloadUrl\":\"https://user:pass@example.test/a\"}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        ProviderFileResolverClient client = new ProviderFileResolverClient(builder.build(), "resolver-token");

        assertThatThrownBy(() -> client.resolve("main", "FILE", "opaque"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid");
    }

    @Test
    void shouldRejectSignedResolvedUrl() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://resolver.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://resolver.test/internal/v1/provider-files/resolve"))
                .andRespond(withSuccess("{\"mode\":\"PUBLIC_URL\",\"downloadUrl\":\"https://example.test/a?token=secret\"}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        ProviderFileResolverClient client = new ProviderFileResolverClient(builder.build(), "resolver-token");

        assertThatThrownBy(() -> client.resolve("main", "FILE", "opaque"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid");
    }

    @Test
    void shouldStreamAuthenticatedContentWithoutReturningProviderCredential() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://resolver.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://resolver.test/internal/v1/provider-files/resolve"))
                .andRespond(withSuccess("{\"mode\":\"STREAM\"}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://resolver.test/internal/v1/provider-files/content"))
                .andExpect(header("Authorization", "Bearer resolver-token"))
                .andExpect(jsonPath("$.providerFileId").value("private-file"))
                .andRespond(withSuccess("private-content", org.springframework.http.MediaType.APPLICATION_OCTET_STREAM));
        ProviderFileResolverClient client = new ProviderFileResolverClient(builder.build(), "resolver-token");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertThat(client.resolve("main", "FILE", "private-file").mode()).isEqualTo("STREAM");
        client.stream("main", "FILE", "private-file", output, 1024);

        assertThat(output.toString()).isEqualTo("private-content");
        server.verify();
    }
}
