package com.yuyutian.mytools.gateway.controller;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class PdfMcpGatewayControllerTest {
    @Test void refusesUnauthenticatedAndUnlistedRoutesBeforeConnecting() throws Exception {
        var controller = new PdfMcpGatewayController(new MockEnvironment().withProperty("PDF_MCP_ROUTE_ENABLED", "true"));
        var request = new MockHttpServletRequest("POST", "/api/app/v1/pdf-mcp");
        var response = new MockHttpServletResponse();
        controller.forward(request, response);
        assertEquals(401, response.getStatus());
        request = new MockHttpServletRequest("GET", "/api/app/v1/pdf-mcp/internal/v1/pdf");
        request.addHeader("Authorization", "Bearer test");
        response = new MockHttpServletResponse();
        controller.forward(request, response);
        assertEquals(404, response.getStatus());
    }

    @Test void preservesProtocolAndAccountBearerWithoutAppEnvelope() throws Exception {
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var bearer = new AtomicReference<String>();
        var version = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        upstream.createContext("/mcp", exchange -> {
            bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
            version.set(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] result = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, result.length);
            exchange.getResponseBody().write(result);
            exchange.close();
        });
        upstream.start();
        try {
            var env = new MockEnvironment().withProperty("PDF_MCP_ROUTE_ENABLED", "true")
                    .withProperty("PDF_MCP_URL", "http://127.0.0.1:" + upstream.getAddress().getPort());
            var request = new MockHttpServletRequest("POST", "/api/app/v1/pdf-mcp");
            request.addHeader("Authorization", "Bearer account-token");
            request.addHeader("MCP-Protocol-Version", "2025-06-18");
            request.setContent("{\"id\":1}".getBytes(StandardCharsets.UTF_8));
            var response = new MockHttpServletResponse();
            new PdfMcpGatewayController(env).forward(request, response);
            assertEquals(200, response.getStatus());
            assertEquals("Bearer account-token", bearer.get());
            assertEquals("2025-06-18", version.get());
            assertEquals("{\"id\":1}", body.get());
            assertTrue(response.getContentAsString().contains("\"jsonrpc\""));
            assertFalse(response.getContentAsString().contains("\"data\""));
        } finally {
            upstream.stop(0);
        }
    }
}
