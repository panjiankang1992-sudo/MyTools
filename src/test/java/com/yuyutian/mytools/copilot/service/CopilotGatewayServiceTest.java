package com.yuyutian.mytools.copilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.copilot.model.CopilotGatewayStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Copilot模型网关服务测试。
 */
class CopilotGatewayServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldForwardProjectedBodyAndStreamSse() throws Exception {
        ByteArrayOutputStream capturedBody = new ByteArrayOutputStream();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().transferTo(capturedBody);
            byte[] response = "data: {\"choices\":[]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        CopilotGatewayService service = new CopilotGatewayService(objectMapper, true,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions", "", "test-model");
        JsonNode request = objectMapper.readTree("""
                {"model":"test-model","stream":true,"messages":[{"role":"user","content":"hello"}]}
                """);

        try (CopilotGatewayStream stream = service.openStream(request)) {
            assertEquals(200, stream.statusCode());
            assertTrue(stream.contentType().startsWith("text/event-stream"));
            assertTrue(new String(stream.body().readAllBytes(), StandardCharsets.UTF_8).contains("[DONE]"));
        }
        assertEquals(request, objectMapper.readTree(capturedBody.toByteArray()));
    }

    @Test
    void shouldRejectDisabledGateway() throws Exception {
        CopilotGatewayService service = new CopilotGatewayService(objectMapper, false,
                "http://127.0.0.1:11434/v1/chat/completions", "", "test");
        JsonNode request = objectMapper.readTree("{" +
                "\"model\":\"test\",\"stream\":true,\"messages\":[]}");

        BusinessException exception = assertThrows(BusinessException.class, () -> service.openStream(request));

        assertEquals(ErrorCode.COPILOT_001.getCode(), exception.getCode());
    }

    @Test
    void shouldRejectNonStreamingRequest() throws Exception {
        CopilotGatewayService service = new CopilotGatewayService(objectMapper, true,
                "http://127.0.0.1:11434/v1/chat/completions", "", "test");
        JsonNode request = objectMapper.readTree("{" +
                "\"model\":\"test\",\"stream\":false,\"messages\":[]}");

        BusinessException exception = assertThrows(BusinessException.class, () -> service.openStream(request));

        assertEquals(ErrorCode.COPILOT_002.getCode(), exception.getCode());
    }

    @Test
    void shouldExposeOnlyPublicGatewayInfo() {
        CopilotGatewayService service = new CopilotGatewayService(objectMapper, true,
                "http://127.0.0.1:11434/v1/chat/completions", "server-secret", "test-model");

        assertTrue(service.getInfo().enabled());
        assertEquals("test-model", service.getInfo().model());
    }
}
