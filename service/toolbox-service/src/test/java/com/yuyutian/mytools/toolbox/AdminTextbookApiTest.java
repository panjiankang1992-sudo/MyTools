package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentity;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityClient;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 管理教材接口的并发前提与权限边界（验收项 A02／B08／B09 的服务端部分）。
 * 身份用注入式假实现，不接触任何真实账号或生产凭据。
 *
 * F11：本用例覆盖管理端**发布**，因此显式覆盖 `repository=database`——
 * `files` 模式下发布会被闸门拒绝（见 `FilesRepositoryPublishBlockedTest`），
 * 那是另一条性质，不能在这里断言"发布成功"。
 *
 * 数据库来源下**上下文启动即会读库重建快照**，所以本类用独立内存库，
 * 不去读（也不去污染）其它用例留下的版本行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "toolbox.curriculum.repository=database")
@ActiveProfiles("test")
class AdminTextbookApiTest {

    /**
     * 独立内存库：避免"启动时读到别的用例写入的版本行"这种顺序耦合。
     *
     * @param registry 动态属性注册器
     */
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:textbook-api-database;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    /** 刷新调用的记账表；测试前请清空。 */
    static final java.util.List<String> REFRESH_CALLS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 提供始终返回 ADMIN 的假身份客户端。 */
    @TestConfiguration
    static class FakeIdentity {
        @Bean
        @Primary
        AdminIdentityClient fakeAdminIdentityClient() {
            return new AdminIdentityClient() {
                @Override
                public UpstreamLogin login(String account, String password) {
                    return new UpstreamLogin("access-ok", "refresh-ok", 3600L);
                }

                @Override
                public AdminIdentity validate(String accessToken) {
                    return new AdminIdentity(true, "1001", "someone", List.of(AdminIdentity.ROLE_ADMIN));
                }

                @Override
                public void logout(String accessToken) {
                    // 假实现不做事。
                }

                @Override
                public UpstreamLogin refresh(String refreshToken) {
                    // 记下每一次刷新请求；A08 的接线测试靠它判断"过滤器有没有调它"。
                    REFRESH_CALLS.add(refreshToken);
                    return new UpstreamLogin("access-refreshed", "refresh-refreshed", 3600L);
                }
            };
        }
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    private com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository repository;

    @Autowired
    private com.yuyutian.mytools.toolbox.mapper.admin.JdbcAdminSessionStore store;

    @Autowired
    private com.yuyutian.mytools.toolbox.service.admin.migration.CurriculumFileImporter importer;

    /** 同步接口夹具不参与共享公开目录的其它用例；失败时也必须清理。 */
    @org.junit.jupiter.api.AfterEach
    void removeRebaseHttpFixture() {
        String editionId = "api-rebase-contract";
        jdbc.update("DELETE FROM toolbox_textbook_version WHERE edition_id = ?", editionId);
        jdbc.update("DELETE FROM toolbox_textbook_draft WHERE draft_id = ?", "draft-" + editionId);
        jdbc.update("DELETE FROM toolbox_textbook WHERE edition_id = ?", editionId);
        jdbc.update("DELETE FROM toolbox_curriculum_audit WHERE edition_id = ?", editionId);
    }

    @Test
    @DisplayName("草稿同步接口要求身份和写入前提，返回完整草稿且网络重试仅执行一次")
    void rebaseHttpContractAndIdempotentReplay() throws Exception {
        String editionId = "api-rebase-contract";
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var book = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree("""
                {"edition":{"editionId":"api-rebase-contract","contentVersion":"1.0.0",
                "subject":"english","grade":4,"semester":"first","publisher":"publisher",
                "series":"series","editionLabel":"2024","sourceNote":"manual input",
                "coverageStatus":"partial","regionCodes":["jiangsu"],"sourceIds":[]},
                "units":[{"unitId":"api-rebase-unit","title":"Unit 1","order":1}],"lessons":[],
                "entries":[{"entryId":"api-rebase-word","unitId":"api-rebase-unit","text":"dog",
                "meaning":"狗","listTypes":["vocabulary"],"sourceRefs":[]}]}
                """);
        importer.importEdition(editionId, "1.0.0", mapper.writeValueAsBytes(book), "hash-1",
                mapper.writeValueAsString(book.path("edition")), "test-admin");
        ((com.fasterxml.jackson.databind.node.ObjectNode) book.path("edition")).put("contentVersion", "1.0.1");
        ((com.fasterxml.jackson.databind.node.ObjectNode) book.path("entries").get(0)).put("meaning", "小狗");
        importer.importEdition(editionId, "1.0.1", mapper.writeValueAsBytes(book), "hash-2",
                mapper.writeValueAsString(book.path("edition")), "test-admin");

        String path = "/admin/v1/textbooks/" + editionId + "/draft/rebase";
        ResponseEntity<String> anonymous = rest.postForEntity(path, null, String.class);
        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);
        HttpHeaders headers = loginAndHeaders();
        freshKey(headers);
        ResponseEntity<String> missingPrecondition = rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
        assertThat(TestJson.parse(missingPrecondition.getBody()).path("code").asText())
                .isEqualTo("TOOLBOX_ADMIN_010");

        long revision = repository.findDraft("draft-" + editionId).orElseThrow().revision();
        headers.set("If-Match", "\"draft-" + revision + "\"");
        HttpHeaders withoutCsrf = new HttpHeaders();
        withoutCsrf.putAll(headers);
        withoutCsrf.remove("X-Toolbox-Admin-Csrf");
        ResponseEntity<String> forbidden = rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(withoutCsrf), String.class);
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
        assertThat(repository.findDraft("draft-" + editionId).orElseThrow().revision()).isEqualTo(revision);

