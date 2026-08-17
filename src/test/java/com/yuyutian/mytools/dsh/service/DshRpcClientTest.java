package com.yuyutian.mytools.dsh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.dsh.config.DshProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DshRpcClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldValidateEnvelopeAndReturnValue() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/host.describe", exchange -> {
            var request = mapper.readTree(exchange.getRequestBody());
            assertThat(request.path("type").asText()).isEqualTo("client-request");
            assertThat(request.path("method").asText()).isEqualTo("host.describe");
            String response = "{\"type\":\"server-response\",\"rpcId\":\""
                    + request.path("rpcId").asText() + "\",\"result\":{\"ok\":true,"
                    + "\"value\":{\"version\":\"0.0.1\"}}}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        DshProperties properties = properties("http://127.0.0.1:" + server.getAddress().getPort());

        var value = new DshRpcClient(mapper, properties).call("host.describe", mapper.createObjectNode());

        assertThat(value.path("version").asText()).isEqualTo("0.0.1");
    }

    @Test
    void shouldRejectNonAllowlistedMethodAndNonLoopbackEndpoint() {
        ObjectMapper mapper = new ObjectMapper();
        DshRpcClient client = new DshRpcClient(mapper, properties("http://127.0.0.1:3080"));

        assertThatThrownBy(() -> client.call("credentials.describe", mapper.createObjectNode()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new DshRpcClient(mapper, properties("http://192.168.1.8:3080")))
                .isInstanceOf(IllegalStateException.class);
    }

    private DshProperties properties(String baseUrl) {
        DshProperties properties = new DshProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(baseUrl);
        properties.setConnectTimeoutSeconds(2);
        properties.setRequestTimeoutSeconds(2);
        return properties;
    }
}
