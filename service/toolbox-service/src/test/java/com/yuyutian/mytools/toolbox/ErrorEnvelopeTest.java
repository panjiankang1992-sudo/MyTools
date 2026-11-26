package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.toolbox.common.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * 错误信封测试：错误码到 HTTP 状态的映射、messageKey 稳定性与不泄漏内部信息。
 */
class ErrorEnvelopeTest extends ToolboxApiTestSupport {

    /** details 允许出现的键，只包含字段路径、限额与索引。 */
    private static final Set<String> SAFE_DETAIL_KEYS =
            Set.of("field", "reason", "limit", "limitName", "actual", "min", "max", "index", "allowed");

    @Test
    @DisplayName("TOOLBOX_001..012 map to documented statuses and message keys")
    void errorCodesMapToContractStatuses() {
        // 公开契约 12 项 + 管理门面 11 项（设计文档第 6 节的 10 项，加 F11 发布闸门的 011）。
        assertThat(ErrorCode.values()).hasSize(23);
        assertThat(ErrorCode.TOOLBOX_001.httpStatus()).isEqualTo(422);
        assertThat(ErrorCode.TOOLBOX_002.httpStatus()).isEqualTo(401);
        assertThat(ErrorCode.TOOLBOX_003.httpStatus()).isEqualTo(404);
        assertThat(ErrorCode.TOOLBOX_004.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.TOOLBOX_005.httpStatus()).isEqualTo(413);
        assertThat(ErrorCode.TOOLBOX_006.httpStatus()).isEqualTo(429);
        assertThat(ErrorCode.TOOLBOX_007.httpStatus()).isEqualTo(410);
        assertThat(ErrorCode.TOOLBOX_008.httpStatus()).isEqualTo(503);
        assertThat(ErrorCode.TOOLBOX_009.httpStatus()).isEqualTo(422);
        assertThat(ErrorCode.TOOLBOX_010.httpStatus()).isEqualTo(403);
        assertThat(ErrorCode.TOOLBOX_011.httpStatus()).isEqualTo(422);
        assertThat(ErrorCode.TOOLBOX_012.httpStatus()).isEqualTo(503);

        assertThat(ErrorCode.TOOLBOX_001.messageKey()).isEqualTo("request.invalid");
        assertThat(ErrorCode.TOOLBOX_002.messageKey()).isEqualTo("session.required");
        assertThat(ErrorCode.TOOLBOX_003.messageKey()).isEqualTo("resource.notFound");
        assertThat(ErrorCode.TOOLBOX_004.messageKey()).isEqualTo("request.conflict");
        assertThat(ErrorCode.TOOLBOX_005.messageKey()).isEqualTo("task.limitExceeded");
        assertThat(ErrorCode.TOOLBOX_006.messageKey()).isEqualTo("request.rateLimited");
        assertThat(ErrorCode.TOOLBOX_007.messageKey()).isEqualTo("resource.expired");
        assertThat(ErrorCode.TOOLBOX_008.messageKey()).isEqualTo("service.unavailable");
        assertThat(ErrorCode.TOOLBOX_009.messageKey()).isEqualTo("input.unsupported");
        assertThat(ErrorCode.TOOLBOX_010.messageKey()).isEqualTo("request.forbidden");
        assertThat(ErrorCode.TOOLBOX_011.messageKey()).isEqualTo("content.unusable");
        assertThat(ErrorCode.TOOLBOX_012.messageKey()).isEqualTo("upstream.invalidResult");

        // 管理门面错误码：状态与 messageKey 必须与设计文档第 6 节表格一致。
        assertThat(ErrorCode.TOOLBOX_ADMIN_001.httpStatus()).isEqualTo(401);
        assertThat(ErrorCode.TOOLBOX_ADMIN_002.httpStatus()).isEqualTo(403);
        assertThat(ErrorCode.TOOLBOX_ADMIN_003.httpStatus()).isEqualTo(403);
        assertThat(ErrorCode.TOOLBOX_ADMIN_004.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.TOOLBOX_ADMIN_005.httpStatus()).isEqualTo(422);
        assertThat(ErrorCode.TOOLBOX_ADMIN_006.httpStatus()).isEqualTo(404);
        assertThat(ErrorCode.TOOLBOX_ADMIN_007.httpStatus()).isEqualTo(413);
        assertThat(ErrorCode.TOOLBOX_ADMIN_008.httpStatus()).isEqualTo(429);
        assertThat(ErrorCode.TOOLBOX_ADMIN_009.httpStatus()).isEqualTo(503);
        assertThat(ErrorCode.TOOLBOX_ADMIN_010.httpStatus()).isEqualTo(428);
        // F11：目录来源为只读文件时禁止发布——"状态冲突"，不是参数错误、也不是权限问题。
        assertThat(ErrorCode.TOOLBOX_ADMIN_011.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.TOOLBOX_ADMIN_001.messageKey()).isEqualTo("admin.session.required");
        assertThat(ErrorCode.TOOLBOX_ADMIN_004.messageKey()).isEqualTo("admin.conflict");
        assertThat(ErrorCode.TOOLBOX_ADMIN_010.messageKey()).isEqualTo("admin.precondition.required");
        assertThat(ErrorCode.TOOLBOX_ADMIN_011.messageKey()).isEqualTo("admin.publish.disabled");

        for (ErrorCode code : ErrorCode.values()) {
            // 两个家族：公开门面 TOOLBOX_0xx 与管理门面 TOOLBOX_ADMIN_0xx。
            assertThat(code.name()).matches("TOOLBOX_(ADMIN_)?0\\d{2}");
            assertThat(code.httpStatus()).isBetween(400, 599);
        }
    }

