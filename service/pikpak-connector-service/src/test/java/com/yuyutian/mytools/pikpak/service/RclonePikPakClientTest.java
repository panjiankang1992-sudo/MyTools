package com.yuyutian.mytools.pikpak.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** rclone RC 白名单请求测试。 */
class RclonePikPakClientTest {
    /** addurl 始终映射到固定 backend/command 且路径由服务端拼装。 */
    @Test
    void shouldCallOnlyBackendCommandForAddUrl() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> body = new AtomicReference<>();
        List<String> calls = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/operations/mkdir", exchange -> {
            JsonNode mkdir = mapper.readTree(exchange.getRequestBody());
            calls.add("mkdir:" + mkdir.path("fs").asText() + mkdir.path("remote").asText());
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/backend/command", exchange -> {
            calls.add("addurl");
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
            assertThat(calls).containsExactly("mkdir:pikpak_remote:offline/operation-token", "addurl");
        } finally {
            server.stop(0);
        }
    }

    /** 创建目录失败时不得向默认收件箱提交，避免后续永远观察错误目录。 */
    @Test
    void shouldNotSubmitWhenIsolatedDirectoryCreationFails() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> submitted = new ArrayList<>();
        server.createContext("/operations/mkdir", exchange -> {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.createContext("/backend/command", exchange -> {
            submitted.add("unexpected");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            RclonePikPakClient client = new RclonePikPakClient(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "", "");
            client.validateConfiguration();
            assertThatThrownBy(() -> client.addUrl("pikpak_remote", "offline/operation-token",
                "magnet:?xt=urn:btih:" + "a".repeat(40)))
                .isInstanceOf(IllegalStateException.class).hasMessage("PIKPAK_010");
            assertThat(submitted).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    /** 空云端目录可继续等待，但缺少 list 字段的协议错误不能被吞掉。 */
    @Test
    void shouldAcceptNullListButRejectMissingList() throws Exception {
        AtomicReference<String> responseBody = new AtomicReference<>("{\"list\":null}");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/operations/list", exchange -> {
            byte[] response = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            RclonePikPakClient client = new RclonePikPakClient(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "", "");
            client.validateConfiguration();
            assertThat(client.list("pikpak_remote", "offline/operation-token")).isEmpty();
            responseBody.set("{}");
            assertThatThrownBy(() -> client.list("pikpak_remote", "offline/operation-token"))
                .isInstanceOf(IllegalStateException.class).hasMessage("PIKPAK_006");
        } finally {
            server.stop(0);
        }
    }
}
