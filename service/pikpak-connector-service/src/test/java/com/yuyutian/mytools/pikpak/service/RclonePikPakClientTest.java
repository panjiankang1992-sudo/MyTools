package com.yuyutian.mytools.pikpak.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** rclone RC 白名单请求测试。 */
class RclonePikPakClientTest {
    /** addurl 始终映射到固定 backend/command 且路径由服务端拼装。 */
    @Test
    void shouldCallOnlyBackendCommandForAddUrl() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/backend/command", exchange -> {
            body.set(mapper.readTree(exchange.getRequestBody()));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            RclonePikPakClient client = new RclonePikPakClient(mapper,
                "http://127.0.0.1:" + server.getAddress().getPort(), "", "");
            client.validateConfiguration();
            String magnet = "magnet:?xt=urn:btih:" + "a".repeat(40);

            client.addUrl("pikpak_remote", "offline/operation-token", magnet);

            assertThat(body.get().path("command").asText()).isEqualTo("addurl");
            assertThat(body.get().path("fs").asText()).isEqualTo("pikpak_remote:offline/operation-token");
            assertThat(body.get().path("arg").get(0).asText()).isEqualTo(magnet);
        } finally {
            server.stop(0);
        }
    }
}