    @Test
    @DisplayName("error body is stable and leaks no stack trace, SQL or internal path")
    void errorBodyIsSafeAndComplete() {
        ResponseEntity<String> response = rest.getForEntity(
                "/public/v1/textbooks/unknown-edition/versions/9.9.9", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        JsonNode body = json(response);
        assertThat(body.path("code").asText()).isEqualTo("TOOLBOX_003");
        assertThat(body.path("messageKey").asText()).isEqualTo("resource.notFound");
        assertThat(body.path("details").isObject()).isTrue();
        assertThat(body.path("requestId").asText()).isNotBlank();
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo(body.path("requestId").asText());

        String raw = response.getBody();
        assertThat(raw).isNotNull();
        assertThat(raw).doesNotContain("stackTrace");
        assertThat(raw).doesNotContain("trace");
        assertThat(raw).doesNotContain("Exception");
        assertThat(raw).doesNotContain("java.");
        assertThat(raw).doesNotContain("select ");
        assertThat(raw).doesNotContain("/Users/");
        assertThat(raw).doesNotContain("jdbc:");
    }

    @Test
    @DisplayName("error details only contain safe keys")
    void errorDetailsOnlyContainSafeKeys() {
        ResponseEntity<String> response = rest.getForEntity(
                "/public/v1/textbooks/chinese-g1-first-a/versions/1.0.0/entries?limit=900", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        JsonNode details = json(response).path("details");
        details.fieldNames().forEachRemaining(name -> assertThat(SAFE_DETAIL_KEYS).contains(name));
        assertThat(details.path("max").asInt()).isEqualTo(500);
        assertThat(details.path("actual").asInt()).isEqualTo(900);
    }

    @Test
    @DisplayName("missing session and bad CSRF return TOOLBOX_002 and TOOLBOX_010")
    void sessionErrorsUseStableCodes() {
        ResponseEntity<String> unauthorized = postJson("/public/v1/events", Map.of("events", List.of()), null);
        assertThat(unauthorized.getStatusCode().value()).isEqualTo(401);
        assertThat(json(unauthorized).path("code").asText()).isEqualTo("TOOLBOX_002");

        TestSession session = createSession();
        HttpHeaders headers = jsonHeaders(session);
        headers.set("X-Toolbox-Csrf", "wrong");
        ResponseEntity<String> forbidden = rest.exchange("/public/v1/events", HttpMethod.POST,
                new HttpEntity<>("{\"events\":[]}", headers), String.class);

        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
        assertThat(json(forbidden).path("code").asText()).isEqualTo("TOOLBOX_010");
    }

    @Test
    @DisplayName("health endpoint leaks no configuration")
    void healthEndpointDoesNotLeakConfiguration() {
        ResponseEntity<String> response = rest.getForEntity("/public/v1/health", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.path("status").asText()).isEqualTo("UP");
        String raw = response.getBody();
        assertThat(raw).isNotNull();
        assertThat(raw).doesNotContain("datasource");
        assertThat(raw).doesNotContain("password");
        assertThat(raw).doesNotContain("jdbc");
        assertThat(raw).doesNotContain("h2");
        assertThat(raw).doesNotContain("mysql");
        assertThat(raw).doesNotContain("components");
        assertThat(raw).doesNotContain("toolbox");
    }
}
