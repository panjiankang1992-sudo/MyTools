package com.yuyutian.mytools.cloudfile.service.impl;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WebdavClientRangeStreamTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldForwardSingleRangeAndStreamPartialBody() throws Exception {
        AtomicReference<String> receivedRange = new AtomicReference<>();
        byte[] expected = "partial-media".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dav/movie.mp4", exchange -> {
            receivedRange.set(exchange.getRequestHeaders().getFirst("Range"));
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.getResponseHeaders().add("Content-Range", "bytes 10-22/100");
            exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
            exchange.sendResponseHeaders(206, expected.length);
            exchange.getResponseBody().write(expected);
            exchange.close();
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/dav";
        WebdavClient client = new WebdavClient(baseUrl, "user", "password");

        HttpResponse<InputStream> response = client.openStream("/movie.mp4", "bytes=10-22");

        assertEquals(206, response.statusCode());
        assertEquals("bytes=10-22", receivedRange.get());
        assertEquals("bytes 10-22/100", response.headers().firstValue("Content-Range").orElseThrow());
        try (InputStream inputStream = response.body()) {
            assertArrayEquals(expected, inputStream.readAllBytes());
        }
    }

    @Test
    void shouldPreserveRangeNotSatisfiableResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dav/movie.mp4", exchange -> {
            exchange.getResponseHeaders().add("Content-Range", "bytes */100");
            exchange.sendResponseHeaders(416, -1);
            exchange.close();
        });
        server.start();
        WebdavClient client = new WebdavClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/dav", "user", "password");

        HttpResponse<InputStream> response = client.openStream("/movie.mp4", "bytes=200-300");

        assertEquals(416, response.statusCode());
        assertEquals("bytes */100", response.headers().firstValue("Content-Range").orElseThrow());
        response.body().close();
    }
}
