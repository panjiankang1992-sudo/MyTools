package com.yuyutian.mytools.drive.infrastructure.rclone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RcloneRcGatewayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> lastRange = new AtomicReference<>();
    private HttpServer server;
    private RcloneRcGateway gateway;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/operations/list", exchange -> respond(exchange, """
                {"list":[{"Path":"Photos","Name":"Photos","Size":0,"MimeType":"inode/directory",
                "ModTime":"2026-08-15T10:20:30Z","IsDir":true,"OrigID":"folder-1"}]}
                """));
        server.createContext("/operations/size", exchange -> respond(exchange, "{\"count\":12,\"bytes\":4096}"));
        server.createContext("/family/movies/a.mp4", exchange -> respondBytes(exchange,
                "segment".getBytes(StandardCharsets.UTF_8)));
        server.start();
        gateway = new RcloneRcGateway(objectMapper);
        ReflectionTestUtils.setField(gateway, "rcUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(gateway, "rcUser", "");
        ReflectionTestUtils.setField(gateway, "rcPassword", "");
        ReflectionTestUtils.setField(gateway, "serveUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(gateway, "serveUser", "");
        ReflectionTestUtils.setField(gateway, "servePassword", "");
        gateway.validateConfiguration();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void shouldUseOnlyConfiguredRemoteAndWhitelistedOperations() {
        RcloneItem item = gateway.list("family", "albums").getFirst();

        assertThat(item.path()).isEqualTo("Photos");
        assertThat(item.directory()).isTrue();
        assertThat(lastRequest.get().path("fs").asText()).isEqualTo("family:");
        assertThat(lastRequest.get().path("remote").asText()).isEqualTo("albums");
        assertThat(gateway.size("family", "albums")).isEqualTo(new RcloneDirectorySize(12L, 4096L));
    }

    @Test
    void shouldOpenOnlyConfiguredFileRange() throws Exception {
        RcloneContent content = gateway.open("family", "movies/a.mp4", 10L, 7L);

        try (InputStream inputStream = content.inputStream()) {
            assertThat(inputStream.readAllBytes()).isEqualTo("segment".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(lastRange.get()).isEqualTo("bytes=10-16");
    }

    private void respond(HttpExchange exchange, String response) throws IOException {
        lastRequest.set(objectMapper.readTree(exchange.getRequestBody()));
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void respondBytes(HttpExchange exchange, byte[] response) throws IOException {
        lastRange.set(exchange.getRequestHeaders().getFirst("Range"));
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("Content-Range", "bytes 10-16/100");
        exchange.sendResponseHeaders(206, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
