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

    private StorageProvider provider(String endpoint) {
        Instant now = Instant.now();
        return new StorageProvider(UUID.randomUUID(), "webdav", "WEBDAV", "webdav_alias", endpoint,
                "env://WEBDAV_SECRET", true, now, now);
    }
}
