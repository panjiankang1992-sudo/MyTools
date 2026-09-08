package com.yuyutian.mytools.task.executor.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yuyutian.mytools.task.executor.client.ClaimedTask;
import com.yuyutian.mytools.task.executor.config.ExecutorLogArchiveProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionLogArchiverTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldUseStableUploadAndSkipContentAfterIdempotentSuccess() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger uploads = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/v1/storage/uploads", exchange -> respond(exchange,
                exchange.getRequestURI().getPath().endsWith("/content")
                        ? uploadResponse(uploads.incrementAndGet()) : createResponse(exchange, creates.incrementAndGet())));
        server.start();
        try {
            Path work = temporaryDirectory.resolve("work");
            Path attempt = work.resolve("run/1");
            Files.createDirectories(attempt);
            Files.writeString(attempt.resolve("stdout-000001.log"), "output");
            ExecutorLogArchiveProperties properties = new ExecutorLogArchiveProperties(true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "secret-token", "archive",
                    "task-logs");
            ExecutionLogArchiver archiver = new ExecutionLogArchiver(properties, new ObjectMapper());
            ClaimedTask task = new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, "sample",
                    UUID.randomUUID(), 7, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                    Map.of(), List.of());

            archiver.archive(task, work);
            archiver.archive(task, work);

            assertEquals(2, creates.get());
            assertEquals(1, uploads.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldDegradeHealthWithoutExposingArchiveConfiguration() {
        ExecutorLogArchiveProperties properties = new ExecutorLogArchiveProperties(true,
                "http://127.0.0.1:1", "secret-token", "archive", "task-logs");
        ExecutionLogArchiver archiver = new ExecutionLogArchiver(properties, new ObjectMapper());
        ClaimedTask task = new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, "sample",
                UUID.randomUUID(), 7, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                Map.of(), List.of());

        assertThrows(IOException.class, () -> archiver.archive(task, temporaryDirectory.resolve("missing")));

        String health = archiver.health().toString();
        assertTrue(health.contains("OUT_OF_SERVICE"));
        assertTrue(health.contains("EXECUTOR_LOG_ARCHIVE_FAILED"));
        assertTrue(!health.contains("secret-token"));
    }

    private String createResponse(HttpExchange exchange, int attempt) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        assertEquals("Bearer secret-token", authorization);
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.contains("executor-log:"));
        assertTrue(body.contains("run/1/stdout-000001.log"));
        if (attempt == 1) {
            return "{\"id\":\"00000000-0000-4000-8000-000000000009\",\"status\":\"CREATED\"}";
        }
        return "{\"id\":\"00000000-0000-4000-8000-000000000009\",\"status\":\"SUCCEEDED\"}";
    }

    private String uploadResponse(int attempt) {
        assertEquals(1, attempt);
        return "{\"status\":\"SUCCEEDED\",\"sha256\":\""
                + "e0ee8bb50685e05fa0f47ed04203ae953fdfd055f5bd2892ea186504254f8c3a" + "\"}";
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
