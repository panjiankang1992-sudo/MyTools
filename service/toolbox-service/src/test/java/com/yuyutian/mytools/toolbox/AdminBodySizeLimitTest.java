package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 管理端请求体上限（设计第 6 节要求，此前**只覆盖两个公开路径**）。
 *
 * **为什么单独一个类**：这里把上限调到 1 KB 来验证拒绝逻辑。
 * 若用真实的 4 MB 上限去测，客户端要写 4 MB 才能被拒——
 * 服务端会先回 413 并关闭连接，客户端于是收到 `Broken pipe` 而不是响应，
 * **测出来的是"连接断了"而不是"返回了 007"**。
 * 上限本身是可配置的，所以用小上限测语义、用默认值断言"正常内容不受影响"。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminBodySizeLimitTest {

    @DynamicPropertySource
    static void tinyLimit(DynamicPropertyRegistry registry) {
        registry.add("toolbox.admin.max-body-bytes", () -> 1024);
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("管理端写请求超限返回 413 与 TOOLBOX_ADMIN_007")
    void oversizedAdminWriteIsRejectedWithAdminCode() {
        // 1200 字节 > 1024 的限额；用一个能完整写出的体，这样读回的是响应而不是断管。
        String body = "{\"pad\":\"" + "x".repeat(1200) + "\"}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode().value()).as("超限应返回 413").isEqualTo(413);
        assertThat(response.getBody()).contains("TOOLBOX_ADMIN_007");
        // 错误码必须是**管理端**的那个：运维看到 007 就知道是管理链路被限。
        assertThat(response.getBody()).doesNotContain("TOOLBOX_005");
    }

    @Test
    @DisplayName("未超限的管理端请求不会被这道限制拦下")
    void underLimitIsNotRejectedBySizeFilter() {
        // 关键的反向断言：限制若写错（比如对所有请求都生效），
        // 这个请求会拿到 413；它拿到 401 才说明**是权限在拦，不是体积在拦**。
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(Map.of("pad", "small"), headers), String.class);
        assertThat(response.getStatusCode().value()).as("未超限时不应因体积被拒").isEqualTo(401);
    }
}
