package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
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
 * D02：回退导出清单。
 *
 * 设计第 8 节第 7 点要求导出包包含「最新 current manifest、**全部曾发布历史的独立索引**、
 * 原始版本字节和来源」。**关键是历史索引**：只带当前版本的话，
 * 曾发布过、后来被取代的内容会在回退时丢失——那些同样是用户的数据。
 *
 * F11：本用例要先发布才有历史可导，因此显式覆盖 `repository=database`；
 * `files` 模式下发布会被闸门拒绝（见 `FilesRepositoryPublishBlockedTest`）。
 *
 * 数据库来源下**上下文启动即会读库重建快照**，所以本类用独立内存库，
 * 不去读（也不去污染）其它用例留下的版本行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "toolbox.curriculum.repository=database")
@ActiveProfiles("test")
class CurriculumExportManifestTest {

    /**
     * 独立内存库：避免"启动时读到别的用例写入的版本行"这种顺序耦合。
     *
     * @param registry 动态属性注册器
     */
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:export-manifest-database;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    /**
     * 注入式假身份。
     *
     * **必须提供**：否则登录会走真实上游（测试环境里不可达），
     * 表现为"登录返回 403"，而错误信息指向 CSRF——**与实际原因完全无关**。
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class FakeIdentity {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityClient fakeIdentityClient() {
            return new com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityClient() {
                @Override
                public UpstreamLogin login(String account, String password) {
                    return new UpstreamLogin("access-x", "refresh-x", 3600L);
                }

                @Override
                public com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentity validate(String accessToken) {
                    return new com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentity(
                            true, "1001", "someone",
                            java.util.List.of(com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentity.ROLE_ADMIN));
                }

                @Override
                public void logout(String accessToken) {
                }
            };
        }
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("导出清单包含历史索引：被新版本取代的旧版本仍在其中（D02）")
    void exportManifestKeepsSupersededVersions() {
        HttpHeaders headers = loginAndHeaders();
        String editionId = "export-book-" + UUID.randomUUID().toString().substring(0, 8);

        // 发布两次，产生一个"被取代的历史版本"。
        create(headers, editionId);
        publish(headers, editionId);
        // 第二次不能"再建一次"（会 409）：必须在**已有草稿上更新**，再发布。
        updateDraft(headers, editionId);
        publish(headers, editionId);

        JsonNode manifest = TestJson.parse(rest.exchange("/admin/v1/curriculum/export-manifest",
                HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody()).path("data");
        assertThat(manifest.path("format").asText()).isEqualTo("toolbox-curriculum-export");

        JsonNode edition = null;
        for (JsonNode item : manifest.path("editions")) {
            if (editionId.equals(item.path("editionId").asText())) {
                edition = item;
            }
        }
        assertThat(edition).as("导出清单必须包含刚发布的教材").isNotNull();

        // 历史索引必须**多于一项**，且恰好一项被标记为 current。
        List<String> versions = new java.util.ArrayList<>();
        int currentCount = 0;
        for (JsonNode entry : edition.path("history")) {
            versions.add(entry.path("contentVersion").asText());
            assertThat(entry.path("contentSha256").asText()).hasSize(64);
            if (entry.path("current").asBoolean()) {
                currentCount += 1;
            }
        }
        assertThat(versions).as("历史索引必须保留被取代的版本，否则回退会丢内容").hasSizeGreaterThan(1);
        assertThat(currentCount).as("恰好一个版本是 current").isEqualTo(1);
        assertThat(edition.path("currentVersion").asText()).isEqualTo(versions.get(versions.size() - 1));
    }

    @Test
    @DisplayName("导出清单对匿名请求不可读（D02）")
    void exportManifestRequiresAdmin() {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/admin/v1/curriculum/export-manifest", HttpMethod.GET,
                new HttpEntity<>("{}", json), String.class);
        // 与既有管理端点对照：同样的匿名请求应当得到同样的结果。
        ResponseEntity<String> baseline = rest.exchange("/admin/v1/curriculum/reconciliation", HttpMethod.GET,
                new HttpEntity<>("{}", json), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    /** 建一册草稿。 */
    private void create(HttpHeaders headers, String editionId) {
        String draft = """
                {"editionId":"%s","subject":"chinese","grade":1,"semester":"first",
                 "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
                 "sourceNote":"纸质教材附录","coverageDeclaration":"partial",
                 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1}],
                 "lessons":[],
                 "entries":[{"rowId":"row-1","entryId":null,"unitId":null,"clientUnitId":"cu1",
                             "lessonId":null,"clientLessonId":null,"text":"山","pinyin":"shān",
                             "meaning":null,"listTypes":["writing"],"confirmed":true}]}
                """.formatted(editionId);
        HttpHeaders write = new HttpHeaders();
        write.putAll(headers);
        write.set("If-Match", "\"draft-0\"");
        write.set("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draft, write), String.class).getStatusCode().value()).isEqualTo(201);
    }

    /** 更新已有草稿：改一处正文，产生第二个版本。 */
    private void updateDraft(HttpHeaders headers, String editionId) {
        String revision = TestJson.parse(rest.exchange("/admin/v1/textbooks/" + editionId + "/draft",
                HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody())
                .path("data").path("revision").asText();
        String draft = """
                {"editionId":"%s","subject":"chinese","grade":1,"semester":"first",
                 "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
                 "sourceNote":"纸质教材附录","coverageDeclaration":"partial",
                 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1}],
                 "lessons":[],
                 "entries":[{"rowId":"row-1","entryId":null,"unitId":null,"clientUnitId":"cu1",
                             "lessonId":null,"clientLessonId":null,"text":"水","pinyin":"shuǐ",
                             "meaning":null,"listTypes":["writing"],"confirmed":true}]}
                """.formatted(editionId);
        HttpHeaders write = new HttpHeaders();
        write.putAll(headers);
        write.set("If-Match", "\"draft-" + revision + "\"");
        write.set("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(rest.exchange("/admin/v1/textbooks/" + editionId + "/draft", HttpMethod.PUT,
                new HttpEntity<>(draft, write), String.class).getStatusCode().value()).isEqualTo(200);
    }

    /** 发布一册。 */
    private void publish(HttpHeaders headers, String editionId) {
        HttpHeaders write = new HttpHeaders();
        write.putAll(headers);
        write.set("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(rest.exchange("/admin/v1/textbooks/" + editionId + "/publish", HttpMethod.POST,
                new HttpEntity<>(write), String.class).getStatusCode().value()).isEqualTo(200);
    }

    /** 走完整登录流程。 */
    private HttpHeaders loginAndHeaders() {
        ResponseEntity<String> csrf = rest.getForEntity("/admin/v1/auth/csrf", String.class);
        String preAuth = cookieOf(csrf, "toolbox_admin_login_local");
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.add(HttpHeaders.COOKIE, "toolbox_admin_login_local=" + preAuth);
        loginHeaders.add("X-Toolbox-Admin-Csrf",
                TestJson.parse(csrf.getBody()).path("data").path("csrfToken").asText());
        ResponseEntity<String> login = rest.exchange("/admin/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"account\":\"someone\",\"password\":\"secret\"}", loginHeaders), String.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, "toolbox_admin_local=" + cookieOf(login, "toolbox_admin_local"));
        headers.add("X-Toolbox-Admin-Csrf",
                TestJson.parse(login.getBody()).path("data").path("csrfToken").asText());
        return headers;
    }

    /** 从响应取 Cookie 值。 */
    private static String cookieOf(ResponseEntity<String> response, String name) {
        for (String header : response.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE, List.of())) {
            if (header.startsWith(name + "=")) {
                return header.substring(name.length() + 1).split(";")[0];
            }
        }
        throw new AssertionError("cookie not found: " + name);
    }
}
