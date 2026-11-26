package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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

/**
 * 来源清单测试：逗号分隔白名单精确匹配、清单外来源被拒绝、缺失 Origin 不因来源被拒。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "toolbox.public-origins=http://allowed-one.example, http://allowed-two.example")
@ActiveProfiles("test")
class OriginAllowlistTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("every origin in the comma separated list is accepted exactly")
    void listedOriginsAreAccepted() {
        for (String origin : List.of("http://allowed-one.example", "http://allowed-two.example")) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.ORIGIN, origin);
            headers.add("Access-Control-Request-Method", "POST");

            ResponseEntity<String> response = rest.exchange(
                    "/public/v1/tools", HttpMethod.OPTIONS, new HttpEntity<>(null, headers), String.class);

            assertThat(response.getStatusCode().value()).isEqualTo(204);
            assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo(origin);
            assertThat(response.getHeaders().getFirst("Access-Control-Allow-Credentials")).isEqualTo("true");
        }
    }

    @Test
    @DisplayName("origin outside the list is rejected on mutation")
    void originOutsideListIsRejected() throws Exception {
        Session session = createSession();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, session.cookie());
        headers.add("X-Toolbox-Csrf", session.csrf());
        headers.add(HttpHeaders.ORIGIN, "http://not-allowed.example");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = rest.exchange("/public/v1/events", HttpMethod.POST,
                new HttpEntity<>("{\"events\":[]}", headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(objectMapper.readTree(response.getBody()).path("code").asText()).isEqualTo("TOOLBOX_010");
    }

    @Test
    @DisplayName("absent or empty Origin is not rejected by the origin filter")
    void absentOriginIsNotRejected() throws Exception {
        Session session = createSession();
        for (String origin : List.of("", "   ")) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.COOKIE, session.cookie());
            headers.add("X-Toolbox-Csrf", session.csrf());
            if (!origin.isEmpty()) {
                headers.add(HttpHeaders.ORIGIN, origin);
            }
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> response = rest.exchange("/public/v1/events", HttpMethod.POST,
                    new HttpEntity<>("{\"events\":[]}", headers), String.class);

            assertThat(response.getStatusCode().value()).as("origin=[%s]", origin).isEqualTo(200);
        }
    }

    /**
     * 创建匿名会话。
     *
     * @return 会话 cookie 与 CSRF token
     */
    private Session createSession() throws Exception {
        ResponseEntity<String> response = rest.postForEntity("/public/v1/session", null, String.class);
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String cookie = setCookie.split(";", 2)[0];
        String csrf = objectMapper.readTree(response.getBody()).path("data").path("csrfToken").asText();
        return new Session(cookie, csrf);
    }

    /**
     * 会话测试数据。
     *
     * @param cookie 会话 cookie
     * @param csrf   CSRF token
     */
    private record Session(String cookie, String csrf) {
    }

}
