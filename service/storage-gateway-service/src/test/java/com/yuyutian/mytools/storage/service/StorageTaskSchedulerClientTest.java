package com.yuyutian.mytools.storage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yuyutian.mytools.storage.model.ChecksumOperation;
import com.yuyutian.mytools.storage.repository.StorageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StorageTaskSchedulerClientTest {

    @Test
    void shouldSubmitOpaqueChecksumIdentityAndServerRootAffinity() throws Exception {
        AtomicReference<JsonNode> requestDocument = new AtomicReference<>();
        ObjectMapper mapper = new ObjectMapper();
        UUID taskId = UUID.randomUUID();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/task-instances", exchange -> {
            requestDocument.set(mapper.readTree(exchange.getRequestBody()));
            byte[] body = mapper.writeValueAsBytes(java.util.Map.of("id", taskId.toString()));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            StorageTaskSchedulerClient client = new StorageTaskSchedulerClient(RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            UUID operationId = UUID.randomUUID();
            Instant now = Instant.now();
            ChecksumOperation operation = new ChecksumOperation(operationId, UUID.randomUUID(), "managed",
                    "checksum:key", "private/path.bin", "CREATED", null, null, null, null, now, now);
            StorageRepository.ManagedRoot root = new StorageRepository.ManagedRoot(
                    operation.rootId(), "managed", "/private/storage", "storage.mount.managed", "present");

            UUID result = client.createChecksumTask(operation, root);

            assertThat(result).isEqualTo(taskId);
            JsonNode document = requestDocument.get();
            assertThat(document.path("parameters").size()).isEqualTo(1);
            assertThat(document.path("parameters").path("checksumOperationId").asText())
                    .isEqualTo(operationId.toString());
            assertThat(document.path("requiredNodeLabels").path("storage.mount.managed").asText())
                    .isEqualTo("present");
            assertThat(document.toString()).doesNotContain("private/path.bin", "/private/storage");
        } finally {
            server.stop(0);
        }
    }
}
