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
                new ObjectMapper(), "http://example.com:5572", "", "");

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
                "http://127.0.0.1:" + server.getAddress().getPort(), "", "");
        connector.validateConfiguration();

        long jobId = connector.startTransfer("COPY_TREE", "source", "books", "target", "backup");

        assertThat(jobId).isEqualTo(77);
        assertThat(path.get()).isEqualTo("/sync/copy");
        assertThat(body.get()).contains("\"srcFs\":\"source:\"")
                .contains("\"srcRemote\":\"books\"")
                .contains("\"dstFs\":\"target:\"")
                .contains("\"dstRemote\":\"backup\"")
                .contains("\"_async\":true");
        assertThat(connector.jobStatus(jobId).success()).isTrue();
    }
}
