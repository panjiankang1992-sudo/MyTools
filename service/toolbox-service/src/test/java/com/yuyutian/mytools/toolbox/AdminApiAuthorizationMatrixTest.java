package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

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
 * 管理接口的**权限矩阵**（设计文档 §10 要求把"权限 API 矩阵"作为证据）。
 *
 * 数据驱动：把每个端点的"方法与路径"列出来，逐一验证匿名访问的结果。
 * 这样**新增端点时如果忘了保护，矩阵会立刻失败**——比逐个写用例更难遗漏。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminApiAuthorizationMatrixTest {

    @Autowired
    private TestRestTemplate rest;

    /** 需要管理员身份的端点：匿名访问必须 401。 */
    private static final List<String[]> PROTECTED = List.of(
            new String[]{"GET", "/admin/v1/textbooks"},
            new String[]{"POST", "/admin/v1/textbooks"},
            new String[]{"GET", "/admin/v1/textbooks/any-book/draft"},
            new String[]{"PUT", "/admin/v1/textbooks/any-book/draft"},
            new String[]{"POST", "/admin/v1/textbooks/any-book/validate"},
            new String[]{"POST", "/admin/v1/textbooks/any-book/publish"},
            new String[]{"GET", "/admin/v1/textbooks/any-book/draft/preview"},
            new String[]{"GET", "/admin/v1/curriculum/reconciliation"},
            // 第 161 轮新增的导入端点：**必须进矩阵**，否则将来它若漏了保护，没人会发现。
            new String[]{"POST", "/admin/v1/curriculum/import-from-files"},
            new String[]{"GET", "/admin/v1/curriculum/export-manifest"}
    );

    /** 按设计公开的端点：匿名也必须可用（否则登录无法开始）。 */
    private static final List<String[]> PUBLIC_BY_DESIGN = List.of(
            new String[]{"GET", "/admin/v1/session"},
            new String[]{"GET", "/admin/v1/auth/csrf"},
            new String[]{"POST", "/admin/v1/auth/login"},
            new String[]{"POST", "/admin/v1/auth/logout"}
    );

    @Test
    @DisplayName("权限矩阵：所有需授权的管理端点对匿名请求返回 401")
    void protectedEndpointsRejectAnonymous() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        StringBuilder matrix = new StringBuilder();
        for (String[] endpoint : PROTECTED) {
            ResponseEntity<String> response = rest.exchange(endpoint[1], HttpMethod.valueOf(endpoint[0]),
                    new HttpEntity<>("{}", headers), String.class);
            matrix.append(endpoint[0]).append(' ').append(endpoint[1])
                    .append(" -> ").append(response.getStatusCode().value()).append('\n');
            assertThat(response.getStatusCode().value())
                    .as("%s %s 必须拒绝匿名请求；矩阵：\n%s", endpoint[0], endpoint[1], matrix)
                    .isEqualTo(401);
            // 拒绝时不得泄漏任何业务数据。
            assertThat(response.getBody() == null || !response.getBody().contains("editionId"))
                    .as("%s %s 的拒绝响应不得包含教材数据", endpoint[0], endpoint[1]).isTrue();
        }
        // 把矩阵写进失败信息之外，也保证数量与清单一致（防止清单被误删）。
        assertThat(PROTECTED).hasSize(10);
    }

    @Test
    @DisplayName("权限矩阵：登录所需的端点对匿名请求保持可用（否则无法登录）")
    void loginEndpointsRemainReachable() {
        for (String[] endpoint : PUBLIC_BY_DESIGN) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> response = rest.exchange(endpoint[1], HttpMethod.valueOf(endpoint[0]),
                    new HttpEntity<>("{}", headers), String.class);
            // 状态码不足以区分"被会话过滤器拦住"与"端点自己拒绝"——两者都可能是 403
            // （登录端点会因缺少 CSRF nonce 自行返回 403 `TOOLBOX_ADMIN_003`）。
            // 因此按**错误码**判断：只要不是"需要会话"（`TOOLBOX_ADMIN_001`），
            // 就说明请求确实到达了端点，用户能正常发起登录。
            String code = response.getBody() == null ? ""
                    : TestJson.parse(response.getBody()).path("code").asText("");
            assertThat(code)
                    .as("%s %s 不应被会话过滤器拦截（实际 %s %s）",
                            endpoint[0], endpoint[1], response.getStatusCode().value(), code)
                    .isNotEqualTo("TOOLBOX_ADMIN_001");
        }
    }
}
