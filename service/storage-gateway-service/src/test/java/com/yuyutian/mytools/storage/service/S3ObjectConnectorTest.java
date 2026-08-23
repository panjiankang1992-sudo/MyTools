package com.yuyutian.mytools.storage.service;

import com.sun.net.httpserver.HttpServer;
import com.yuyutian.mytools.storage.model.StorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3ObjectConnectorTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldSignPaginateAndNormalizeDepthOneObjects() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> sessionToken = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/reader-bucket", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            sessionToken.set(exchange.getRequestHeaders().getFirst("x-amz-security-token"));
            boolean second = exchange.getRequestURI().getRawQuery().contains("continuation-token=next-page");
            String body = second ? """
                    <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                      <IsTruncated>false</IsTruncated>
                      <CommonPrefixes><Prefix>books/folder/</Prefix></CommonPrefixes>
                    </ListBucketResult>
                    """ : """
                    <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                      <IsTruncated>true</IsTruncated><NextContinuationToken>next-page</NextContinuationToken>
                      <Contents><Key>books/a.txt</Key><LastModified>2026-01-02T03:04:05Z</LastModified>
                        <Size>3</Size></Contents>
                    </ListBucketResult>
                    """;
            calls.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        SecretMaterialResolver resolver = ignored -> Map.of(
                "accessKeyId", "AKIDEXAMPLE", "secretAccessKey", "private-key",
                "sessionToken", "temporary-token");
        Clock clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
        S3ObjectConnector connector = new S3ObjectConnector(resolver,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), clock);

        var objects = connector.list(provider(), "books");

        assertThat(objects).hasSize(2);
        assertThat(objects.getFirst().path()).isEqualTo("books/a.txt");
        assertThat(objects.getFirst().modifiedAt()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
        assertThat(objects.get(1).path()).isEqualTo("books/folder");
        assertThat(objects.get(1).directory()).isTrue();
        assertThat(calls).hasValue(2);
        assertThat(authorization.get()).startsWith(
                "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20260102/test-region-1/s3/aws4_request")
                .doesNotContain("private-key", "temporary-token");
        assertThat(sessionToken.get()).isEqualTo("temporary-token");
    }

    @Test
    void shouldRejectUnsafeEndpointBeforeResolvingSecret() {
        SecretMaterialResolver resolver = ignored -> {
            throw new AssertionError("secret must not be resolved");
        };
        S3ObjectConnector connector = new S3ObjectConnector(resolver);
        StorageProvider provider = new StorageProvider(UUID.randomUUID(), "s3", "S3", "reader-bucket",
                "http://example.com", "test-region-1", "env://S3_SECRET", true,
                Instant.now(), Instant.now());

        assertThatThrownBy(() -> connector.list(provider, "books"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("STORAGE_013");
    }

    private StorageProvider provider() {
        Instant now = Instant.now();
        return new StorageProvider(UUID.randomUUID(), "s3", "S3", "reader-bucket",
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-region-1",
                "env://S3_SECRET", true, now, now);
    }
}
