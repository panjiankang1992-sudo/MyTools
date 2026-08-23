package com.yuyutian.mytools.task.executor.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchedulerNodeClientTest {

    private HttpServer server;
    private final AtomicInteger heartbeatCount = new AtomicInteger();
    private UUID nodeId;

    @BeforeEach
    void startServer() throws IOException {
        nodeId = UUID.randomUUID();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/execution-topology/nodes/register", this::handleRegister);
        server.createContext("/api/v1/execution-topology/nodes/", this::handleHeartbeat);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void shouldRegisterAndSendHeartbeat() throws Exception {
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:" + server.getAddress().getPort(), Path.of("runtime/tasks"),
                Path.of("scripts"), Path.of("sdk/python"), 10, 1, 60, 4,
                Map.of("runtimes", "python3.12"), Map.of("gpu", false),
                java.util.Set.of("media"), Map.of()
        );
        SchedulerNodeClient client = new SchedulerNodeClient(properties, new ObjectMapper());
        UUID instanceId = UUID.randomUUID();

        ExecutorNodeRegistration registration = client.register(instanceId);
        client.heartbeat(registration.id(), instanceId, 2);

        assertEquals(nodeId, registration.id());
        assertEquals(1, heartbeatCount.get());
    }

    private void handleRegister(HttpExchange exchange) throws IOException {
        String response = "{\"id\":\"" + nodeId + "\",\"name\":\"executor-test\",\"instanceId\":\"instance\"}";
        send(exchange, response);
    }

    private void handleHeartbeat(HttpExchange exchange) throws IOException {
        if (exchange.getRequestHeaders().getFirst("X-Executor-Instance-Id") != null
                && "2".equals(exchange.getRequestHeaders().getFirst("X-Running-Tasks"))) {
            heartbeatCount.incrementAndGet();
        }
        send(exchange, "{}");
    }

    private void send(HttpExchange exchange, String response) throws IOException {
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
