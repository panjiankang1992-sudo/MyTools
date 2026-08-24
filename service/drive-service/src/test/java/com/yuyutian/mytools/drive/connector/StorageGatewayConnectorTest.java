package com.yuyutian.mytools.drive.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StorageGatewayConnectorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<byte[]> requestBody = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/v1/storage/operations", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(exchange.getRequestBody().readAllBytes());
            byte[] response = ("{\"id\":\"" + UUID.randomUUID() + "\",\"taskInstanceId\":\""
                    + UUID.randomUUID() + "\",\"operationType\":\"COPY_TREE_NATIVE\","
                    + "\"status\":\"RUNNING\",\"errorCode\":null}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void shouldCreateNativeTreeCopyOperation() throws Exception {
        UUID sourceProvider = UUID.randomUUID();
        UUID targetProvider = UUID.randomUUID();
        StorageGatewayConnector connector = new StorageGatewayConnector(objectMapper,
                "http://127.0.0.1:" + server.getAddress().getPort(), "storage-token");
        connector.validateConfiguration();

        var result = connector.copyTree("drive-copy-tree:key", sourceProvider, "books", targetProvider,
                "backup", 5000);

        JsonNode payload = objectMapper.readTree(requestBody.get());
        assertThat(authorization.get()).isEqualTo("Bearer storage-token");
        assertThat(payload.path("operationType").asText()).isEqualTo("COPY_TREE_NATIVE");
        assertThat(payload.path("providerId").asText()).isEqualTo(sourceProvider.toString());
        assertThat(payload.path("targetProviderId").asText()).isEqualTo(targetProvider.toString());
        assertThat(payload.path("sourcePath").asText()).isEqualTo("books");
        assertThat(payload.path("targetPath").asText()).isEqualTo("backup");
        assertThat(payload.path("maximumObjects").asInt()).isEqualTo(5000);
        assertThat(result.operationType()).isEqualTo("COPY_TREE_NATIVE");
    }
}