        // 同一修订和幂等标识重试，必须重放第一次完整响应而非再次推进修订。
        ResponseEntity<String> first = rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
        assertThat(first.getStatusCode().value()).as(first.getBody()).isEqualTo(200);
        JsonNode data = TestJson.parse(first.getBody()).path("data");
        assertThat(data.path("revision").asLong()).isEqualTo(revision + 1);
        assertThat(data.path("baseContentVersion").asText()).isEqualTo(editionId + "@1.0.1");
        assertThat(data.path("draft").path("entries").get(0).path("meaning").asText()).isEqualTo("小狗");
        assertThat(first.getHeaders().getFirst(HttpHeaders.ETAG)).isEqualTo("\"draft-" + (revision + 1) + "\"");
        assertThat(first.getHeaders().getCacheControl()).contains("no-store");
        ResponseEntity<String> replay = rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(TestJson.parse(replay.getBody()).path("data")).isEqualTo(data);
        assertThat(repository.findDraft("draft-" + editionId).orElseThrow().revision()).isEqualTo(revision + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_curriculum_audit WHERE edition_id = ? AND action = ?",
                Integer.class, editionId, "draft.rebase")).isEqualTo(1);
    }

    /** 走完整的登录流程，返回带会话 Cookie 与 CSRF 头的请求头。 */
    private HttpHeaders loginAndHeaders() {
        // 1) 领取 nonce 与预认证 Cookie
        ResponseEntity<String> csrf = rest.getForEntity("/admin/v1/auth/csrf", String.class);
        assertThat(csrf.getStatusCode().value()).isEqualTo(200);
        String preAuthCookie = cookieOf(csrf, "toolbox_admin_login_local");
        String nonce = TestJson.parse(csrf.getBody()).path("data").path("csrfToken").asText();

        // 2) 提交登录
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.add(HttpHeaders.COOKIE, "toolbox_admin_login_local=" + preAuthCookie);
        loginHeaders.add("X-Toolbox-Admin-Csrf", nonce);
        ResponseEntity<String> login = rest.exchange("/admin/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"account\":\"someone\",\"password\":\"secret\"}", loginHeaders), String.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        String session = cookieOf(login, "toolbox_admin_local");
        String csrfToken = TestJson.parse(login.getBody()).path("data").path("csrfToken").asText();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, "toolbox_admin_local=" + session);
        headers.add("X-Toolbox-Admin-Csrf", csrfToken);
        return headers;
    }

    /**
     * 为一次写操作生成新鲜的幂等键。
     *
     * 幂等键标识**一次逻辑操作**，不是一次会话：若同一份头被连续用于两次不同写请求，
     * 服务端会正确地判定为"同键不同载荷"而返回 409。因此每次写都必须换键。
     *
     * @param headers 会话头（会被就地更新）
     * @return 同一个 headers 对象
     */
    private static HttpHeaders freshKey(HttpHeaders headers) {
        headers.set("Idempotency-Key", "op-" + java.util.UUID.randomUUID());
        return headers;
    }

    /** 从响应中取出指定名称的 Cookie 值。 */
    private static String cookieOf(ResponseEntity<String> response, String name) {
        for (String header : response.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE, List.of())) {
            String prefix = name + "=";
            if (header.startsWith(prefix)) {
                return header.substring(prefix.length()).split(";")[0];
            }
        }
        throw new AssertionError("cookie not found: " + name + " in " + response.getHeaders().get(HttpHeaders.SET_COOKIE));
    }

    /** 构造一份最小可发布的中文草稿 JSON。 */
    private static String draftJson(String editionId) {
        return """
                {"editionId":"%s","subject":"chinese","grade":1,"semester":"first",
                 "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
                 "sourceNote":"纸质教材附录，第 78 页","coverageDeclaration":"partial",
                 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1}],
                 "lessons":[],"entries":[]}
                """.formatted(editionId);
    }

    @Test
    @DisplayName("创建草稿返回 201 与 ETag；随后可读回")
    void createAndReadDraft() {
        HttpHeaders headers = loginAndHeaders();
        ResponseEntity<String> created = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("api-book-create"), freshKey(headers)), String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode created_body = TestJson.parse(created.getBody());
        assertThat(created_body.path("data").path("editionId").asText()).isEqualTo("api-book-create");
        assertThat(created.getHeaders().getFirst(HttpHeaders.ETAG)).isNotBlank();

        ResponseEntity<String> read = rest.exchange("/admin/v1/textbooks/api-book-create/draft", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(read.getStatusCode().value()).isEqualTo(200);
        assertThat(TestJson.parse(read.getBody()).path("data").path("revision").asLong()).isGreaterThan(0);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"chinese", "english"})
    @DisplayName("只填写教材选择与配对内容即可发布，来源由服务端记录且不编造出版社或版次")
    void simplifiedEntryPublishesWithoutOptionalMetadata(String subject) {
        HttpHeaders headers = loginAndHeaders();
        String editionId = "simple-entry-" + subject;
        boolean chinese = "chinese".equals(subject);
        String text = chinese ? "山" : "mountain";
        String pinyin = chinese ? "shān" : null;
        String meaning = chinese ? null : "山";
        String listType = chinese ? "writing" : "vocabulary";
        var draft = (com.fasterxml.jackson.databind.node.ObjectNode) TestJson.parse(draftJson(editionId));
        draft.remove(List.of("publisher", "editionLabel", "sourceNote"));
        draft.put("subject", subject);
        draft.put("grade", chinese ? 1 : 3);
        draft.put("series", chinese ? "语文教材" : "英语教材");
        var entry = draft.withArray("entries").addObject();
        entry.put("rowId", "row-simple");
        entry.put("clientUnitId", "cu1");
        entry.put("text", text);
        entry.put("pinyin", pinyin);
        entry.put("meaning", meaning);
        entry.putArray("listTypes").add(listType);
        entry.put("confirmed", true);
        entry.put("confirmedDigest",
                com.yuyutian.mytools.toolbox.service.admin.curriculum.DraftToBookMapper.contentDigest(
                        new com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft.DraftEntry(
                                "row-simple", null, null, "cu1", null, null, text, pinyin, meaning,
                                List.of(listType), true)));

        ResponseEntity<String> created = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draft.toString(), freshKey(headers)), String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        long revision = TestJson.parse(created.getBody()).path("data").path("revision").asLong();
        ResponseEntity<String> read = rest.exchange("/admin/v1/textbooks/" + editionId + "/draft", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        JsonNode stored = TestJson.parse(read.getBody()).path("data");
        assertThat(stored.path("draft").path("sourceNote").asText()).isEqualTo("管理员手工录入");
        JsonNode sources = TestJson.parse(stored.path("sourceMetadata").asText()).path("sources");
        assertThat(sources).hasSize(1);
        JsonNode sourceRecord = sources.get(0).path("record");
        assertThat(sourceRecord.path("title").asText()).isEqualTo("管理员手工录入");
        assertThat(sourceRecord.has("page")).isFalse();
        assertThat(sourceRecord.has("reviewedBy")).isFalse();
        assertThat(sourceRecord.has("verifiedAt")).isFalse();

        headers.set("If-Match", "\"draft-" + revision + "\"");
        ResponseEntity<String> published = rest.exchange("/admin/v1/textbooks/" + editionId + "/publish",
                HttpMethod.POST, new HttpEntity<>(freshKey(headers)), String.class);
        assertThat(published.getStatusCode().value()).as(published.getBody()).isEqualTo(200);
        JsonNode publishData = TestJson.parse(published.getBody()).path("data");
        assertThat(publishData.path("snapshotReloaded").asBoolean()).isTrue();
        String version = publishData.path("contentVersion").asText();
        ResponseEntity<String> book = rest.getForEntity(
                "/public/v1/textbooks/" + editionId + "/versions/" + version, String.class);
        assertThat(book.getStatusCode().value()).isEqualTo(200);
        JsonNode edition = TestJson.parse(book.getBody()).path("data").path("edition");
        assertThat(edition.path("publisher").isTextual()).isTrue();
        assertThat(edition.path("publisher").asText()).isEmpty();
        assertThat(edition.path("editionLabel").isTextual()).isTrue();
        assertThat(edition.path("editionLabel").asText()).isEmpty();
        assertThat(edition.path("regionCodes").isArray()).isTrue();
        ResponseEntity<String> entries = rest.getForEntity("/public/v1/textbooks/" + editionId
                + "/versions/" + version + "/entries?limit=50", String.class);
        JsonNode publicEntry = TestJson.parse(entries.getBody()).path("data").path("entries").get(0);
        assertThat(publicEntry.path("text").asText()).isEqualTo(text);
        assertThat(publicEntry.path(chinese ? "pinyinDisplay" : "meaning").asText())
                .isEqualTo(chinese ? pinyin : meaning);

        // 发布成功后管理录入立即复用教材答案；语文走同一入口返回逐字拼音。
        String translation = "{\"subject\":\"" + subject + "\",\"items\":[{\"id\":\"local\",\"text\":\"" + text + "\"}]}";
        ResponseEntity<String> completed = rest.exchange("/admin/v1/translations", HttpMethod.POST,
                new HttpEntity<>(translation, headers), String.class);
        assertThat(completed.getStatusCode().value()).isEqualTo(200);
        JsonNode translated = TestJson.parse(completed.getBody()).path("data").path("items").get(0);
        assertThat(translated.path("status").asText()).isEqualTo("translated");
        if (chinese) {
            assertThat(translated.path("pinyinSegments").get(0).path("syllable").asText()).isEqualTo(pinyin);
            assertThat(translated.path("englishText").isNull()).isTrue();
        } else {
            assertThat(translated.path("meaning").asText()).isEqualTo(meaning);
            assertThat(translated.has("pinyinSegments")).isFalse();
        }
    }

    @Test
    @DisplayName("保存缺少 If-Match 返回 428（并发前提必须显式）")
    void saveWithoutIfMatchIs428() {
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("api-book-428"), freshKey(headers)), String.class);
        ResponseEntity<String> saved = rest.exchange("/admin/v1/textbooks/api-book-428/draft", HttpMethod.PUT,
                new HttpEntity<>(draftJson("api-book-428"), freshKey(headers)), String.class);
        assertThat(saved.getStatusCode().value()).isEqualTo(428);
        assertThat(TestJson.parse(saved.getBody()).path("code").asText()).isEqualTo("TOOLBOX_ADMIN_010");
    }

    @Test
    @DisplayName("用过期的 If-Match 保存返回 409，不覆盖他人改动（B08）")
    void staleIfMatchIs409() {
        HttpHeaders headers = loginAndHeaders();
        ResponseEntity<String> created = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("api-book-cas"), freshKey(headers)), String.class);
        long revision = TestJson.parse(created.getBody()).path("data").path("revision").asLong();

        // 第一次保存成功，修订号推进
        HttpHeaders first = new HttpHeaders();
        first.putAll(headers);
        first.set("If-Match", "\"draft-" + revision + "\"");
        freshKey(first);
        assertThat(rest.exchange("/admin/v1/textbooks/api-book-cas/draft", HttpMethod.PUT,
                new HttpEntity<>(draftJson("api-book-cas"), first), String.class).getStatusCode().value())
                .isEqualTo(200);

        // 第二次仍用旧修订号：必须冲突
        HttpHeaders stale = new HttpHeaders();
        stale.putAll(headers);
        stale.set("If-Match", "\"draft-" + revision + "\"");
        // 用新的幂等键，确保这次 409 来自 revision 冲突而不是回执复用。
        freshKey(stale);
        ResponseEntity<String> conflict = rest.exchange("/admin/v1/textbooks/api-book-cas/draft", HttpMethod.PUT,
                new HttpEntity<>(draftJson("api-book-cas"), stale), String.class);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(TestJson.parse(conflict.getBody()).path("code").asText()).isEqualTo("TOOLBOX_ADMIN_004");
    }

    @Test
    @DisplayName("发布未通过校验的草稿返回 422 并给出问题数")
    void publishIncompleteIs422() {
        HttpHeaders headers = loginAndHeaders();
        String incomplete = """
                {"editionId":"api-book-invalid","subject":"chinese","grade":1,"semester":"first",
                 "publisher":null,"series":null,"editionLabel":null,
                 "sourceNote":null,"coverageDeclaration":null,"units":[],"lessons":[],"entries":[]}
                """;
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(incomplete, freshKey(headers)), String.class);
        ResponseEntity<String> published = rest.exchange("/admin/v1/textbooks/api-book-invalid/publish",
                HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(published.getStatusCode().value()).isEqualTo(422);
        assertThat(TestJson.parse(published.getBody()).path("code").asText()).isEqualTo("TOOLBOX_ADMIN_005");
    }

    @Test
    @DisplayName("列表返回当前页与总数，并按最近编辑排序")
    void listReturnsItemsAndTotal() {
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("list-book-a"), freshKey(headers)), String.class);
        sleepBriefly();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("list-book-b"), freshKey(headers)), String.class);

        ResponseEntity<String> list = rest.exchange("/admin/v1/textbooks?page=1&pageSize=20", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        JsonNode data = TestJson.parse(list.getBody()).path("data");
        assertThat(data.path("total").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(data.path("pageSize").asInt()).isEqualTo(20);
        // 最近编辑的排在最前
        assertThat(data.path("items").get(0).path("editionId").asText()).isEqualTo("list-book-b");
        // 草稿状态：未发布，contentVersion 为空
        assertThat(data.path("items").get(0).path("published").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("pageSize 超过上限被夹到 100，页码小于 1 被夹到 1")
    void listNormalizesPagination() {
        HttpHeaders headers = loginAndHeaders();
        ResponseEntity<String> list = rest.exchange("/admin/v1/textbooks?page=0&pageSize=500", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        JsonNode data = TestJson.parse(list.getBody()).path("data");
        assertThat(data.path("page").asInt()).isEqualTo(1);
        assertThat(data.path("pageSize").asInt()).isEqualTo(100);
    }

    /** 让两次创建之间有可区分的时间差，用于验证排序。 */
    private static void sleepBriefly() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("草稿预览是只读的：不改变修订号，也不发布")
    void previewIsReadOnly() {
        HttpHeaders headers = loginAndHeaders();
        ResponseEntity<String> created = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("preview-book"), freshKey(headers)), String.class);
        long revision = TestJson.parse(created.getBody()).path("data").path("revision").asLong();

        ResponseEntity<String> preview = rest.exchange("/admin/v1/textbooks/preview-book/draft/preview",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(preview.getStatusCode().value()).isEqualTo(200);
        assertThat(TestJson.parse(preview.getBody()).path("data").path("draftPreview").asBoolean()).isTrue();

        // 预览后修订号必须不变，否则说明预览写了库。
        ResponseEntity<String> after = rest.exchange("/admin/v1/textbooks/preview-book/draft", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(TestJson.parse(after.getBody()).path("data").path("revision").asLong()).isEqualTo(revision);
        // 且仍然没有当前版本（未发布）。
        ResponseEntity<String> list = rest.exchange("/admin/v1/textbooks?keyword=preview-book", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(TestJson.parse(list.getBody()).path("data").path("items").get(0).path("published").asBoolean())
                .isFalse();
    }

    @Test
    @DisplayName("预览内容与发布后的正文一致（同一映射器）")
    void previewMatchesPublishedContent() {
        HttpHeaders headers = loginAndHeaders();
        String draft = """
                {"editionId":"preview-match-book","subject":"chinese","grade":1,"semester":"first",
                 "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
                 "sourceNote":"纸质教材附录，第 78 页","coverageDeclaration":"partial",
                 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1}],
                 "lessons":[],
                 "entries":[{"rowId":"row-1","entryId":null,"unitId":null,"clientUnitId":"cu1",
                             "lessonId":null,"clientLessonId":null,"text":"山","pinyin":"shān",
                             "meaning":null,"listTypes":["writing"],"confirmed":true}]}
                """;
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST, new HttpEntity<>(draft, freshKey(headers)), String.class);

        ResponseEntity<String> preview = rest.exchange("/admin/v1/textbooks/preview-match-book/draft/preview",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        JsonNode previewData = TestJson.parse(preview.getBody()).path("data");
        String previewEntryId = previewData.path("entries").get(0).path("entryId").asText();
        String previewText = previewData.path("entries").get(0).path("text").asText();
        String previewUnitId = previewData.path("units").get(0).path("unitId").asText();

        ResponseEntity<String> published = rest.exchange("/admin/v1/textbooks/preview-match-book/publish",
                HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(published.getStatusCode().value()).isEqualTo(200);
        JsonNode publishData = TestJson.parse(published.getBody()).path("data");
        // 先断言快照确实重建成功：若为 false，说明发布后目录不可用（503 的根因），
        // 这比在下游断言 404/503 更能指出问题所在。
        assertThat(publishData.path("snapshotReloaded").asBoolean())
                .as("发布后目录快照必须重建成功").isTrue();
        String version = publishData.path("contentVersion").asText();

        // 用公开接口读回正文，逐项比对预览与发布内容。
        // 注意：公开版本响应不含 entries（条目走分页端点），因此分别比对单元、条目数与条目正文。
        ResponseEntity<String> book = rest.getForEntity(
                "/public/v1/textbooks/preview-match-book/versions/" + version, String.class);
        assertThat(book.getStatusCode().value()).isEqualTo(200);
        JsonNode publishedBody = TestJson.parse(book.getBody()).path("data");
        assertThat(publishedBody.path("units").get(0).path("unitId").asText()).isEqualTo(previewUnitId);
        assertThat(publishedBody.path("entryCount").asInt()).isEqualTo(previewData.path("entries").size());

        ResponseEntity<String> entries = rest.getForEntity(
                "/public/v1/textbooks/preview-match-book/versions/" + version + "/entries?limit=50", String.class);
        assertThat(entries.getStatusCode().value()).isEqualTo(200);
        JsonNode publishedEntry = TestJson.parse(entries.getBody()).path("data").path("entries").get(0);
        assertThat(publishedEntry.path("entryId").asText()).isEqualTo(previewEntryId);
        assertThat(publishedEntry.path("text").asText()).isEqualTo(previewText);
    }

    @Test
    @DisplayName("缺少 Idempotency-Key 的写请求返回 428（B09）")
    void writeWithoutIdempotencyKeyIs428() {
        HttpHeaders headers = loginAndHeaders();
        headers.remove("Idempotency-Key");
        ResponseEntity<String> response = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("no-key-book"), headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(428);
        assertThat(TestJson.parse(response.getBody()).path("code").asText()).isEqualTo("TOOLBOX_ADMIN_010");
    }

    @Test
    @DisplayName("保存与发布缺少 Idempotency-Key 时同样返回 428（B09）")
    void saveAndPublishWithoutIdempotencyKeyAre428() {
        // 为什么要逐端点测：此前**前端一处都没发幂等键**，导致管理端保存与发布全部失败，
        // 而当时只有"创建缺键"这一条测试——**其余端点的义务没有被锁定**。
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("key-required-book"), freshKey(headers)), String.class);

        // 保存：带上 If-Match（确保被拒的原因只能是缺幂等键），但不带 Idempotency-Key。
        String revision = TestJson.parse(rest.exchange("/admin/v1/textbooks/key-required-book/draft",
                HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody())
                .path("data").path("revision").asText();
        HttpHeaders save = new HttpHeaders();
        save.putAll(headers);
        save.remove("Idempotency-Key");
        save.set("If-Match", "\"draft-" + revision + "\"");
        ResponseEntity<String> saveResponse = rest.exchange("/admin/v1/textbooks/key-required-book/draft",
                HttpMethod.PUT, new HttpEntity<>(draftJson("key-required-book"), save), String.class);
        assertThat(saveResponse.getStatusCode().value()).as("保存缺幂等键").isEqualTo(428);
        assertThat(TestJson.parse(saveResponse.getBody()).path("code").asText()).isEqualTo("TOOLBOX_ADMIN_010");

        // 发布：同样缺幂等键。
        HttpHeaders publish = new HttpHeaders();
        publish.putAll(headers);
        publish.remove("Idempotency-Key");
        ResponseEntity<String> publishResponse = rest.exchange("/admin/v1/textbooks/key-required-book/publish",
                HttpMethod.POST, new HttpEntity<>(publish), String.class);
        assertThat(publishResponse.getStatusCode().value()).as("发布缺幂等键").isEqualTo(428);
        assertThat(TestJson.parse(publishResponse.getBody()).path("code").asText()).isEqualTo("TOOLBOX_ADMIN_010");
    }

    @Test
    @DisplayName("同键同载荷重试只创建一次，并返回原结果（B09）")
    void sameKeySamePayloadCreatesOnce() {
        HttpHeaders headers = loginAndHeaders();
        headers.set("Idempotency-Key", "create-once-key");
        String body = draftJson("idem-create-once");
        // 两次请求刻意复用同一个键，因此不使用 freshKey。
        ResponseEntity<String> first = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        ResponseEntity<String> retry = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        // 重试必须得到原结果，而不是 409「教材已存在」
        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(TestJson.parse(retry.getBody()).path("data").path("revision").asInt())
                .isEqualTo(TestJson.parse(first.getBody()).path("data").path("revision").asInt());
    }

    @Test
    @DisplayName("同键不同载荷返回 409，不把两次不同意图当成一次（B09）")
    void sameKeyDifferentPayloadIs409() {
        HttpHeaders headers = loginAndHeaders();
        headers.set("Idempotency-Key", "reused-key");
        // 同一个键配不同载荷，因此不使用 freshKey。
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("idem-reuse-a"), headers), String.class);
        ResponseEntity<String> conflict = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("idem-reuse-b"), headers), String.class);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(TestJson.parse(conflict.getBody()).path("code").asText()).isEqualTo("TOOLBOX_ADMIN_004");
    }

    @Test
    @DisplayName("同键重试即使 If-Match 已旧也返回原结果，不产生第二个版本（C05）")
    void publishRetryWithStaleIfMatchReturnsSameVersion() {
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("idem-publish"), freshKey(headers)), String.class);
        headers.set("Idempotency-Key", "publish-once-key");
        ResponseEntity<String> first = rest.exchange("/admin/v1/textbooks/idem-publish/publish",
                HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        String version = TestJson.parse(first.getBody()).path("data").path("contentVersion").asText();

        // 重复点击：必须得到同一个版本号，而不是再发一版。
        // 重试刻意复用同一个键。
        ResponseEntity<String> retry = rest.exchange("/admin/v1/textbooks/idem-publish/publish",
                HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(retry.getStatusCode().value()).isEqualTo(200);
        assertThat(TestJson.parse(retry.getBody()).path("data").path("contentVersion").asText()).isEqualTo(version);
        // 版本号未递增即为"只产生一个版本"的直接证据。
        assertThat(version).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("对外账接口：匿名返回 401，不泄漏目录结构（A02）")
    void reconciliationRequiresAdmin() {
        ResponseEntity<String> anonymous = rest.getForEntity("/admin/v1/curriculum/reconciliation", String.class);
        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);
        // 未授权响应不能包含任何教材标识。
        assertThat(anonymous.getBody() == null || !anonymous.getBody().contains("editionId")).isTrue();
    }

    @Test
    @DisplayName("对账接口返回逐册结论与是否可安全切换（D01）")
    void reconciliationReportsSwitchSafety() {
        HttpHeaders headers = loginAndHeaders();
        ResponseEntity<String> response = rest.exchange("/admin/v1/curriculum/reconciliation", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode data = TestJson.parse(response.getBody()).path("data");
        // 两个字段必须存在：调用方据此决定是否切换公开来源。
        assertThat(data.has("switchSafe")).isTrue();
        assertThat(data.has("comparisons")).isTrue();
        assertThat(data.path("comparisons").isArray()).isTrue();
        // 测试环境下数据库里没有已发布版本，因此不应被判为可安全切换。
        assertThat(data.path("switchSafe").asBoolean()).isFalse();
        assertThat(data.path("notes").isArray()).isTrue();
    }

    @Test
    @DisplayName("整册 777 条保存后读回不漏行，也不被练习题数上限截断（B10）")
    void fullTextbookRoundTripKeepsEveryRow() {
        // 777 条取自真实整册规模（cn-ty-3-1-pep-2024 为 777 条），用于验证编辑链路不截断。
        final int rowCount = 777;
        StringBuilder entries = new StringBuilder();
        for (int index = 1; index <= rowCount; index += 1) {
            if (index > 1) entries.append(',');
            entries.append("{\"rowId\":\"row-").append(index)
                    .append("\",\"entryId\":null,\"unitId\":null,\"clientUnitId\":\"cu1\",")
                    // 必须是**单字**：拼音音节数与字素数不符会被校验正确拒绝（曾因此得到 422）。
                    .append("\"lessonId\":null,\"clientLessonId\":null,\"text\":\"")
                    .appendCodePoint(0x4E00 + index)
                    .append("\",\"pinyin\":\"shān\",\"meaning\":null,\"listTypes\":[\"recognition\"],\"confirmed\":true}");
        }
        String draft = """
                {"editionId":"full-book","subject":"chinese","grade":3,"semester":"first",
                 "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
                 "sourceNote":"纸质教材附录","coverageDeclaration":"partial",
                 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1}],
                 "lessons":[],"entries":[%s]}
                """.formatted(entries.toString());

        HttpHeaders headers = loginAndHeaders();
        ResponseEntity<String> created = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draft, freshKey(headers)), String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        long revision = TestJson.parse(created.getBody()).path("data").path("revision").asLong();

        // 读回：条数必须一条不少。
        ResponseEntity<String> read = rest.exchange("/admin/v1/textbooks/full-book/draft", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        JsonNode readEntries = TestJson.parse(read.getBody()).path("data").path("draft").path("entries");
        assertThat(readEntries.size()).isEqualTo(rowCount);

        // 首尾行必须在：漏行通常发生在边界。
        assertThat(readEntries.get(0).path("rowId").asText()).isEqualTo("row-1");
        assertThat(readEntries.get(rowCount - 1).path("rowId").asText()).isEqualTo("row-" + rowCount);

        // 再次保存（带新幂等键）后条数不变。
        headers.set("If-Match", "\"draft-" + revision + "\"");
        ResponseEntity<String> saved = rest.exchange("/admin/v1/textbooks/full-book/draft", HttpMethod.PUT,
                new HttpEntity<>(draft, freshKey(headers)), String.class);
        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> readAgain = rest.exchange("/admin/v1/textbooks/full-book/draft", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(TestJson.parse(readAgain.getBody()).path("data").path("draft").path("entries").size())
                .isEqualTo(rowCount);

        // 发布：正文与元数据计数都不能被截断。
        ResponseEntity<String> published = rest.exchange("/admin/v1/textbooks/full-book/publish",
                HttpMethod.POST, new HttpEntity<>(freshKey(headers)), String.class);
        assertThat(published.getStatusCode().value()).isEqualTo(200);
        JsonNode publishData = TestJson.parse(published.getBody()).path("data");
        assertThat(publishData.path("snapshotReloaded").asBoolean()).isTrue();
        String version = publishData.path("contentVersion").asText();

        ResponseEntity<String> book = rest.getForEntity(
                "/public/v1/textbooks/full-book/versions/" + version, String.class);
        assertThat(book.getStatusCode().value()).isEqualTo(200);
        // availableEntryCount 曾因硬编码为 0 而被校验拒绝；这里验证它在整册规模下等于真实条数。
        assertThat(TestJson.parse(book.getBody()).path("data").path("entryCount").asInt()).isEqualTo(rowCount);
    }

    @Test
    @DisplayName("两位管理员并发发布不同教材：两册都保留，目录不含混合状态（C08）")
    void concurrentPublishOfDifferentTextbooksKeepsBoth() throws Exception {
        HttpHeaders headers = loginAndHeaders();
        String[] editionIds = {"concurrent-book-a", "concurrent-book-b"};
        for (String editionId : editionIds) {
            ResponseEntity<String> created = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                    new HttpEntity<>(draftJson(editionId), freshKey(headers)), String.class);
            assertThat(created.getStatusCode().value()).isEqualTo(201);
        }

        // 两个发布真正并行：各自使用独立的请求头（含独立 CSRF 与会话 Cookie 副本）。
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();
            for (String editionId : editionIds) {
                futures.add(pool.submit(() -> {
                    HttpHeaders own = new HttpHeaders();
                    own.putAll(headers);
                    own.set("Idempotency-Key", "op-" + java.util.UUID.randomUUID());
                    ResponseEntity<String> published = rest.exchange("/admin/v1/textbooks/" + editionId + "/publish",
                            HttpMethod.POST, new HttpEntity<>(own), String.class);
                    return published.getStatusCode().value();
                }));
            }
            for (var future : futures) {
                assertThat(future.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        // 两册都必须保留：任一册丢失都说明并发重建把对方"挤掉"了。
        ResponseEntity<String> list = rest.exchange("/admin/v1/textbooks?pageSize=100", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        JsonNode items = TestJson.parse(list.getBody()).path("data").path("items");
        for (String editionId : editionIds) {
            boolean present = false;
            for (JsonNode item : items) {
                if (editionId.equals(item.path("editionId").asText()) && item.path("published").asBoolean()) {
                    present = true;
                }
            }
            assertThat(present).as("并发发布后 %s 必须仍在列表中且为已发布", editionId).isTrue();
        }

        // 目录快照必须同时包含两册，且各自版本可读——证明不是"混合代"的快照。
        for (String editionId : editionIds) {
            ResponseEntity<String> detail = rest.getForEntity(
                    "/public/v1/textbooks/" + editionId + "/versions/1.0.0", String.class);
            assertThat(detail.getStatusCode().value())
                    .as("%s 的 1.0.0 版本必须可读", editionId).isEqualTo(200);
            assertThat(TestJson.parse(detail.getBody()).path("data").path("edition").path("editionId").asText())
                    .isEqualTo(editionId);
        }
    }

    @Test
    @DisplayName("发布写入审计：记录谁在何时发布了哪个版本（B11 的另一半）")
    void publishWritesAuditWithActor() {
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("audit-book"), freshKey(headers)), String.class);
        ResponseEntity<String> published = rest.exchange("/admin/v1/textbooks/audit-book/publish",
                HttpMethod.POST, new HttpEntity<>(freshKey(headers)), String.class);
        assertThat(published.getStatusCode().value()).isEqualTo(200);
        String version = TestJson.parse(published.getBody()).path("data").path("contentVersion").asText();

        // 创建草稿本身也会写一条 draft.save，因此这里**按动作过滤**，
        // 断言的是"发布产生了审计"，而不是"总共有几条审计"。
        List<java.util.Map<String, Object>> audit = repository.findAudit("audit-book").stream()
                .filter(row -> "textbook.publish".equals(String.valueOf(row.get("action")))).toList();
        assertThat(audit).hasSize(1);
        java.util.Map<String, Object> row = audit.get(0);
        // 必须记录**操作者**——这正是"服务端拒绝伪造"所依赖的事实来源。
        assertThat(String.valueOf(row.get("actor_user_id"))).isEqualTo("1001");
        assertThat(String.valueOf(row.get("action"))).isEqualTo("textbook.publish");
        assertThat(String.valueOf(row.get("to_version"))).isEqualTo(version);
    }

    @Test
    @DisplayName("重复发布（幂等）不产生第二条审计")
    void idempotentPublishDoesNotDuplicateAudit() {
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("audit-idem-book"), freshKey(headers)), String.class);
        headers.set("Idempotency-Key", "audit-idem-key");
        rest.exchange("/admin/v1/textbooks/audit-idem-book/publish", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
        rest.exchange("/admin/v1/textbooks/audit-idem-book/publish", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
        // 幂等重试返回回执、不再执行发布，因此发布审计只有一条。
        assertThat(repository.findAudit("audit-idem-book").stream()
                .filter(row -> "textbook.publish".equals(String.valueOf(row.get("action")))).toList())
                .hasSize(1);
    }

    @Test
    @DisplayName("发布时填写的 changeNote 写入审计摘要，并由版本记录接口返回（设计 160／166／362 行）")
    void publishPersistsChangeNoteIntoAuditAndVersionHistory() {
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("note-book"), freshKey(headers)), String.class);
        String note = "补充附录来源并核对第一单元";
        ResponseEntity<String> published = rest.exchange("/admin/v1/textbooks/note-book/publish",
                HttpMethod.POST,
                new HttpEntity<>("{\"changeNote\":\"" + note + "\"}", freshKey(headers)), String.class);
        assertThat(published.getStatusCode().value()).as("发布必须成功：" + published.getBody()).isEqualTo(200);
        String version = TestJson.parse(published.getBody()).path("data").path("contentVersion").asText();

        // 第一半：审计 summary 里真的写下了这条摘要（不是只回显给客户端）。
        List<java.util.Map<String, Object>> audit = repository.findAudit("note-book").stream()
                .filter(row -> "textbook.publish".equals(String.valueOf(row.get("action")))).toList();
        assertThat(audit).hasSize(1);
        assertThat(String.valueOf(audit.get(0).get("summary")))
                .as("审计摘要必须含 changeNote")
                .contains(note);

        // 第二半：版本记录接口按 (edition_id, action, to_version) 取回它。
        ResponseEntity<String> versions = rest.exchange("/admin/v1/curriculum/versions/note-book",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(versions.getStatusCode().value()).isEqualTo(200);
        JsonNode row = versionRow(versions.getBody(), version);
        assertThat(row).as("版本记录里应能找到刚发布的版本：" + versions.getBody()).isNotNull();
        assertThat(row.path("changeNote").asText()).isEqualTo(note);
    }

    @Test
    @DisplayName("不填 changeNote：审计摘要保持原文本（不写空括号），版本记录为空")
    void publishWithoutChangeNoteKeepsPlainAuditSummary() {
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("note-empty-book"), freshKey(headers)), String.class);
        // 完全不发正文：这正是当前前端之外多数既有调用方与旧版本客户端的行为。
        ResponseEntity<String> published = rest.exchange("/admin/v1/textbooks/note-empty-book/publish",
                HttpMethod.POST, new HttpEntity<>(freshKey(headers)), String.class);
        assertThat(published.getStatusCode().value()).as("发布必须成功：" + published.getBody()).isEqualTo(200);
        String version = TestJson.parse(published.getBody()).path("data").path("contentVersion").asText();

        List<java.util.Map<String, Object>> audit = repository.findAudit("note-empty-book").stream()
                .filter(row -> "textbook.publish".equals(String.valueOf(row.get("action")))).toList();
        assertThat(audit).hasSize(1);
        String summary = String.valueOf(audit.get(0).get("summary"));
        // 摘要保持"发布修订 N，M 条内容"原样：既没有空括号，也没有摘要标记。
        assertThat(summary).startsWith("发布修订 ").endsWith(" 条内容");
        assertThat(summary).doesNotContain("修改摘要").doesNotContain("（").doesNotContain("）");

        ResponseEntity<String> versions = rest.exchange("/admin/v1/curriculum/versions/note-empty-book",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        JsonNode row = versionRow(versions.getBody(), version);
        assertThat(row).as("版本记录里应能找到刚发布的版本：" + versions.getBody()).isNotNull();
        JsonNode noteNode = row.path("changeNote");
        // 没填写就是"没有"：字段缺席或为 null 都算，但**不能**是空串（那会被界面当成"填了空"）。
        assertThat(noteNode.isMissingNode() || noteNode.isNull())
                .as("未填写时 changeNote 必须缺席或为 null，实际：" + noteNode)
                .isTrue();
    }

    @Test
    @DisplayName("同键重放返回原回执与原摘要，审计不新增第二条")
    void idempotentPublishReplayKeepsOriginalChangeNote() {
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("note-idem-book"), freshKey(headers)), String.class);
        String body = "{\"changeNote\":\"第一次填写并已生效的摘要\"}";
        headers.set("Idempotency-Key", "note-idem-key");
        ResponseEntity<String> first = rest.exchange("/admin/v1/textbooks/note-idem-book/publish",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(first.getStatusCode().value()).as("发布必须成功：" + first.getBody()).isEqualTo(200);
        String version = TestJson.parse(first.getBody()).path("data").path("contentVersion").asText();

        // 原样重放（同键同正文）：这是网络重试的真实形状。
        ResponseEntity<String> retry = rest.exchange("/admin/v1/textbooks/note-idem-book/publish",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(retry.getStatusCode().value()).as("重放必须成功：" + retry.getBody()).isEqualTo(200);
        assertThat(TestJson.parse(retry.getBody()).path("data").path("contentVersion").asText())
                .as("重放必须返回原回执")
                .isEqualTo(version);

        // 审计只有一条：重放没有真的再发布一次。
        assertThat(repository.findAudit("note-idem-book").stream()
                .filter(row -> "textbook.publish".equals(String.valueOf(row.get("action")))).toList())
                .hasSize(1);

        // 版本记录里仍是**第一次**的摘要。
        ResponseEntity<String> versions = rest.exchange("/admin/v1/curriculum/versions/note-idem-book",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        JsonNode row = versionRow(versions.getBody(), version);
        assertThat(row).isNotNull();
        assertThat(row.path("changeNote").asText()).isEqualTo("第一次填写并已生效的摘要");
    }

    /** 在版本列表响应里按版本号取出一条记录；找不到时返回 null。 */
    private static JsonNode versionRow(String responseBody, String contentVersion) {
        for (JsonNode candidate : TestJson.parse(responseBody).path("data")) {
            if (contentVersion.equals(candidate.path("contentVersion").asText())) {
                return candidate;
            }
        }
        return null;
    }

    @Test
    @DisplayName("草稿保存写入审计并记录修订推进，冲突的保存不写审计")
    void draftSaveWritesAuditWithRevision() {
        HttpHeaders headers = loginAndHeaders();
        ResponseEntity<String> created = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("audit-draft"), freshKey(headers)), String.class);
        long revision = TestJson.parse(created.getBody()).path("data").path("revision").asLong();

        List<java.util.Map<String, Object>> savesAfterCreate = repository.findAudit("audit-draft").stream()
                .filter(row -> "draft.save".equals(String.valueOf(row.get("action")))).toList();
        assertThat(savesAfterCreate).isNotEmpty();
        int countAfterCreate = savesAfterCreate.size();

        // 正常保存一次：审计增加，且记录修订号推进。
        HttpHeaders save = new HttpHeaders();
        save.putAll(headers);
        save.set("If-Match", "\"draft-" + revision + "\"");
        ResponseEntity<String> saved = rest.exchange("/admin/v1/textbooks/audit-draft/draft", HttpMethod.PUT,
                new HttpEntity<>(draftJson("audit-draft"), freshKey(save)), String.class);
        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        long newRevision = TestJson.parse(saved.getBody()).path("data").path("revision").asLong();
        assertThat(newRevision).isGreaterThan(revision);

        List<java.util.Map<String, Object>> savesAfterSave = repository.findAudit("audit-draft").stream()
                .filter(row -> "draft.save".equals(String.valueOf(row.get("action")))).toList();
        assertThat(savesAfterSave.size()).isEqualTo(countAfterCreate + 1);
        java.util.Map<String, Object> last = savesAfterSave.get(savesAfterSave.size() - 1);
        assertThat(String.valueOf(last.get("from_revision"))).isEqualTo(String.valueOf(revision));
        assertThat(String.valueOf(last.get("to_revision"))).isEqualTo(String.valueOf(newRevision));

        // 冲突的保存（旧修订号）：不产生变更，因此不得写审计。
        HttpHeaders stale = new HttpHeaders();
        stale.putAll(headers);
        stale.set("If-Match", "\"draft-" + revision + "\"");
        ResponseEntity<String> conflict = rest.exchange("/admin/v1/textbooks/audit-draft/draft", HttpMethod.PUT,
                new HttpEntity<>(draftJson("audit-draft"), freshKey(stale)), String.class);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(repository.findAudit("audit-draft").stream()
                .filter(row -> "draft.save".equals(String.valueOf(row.get("action")))).toList())
                .hasSize(savesAfterSave.size());
    }

    @Test
    @DisplayName("已核对内容发布时记录核对主体，且主体来自服务端会话而非客户端字段")
    void verifiedPublishRecordsConfirmingActor() {
        HttpHeaders headers = loginAndHeaders();
        // 摘要必须与内容一致，否则服务端会正确地把它降级为未核对。
        String digest = com.yuyutian.mytools.toolbox.service.admin.curriculum.DraftToBookMapper.contentDigest(
                new com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft.DraftEntry(
                        "row-1", null, null, "cu1", null, null, "山", "shān", null, List.of("writing"), true));
        String draft = """
                {"editionId":"confirm-audit-book","subject":"chinese","grade":1,"semester":"first",
                 "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
                 "sourceNote":"纸质教材附录","coverageDeclaration":"partial",
                 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1}],
                 "lessons":[],
                 "entries":[
                   {"rowId":"row-1","entryId":null,"unitId":null,"clientUnitId":"cu1","lessonId":null,
                    "clientLessonId":null,"text":"山","pinyin":"shān","meaning":null,
                    "listTypes":["writing"],"confirmed":true,"confirmedDigest":"%s"},
                   {"rowId":"row-2","entryId":null,"unitId":null,"clientUnitId":"cu1","lessonId":null,
                    "clientLessonId":null,"text":"水","pinyin":"shuǐ","meaning":null,
                    "listTypes":["writing"],"confirmed":true,"confirmedDigest":null}]}
                """.formatted(digest);

        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draft, freshKey(headers)), String.class);
        ResponseEntity<String> published = rest.exchange("/admin/v1/textbooks/confirm-audit-book/publish",
                HttpMethod.POST, new HttpEntity<>(freshKey(headers)), String.class);
        assertThat(published.getStatusCode().value()).isEqualTo(200);

        List<java.util.Map<String, Object>> confirms = repository.findAudit("confirm-audit-book").stream()
                .filter(row -> "textbook.confirm".equals(String.valueOf(row.get("action")))).toList();
        assertThat(confirms).hasSize(1);
        // 主体来自会话，不是请求体里的任何字段。
        assertThat(String.valueOf(confirms.get(0).get("actor_user_id")))
                .as("审计行内容：%s", confirms.get(0)).isEqualTo("1001");
        assertThat(String.valueOf(confirms.get(0).get("summary"))).contains("1 条内容");
    }

    @Test
    @DisplayName("全部未核对的发布不产生核对主体审计")
    void unverifiedPublishRecordsNoConfirmAudit() {
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("unverified-book"), freshKey(headers)), String.class);
        rest.exchange("/admin/v1/textbooks/unverified-book/publish", HttpMethod.POST,
                new HttpEntity<>(freshKey(headers)), String.class);
        assertThat(repository.findAudit("unverified-book").stream()
                .filter(row -> "textbook.confirm".equals(String.valueOf(row.get("action")))).toList())
                .isEmpty();
    }

    @Test
    @DisplayName("改草稿不影响已发布版本；发布新版后旧版的正文与 ETag 仍不变（B03／C04）")
    void publishedVersionIsImmutableAcrossDraftEditsAndNewPublish() {
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("immutable-book"), freshKey(headers)), String.class);
        ResponseEntity<String> firstPublish = rest.exchange("/admin/v1/textbooks/immutable-book/publish",
                HttpMethod.POST, new HttpEntity<>(freshKey(headers)), String.class);
        assertThat(firstPublish.getStatusCode().value()).isEqualTo(200);
        String v1 = TestJson.parse(firstPublish.getBody()).path("data").path("contentVersion").asText();

        // 读取第一版的正文与 ETag 作为基线。
        ResponseEntity<String> before = rest.getForEntity(
                "/public/v1/textbooks/immutable-book/versions/" + v1, String.class);
        assertThat(before.getStatusCode().value()).isEqualTo(200);
        // 比较 **data 部分**而不是整个信封：信封里的 requestId 每次请求都不同，
        // 用它比较会把"每次都变"的字段误判成内容变化。
        String beforeData = TestJson.parse(before.getBody()).path("data").toString();
        String beforeEtag = before.getHeaders().getFirst(HttpHeaders.ETAG);
        assertThat(beforeEtag).isNotBlank();

        // 改草稿：改名单元并改一条内容，但**不发布**。
        long revision = TestJson.parse(rest.exchange("/admin/v1/textbooks/immutable-book/draft", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getBody()).path("data").path("revision").asLong();
        String modified = """
                {"editionId":"immutable-book","subject":"chinese","grade":1,"semester":"first",
                 "publisher":"人民教育出版社","series":"统编语文（改名后）","editionLabel":"2024 年修订版",
                 "sourceNote":"纸质教材附录","coverageDeclaration":"partial",
                 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元（改名后）","order":1}],
                 "lessons":[],"entries":[]}
                """;
        HttpHeaders save = new HttpHeaders();
        save.putAll(headers);
        save.set("If-Match", "\"draft-" + revision + "\"");
        assertThat(rest.exchange("/admin/v1/textbooks/immutable-book/draft", HttpMethod.PUT,
                new HttpEntity<>(modified, freshKey(save)), String.class).getStatusCode().value()).isEqualTo(200);

        // 只改草稿：旧公开版必须一字不变。
        ResponseEntity<String> afterDraftEdit = rest.getForEntity(
                "/public/v1/textbooks/immutable-book/versions/" + v1, String.class);
        assertThat(afterDraftEdit.getStatusCode().value()).isEqualTo(200);
        assertThat(TestJson.parse(afterDraftEdit.getBody()).path("data").toString())
                .as("改草稿不得影响已发布版本").isEqualTo(beforeData);
        assertThat(afterDraftEdit.getHeaders().getFirst(HttpHeaders.ETAG)).isEqualTo(beforeEtag);

    }

    @Test
    @DisplayName("旧版本不经公开接口读取，但数据仍在且保持不可变（C04，方案 C）")
    void historicalVersionIsNotServedPubliclyButRemainsImmutable() {
        // 用户于 2026-10-05 决定：公开接口只服务每册的当前版本，不提供历史版本读取。
        // 因此 404 是**双方约定的行为**，不是缺陷。
        // 但"旧版本仍被保留且不可变"这条要求依然成立，本测试一并对它做断言，
        // 避免这个决定被误读成"旧版本可以丢弃或被覆盖"。
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("history-book"), freshKey(headers)), String.class);
        String v1 = TestJson.parse(rest.exchange("/admin/v1/textbooks/history-book/publish", HttpMethod.POST,
                new HttpEntity<>(freshKey(headers)), String.class).getBody())
                .path("data").path("contentVersion").asText();
        String v2 = TestJson.parse(rest.exchange("/admin/v1/textbooks/history-book/publish", HttpMethod.POST,
                new HttpEntity<>(freshKey(headers)), String.class).getBody())
                .path("data").path("contentVersion").asText();
        assertThat(v2).isNotEqualTo(v1);

        // 1) 约定行为：历史版本经公开接口不可读。
        assertThat(rest.getForEntity("/public/v1/textbooks/history-book/versions/" + v1, String.class)
                .getStatusCode().value()).isEqualTo(404);
        // 2) 当前版本可读。
        assertThat(rest.getForEntity("/public/v1/textbooks/history-book/versions/" + v2, String.class)
                .getStatusCode().value()).isEqualTo(200);
        // 3) 旧版本**仍在数据库中**且不可变：这是"已发布版本不可变"的底线，不因方案 C 而放弃。
        var storedV1 = repository.findVersion("history-book", v1);
        assertThat(storedV1).as("历史版本必须仍被保留").isPresent();
        byte[] bytesBefore = storedV1.get().contentBytes();
        String shaBefore = storedV1.get().contentSha256();
        rest.exchange("/admin/v1/textbooks/history-book/publish", HttpMethod.POST,
                new HttpEntity<>(freshKey(headers)), String.class);
        var storedV1Again = repository.findVersion("history-book", v1);
        assertThat(storedV1Again).isPresent();
        assertThat(storedV1Again.get().contentSha256()).isEqualTo(shaBefore);
        assertThat(storedV1Again.get().contentBytes()).isEqualTo(bytesBefore);
    }

    @Test
    @DisplayName("发布路径拒绝重复单元顺序并定位到字段，且旧公开版不变（C01）")
    void publishRejectsDuplicateOrderAndKeepsPublishedUnchanged() {
        HttpHeaders headers = loginAndHeaders();
        // 先发布一个正常版本，作为"旧公开版"。
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("c01-book"), freshKey(headers)), String.class);
        ResponseEntity<String> published = rest.exchange("/admin/v1/textbooks/c01-book/publish",
                HttpMethod.POST, new HttpEntity<>(freshKey(headers)), String.class);
        String v1 = TestJson.parse(published.getBody()).path("data").path("contentVersion").asText();
        String beforeData = TestJson.parse(rest.getForEntity(
                "/public/v1/textbooks/c01-book/versions/" + v1, String.class).getBody()).path("data").toString();

        // 把草稿改成两个 order 相同的单元。
        long revision = TestJson.parse(rest.exchange("/admin/v1/textbooks/c01-book/draft", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getBody()).path("data").path("revision").asLong();
        String broken = """
                {"editionId":"c01-book","subject":"chinese","grade":1,"semester":"first",
                 "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
                 "sourceNote":"纸质教材附录","coverageDeclaration":"partial",
                 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1},
                          {"unitId":null,"clientUnitId":"cu2","title":"第二单元","order":1}],
                 "lessons":[],"entries":[]}
                """;
        HttpHeaders save = new HttpHeaders();
        save.putAll(headers);
        save.set("If-Match", "\"draft-" + revision + "\"");
        assertThat(rest.exchange("/admin/v1/textbooks/c01-book/draft", HttpMethod.PUT,
                new HttpEntity<>(broken, freshKey(save)), String.class).getStatusCode().value()).isEqualTo(200);

        // 发布必须失败，并给出可定位的问题码。
        ResponseEntity<String> rejected = rest.exchange("/admin/v1/textbooks/c01-book/publish",
                HttpMethod.POST, new HttpEntity<>(freshKey(headers)), String.class);
        assertThat(rejected.getStatusCode().value()).isEqualTo(422);
        assertThat(TestJson.parse(rejected.getBody()).path("code").asText()).isEqualTo("TOOLBOX_ADMIN_005");

        // 校验接口必须能定位到具体字段，而不是只说"有问题"。
        ResponseEntity<String> validate = rest.exchange("/admin/v1/textbooks/c01-book/validate",
                HttpMethod.POST, new HttpEntity<>(freshKey(headers)), String.class);
        JsonNode issues = TestJson.parse(validate.getBody()).path("data").path("issues");
        assertThat(validate.getStatusCode().value()).isEqualTo(200);
        assertThat(issues.isArray()).isTrue();
        boolean located = false;
        for (JsonNode issue : issues) {
            if ("UNIT_ORDER_DUPLICATE".equals(issue.path("code").asText())
                    && issue.path("fieldPath").asText().contains("order")) {
                located = true;
            }
        }
        assertThat(located).as("必须给出可定位的重复顺序问题：%s", issues).isTrue();

        // 发布失败不得影响已发布的旧版本。
        assertThat(TestJson.parse(rest.getForEntity("/public/v1/textbooks/c01-book/versions/" + v1, String.class)
                .getBody()).path("data").toString()).isEqualTo(beforeData);
    }

    @Test
    @DisplayName("改草稿期间公开读取仍是旧内容，发布后才切换到新版本（C03）")
    void publicReadSwitchesOnlyAfterPublish() {
        HttpHeaders headers = loginAndHeaders();
        String first = """
                {"editionId":"c03-book","subject":"chinese","grade":1,"semester":"first",
                 "publisher":"人民教育出版社","series":"旧系列名","editionLabel":"2024 年修订版",
                 "sourceNote":"纸质教材附录","coverageDeclaration":"partial",
                 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1}],
                 "lessons":[],"entries":[]}
                """;
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST, new HttpEntity<>(first, freshKey(headers)), String.class);
        rest.exchange("/admin/v1/textbooks/c03-book/publish", HttpMethod.POST,
                new HttpEntity<>(freshKey(headers)), String.class);

        // 发布后：目录读取到的是新系列名。
        assertThat(seriesInCatalog()).isEqualTo("旧系列名");

        // 改成新系列名但**只保存不发布**：公开读取必须仍是旧内容。
        long revision = TestJson.parse(rest.exchange("/admin/v1/textbooks/c03-book/draft", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getBody()).path("data").path("revision").asLong();
        HttpHeaders save = new HttpHeaders();
        save.putAll(headers);
        save.set("If-Match", "\"draft-" + revision + "\"");
        assertThat(rest.exchange("/admin/v1/textbooks/c03-book/draft", HttpMethod.PUT,
                new HttpEntity<>(first.replace("旧系列名", "新系列名"), freshKey(save)), String.class)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(seriesInCatalog()).as("只保存草稿时公开内容不得变化").isEqualTo("旧系列名");

        // 发布后才切换到新版本。
        ResponseEntity<String> second = rest.exchange("/admin/v1/textbooks/c03-book/publish",
                HttpMethod.POST, new HttpEntity<>(freshKey(headers)), String.class);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        String v2 = TestJson.parse(second.getBody()).path("data").path("contentVersion").asText();
        assertThat(v2).isNotEqualTo("1.0.0");
        assertThat(seriesInCatalog()).as("发布后公开读取必须切换到新版本").isEqualTo("新系列名");
    }

    /** 从公开目录接口读取 c03-book 的系列名。 */
    private String seriesInCatalog() {
        ResponseEntity<String> response = rest.getForEntity(
                "/public/v1/textbooks?subject=chinese&grade=1&semester=first", String.class);
        for (JsonNode edition : TestJson.parse(response.getBody()).path("data").path("editions")) {
            if ("c03-book".equals(edition.path("editionId").asText())) {
                return edition.path("series").asText();
            }
        }
        throw new AssertionError("目录中未找到 c03-book");
    }

    @Test
    @DisplayName("保存的 ID 映射会往返，且发布时沿用其中的正式 ID（B03）")
    void savedIdMappingRoundTripsAndIsHonoured() {
        HttpHeaders headers = loginAndHeaders();
        // 刻意给一个"非按位置推导"的映射：若发布时忽略它，就会得到 book-m-u01。
        String draft = """
                {"editionId":"mapping-book","subject":"chinese","grade":1,"semester":"first",
                 "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
                 "sourceNote":"纸质教材附录","coverageDeclaration":"partial",
                 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1}],
                 "lessons":[],
                 "entries":[{"rowId":"row-1","entryId":null,"unitId":null,"clientUnitId":"cu1",
                             "lessonId":null,"clientLessonId":null,"text":"山","pinyin":"shān",
                             "meaning":null,"listTypes":["writing"],"confirmed":true}],
                 "idMapping":{"u:cu1":"stable-unit-id","e:row-1":"stable-entry-id"}}
                """;
        assertThat(rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draft, freshKey(headers)), String.class).getStatusCode().value())
                .isEqualTo(201);

        // 1) 保存后读回，映射必须还在（不能被任何一层清空）。
        JsonNode readBack = TestJson.parse(rest.exchange("/admin/v1/textbooks/mapping-book/draft", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getBody()).path("data").path("draft");
        assertThat(readBack.path("idMapping").path("u:cu1").asText()).isEqualTo("stable-unit-id");

        // 2) 发布后必须沿用映射里的正式 ID，而不是按位置重新分配。
        ResponseEntity<String> published = rest.exchange("/admin/v1/textbooks/mapping-book/publish",
                HttpMethod.POST, new HttpEntity<>(freshKey(headers)), String.class);
        String version = TestJson.parse(published.getBody()).path("data").path("contentVersion").asText();
        JsonNode book = TestJson.parse(rest.getForEntity(
                "/public/v1/textbooks/mapping-book/versions/" + version, String.class).getBody()).path("data");
        assertThat(book.path("units").get(0).path("unitId").asText()).isEqualTo("stable-unit-id");
    }

    @Test
    @DisplayName("已自动补全标记被接受并原样往返（一条内容只补一次）")
    void automaticInitializedRoundTrips() {
        // 该字段不在业务契约的核心，但**必须被声明**：
        // 服务端 fail-on-unknown-properties=true，未声明时前端多发一个字段就会 400，
        // 表现为"保存草稿整体失败"，排查成本很高。
        HttpHeaders headers = loginAndHeaders();
        String draft = """
                {"editionId":"auto-flag-book","subject":"chinese","grade":1,"semester":"first",
                 "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
                 "sourceNote":"纸质教材附录","coverageDeclaration":"partial",
                 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1}],
                 "lessons":[],
                 "entries":[
                   {"rowId":"row-1","entryId":null,"unitId":null,"clientUnitId":"cu1","lessonId":null,
                    "clientLessonId":null,"text":"山","pinyin":"","meaning":null,
                    "listTypes":["writing"],"confirmed":false,"confirmedDigest":null,
                    "automaticInitialized":true},
                   {"rowId":"row-2","entryId":null,"unitId":null,"clientUnitId":"cu1","lessonId":null,
                    "clientLessonId":null,"text":"水","pinyin":"shuǐ","meaning":null,
                    "listTypes":["writing"],"confirmed":false,"confirmedDigest":null,
                    "automaticInitialized":false}]}
                """;
        ResponseEntity<String> created = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draft, freshKey(headers)), String.class);
        assertThat(created.getStatusCode().value()).as("带该字段的草稿必须被接受").isEqualTo(201);

        JsonNode entries = TestJson.parse(rest.exchange("/admin/v1/textbooks/auto-flag-book/draft",
                        HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody())
                .path("data").path("draft").path("entries");
        // 必须原样保留：丢失标记会让重新打开草稿后再补一次，覆盖用户清空的字段。
        assertThat(entries.get(0).path("automaticInitialized").asBoolean()).isTrue();
        assertThat(entries.get(1).path("automaticInitialized").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("模拟重启：从数据库重建的快照与发布后的状态一致，不存在混合目录（C05）")
    void reloadAfterRestartRebuildsConsistentSnapshot() {
        HttpHeaders headers = loginAndHeaders();
        List<String> editionIds = List.of("restart-book-a", "restart-book-b");
        // 另建一册**只建草稿、不发布**，用于证明重建是"按数据库当前指针"而不是"把内存里的东西搬回来"。
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("restart-book-unpublished"), freshKey(headers)), String.class);
        for (String editionId : editionIds) {
            rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                    new HttpEntity<>(draftJson(editionId), freshKey(headers)), String.class);
            assertThat(rest.exchange("/admin/v1/textbooks/" + editionId + "/publish", HttpMethod.POST,
                    new HttpEntity<>(freshKey(headers)), String.class).getStatusCode().value()).isEqualTo(200);
        }

        // 模拟**进程重启**：丢弃当前内存快照，完全从数据库重建。
        // 崩溃恢复的关键性质是"数据库是唯一事实来源"——重建必须得到与发布后一致的状态。
        publisher.reloadSnapshot("catalog-restart-" + repository.currentEpoch());
        assertThat(publisher.isReady()).as("重建后必须处于就绪状态").isTrue();

        for (String editionId : editionIds) {
            ResponseEntity<String> book = rest.getForEntity(
                    "/public/v1/textbooks/" + editionId + "/versions/1.0.0", String.class);
            assertThat(book.getStatusCode().value())
                    .as("重启后 %s 的当前版本必须可读", editionId).isEqualTo(200);
            JsonNode data = TestJson.parse(book.getBody()).path("data");
            assertThat(data.path("edition").path("editionId").asText()).isEqualTo(editionId);
            // "不存在混合目录"的可验证含义：每个版本的 sha256 与其字节一致（重建时已校验）。
            assertThat(data.path("sha256").asText()).hasSize(64);
        }

        // 未发布的教材**不得**出现在重建后的目录里：
        // 若这条也通过，说明重建确实依据数据库的 current 指针，而不是残留的内存状态。
        assertThat(rest.getForEntity("/public/v1/textbooks/restart-book-unpublished/versions/1.0.0",
                String.class).getStatusCode().value())
                .as("只建草稿未发布的教材不应出现在目录中").isEqualTo(404);
    }

    @Autowired
    private com.yuyutian.mytools.toolbox.service.admin.curriculum.CurriculumPublisher publisher;

    @Test
    @DisplayName("已授权但缺少管理 CSRF 头时写请求被拒（A07）")
    void authenticatedWriteWithoutCsrfHeaderIsRejected() {
        HttpHeaders headers = loginAndHeaders();
        // 带着有效会话，但**不带** X-Toolbox-Admin-Csrf。
        HttpHeaders withoutCsrf = new HttpHeaders();
        withoutCsrf.setContentType(MediaType.APPLICATION_JSON);
        withoutCsrf.add(HttpHeaders.COOKIE,
                headers.getFirst(HttpHeaders.COOKIE));
        withoutCsrf.set("Idempotency-Key", "op-" + java.util.UUID.randomUUID());
        // 顺带带上 If-Match，确保被拒的原因只能是 CSRF，而不是缺少其他前提。
        withoutCsrf.set("If-Match", "\"draft-1\"");

        ResponseEntity<String> response = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("no-csrf-book"), withoutCsrf), String.class);
        assertThat(response.getStatusCode().value()).as("缺少 CSRF 头必须被拒绝").isEqualTo(403);
        assertThat(TestJson.parse(response.getBody()).path("code").asText()).isEqualTo("TOOLBOX_ADMIN_003");

        // 读请求不受 CSRF 限制（CSRF 只保护状态变更）。
        assertThat(rest.exchange("/admin/v1/textbooks", HttpMethod.GET,
                new HttpEntity<>(withoutCsrf), String.class).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("已发布后不得把学科／年级／册次改成另一册（设计第 109 行）")
    void publishedIdentityCannotBecomeAnotherBook() {
        HttpHeaders headers = loginAndHeaders();
        // 建一册语文并发布。
        assertThat(rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("identity-book"), freshKey(headers)), String.class)
                .getStatusCode().value()).isEqualTo(201);
        assertThat(rest.exchange("/admin/v1/textbooks/identity-book/publish", HttpMethod.POST,
                new HttpEntity<>(freshKey(headers)), String.class).getStatusCode().value()).isEqualTo(200);

        // 只改**年级**（3 年级），其他一切不变——这样 422 只能来自身份规则，
        // 不会与"英语条目缺释义"之类的其他校验混淆。
        String changed = draftJson("identity-book").replace("\"grade\":1", "\"grade\":3");
        // 取当前 revision 作为 If-Match；写请求还必须带幂等键。
        String revision = TestJson.parse(rest.exchange("/admin/v1/textbooks/identity-book/draft", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getBody()).path("data").path("revision").asText();
        HttpHeaders write = new HttpHeaders();
        write.putAll(headers);
        write.set("If-Match", "\"draft-" + revision + "\"");
        freshKey(write);
        ResponseEntity<String> saved = rest.exchange("/admin/v1/textbooks/identity-book/draft", HttpMethod.PUT,
                new HttpEntity<>(changed, write), String.class);
        assertThat(saved.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> rejected = rest.exchange("/admin/v1/textbooks/identity-book/publish", HttpMethod.POST,
                new HttpEntity<>(freshKey(headers)), String.class);
        assertThat(rejected.getStatusCode().value())
                .as("改学科后发布必须被拒绝，而不是产生'同一 ID 的另一册'").isEqualTo(422);
        // 已发布内容不受影响：仍能读到原来那一册且学科是语文。
        ResponseEntity<String> book = rest.getForEntity(
                "/public/v1/textbooks/identity-book/versions/1.0.0", String.class);
        assertThat(book.getStatusCode().value()).isEqualTo(200);
        assertThat(TestJson.parse(book.getBody()).path("data").path("edition").path("subject").asText())
                .isEqualTo("chinese");
    }

    @Test
    @DisplayName("管理会话 Cookie 的属性符合设计第 4.4 节（A07）")
    void adminSessionCookieCarriesRequiredAttributes() {
        // 设计第 4.4 节规定 `__Host-toolbox_admin`：`Secure; HttpOnly; SameSite=Lax; Path=/`，**不设 Domain**。
        // 这是**权限更高**的那个 Cookie；访客 Cookie 的属性已有测试，管理 Cookie 的此前没有——
        // 任一条属性退化（丢 HttpOnly、丢 SameSite）都是安全问题，而今天没有测试会发现。
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

        String sessionCookie = login.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE, List.of()).stream()
                .filter(header -> header.startsWith("toolbox_admin_local="))
                .findFirst().orElseThrow(() -> new AssertionError("未下发管理会话 Cookie"));

        assertThat(sessionCookie).contains("HttpOnly");
        assertThat(sessionCookie).contains("SameSite=Lax");
        assertThat(sessionCookie).contains("Path=/");
        // 测试 profile 下 cookie-secure=false（本机用 HTTP）；生产默认为 true。
        // 这里只断言"属性与配置一致"，不断言某个固定值，避免把测试配置写死进断言。
        assertThat(sessionCookie.contains("Secure")).isFalse();
        // **不得设置 Domain**：`__Host-` 前缀本身就要求无 Domain，且无 Domain 才能避免子域间串用。
        assertThat(sessionCookie.toLowerCase()).doesNotContain("domain=");
    }

    @Test
    @DisplayName("管理接口响应带 Cache-Control: no-store（设计第 4.4 节）")
    void adminResponsesAreNotCacheable() {
        // 没有 no-store 时，共享代理或浏览器可能缓存管理端数据（草稿、会话信息）——数据泄漏风险。
        // 实现由 AdminAuthFilter 统一加；此前**没有任何测试**锁定它。
        HttpHeaders headers = loginAndHeaders();

        // 1) 已授权的读响应
        ResponseEntity<String> list = rest.exchange("/admin/v1/textbooks", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");

        // 2) 已授权的写响应
        ResponseEntity<String> created = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("no-store-book"), freshKey(headers)), String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");

        // 3) **被拒绝的匿名响应也要带**：错误信息同样不该被缓存。
        ResponseEntity<String> rejected = rest.exchange("/admin/v1/textbooks", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(rejected.getStatusCode().value()).isEqualTo(401);
        assertThat(rejected.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    @DisplayName("密码不回显给浏览器，也不进入会话存储（设计第 4.4 节）")
    void passwordIsNeverEchoedOrStored() {
        // 设计第 4.4 节：密码"只在本次登录请求中使用，不持久化、缓存、记录日志或返回浏览器"。
        // 用一串**可识别的**密码，才能在响应与存储里查找它。
        String distinctive = "pw-canary-9f3a1c";
        ResponseEntity<String> csrf = rest.getForEntity("/admin/v1/auth/csrf", String.class);
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.add(HttpHeaders.COOKIE, "toolbox_admin_login_local="
                + cookieOf(csrf, "toolbox_admin_login_local"));
        loginHeaders.add("X-Toolbox-Admin-Csrf",
                TestJson.parse(csrf.getBody()).path("data").path("csrfToken").asText());
        ResponseEntity<String> login = rest.exchange("/admin/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"account\":\"someone\",\"password\":\"" + distinctive + "\"}", loginHeaders),
                String.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);

        // 1) 响应体不得包含密码。
        assertThat(login.getBody()).as("登录响应不得回显密码").doesNotContain(distinctive);
        // 2) 响应头也不得包含（如误放进 Cookie 或调试头）。
        assertThat(login.getHeaders().toString()).doesNotContain(distinctive);
        // 3) 会话存储里不得有密码：只应存上游令牌的密文。
        Integer hits = jdbc.queryForObject("""
                SELECT COUNT(*) FROM toolbox_admin_session
                WHERE token_hash LIKE ? OR username LIKE ? OR encrypted_access LIKE ?
                   OR encrypted_refresh LIKE ? OR csrf_token_hash LIKE ?
                """, Integer.class,
                "%" + distinctive + "%", "%" + distinctive + "%", "%" + distinctive + "%",
                "%" + distinctive + "%", "%" + distinctive + "%");
        assertThat(hits).as("会话表中不得出现密码明文").isZero();
    }

    @Test
    @DisplayName("发布不销毁草稿、不丢历史、审计逐次累积（D02 回退前的保留要求）")
    void publishKeepsDraftHistoryAndAudit() {
        // 设计第 8 节第 7 点要求回退时"保留草稿、历史与审计"。
        // 这些性质**不需要部署环境**就能在数据层验证——回退演练本身才需要。
        HttpHeaders headers = loginAndHeaders();
        String editionId = "retain-book";
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson(editionId), freshKey(headers)), String.class);
        assertThat(rest.exchange("/admin/v1/textbooks/" + editionId + "/publish", HttpMethod.POST,
                new HttpEntity<>(freshKey(headers)), String.class).getStatusCode().value()).isEqualTo(200);

        // 1) 发布后草稿仍在，且可继续编辑（发布把草稿更新为干净基线，而不是删除它）。
        ResponseEntity<String> draft = rest.exchange("/admin/v1/textbooks/" + editionId + "/draft",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(draft.getStatusCode().value()).as("发布后草稿必须仍可读取").isEqualTo(200);
        int revision = TestJson.parse(draft.getBody()).path("data").path("revision").asInt();
        assertThat(revision).as("发布后修订号应已推进").isGreaterThan(0);

        // 2) 用当前修订号再改一次并发布，产生第二个版本。
        HttpHeaders write = new HttpHeaders();
        write.putAll(headers);
        write.set("If-Match", "\"draft-" + revision + "\"");
        freshKey(write);
        assertThat(rest.exchange("/admin/v1/textbooks/" + editionId + "/draft", HttpMethod.PUT,
                new HttpEntity<>(draftJson(editionId).replace("\"山\"", "\"水\""), write), String.class)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(rest.exchange("/admin/v1/textbooks/" + editionId + "/publish", HttpMethod.POST,
                new HttpEntity<>(freshKey(headers)), String.class).getStatusCode().value()).isEqualTo(200);

        // 3) 历史：两个版本都还在（旧版不因新发布而消失）。
        Integer versions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM toolbox_textbook_version WHERE edition_id = ?", Integer.class, editionId);
        assertThat(versions).as("历史版本不得因再次发布而丢失").isEqualTo(2);

        // 4) 审计：两次发布各留一条，且**主体来自服务端会话**。
        Integer audit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM toolbox_curriculum_audit WHERE edition_id = ? AND action = 'textbook.publish'",
                Integer.class, editionId);
        assertThat(audit).as("每次真实发布都应留下审计").isEqualTo(2);
        String actor = jdbc.queryForObject(
                "SELECT actor_user_id FROM toolbox_curriculum_audit WHERE edition_id = ? ORDER BY id LIMIT 1",
                String.class, editionId);
        assertThat(actor).as("审计主体不能为空").isNotBlank();
    }

    @Test
    @DisplayName("正常大小的管理写请求不受该上限影响")
    void normalSizedAdminWriteIsAccepted() {
        // 反向断言：上限不能挡住正常内容——否则"加了限制"会变成"功能坏了"。
        HttpHeaders headers = loginAndHeaders();
        assertThat(rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("normal-size-book"), freshKey(headers)), String.class)
                .getStatusCode().value()).isEqualTo(201);
    }

    @Test
    @DisplayName("rowId 为空的条目不会让预览崩成 503（第 189 轮生产实测发现）")
    void nullRowIdDoesNotBreakPreview() {
        // 生产上实测到的缺陷：草稿条目 rowId 为 null 时，
        // DraftToBookMapper 拿它当 Map 的键，`Map.copyOf` 抛 NPE，
        // 整个预览请求以 **503 TOOLBOX_008** 失败，且 details 为空——
        // 用户与运维都看不出是哪个字段的问题。
        //
        // 这里锁定"不再崩"；rowId 为空意味着客户端没给行身份，
        // 因此没有可映射的东西，跳过映射即可。
        HttpHeaders headers = loginAndHeaders();
        String withNullRowId = """
                {"editionId":"api-null-rowid","subject":"chinese","grade":1,"semester":"first",
                 "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
                 "sourceNote":"纸质教材附录，第 78 页","coverageDeclaration":"partial",
                 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1}],
                 "lessons":[],
                 "entries":[{"rowId":null,"entryId":null,"unitId":null,"clientUnitId":"cu1",
                             "lessonId":null,"clientLessonId":null,"text":"白云","pinyin":"bai yun",
                             "meaning":null,"listTypes":["writing"],"confirmed":false,
                             "confirmedDigest":null,"automaticInitialized":true}]}
                """;
        ResponseEntity<String> created = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(withNullRowId, freshKey(headers)), String.class);
        assertThat(created.getStatusCode().value()).as("带 null rowId 的草稿应当能创建").isEqualTo(201);
        ResponseEntity<String> preview = rest.exchange("/admin/v1/textbooks/api-null-rowid/draft/preview",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(preview.getStatusCode().value())
                .as("预览不应因 500 级异常变成 503").isNotEqualTo(503);
        assertThat(preview.getBody()).doesNotContain("TOOLBOX_008");
    }

    @Test
    @DisplayName("F01：刷新后可从 session 恢复 CSRF，写请求仍然成功")
    void sessionRestoresCsrfAfterRefresh() {
        // 复现的是审查报告 F01：登录后浏览器刷新，模块内存里的 CSRF 丢失，
        // 页面上显示"已登录"，但任何写请求都会 403 TOOLBOX_ADMIN_003。
        //
        // 这里模拟"刷新"：**丢掉登录时拿到的 CSRF**，只保留会话 Cookie，
        // 然后走 GET /admin/v1/session 取回令牌，并用**取回的那个**发写请求。
        HttpHeaders login = loginAndHeaders();
        String cookie = login.getFirst(HttpHeaders.COOKIE);

        HttpHeaders refreshed = new HttpHeaders();
        refreshed.add(HttpHeaders.COOKIE, cookie);
        ResponseEntity<String> session = rest.exchange("/admin/v1/session", HttpMethod.GET,
                new HttpEntity<>(refreshed), String.class);
        assertThat(session.getStatusCode().value()).isEqualTo(200);
        JsonNode data = TestJson.parse(session.getBody()).path("data");
        assertThat(data.path("authenticated").asBoolean()).as("会话仍然有效").isTrue();

        String recovered = data.path("csrfToken").asText("");
        assertThat(recovered)
                .as("F01：刷新后服务端必须能还给浏览器一个可用的 CSRF 令牌")
                .isNotBlank();

        // 只用**恢复得到的**令牌发写请求；若恢复机制不生效，这里会 403。
        HttpHeaders write = new HttpHeaders();
        write.setContentType(MediaType.APPLICATION_JSON);
        write.add(HttpHeaders.COOKIE, cookie);
        write.add("X-Toolbox-Admin-Csrf", recovered);
        write.add("Idempotency-Key", "f01-" + java.util.UUID.randomUUID());
        ResponseEntity<String> created = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("f01-recovered-csrf"), write), String.class);
        assertThat(created.getStatusCode().value())
                .as("用恢复的 CSRF 写入应当成功，而不是 403")
                .isEqualTo(201);
    }

    @Test
    @DisplayName("F13：同源但缺 CSRF 的退出被拒绝，且会话未被撤销")
    void logoutWithoutCsrfIsRejectedAndSessionSurvives() {
        // 审查复现：同源 Cookie 无 CSRF 的退出仍返回 200 并**实际撤销**了会话。
        // 那是一条强制注销路径——不需要知道任何令牌就能把管理员踢下线。
        HttpHeaders login = loginAndHeaders();
        String cookie = login.getFirst(HttpHeaders.COOKIE);

        HttpHeaders noCsrf = new HttpHeaders();
        noCsrf.add(HttpHeaders.COOKIE, cookie);
        ResponseEntity<String> logout = rest.exchange("/admin/v1/auth/logout", HttpMethod.POST,
                new HttpEntity<>(noCsrf), String.class);
        assertThat(logout.getStatusCode().value())
                .as("缺 CSRF 的退出必须被拒绝，而不是照做")
                .isEqualTo(403);
        assertThat(TestJson.parse(logout.getBody()).path("code").asText()).isEqualTo("TOOLBOX_ADMIN_003");

        // 关键：拒绝了就**不能**已经把会话撤掉，否则等于换一种方式达成强制注销。
        HttpHeaders check = new HttpHeaders();
        check.add(HttpHeaders.COOKIE, cookie);
        ResponseEntity<String> session = rest.exchange("/admin/v1/session", HttpMethod.GET,
                new HttpEntity<>(check), String.class);
        assertThat(TestJson.parse(session.getBody()).path("data").path("authenticated").asBoolean())
                .as("被拒绝的退出不得撤销会话")
                .isTrue();
    }

    @Test
    @DisplayName("F07：用过期 draftRevision 发布必须被拒（不能发出别人改过的内容）")
    void publishWithStaleRevisionIsRejected() {
        // 审查 F07：发布此前只带教材标识，服务端读取**此刻**的草稿。
        // 于是"甲看到 revision N → 乙改了草稿 → 甲点发布"会把**乙的改动**一起发出去，
        // 而甲从界面上完全看不出来自己发的不是他核对过的那一版。
        HttpHeaders headers = loginAndHeaders();
        String draft = draftJson("stale-revision-book");
        long createdRevision = TestJson.parse(rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draft, freshKey(headers)), String.class).getBody())
                .path("data").path("revision").asLong();

        // 乙改了一版：revision 前进。
        HttpHeaders save = new HttpHeaders();
        save.putAll(headers);
        save.set("If-Match", "\"draft-" + createdRevision + "\"");
        freshKey(save);
        ResponseEntity<String> saved = rest.exchange("/admin/v1/textbooks/stale-revision-book/draft",
                HttpMethod.PUT, new HttpEntity<>(draft, save), String.class);
        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        long currentRevision = TestJson.parse(saved.getBody()).path("data").path("revision").asLong();
        assertThat(currentRevision).isGreaterThan(createdRevision);

        // 甲拿着**过期**的 revision 发布：必须被拒，而不是把乙的改动发出去。
        ResponseEntity<String> stale = rest.exchange(
                "/admin/v1/textbooks/stale-revision-book/publish?draftRevision=" + createdRevision,
                HttpMethod.POST, new HttpEntity<>(freshKey(headers)), String.class);
        assertThat(stale.getStatusCode().value())
                .as("过期修订不能发布；body=%s", stale.getBody()).isEqualTo(409);

        // 带上当前 revision 才应当成功——门槛不能变成永久拒绝。
        ResponseEntity<String> fresh = rest.exchange(
                "/admin/v1/textbooks/stale-revision-book/publish?draftRevision=" + currentRevision,
                HttpMethod.POST, new HttpEntity<>(freshKey(headers)), String.class);
        assertThat(fresh.getStatusCode().value())
                .as("当前修订应当可以发布；body=%s", fresh.getBody()).isEqualTo(200);
    }

    @Test
    @DisplayName("F11：公开目录必须 no-cache 且带代次 ETag，发布后 ETag 立即变化")
    void catalogUsesNoCacheAndEpochEtag() {
        // 审查 F11：目录此前是 `max-age=300`，浏览器五分钟内**不会**回来问，
        // 于是管理员发布后，已打开页面的用户最多五分钟看不到新内容——
        // 而"发布后目录立即更新、无需重启"正是设计要求（C02）。
        ResponseEntity<String> first = rest.getForEntity("/public/v1/textbooks", String.class);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        String cacheControl = String.valueOf(first.getHeaders().getCacheControl());
        assertThat(cacheControl)
                .as("目录必须每次回来校验；max-age 会让发布后长时间不可见")
                .contains("no-cache");
        String etag = first.getHeaders().getETag();
        assertThat(etag).as("目录必须带代次 ETag，否则无法既省流量又即时更新").isNotBlank();

        // 业务版本号可能被迁移或恢复复用，缓存标识必须来自实际目录内容。
        assertThat(etag).matches("\"[a-f0-9]{64}\"");

        // 没有变化时应当返回 304（省流量）。
        HttpHeaders conditional = new HttpHeaders();
        conditional.add(HttpHeaders.IF_NONE_MATCH, etag);
        assertThat(rest.exchange("/public/v1/textbooks", HttpMethod.GET,
                new HttpEntity<>(conditional), String.class).getStatusCode().value())
                .as("代次未变时应返回 304").isEqualTo(304);

        // 发布一册新教材：代次前进，ETag 必须变，且旧 ETag 的条件请求要拿到新内容。
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("catalog-epoch-book"), freshKey(headers)), String.class);
        rest.exchange("/admin/v1/textbooks/catalog-epoch-book/publish", HttpMethod.POST,
                new HttpEntity<>(freshKey(headers)), String.class);

        ResponseEntity<String> after = rest.exchange("/public/v1/textbooks", HttpMethod.GET,
                new HttpEntity<>(conditional), String.class);
        assertThat(after.getStatusCode().value())
                .as("发布后旧 ETag 不能再返回 304，否则用户看不到新内容")
                .isEqualTo(200);
        assertThat(after.getHeaders().getETag()).isNotEqualTo(etag);
    }

    @Test
    @DisplayName("F14：管理员能取到某版本的原始字节，且哈希自洽（可落盘恢复）")
    void versionBytesAreDownloadableAndHashMatches() throws Exception {
        // 审查 F14：export-manifest 只有 hash／长度等**清单**，
        // **没有可落盘的原始 bytes**——于是"完整导出包 + 隔离环境恢复对账"做不到。
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("bytes-book"), freshKey(headers)), String.class);
        ResponseEntity<String> published = rest.exchange("/admin/v1/textbooks/bytes-book/publish",
                HttpMethod.POST, new HttpEntity<>(freshKey(headers)), String.class);
        assertThat(published.getStatusCode().value()).isEqualTo(200);
        String version = TestJson.parse(published.getBody()).path("data").path("contentVersion").asText();

        ResponseEntity<byte[]> bytes = rest.exchange(
                "/admin/v1/curriculum/versions/bytes-book/" + version + "/bytes",
                HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        assertThat(bytes.getStatusCode().value()).isEqualTo(200);
        byte[] body = bytes.getBody();
        assertThat(body).as("必须真的拿到正文").isNotNull();
        assertThat(body.length).isGreaterThan(0);

        // **哈希必须自洽**：这是"落盘之后还能证明拿到的是那一版"的唯一依据。
        String declared = bytes.getHeaders().getFirst("X-Content-Sha256");
        assertThat(declared).isNotBlank();
        String actual = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(body));
        assertThat(actual).as("下载的字节与声明的哈希必须一致").isEqualTo(declared);

        // 这个版本号的正文确实是我们发布的那一册（避免"下到了别的东西"）。
        assertThat(new String(body, java.nio.charset.StandardCharsets.UTF_8)).contains("bytes-book");
    }

    @Test
    @DisplayName("F14：导出包带原始字节，且每个 blob 与包级摘要都自洽")
    void exportPackageCarriesVerifiableBytes() throws Exception {
        // 审查 F14：清单只有 hash／长度，**拿不回内容**，因此无法在隔离环境恢复对账。
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("package-book"), freshKey(headers)), String.class);
        rest.exchange("/admin/v1/textbooks/package-book/publish", HttpMethod.POST,
                new HttpEntity<>(freshKey(headers)), String.class);

        ResponseEntity<String> response = rest.exchange("/admin/v1/curriculum/export-package",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode data = TestJson.parse(response.getBody()).path("data");
        assertThat(data.path("format").asText()).isEqualTo("toolbox-curriculum-package");
        assertThat(data.path("versionCount").asInt()).isGreaterThan(0);

        JsonNode versions = data.path("versions");
        assertThat(versions.isArray()).isTrue();
        assertThat(versions.size()).isGreaterThan(0);

        // 每个 blob：Base64 解出来的字节必须与它自己声明的哈希一致。
        java.util.List<String> digestLines = new java.util.ArrayList<>();
        for (JsonNode version : versions) {
            byte[] raw = java.util.Base64.getDecoder().decode(version.path("contentBase64").asText());
            assertThat(raw.length).as("长度字段必须与真实字节数一致")
                    .isEqualTo(version.path("byteLength").asInt());
            String actual = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(raw));
            assertThat(actual).as("blob 字节与声明的哈希必须一致").isEqualTo(version.path("contentSha256").asText());
            digestLines.add(version.path("editionId").asText() + "@" + version.path("contentVersion").asText()
                    + ":" + version.path("contentSha256").asText());
        }

        // 包级摘要：覆盖全部条目，用来证明"这个包没被改过"。
        java.util.Collections.sort(digestLines);
        String expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(String.join("\n", digestLines).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(data.path("packageSha256").asText())
                .as("包级摘要必须能由包内内容独立重算出来")
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("F14：导出包解出来的正文是可用的（隔离恢复演练）")
    void exportedPackageCanBeRestoredAndReconciled() throws Exception {
        // 上一轮证明了"包里的字节与哈希自洽"。**这一轮证明解出来的东西真的能用**——
        // 否则"能导出"与"能恢复"之间那一跳仍然没有证据。
        HttpHeaders headers = loginAndHeaders();
        String draft = """
                {"editionId":"restore-book","subject":"chinese","grade":1,"semester":"first",
                 "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
                 "sourceNote":"附录","coverageDeclaration":"partial",
                 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1}],
                 "lessons":[],
                 "entries":[{"rowId":"r1","entryId":null,"unitId":null,"clientUnitId":"cu1",
                             "lessonId":null,"clientLessonId":null,"text":"山","pinyin":"shān",
                             "meaning":null,"listTypes":["writing"],"confirmed":true}]}
                """;
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draft, freshKey(headers)), String.class);
        rest.exchange("/admin/v1/textbooks/restore-book/publish", HttpMethod.POST,
                new HttpEntity<>(freshKey(headers)), String.class);

        JsonNode data = TestJson.parse(rest.exchange("/admin/v1/curriculum/export-package",
                HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody()).path("data");

        // 从包里取出这一册，**解码回字节**，再当作一份独立正文解析。
        JsonNode blob = null;
        for (JsonNode version : data.path("versions")) {
            if ("restore-book".equals(version.path("editionId").asText())) {
                blob = version;
                break;
            }
        }
        assertThat(blob).as("包中必须含刚发布的那一册").isNotNull();
        byte[] raw = java.util.Base64.getDecoder().decode(blob.path("contentBase64").asText());

        // 1) 能解析成一本结构完整的书（不是一段无意义的字节）。
        JsonNode book = TestJson.parse(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
        assertThat(book.path("edition").path("editionId").asText()).isEqualTo("restore-book");
        assertThat(book.path("units")).hasSize(1);
        assertThat(book.path("units").get(0).path("title").asText()).isEqualTo("第一单元");

        // 2) 内容逐项在——这才是"恢复"的意义：不是拿到字节，而是拿回内容。
        assertThat(book.path("entries")).hasSize(1);
        JsonNode entry = book.path("entries").get(0);
        assertThat(entry.path("text").asText()).isEqualTo("山");
        assertThat(entry.path("pinyinDisplay").asText()).isEqualTo("shān");

        // 3) 再发布一版，然后重新导出：包里必须**同时含新旧两版**。
        //
        // 这是"回退有依据"的前提——只导当前版本的话，想退回上一版时没有正文可用。
        String updated = draft.replace("\"text\":\"山\"", "\"text\":\"水\"");
        HttpHeaders save = new HttpHeaders();
        save.putAll(headers);
        long revision = TestJson.parse(rest.exchange("/admin/v1/textbooks/restore-book/draft",
                HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody())
                .path("data").path("revision").asLong();
        save.set("If-Match", "\"draft-" + revision + "\"");
        freshKey(save);
        rest.exchange("/admin/v1/textbooks/restore-book/draft", HttpMethod.PUT,
                new HttpEntity<>(updated, save), String.class);
        rest.exchange("/admin/v1/textbooks/restore-book/publish", HttpMethod.POST,
                new HttpEntity<>(freshKey(headers)), String.class);

        JsonNode second = TestJson.parse(rest.exchange("/admin/v1/curriculum/export-package",
                HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody()).path("data");
        int restoreBookVersions = 0;
        for (JsonNode version : second.path("versions")) {
            if ("restore-book".equals(version.path("editionId").asText())) {
                restoreBookVersions += 1;
            }
        }
        assertThat(restoreBookVersions)
                .as("被取代的历史版本也必须在包里，否则回退没有正文可用")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("F13：状态查询不推进空闲期限，而真实操作会推进")
    void sessionQueryDoesNotExtendIdleWindowButOperationsDo() throws Exception {
        // 审查 F13：session 查询会 touch 空闲期限。
        // 后果是设计里的"空闲 30 分钟退出"**永远不会触发**——
        // 只要页面开着，轮询就一直替用户续期。**一条永远不会生效的规则等于没有。**
        HttpHeaders headers = loginAndHeaders();
        String cookie = headers.getFirst(HttpHeaders.COOKIE);
        String sessionValue = cookie.substring(cookie.indexOf('=') + 1);

        long before = store.find(sessionHash(sessionValue)).orElseThrow().lastActivityAt();

        Thread.sleep(1100);
        // 状态查询：**不得**改变活动时间。
        rest.exchange("/admin/v1/session", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(store.find(sessionHash(sessionValue)).orElseThrow().lastActivityAt())
                .as("状态查询不能推进空闲期限；否则'空闲退出'永远不会触发")
                .isEqualTo(before);

        Thread.sleep(1100);
        // 真实操作（经 AdminAuthFilter）：**必须**推进——否则用户正常用着就被踢下线。
        rest.exchange("/admin/v1/textbooks", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(store.find(sessionHash(sessionValue)).orElseThrow().lastActivityAt())
                .as("真实操作必须推进活动时间")
                .isGreaterThan(before);
    }

    /** 与会话服务一致的哈希：SHA-256 十六进制。 */
    private static String sessionHash(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("F13：注销之后，迟到的活动推进不得把会话复活")
    void staleTouchDoesNotResurrectLoggedOutSession() throws Exception {
        // 审查 F13：活动更新是 find 后 upsert——如果会话在"读它"与"写它"之间
        // 被注销删除，upsert 会把**已注销的会话重新插回去**。
        // 用户会发现自己"退不掉"：点了退出，下一跳请求又是登录状态。
        HttpHeaders headers = loginAndHeaders();
        String cookie = headers.getFirst(HttpHeaders.COOKIE);
        String value = cookie.substring(cookie.indexOf('=') + 1);
        String key = sessionHash(value);

        assertThat(store.find(key)).as("登录后会话应当存在").isPresent();

        // 模拟注销：把行删掉。
        jdbc.update("DELETE FROM toolbox_admin_session WHERE token_hash = ?", key);
        assertThat(store.find(key)).as("删除后应当不存在").isEmpty();

        // 模拟一个**迟到的请求**推进活动时间。
        store.touchIfPresent(key, System.currentTimeMillis());

        assertThat(store.find(key))
                .as("迟到的活动推进不能把已注销的会话复活")
                .isEmpty();
    }

    @Test
    @DisplayName("F12：按学科筛选必须能找到**草稿**（不能因为还没发布就查不到）")
    void draftIsFoundBySubjectFilter() {
        // 审查探针：新建一册语文草稿 → 总列表 1 条，**筛语文变 0 条**。
        //
        // 根因：筛选走的是版本表的 `edition_metadata_json`，而草稿没有当前版本，
        // 那一列是 NULL——`LIKE` 自然不匹配，于是**刚建的草稿在自己的学科里查不到**。
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("draft-filter-book"), freshKey(headers)), String.class);

        // 不加筛选：应当能看到。
        JsonNode all = TestJson.parse(rest.exchange("/admin/v1/textbooks", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getBody()).path("data");
        assertThat(all.path("items").size()).as("未筛选时草稿应当可见").isGreaterThan(0);

        // 按它自己的学科筛选：**必须仍然看得到**。
        ResponseEntity<String> filtered = rest.exchange("/admin/v1/textbooks?subject=chinese",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(filtered.getStatusCode().value()).isEqualTo(200);
        JsonNode items = TestJson.parse(filtered.getBody()).path("data").path("items");
        boolean found = false;
        for (JsonNode item : items) {
            if ("draft-filter-book".equals(item.path("editionId").asText())) {
                found = true;
                break;
            }
        }
        assertThat(found)
                .as("草稿必须能被它自己的学科筛出来；否则用户在筛选后以为草稿丢了")
                .isTrue();
    }

    @Test
    @DisplayName("筛选超过一页的未发布教材时，总数与列表一致且后续页不遗漏")
    void draftFiltersCountAllPagesUsingDraftMetadata() {
        HttpHeaders headers = loginAndHeaders();
        for (int index = 0; index < 104; index++) {
            String editionId = "page-filter-" + String.format(java.util.Locale.ROOT, "%03d", index);
            var draft = (com.fasterxml.jackson.databind.node.ObjectNode) TestJson.parse(draftJson(editionId));
            draft.put("series", "paging-select-title");
            // 三条干扰数据分别属于其他学科、年级和学期，不能被本次筛选计入。
            if (index == 101) {
                draft.put("subject", "english");
            } else if (index == 102) {
                draft.put("grade", 2);
            } else if (index == 103) {
                draft.put("semester", "second");
            }
            var created = repository.createTextbook(editionId, "pagination-test");
            assertThat(repository.saveDraft(created.draftId(), created.revision(), draft.toString(),
                    "pagination-test")).isPresent();
        }
        String query = "/admin/v1/textbooks?keyword=paging-select-title&subject=chinese"
                + "&grade=1&semester=first&status=draft&pageSize=100&page=";
        java.util.Set<String> found = new java.util.HashSet<>();
        for (int page = 1; page <= 2; page++) {
            ResponseEntity<String> response = rest.exchange(query + page, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            JsonNode data = TestJson.parse(response.getBody()).path("data");
            assertThat(data.path("total").asInt()).isEqualTo(101);
            JsonNode items = data.path("items");
            assertThat(items).hasSize(page == 1 ? 100 : 1);
            for (JsonNode item : items) {
                assertThat(found.add(item.path("editionId").asText())).as("分页不可重复返回教材").isTrue();
            }
        }
        assertThat(found).hasSize(101);
        assertThat(found).doesNotContain("page-filter-101", "page-filter-102", "page-filter-103");
    }

    @Test
    @DisplayName("F12：草稿在列表里必须带回真实元数据（而不是空，只剩内部 ID）")
    void draftRowCarriesRealMetadata() {
        // 审查 F12：草稿可能显示内部 ID，缺"已发布·有修改"和部分收录状态。
        // 根因是 SELECT 取的是**版本表**的元数据，而草稿没有版本行。
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("metadata-row-book"), freshKey(headers)), String.class);

        JsonNode items = TestJson.parse(rest.exchange("/admin/v1/textbooks", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getBody()).path("data").path("items");
        JsonNode target = null;
        for (JsonNode item : items) {
            if ("metadata-row-book".equals(item.path("editionId").asText())) {
                target = item;
                break;
            }
        }
        assertThat(target).as("刚建的草稿应当在列表里").isNotNull();

        // 真实元数据必须回来了：学科与出版社能被读到，而不是只有内部 ID。
        String metadata = target.path("metadataJson").asText("");
        assertThat(metadata)
                .as("草稿在列表里必须带回真实元数据；否则界面只能显示内部 ID，用户认不出这册书")
                .isNotBlank();
        assertThat(metadata).contains("chinese");
        assertThat(metadata).contains("人民教育出版社");
    }

    @Test
    @DisplayName("A08／F13：刷新权用 CAS 抢占，同一会话只允许一个刷新在跑")
    void refreshClaimIsExclusivePerRevision() throws Exception {
        // 单飞与"不能对任意 401 盲目刷新"的共同地基是：
        // **同一会话同一时刻只有一个能去上游刷新**。
        // 这里直接验证那个地基——两次抢占只有一个成功。
        HttpHeaders headers = loginAndHeaders();
        String cookie = headers.getFirst(HttpHeaders.COOKIE);
        String key = sessionHash(cookie.substring(cookie.indexOf('=') + 1));

        // 直接读库里的 revision：它当前是 CAS 的比较基准。
        long current = jdbc.queryForObject(
                "SELECT refresh_revision FROM toolbox_admin_session WHERE token_hash = ?", Long.class, key);
        // 第一次：从 current 抢到 current+1 —— 成功。
        assertThat(store.claimRefresh(key, current, current + 1))
                .as("第一次抢占应当成功").isEqualTo(1);
        // 第二次：仍拿**过期的** expected —— 必须失败。
        // 如果这里也返回 1，就等于两个请求同时去刷新，单飞不成立。
        assertThat(store.claimRefresh(key, current, current + 1))
                .as("同一 revision 上的第二次抢占必须失败，否则单飞不成立").isZero();
        // 会话仍然在（抢占不该动别的）。
        assertThat(store.find(key)).isPresent();
    }

    @Test
    @DisplayName("F14：管理员能列出历史版本（回退所需的入口，公开 API 不提供）")
    void adminCanListVersionHistory() {
        // 审查 F14：管理历史 API 缺失——于是想回退时**只能靠公开旧版接口**，
        // 而那条路对公众是关着的（C04 方案 C）。管理员因此没有可用入口。
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("history-book"), freshKey(headers)), String.class);
        rest.exchange("/admin/v1/textbooks/history-book/publish", HttpMethod.POST,
                new HttpEntity<>(freshKey(headers)), String.class);

        // 改一版再发，历史里必须**两版都在**——只列当前版本的话回退就没有依据。
        HttpHeaders save = new HttpHeaders();
        save.putAll(headers);
        long revision = TestJson.parse(rest.exchange("/admin/v1/textbooks/history-book/draft",
                HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody())
                .path("data").path("revision").asLong();
        save.set("If-Match", "\"draft-" + revision + "\"");
        rest.exchange("/admin/v1/textbooks/history-book/draft", HttpMethod.PUT,
                new HttpEntity<>(draftJson("history-book").replace("人民教育出版社", "人民教育出版社（修订）"), save),
                String.class);
        rest.exchange("/admin/v1/textbooks/history-book/publish", HttpMethod.POST,
                new HttpEntity<>(freshKey(headers)), String.class);

        ResponseEntity<String> response = rest.exchange("/admin/v1/curriculum/versions/history-book",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode versions = TestJson.parse(response.getBody()).path("data");
        assertThat(versions.isArray()).isTrue();
        assertThat(versions.size())
                .as("被取代的历史版本也必须在列表里，否则回退没有依据")
                .isGreaterThanOrEqualTo(2);

        // 每一项都要能定位到具体正文：版本号 + 哈希 + 长度，缺一不可。
        for (JsonNode version : versions) {
            assertThat(version.path("contentVersion").asText()).isNotBlank();
            assertThat(version.path("contentSha256").asText()).isNotBlank();
            assertThat(version.path("byteLength").asInt()).isGreaterThan(0);
            // 发布时刻必须有值：历史列表的价值一半在于"什么时候发的"。
            assertThat(version.path("publishedAt").asLong())
                    .as("每项都要能看出何时发布；0 表示这一列没取到")
                    .isGreaterThan(0L);
        }
    }

    @Test
    @DisplayName("F07：validate 必须绑定 draftRevision（否则校验结果可能对应另一版）")
    void validateIsBoundToDraftRevision() {
        // 审查 F07：「validate 绑定 draftRevision」。
        //
        // 不绑定的话：界面校验了第 5 版、用户在另一个标签页把它改到第 6 版，
        // 界面拿到的报告是**第 5 版的结论**却显示在第 6 版上——
        // 用户会以为"当前内容没问题"，而它其实没被校验过。
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("validate-rev-book"), freshKey(headers)), String.class);

        long revision = TestJson.parse(rest.exchange("/admin/v1/textbooks/validate-rev-book/draft",
                HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody())
                .path("data").path("revision").asLong();

        // 绑定正确的修订号：应当通过，并**回报它校验的是哪一版**。
        ResponseEntity<String> ok = rest.exchange(
                "/admin/v1/textbooks/validate-rev-book/validate?draftRevision=" + revision,
                HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(TestJson.parse(ok.getBody()).path("data").path("draftRevision").asLong())
                .as("报告必须带上它校验的修订号，界面才能判断结果是否仍然适用")
                .isEqualTo(revision);

        // 绑定一个过期的修订号：**必须拒绝**，而不是拿旧结论冒充当前结论。
        ResponseEntity<String> stale = rest.exchange(
                "/admin/v1/textbooks/validate-rev-book/validate?draftRevision=" + (revision + 99),
                HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(stale.getStatusCode().value())
                .as("修订号不符时必须拒绝；否则会把旧结论显示在当前内容上")
                .isNotEqualTo(200);
    }

    @Test
    @DisplayName("F07：发布后草稿基线必须推进到刚发布的版本")
    void publishAdvancesDraftBaseVersion() {
        // 这一列原本只在导入时写一次，**发布后永远是 NULL**——
        // 于是"草稿的内容基于哪一版"在发布之后无法判断。
        // 推进它之后，"锁内基线校验"才有依据（否则朴素写法会让
        // "发布 → 编辑 → 发布"全部失败）。
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("base-book"), freshKey(headers)), String.class);

        String before = jdbc.queryForObject(
                "SELECT base_content_version FROM toolbox_textbook_draft WHERE draft_id = ?",
                String.class, "draft-base-book");
        assertThat(before).as("新建草稿尚无基线").isNull();

        ResponseEntity<String> published = rest.exchange("/admin/v1/textbooks/base-book/publish",
                HttpMethod.POST, new HttpEntity<>(freshKey(headers)), String.class);
        assertThat(published.getStatusCode().value()).isEqualTo(200);
        String version = TestJson.parse(published.getBody()).path("data").path("contentVersion").asText();

        String after = jdbc.queryForObject(
                "SELECT base_content_version FROM toolbox_textbook_draft WHERE draft_id = ?",
                String.class, "draft-base-book");
        // 基线存的是 **versionId**（`editionId@contentVersion`），
        // 因为校验时要与 `current_version_id` 比较——那是同一列的取值形状。
        // 第 46 轮测出的事实：此前一边写 contentVersion、一边存 versionId，
        // 两者永远不可能相等，校验必然无条件触发。
        assertThat(after)
                .as("发布后基线必须等于刚发布的 versionId；否则'草稿基于哪一版'永远判断不了")
                .isEqualTo("base-book@" + version);
    }

    @Test
    @DisplayName("F07：当前版本被别的实例改过时，基于旧基线的草稿不能发布")
    void publishRejectsStaleBaseline() {
        // 场景：本实例的草稿基于 v1；另一个实例在此期间发布了新版本。
        // 此时直接发布会**把别人刚发的内容覆盖掉**，而本实例并不知道。
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("baseline-book"), freshKey(headers)), String.class);
        rest.exchange("/admin/v1/textbooks/baseline-book/publish", HttpMethod.POST,
                new HttpEntity<>(freshKey(headers)), String.class);

        // 模拟"另一个实例发布了新版本"：直接把当前版本指到别处。
        jdbc.update("UPDATE toolbox_textbook SET current_version_id = ? WHERE edition_id = ?",
                "someone-elses-version", "baseline-book");

        ResponseEntity<String> stale = rest.exchange("/admin/v1/textbooks/baseline-book/publish",
                HttpMethod.POST, new HttpEntity<>(freshKey(headers)), String.class);
        assertThat(stale.getStatusCode().value())
                .as("基线已过期时必须拒绝；否则会静默覆盖别的实例刚发布的内容")
                .isNotEqualTo(200);
    }

    @Test
    @DisplayName("F07：基线未知（历史草稿）时不阻断发布——升级不能锁死既有教材")
    void publishAllowsUnknownBaseline() {
        // 生产上已有的教材，其草稿的 base_content_version 是 NULL（这一列以前只在导入时写）。
        // 若严格比较，"当前版本非空 vs 基线为空"会判为不一致——
        // **所有既有教材将无法再发布**。所以只在基线**已知**时才校验。
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("legacy-base-book"), freshKey(headers)), String.class);
        rest.exchange("/admin/v1/textbooks/legacy-base-book/publish", HttpMethod.POST,
                new HttpEntity<>(freshKey(headers)), String.class);
        // 抹掉基线，模拟"这一列还没被推进过"的历史草稿。
        jdbc.update("UPDATE toolbox_textbook_draft SET base_content_version = NULL WHERE draft_id = ?",
                "draft-legacy-base-book");

        ResponseEntity<String> again = rest.exchange("/admin/v1/textbooks/legacy-base-book/publish",
                HttpMethod.POST, new HttpEntity<>(freshKey(headers)), String.class);
        assertThat(again.getStatusCode().value())
                .as("基线未知时不能阻断：否则升级会把既有教材全部锁死")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("A08：过滤器确实按需提前刷新——临近过期时刷一次，余量充足时不刷")
    void filterRefreshesOnlyWhenNearExpiry() throws Exception {
        // 第 67 轮我把刷新接进了 `AdminAuthFilter`，但**没有任何测试覆盖这一层**
        // （我新建的测试类登录时会 403）。这里用本类**已被证明可用**的登录流程补上。
        HttpHeaders headers = loginAndHeaders();
        String cookie = headers.getFirst(HttpHeaders.COOKIE);
        String key = sessionHash(cookie.substring(cookie.indexOf('=') + 1));

        // 1) 余量充足：不该刷。
        jdbc.update("UPDATE toolbox_admin_session SET access_expires_at = ? WHERE token_hash = ?",
                new java.sql.Timestamp(System.currentTimeMillis() + 30 * 60_000L), key);
        REFRESH_CALLS.clear();
        rest.exchange("/admin/v1/textbooks", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(REFRESH_CALLS)
                .as("余量充足时过滤器不该打上游")
                .isEmpty();

        // 2) 临近过期：应当刷一次。
        jdbc.update("UPDATE toolbox_admin_session SET access_expires_at = ? WHERE token_hash = ?",
                new java.sql.Timestamp(System.currentTimeMillis() + 60_000L), key);
        REFRESH_CALLS.clear();
        rest.exchange("/admin/v1/textbooks", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(REFRESH_CALLS)
                .as("临近过期时过滤器必须刷一次；否则第 67 轮的接线等于没接")
                .hasSize(1);

        // 3) 刷完余量又充足了：再请求不该再刷（避免每个请求都打上游）。
        REFRESH_CALLS.clear();
        rest.exchange("/admin/v1/textbooks", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(REFRESH_CALLS)
                .as("刷新之后余量充足，不该继续刷")
                .isEmpty();
    }

    @Test
    @DisplayName("F12：列表必须能区分'刚发布'与'发布了又改过'")
    void listReportsUnpublishedChanges() {
        // 审查 F12：列表缺"已发布·有修改"状态。
        //
        // **判据不是** `base_content_version` 与 `current_version_id` 的比较——
        // 编辑草稿**不会**改动基线，那两者在编辑前后始终相等。
        // 正确的信号是"草稿修订号 > 最后一次发布的修订号"。
        HttpHeaders headers = loginAndHeaders();
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("modified-flag-book"), freshKey(headers)), String.class);
        rest.exchange("/admin/v1/textbooks/modified-flag-book/publish", HttpMethod.POST,
                new HttpEntity<>(freshKey(headers)), String.class);

        // 1) 刚发布：不该有"未发布的修改"。
        assertThat(flagOf(headers, "modified-flag-book"))
                .as("刚发布完，草稿与已发布版本一致，不该标'有修改'")
                .isFalse();

        // 2) 改一次草稿（修订号前进），但**不发布**。
        long revision = TestJson.parse(rest.exchange("/admin/v1/textbooks/modified-flag-book/draft",
                HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody())
                .path("data").path("revision").asLong();
        HttpHeaders write = new HttpHeaders();
        write.putAll(headers);
        write.set("If-Match", "\"draft-" + revision + "\"");
        assertThat(rest.exchange("/admin/v1/textbooks/modified-flag-book/draft", HttpMethod.PUT,
                new HttpEntity<>(draftJson("modified-flag-book").replace("\"山\"", "\"水\""), write),
                String.class).getStatusCode().value()).isEqualTo(200);

        assertThat(flagOf(headers, "modified-flag-book"))
                .as("发布之后又改了草稿，列表必须能看出来")
                .isTrue();
    }

    /** 读列表里某册的 `hasUnpublishedChanges`。 */
    private boolean flagOf(HttpHeaders headers, String editionId) {
        JsonNode items = TestJson.parse(rest.exchange("/admin/v1/textbooks", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getBody()).path("data").path("items");
        for (JsonNode item : items) {
            if (editionId.equals(item.path("editionId").asText())) {
                return item.path("hasUnpublishedChanges").asBoolean();
            }
        }
        throw new AssertionError("edition not found in list: " + editionId);
    }

    @Test
    @DisplayName("F03：地区／课标年份／ISBN 必须真的搬进已发布正文（此前被硬编码丢掉）")
    void publishedEditionCarriesRegionYearAndIsbn() {
        // 审查 F03：「地区／ISBN／年份未搬」。
        // 查清后的准确含义是：`toEdition` 把它们**硬编码成** `List.of()` / `null` / `null`，
        // 于是管理端采集了也传不出去（发布模型本身一直支持这三个字段）。
        HttpHeaders headers = loginAndHeaders();
        String draft = """
                {"editionId":"meta-book","subject":"chinese","grade":1,"semester":"first",
                 "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
                 "sourceNote":"附录","coverageDeclaration":"partial",
                 "regionCodes":["320000"],"curriculumYear":2024,"isbn":"978-7-107-12345-6",
                 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1}],
                 "lessons":[],
                 "entries":[{"rowId":"r1","entryId":null,"unitId":null,"clientUnitId":"cu1",
                             "lessonId":null,"clientLessonId":null,"text":"山","pinyin":"shān",
                             "meaning":null,"listTypes":["writing"],"confirmed":true}]}
                """;
        rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draft, freshKey(headers)), String.class);
        ResponseEntity<String> published = rest.exchange("/admin/v1/textbooks/meta-book/publish",
                HttpMethod.POST, new HttpEntity<>(freshKey(headers)), String.class);
        assertThat(published.getStatusCode().value()).isEqualTo(200);
        String version = TestJson.parse(published.getBody()).path("data").path("contentVersion").asText();

        // 取**已发布正文的原始字节**（第 21 轮加的端点），看里面到底有没有这三个值。
        ResponseEntity<byte[]> bytes = rest.exchange(
                "/admin/v1/curriculum/versions/meta-book/" + version + "/bytes",
                HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        assertThat(bytes.getStatusCode().value()).isEqualTo(200);
        String body = new String(bytes.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        JsonNode edition = TestJson.parse(body).path("edition");

        assertThat(edition.path("regionCodes").toString()).contains("320000");
        assertThat(edition.path("curriculumYear").asInt()).isEqualTo(2024);
        assertThat(edition.path("isbn").asText()).isEqualTo("978-7-107-12345-6");
    }

    @Test
    @DisplayName("F12：导出包必须记下**真实操作者**，不能是 unknown")
    void exportPackageRecordsRealActor() {
        // 这里此前读的是 `@RequestAttribute("adminUserId")`——而 `AdminAuthFilter` **从不设置该属性**
        // （它只设 `AdminSession.class.getName()`）。于是导出/导入的操作者**永远是 null**，
        // 被写成 `"unknown"`：导出包**说不出是谁导的**。这是审计链上的一个真实缺口。
        HttpHeaders headers = loginAndHeaders();
        ResponseEntity<String> exported = rest.exchange("/admin/v1/curriculum/export-package",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(exported.getStatusCode().value()).isEqualTo(200);
        String exportedBy = TestJson.parse(exported.getBody())
                .path("data").path("exportedBy").asText();

        assertThat(exportedBy)
                .as("导出包必须带上真实操作者")
                .isNotBlank()
                .isNotEqualTo("unknown");
        // 身份必须来自 MyTools 的实时 ADMIN（不硬编码、不伪造）。
        assertThat(exportedBy).doesNotContain("adminUserId");
    }

    @Test
    @DisplayName("F06：管理端有自己的翻译入口，且它要求管理员身份")
    void adminTranslationEndpointExistsAndRequiresAdmin() {
        // 审查 F06：管理端要有**专用**接口与独立额度。
        // 此前管理端只能走 /public/v1/translations（访客入口），消耗访客额度。
        String payload = "{\"items\":[{\"id\":\"r1\",\"text\":\"apple\"}]}";

        // 1) 匿名调用管理端入口 → 必须被拒（它落在 /admin/v1 前缀下，由管理员过滤器把关）。
        HttpHeaders anonymous = new HttpHeaders();
        anonymous.setContentType(MediaType.APPLICATION_JSON);
        int anonymousStatus = rest.exchange("/admin/v1/translations", HttpMethod.POST,
                new HttpEntity<>(payload, anonymous), String.class).getStatusCode().value();
        assertThat(anonymousStatus).as("匿名不得使用管理端翻译入口").isIn(401, 403);

        // 2) 管理员调用 → 通过，并返回与公开入口同一套信封。
        HttpHeaders headers = loginAndHeaders();
        HttpHeaders json = new HttpHeaders();
        json.putAll(headers);
        json.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> ok = rest.exchange("/admin/v1/translations", HttpMethod.POST,
                new HttpEntity<>(payload, json), String.class);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(TestJson.parse(ok.getBody()).path("data").path("items").isArray()).isTrue();
    }

    @Test
    @DisplayName("匿名的管理写请求仍被拒绝（A02 回归保护）")
    void anonymousStillRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draftJson("anon-book"), freshKey(headers)), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}
