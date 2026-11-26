package com.yuyutian.mytools.toolbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.service.stats.StatsAggregator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * HTTP 级测试基类：真实随机端口启动服务，并在每个用例前清空业务表。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class ToolboxApiTestSupport {

    /** 需要按外键顺序清空的业务表。 */
    private static final List<String> TABLES = List.of(
            "toolbox_task_input",
            "toolbox_task",
            "toolbox_upload",
            "toolbox_stat_outbox",
            "toolbox_stat_applied",
            "toolbox_daily_stat",
            "toolbox_stat_cursor",
            "toolbox_success_dedupe",
            "toolbox_event",
            "toolbox_guest");

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected StatsAggregator aggregator;

    /**
     * 清空全部业务表，避免用例之间互相影响。
     */
    @BeforeEach
    void truncateBusinessTables() {
        for (String table : TABLES) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    /**
     * 匿名会话测试数据。
     *
     * @param cookie 会话 cookie 键值对
     * @param csrf   CSRF token
     */
    protected record TestSession(String cookie, String csrf) {
    }

    /**
     * 创建匿名会话并返回 cookie 与 CSRF token。
     *
     * @return 会话测试数据
     */
    protected TestSession createSession() {
        ResponseEntity<String> response = rest.postForEntity("/public/v1/session", null, String.class);
        return sessionOf(response);
    }

    /**
     * 从会话响应中提取 cookie 与 CSRF token。
     *
     * @param response 会话响应
     * @return 会话测试数据
     */
    protected TestSession sessionOf(ResponseEntity<String> response) {
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        if (setCookie == null) {
            throw new IllegalStateException("missing set-cookie header");
        }
        String cookie = setCookie.split(";", 2)[0];
        JsonNode body = json(response);
        return new TestSession(cookie, body.path("data").path("csrfToken").asText());
    }

    /**
     * 构造带会话与 CSRF 的 JSON 请求头。
     *
     * @param session 会话测试数据
     * @return 请求头
     */
    protected HttpHeaders jsonHeaders(TestSession session) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (session != null) {
            headers.add(HttpHeaders.COOKIE, session.cookie());
            headers.add("X-Toolbox-Csrf", session.csrf());
        }
        return headers;
    }

    /**
     * 发送 JSON POST 请求。
     *
     * @param path    接口路径，不含上下文路径
     * @param body    请求体对象，可为空
     * @param session 会话，可为空
     * @return 响应
     */
    protected ResponseEntity<String> postJson(String path, Object body, TestSession session) {
        return exchange(path, HttpMethod.POST, body, session);
    }

    /**
     * 发送通用请求。
     *
     * @param path    接口路径
     * @param method  HTTP 方法
     * @param body    请求体对象，可为空
     * @param session 会话，可为空
     * @return 响应
     */
    protected ResponseEntity<String> exchange(String path, HttpMethod method, Object body, TestSession session) {
        HttpHeaders headers = jsonHeaders(session);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        return rest.exchange(path, method, entity, String.class);
    }

    /**
     * 发送带原始 JSON 文本的请求，用于未知字段与超限场景。
     *
     * @param path    接口路径
     * @param rawBody 原始请求体
     * @param session 会话，可为空
     * @return 响应
     */
    protected ResponseEntity<String> postRaw(String path, String rawBody, TestSession session) {
        HttpHeaders headers = jsonHeaders(session);
        HttpEntity<String> entity = new HttpEntity<>(rawBody, headers);
        return rest.exchange(path, HttpMethod.POST, entity, String.class);
    }

    /**
     * 解析响应体为 JSON 树。
     *
     * @param response 响应
     * @return JSON 树
     */
    protected JsonNode json(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (IOException exception) {
            throw new IllegalStateException("response body is not json: " + response.getBody(), exception);
        }
    }

    /**
     * 解析字符串为 JSON 树。
     *
     * @param text JSON 文本
     * @return JSON 树
     */
    protected JsonNode json(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (IOException exception) {
            throw new IllegalStateException("text is not json", exception);
        }
    }

    /**
     * 把对象序列化为 JSON 文本。
     *
     * @param value 对象
     * @return JSON 文本
     */
    protected String jsonText(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot serialize json body", exception);
        }
    }

    /**
     * 使用 UTF-8 计算字符串字节数，用于断言请求体大小限制。
     *
     * @param text 文本
     * @return 字节数
     */
    protected int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
