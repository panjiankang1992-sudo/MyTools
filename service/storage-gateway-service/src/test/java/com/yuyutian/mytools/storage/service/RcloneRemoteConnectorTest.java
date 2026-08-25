package com.yuyutian.mytools.storage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RcloneRemoteConnectorTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectNonLoopbackControlEndpoint() {
        RcloneRemoteConnector connector = new RcloneRemoteConnector(
                new ObjectMapper(), "http://example.com:5572", "", "", "/tmp/rclone.conf", "http://127.0.0.1:7893");

        assertThatThrownBy(connector::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rclone RC must use a loopback HTTP endpoint");
    }

    @Test
    void shouldStartOnlyServerDefinedCopyAndNormalizeRemoteJobState() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sync/copy", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"jobid\":77}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/job/status", exchange -> {
            byte[] response = "{\"finished\":true,\"success\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        RcloneRemoteConnector connector = new RcloneRemoteConnector(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "", "", "/tmp/rclone.conf", "http://127.0.0.1:7893");
        connector.validateConfiguration();

        long jobId = connector.startTransfer("COPY_TREE", "source", "books", "target", "backup");

        assertThat(jobId).isEqualTo(77);
        assertThat(path.get()).isEqualTo("/sync/copy");
        assertThat(body.get()).contains("\"srcFs\":\"source:books\"")
                .contains("\"dstFs\":\"target:backup\"")
                .doesNotContain("srcRemote", "dstRemote")
                .contains("\"_async\":true");
        assertThat(connector.jobStatus(jobId).success()).isTrue();
    }

    @Test
    void shouldUseWhitelistedVerificationStatAndPurgeContracts() throws Exception {
        AtomicReference<String> checkBody = new AtomicReference<>();
        AtomicReference<String> purgeBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/operations/stat", exchange -> respond(exchange, "{\"item\":null}"));
        server.createContext("/operations/check", exchange -> {
            checkBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"jobid\":81}");
        });
        server.createContext("/operations/purge", exchange -> {
            purgeBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"jobid\":82}");
        });
        server.createContext("/job/status", exchange -> respond(exchange,
                "{\"finished\":true,\"success\":true,\"output\":{\"success\":false}}"));
        server.start();
        RcloneRemoteConnector connector = new RcloneRemoteConnector(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "", "", "/tmp/rclone.conf", "http://127.0.0.1:7893");
        connector.validateConfiguration();

        assertThat(connector.exists("target", "archive/books")).isFalse();
        assertThat(connector.startVerification("source", "books", "target", "archive/books")).isEqualTo(81L);
        assertThat(connector.verificationJobStatus(81L).success()).isFalse();
        assertThat(connector.startPurge("target", "archive/books")).isEqualTo(82L);

        assertThat(checkBody.get()).contains("\"srcFs\":\"source:books\"")
                .contains("\"dstFs\":\"target:archive/books\"")
                .contains("\"download\":true");
        assertThat(purgeBody.get()).contains("\"fs\":\"target:\"")
                .contains("\"remote\":\"archive/books\"");
    }

    @Test
    void shouldStreamOnlyServerResolvedRemoteContentWithinLimit() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/core/command", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "remote-content".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        RcloneRemoteConnector connector = new RcloneRemoteConnector(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "", "", "/tmp/rclone.conf", "http://127.0.0.1:7893");
        connector.validateConfiguration();

        var content = connector.openContent("pikpak_remote", "ready/operation/book.epub", 1024);

        assertThat(new String(content.stream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("remote-content");
        assertThat(body.get()).contains("\"command\":\"cat\"")
                .contains("\"pikpak_remote:ready/operation/book.epub\"")
                .contains("\"config\":\"/tmp/rclone.conf\"")
                .contains("\"returnType\":\"STREAM_ONLY_STDOUT\"");
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String json) throws java.io.IOException {
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
