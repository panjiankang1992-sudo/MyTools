package com.yuyutian.mytools.pdf.service;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 调度器 HTTP 替身：记录调用方声明的服务身份与请求正文，用于锁定派发参数。
 * 只监听回环地址与随机端口，不访问任何真实调度器。
 */
final class SchedulerStub implements AutoCloseable {

    private final HttpServer server;
    /** 每次调用携带的 X-Task-Service-Id。 */
    final List<String> serviceIds = new ArrayList<>();
    /** 每次创建任务实例的请求正文。 */
    final List<String> createBodies = new ArrayList<>();

    /** 启动替身并注册任务实例路径。 */
    SchedulerStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/task-instances", exchange -> {
            serviceIds.add(exchange.getRequestHeaders().getFirst("X-Task-Service-Id"));
            String path = exchange.getRequestURI().getPath();
            byte[] request = exchange.getRequestBody().readAllBytes();
            byte[] response;
            // 创建任务实例返回带标识的结果，其余状态查询返回运行中。
            if ("POST".equals(exchange.getRequestMethod()) && "/api/v1/task-instances".equals(path)) {
                createBodies.add(new String(request, StandardCharsets.UTF_8));
                response = ("{\"id\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8);
            } else {
                response = "{\"status\":\"RUNNING\"}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    /** 返回可配置给 pdf.scheduler-url 的基址。 */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 清空已记录的调用，便于每个用例独立断言。 */
    void reset() {
        serviceIds.clear();
        createBodies.clear();
    }

    /** 停止替身。 */
    @Override
    public void close() {
        server.stop(0);
    }
}
