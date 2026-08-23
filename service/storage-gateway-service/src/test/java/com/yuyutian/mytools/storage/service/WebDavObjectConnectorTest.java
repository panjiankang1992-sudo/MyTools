package com.yuyutian.mytools.storage.service;

import com.sun.net.httpserver.HttpServer;
import com.yuyutian.mytools.storage.model.StorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebDavObjectConnectorTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldListDepthOneWithoutExposingCredentials() throws Exception {
        AtomicReference<String> depth = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dav/books/", exchange -> {
            depth.set(exchange.getRequestHeaders().getFirst("Depth"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String body = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:multistatus xmlns:d="DAV:">
                      <d:response><d:href>/dav/books/</d:href><d:propstat><d:prop>
                        <d:displayname>books</d:displayname><d:resourcetype><d:collection/></d:resourcetype>
                      </d:prop></d:propstat></d:response>
                      <d:response><d:href>/dav/books/a.txt</d:href><d:propstat><d:prop>
                        <d:displayname>a.txt</d:displayname><d:resourcetype/><d:getcontentlength>3</d:getcontentlength>
                        <d:getlastmodified>Sun, 06 Nov 1994 08:49:37 GMT</d:getlastmodified>
                      </d:prop></d:propstat></d:response>
                      <d:response><d:href>/dav/books/folder/</d:href><d:propstat><d:prop>
                        <d:displayname>folder</d:displayname><d:resourcetype><d:collection/></d:resourcetype>
                      </d:prop></d:propstat></d:response>
                    </d:multistatus>
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(207, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        SecretMaterialResolver resolver = ignored -> Map.of("username", "reader", "password", "private");
        WebDavObjectConnector connector = new WebDavObjectConnector(resolver);
        StorageProvider provider = provider("http://127.0.0.1:" + server.getAddress().getPort() + "/dav/");

        var objects = connector.list(provider, "books");

        assertThat(objects).hasSize(2);
        assertThat(objects.getFirst().path()).isEqualTo("books/a.txt");
        assertThat(objects.getFirst().sizeBytes()).isEqualTo(3);
        assertThat(objects.getFirst().modifiedAt()).isEqualTo(Instant.parse("1994-11-06T08:49:37Z"));
        assertThat(objects.get(1).directory()).isTrue();
        assertThat(depth.get()).isEqualTo("1");
        assertThat(authorization.get()).startsWith("Basic ").doesNotContain("reader", "private");
    }

    @Test
    void shouldRejectPlainHttpOutsideLoopbackBeforeResolvingSecret() {
        SecretMaterialResolver resolver = ignored -> {
            throw new AssertionError("secret must not be resolved");
        };
        WebDavObjectConnector connector = new WebDavObjectConnector(resolver);

        assertThatThrownBy(() -> connector.list(provider("http://example.com/dav"), "books"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("STORAGE_013");
    }

    @Test
    void shouldRejectPathTraversalBeforeResolvingSecret() {
        SecretMaterialResolver resolver = ignored -> {
            throw new AssertionError("secret must not be resolved");
        };
        WebDavObjectConnector connector = new WebDavObjectConnector(resolver);

        assertThatThrownBy(() -> connector.list(provider("https://example.com/dav"), "../private"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("STORAGE_004");
    }

    @Test
    void shouldStreamWriteReadAndCompensateDeleteOneObject() throws Exception {
        AtomicReference<byte[]> stored = new AtomicReference<>();
        AtomicReference<String> ifNoneMatch = new AtomicReference<>();
        AtomicBoolean deleted = new AtomicBoolean();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dav/books/a.bin", exchange -> {
            switch (exchange.getRequestMethod()) {
                case "PUT" -> {
                    ifNoneMatch.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
                    byte[] value = exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(stored.compareAndSet(null, value) ? 201 : 412, -1);
                }
                case "GET" -> {
                    byte[] value = stored.get();
                    exchange.sendResponseHeaders(200, value.length);
                    exchange.getResponseBody().write(value);
                }
                case "DELETE" -> {
                    deleted.set(true);
                    exchange.sendResponseHeaders(204, -1);
                }
                default -> exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
        });
        server.start();
        WebDavObjectConnector connector = new WebDavObjectConnector(
                ignored -> Map.of("username", "reader", "password", "private"));
        StorageProvider provider = provider("http://127.0.0.1:" + server.getAddress().getPort() + "/dav/");

        assertThat(connector.writeContent(provider, "books/a.bin",
                new java.io.ByteArrayInputStream("payload".getBytes()), 7)).isTrue();
        assertThat(connector.writeContent(provider, "books/a.bin",
                new java.io.ByteArrayInputStream("changed".getBytes()), 7)).isFalse();
        try (var content = connector.openContent(provider, "books/a.bin", 7).stream()) {
            assertThat(content.readAllBytes()).isEqualTo("payload".getBytes());
        }
        connector.deleteContent(provider, "books/a.bin");

        assertThat(stored.get()).isEqualTo("payload".getBytes());
        assertThat(ifNoneMatch.get()).isEqualTo("*");
        assertThat(deleted).isTrue();
    }

    @Test
    void shouldRejectDeclaredRemoteContentAboveLimit() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dav/large.bin", exchange -> {
            exchange.sendResponseHeaders(200, 8);
            exchange.getResponseBody().write(new byte[8]);
            exchange.close();
        });
        server.start();
        WebDavObjectConnector connector = new WebDavObjectConnector(
                ignored -> Map.of("username", "reader", "password", "private"));
        StorageProvider provider = provider("http://127.0.0.1:" + server.getAddress().getPort() + "/dav/");

        assertThatThrownBy(() -> connector.openContent(provider, "large.bin", 7))
                .isInstanceOf(IllegalStateException.class).hasMessage("STORAGE_029");
    }

    private StorageProvider provider(String endpoint) {
        Instant now = Instant.now();
        return new StorageProvider(UUID.randomUUID(), "webdav", "WEBDAV", "webdav_alias", endpoint,
                "env://WEBDAV_SECRET", true, now, now);
    }
}
