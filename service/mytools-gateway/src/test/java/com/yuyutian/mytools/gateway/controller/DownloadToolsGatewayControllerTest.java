package com.yuyutian.mytools.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DownloadToolsGatewayControllerTest {
    @Test void restrictsRoutes() {
        assertFalse(DownloadToolsGatewayController.allowed("GET", "/../../health"));
        assertFalse(DownloadToolsGatewayController.allowed("POST", "/internal/result"));
        assertFalse(DownloadToolsGatewayController.allowed("DELETE", "/history"));
        assertTrue(DownloadToolsGatewayController.allowed("GET", "/history"));
    }
    @Test void requiresTrustedPrincipalAndOverwritesIdentity() throws Exception {
        var properties=mock(GatewayProperties.class);
        when(properties.downloadRouteEnabled()).thenReturn(true);
        when(properties.downloadToken()).thenReturn("internal-test");
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<String> owner=new AtomicReference<>();
        AtomicReference<String> username=new AtomicReference<>();
        AtomicReference<String> query=new AtomicReference<>();
        server.createContext("/internal/v1/app-download-tools/history",exchange->{
            owner.set(exchange.getRequestHeaders().getFirst("X-Owner-Id"));
            username.set(exchange.getRequestHeaders().getFirst("X-Resource-Username"));
            query.set(exchange.getRequestURI().getRawQuery());
            assertEquals("Bearer internal-test",exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes="[]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            when(properties.downloadUrl()).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
            var controller=new DownloadToolsGatewayController(properties,new ObjectMapper());
            var request=new MockHttpServletRequest("GET","/api/app/v1/download-tools/history");
            request.addHeader("X-Owner-Id","999");request.addHeader("X-Resource-Username","victim");
            var rejected=new MockHttpServletResponse();controller.forward(request,rejected);assertEquals(401,rejected.getStatus());
            request.setAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE,new GatewayPrincipal(7,"demo",List.of(),UUID.randomUUID()));
            var response=new MockHttpServletResponse();controller.forward(request,response);
            assertEquals(200,response.getStatus());assertEquals("7",owner.get());assertEquals("demo",username.get());
            assertEquals("0000",new ObjectMapper().readTree(response.getContentAsString()).path("code").asText());
            assertNull(query.get());
            request.addParameter("limit","10");
            request.addParameter("before","sample_Cursor-1");
            request.addParameter("ownerId","999");
            response=new MockHttpServletResponse();controller.forward(request,response);
            assertEquals(200,response.getStatus());
            assertEquals("limit=10&before=sample_Cursor-1",query.get());
            assertEquals("7",owner.get());
            request.addParameter("before","second");
            response=new MockHttpServletResponse();controller.forward(request,response);
            assertEquals(400,response.getStatus());
            request.setMethod("PUT");request.setRequestURI("/api/app/v1/download-tools/uploads/"+UUID.randomUUID()+"/content");
            request.setContent(new byte[1048577]);
            response=new MockHttpServletResponse();controller.forward(request,response);assertEquals(413,response.getStatus());
        } finally {server.stop(0);}
    }
}
