package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentity;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityClient;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 后端剩余两项的验收：
 *
 * 1. **发布响应并存 `catalogVersion` 与 `catalogEpoch`**（设计文档第 391-392 行），
 *    两者都读 `toolbox_curriculum_catalog` 的列，且与库中值逐字一致；
 * 2. **预览的真范围过滤**（设计 164 行「预览字帖使用当前草稿的已选范围」），
 *    并回显本次生成所依据的 `draftRevision`；参数缺省时仍为整册。
 *
 * 与 `AdminTextbookApiTest` 同样的边界：这两个用例要走**发布**，
 * 因此显式覆盖 `toolbox.curriculum.repository=database`（`files` 模式下发布会被闸门拒绝），
 * 并用**独立内存库**避免与其它用例的版本行互相污染。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "toolbox.curriculum.repository=database")
@ActiveProfiles("test")
class AdminTextbookPublishAndPreviewScopeTest {

    /**
     * 独立内存库：避免"启动时读到别的用例写入的版本行"这种顺序耦合。
     *
     * @param registry 动态属性注册器
     */
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:publish-catalog-preview-scope;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    /** 注入式假身份：登录必须走这个替身，否则会去连不可达的真实上游。 */
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
            };
        }
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AdminTextbookRepository repository;

    // ================= 任务 1：发布响应带目录版本与世代 =================

    @Test
    @DisplayName("发布响应并存 catalogVersion 与 catalogEpoch，且与库中列逐字一致（设计 391-392）")
    void publishResponseCarriesCatalogVersionAndEpoch() {
        HttpHeaders headers = loginAndHeaders();
        long epochBefore = epochInDatabase();
        String editionId = "catalog-fields-" + shortId();
        createDraft(headers, editionId);

        JsonNode data = publish(headers, editionId);

        long epochAfter = epochInDatabase();
        String versionAfter = catalogVersionInDatabase();
        // 两个字段都必须存在：只回一个不满足设计"并存"的要求。
        assertThat(data.has("catalogVersion")).as("发布响应必须带 catalogVersion").isTrue();
        assertThat(data.has("catalogEpoch")).as("发布响应必须带 catalogEpoch").isTrue();
        // 设计示例里 catalogEpoch 是字符串（"42"），不是 JSON 数字。
        assertThat(data.path("catalogEpoch").isTextual())
                .as("catalogEpoch 按设计示例用字符串表达").isTrue();
        // 与库中列逐字一致——而不是由 epoch 现算出来的。
        assertThat(data.path("catalogVersion").asText()).isEqualTo(versionAfter);
        assertThat(data.path("catalogEpoch").asText()).isEqualTo(String.valueOf(epochAfter));
        assertThat(epochAfter).as("一次真实发布推进一格世代").isEqualTo(epochBefore + 1);
        // 只锁定"发布后确实生成了新的目录版本名"。
        //
        // **不**断言 `catalog_version == "catalog-" + epoch`：真实 MySQL 上
        // `SET epoch = epoch + 1, catalog_version = CONCAT('catalog-', epoch + 1)`
        // 的赋值是**从左到右**求值的，第二个表达式用到的是**已更新**的 epoch，
        // 于是首次发布得到 `catalog-2`（epoch=1）；H2 得到 `catalog-1`。
        // 两者不一致是既有事实，已记录在验证文档「任务 3-1」里，不在本用例中假装一致。
        assertThat(versionAfter).startsWith("catalog-");
    }

    @Test
    @DisplayName("每次真实发布都推进 epoch，catalog_version 同步变化")
    void eachPublishAdvancesEpochAndCatalogVersion() {
        HttpHeaders headers = loginAndHeaders();
        String firstEdition = "catalog-advance-a-" + shortId();
        String secondEdition = "catalog-advance-b-" + shortId();
        createDraft(headers, firstEdition);
        createDraft(headers, secondEdition);

        JsonNode first = publish(headers, firstEdition);
        JsonNode second = publish(headers, secondEdition);

        long firstEpoch = Long.parseLong(first.path("catalogEpoch").asText());
        long secondEpoch = Long.parseLong(second.path("catalogEpoch").asText());
        String firstVersion = first.path("catalogVersion").asText();
        String secondVersion = second.path("catalogVersion").asText();
        assertThat(secondEpoch).isEqualTo(firstEpoch + 1);
        assertThat(secondVersion).as("目录版本号必须随发布变化").isNotEqualTo(firstVersion);
        // 与上一个用例同理：不断言版本名与 epoch 的拼装关系（MySQL 与 H2 不一致）。
        assertThat(secondVersion).startsWith("catalog-");
        // 库里此刻就是第二次发布的值，响应不得是旧值。
        assertThat(catalogVersionInDatabase()).isEqualTo(secondVersion);
        assertThat(epochInDatabase()).isEqualTo(secondEpoch);
    }

    @Test
    @DisplayName("catalogVersion 读库中列而不是由 epoch 现算（epoch 与列可互相独立）")
    void catalogVersionComesFromStoredColumn() {
        long savedEpoch = epochInDatabase();
        String savedVersion = catalogVersionInDatabase();
        try {
            // 构造"列值与 epoch 无派生关系"的库状态：这正是初始迁移留下的形态
            // （设计第 8 节第 5 点：迁移保留旧 catalogVersion，epoch 独立初始化）。
            jdbc.update("UPDATE toolbox_curriculum_catalog SET epoch = 7, catalog_version = 'catalog-77' "
                    + "WHERE catalog_key = 'current'");
            assertThat(repository.currentCatalogVersion())
                    .as("必须原样读出列值，而不是拼出 catalog-7").isEqualTo("catalog-77");
            assertThat(repository.currentEpoch()).isEqualTo(7L);
        } finally {
            jdbc.update("UPDATE toolbox_curriculum_catalog SET epoch = ?, catalog_version = ? "
                    + "WHERE catalog_key = 'current'", savedEpoch, savedVersion);
        }
        assertThat(repository.currentCatalogVersion()).isEqualTo(savedVersion);
    }

    // ================= 任务 2：预览的真范围过滤 =================

    @Test
    @DisplayName("预览缺省仍为整册：单元/课文/条目与改造前一致，并回显 draftRevision")
    void previewWithoutScopeReturnsWholeBook() {
        HttpHeaders headers = loginAndHeaders();
        String editionId = "preview-whole-" + shortId();
        createDraft(headers, editionId);
        long revision = revisionOf(headers, editionId);

        JsonNode data = preview(headers, editionId, "").path("data");

        assertThat(data.path("entries")).as("缺省必须返回整册 4 条").hasSize(4);
        assertThat(data.path("units")).hasSize(2);
        assertThat(data.path("lessons")).hasSize(2);
        assertThat(data.path("scope").path("filtered").asBoolean()).isFalse();
        // 回显本次生成所依据的修订号：客户端据此校验"预览的就是我保存的那一版"。
        assertThat(data.path("draftRevision").asLong()).isEqualTo(revision);
        assertThat(data.path("edition").path("availableEntryCount").asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("预览按单元过滤：只返回该单元的条目与课文（含正式标识写法）")
    void previewWithUnitScopeReturnsOnlyThatUnit() {
        HttpHeaders headers = loginAndHeaders();
        String editionId = "preview-unit-" + shortId();
        createDraft(headers, editionId);
        long revisionBefore = revisionOf(headers, editionId);

        JsonNode whole = preview(headers, editionId, "").path("data");
        // cu2 是第二个单元；整册预览里单元的正式标识与草稿顺序一致。
        String officialUnitId = officialUnitIdAt(whole, 1);
        assertThat(officialUnitId).endsWith("-u02");

        JsonNode data = preview(headers, editionId, "?nodeKind=unit&nodeKey=cu2").path("data");

        assertThat(data.path("scope").path("filtered").asBoolean()).isTrue();
        assertThat(textsOf(data)).containsExactly("火", "土");
        assertThat(data.path("units")).hasSize(1);
        assertThat(data.path("units").get(0).path("unitId").asText()).isEqualTo(officialUnitId);
        assertThat(data.path("lessons")).as("只保留该单元名下的课文").hasSize(1);
        assertThat(data.path("edition").path("availableEntryCount").asInt())
                .as("带范围时声明条目数必须等于实际返回条目数，不能谎报整册条数").isEqualTo(2);
        assertThat(data.path("draftRevision").asLong()).isEqualTo(revisionBefore);

        // 正式标识同样可作范围键：编辑器在已发布教材上两套标识都可能出现。
        JsonNode byOfficial = preview(headers, editionId,
                "?nodeKind=unit&nodeKey=" + officialUnitId).path("data");
        assertThat(textsOf(byOfficial)).containsExactly("火", "土");

        // 预览只读：范围预览之后修订号不变。
        assertThat(revisionOf(headers, editionId)).isEqualTo(revisionBefore);
    }

    @Test
    @DisplayName("预览按课文过滤：只返回该课文的条目与父单元")
    void previewWithLessonScopeReturnsOnlyThatLesson() {
        HttpHeaders headers = loginAndHeaders();
        String editionId = "preview-lesson-" + shortId();
        createDraft(headers, editionId);

        JsonNode data = preview(headers, editionId, "?nodeKind=lesson&nodeKey=cl1").path("data");

        assertThat(data.path("scope").path("filtered").asBoolean()).isTrue();
        assertThat(textsOf(data)).containsExactly("山", "水");
        assertThat(data.path("lessons")).hasSize(1);
        assertThat(data.path("lessons").get(0).path("lessonId").asText()).endsWith("-l01");
        // 父单元随课文一起返回，否则前端渲染时没有归属。
        assertThat(data.path("units")).hasSize(1);
        assertThat(data.path("units").get(0).path("unitId").asText()).endsWith("-u01");
        assertThat(data.path("edition").path("availableEntryCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("范围不存在时返回 404（而不是静默退回整册）")
    void unknownPreviewScopeIs404() {
        HttpHeaders headers = loginAndHeaders();
        String editionId = "preview-missing-scope-" + shortId();
        createDraft(headers, editionId);

        // a) 标识在草稿里找不到
        assertScopeNotFound(headers, editionId, "?nodeKind=unit&nodeKey=nope");
        // b) 未识别的范围类型
        assertScopeNotFound(headers, editionId, "?nodeKind=chapter&nodeKey=cu1");
        // c) 只给了一半参数：范围没被完整指定，不能当成"整册"
        assertScopeNotFound(headers, editionId, "?nodeKind=unit");
        assertScopeNotFound(headers, editionId, "?nodeKey=cu1");
    }

    @Test
    @DisplayName("预览可被服务端校验修订：draftRevision 不符返回 409，相符返回 200")
    void previewChecksExpectedDraftRevision() {
        HttpHeaders headers = loginAndHeaders();
        String editionId = "preview-revision-" + shortId();
        createDraft(headers, editionId);
        long revision = revisionOf(headers, editionId);

        // 1) 声称的修订号就是当前修订：通过，并回显该修订号。
        JsonNode matching = preview(headers, editionId, "?draftRevision=" + revision).path("data");
        assertThat(matching.path("draftRevision").asLong()).isEqualTo(revision);

        // 2) 声称的修订号是旧的：**服务端**拒绝（409 TOOLBOX_ADMIN_004），
        //    不能拿"另一版的草稿"生成预览却让界面标注成用户以为的那一版。
        ResponseEntity<String> stale = rest.exchange(
                "/admin/v1/textbooks/" + editionId + "/draft/preview?draftRevision=" + (revision - 1),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(stale.getStatusCode().value()).isEqualTo(409);
        JsonNode body = TestJson.parse(stale.getBody());
        assertThat(body.path("code").asText()).isEqualTo("TOOLBOX_ADMIN_004");
        assertThat(body.path("details").path("reason").asText()).isEqualTo("revisionMismatch");

        // 3) 范围与修订号可以一起用。
        JsonNode scoped = preview(headers, editionId,
                "?nodeKind=unit&nodeKey=cu2&draftRevision=" + revision).path("data");
        assertThat(textsOf(scoped)).containsExactly("火", "土");
    }

    // ================= 断言与请求辅助 =================

    /** 断言某次范围预览返回 404 且原因是"范围不存在"。 */
    private void assertScopeNotFound(HttpHeaders headers, String editionId, String query) {
        ResponseEntity<String> response = rest.exchange(
                "/admin/v1/textbooks/" + editionId + "/draft/preview" + query,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).as("query=" + query).isEqualTo(404);
        JsonNode body = TestJson.parse(response.getBody());
        assertThat(body.path("code").asText()).isEqualTo("TOOLBOX_ADMIN_006");
        assertThat(body.path("details").path("reason").asText()).isEqualTo("previewScopeNotFound");
    }

    /** 从预览响应里取条目正文列表，保持服务端顺序。 */
    private static List<String> textsOf(JsonNode data) {
        List<String> texts = new ArrayList<>();
        for (JsonNode entry : data.path("entries")) {
            texts.add(entry.path("text").asText());
        }
        return texts;
    }

    /** 从整册预览里取第 index 个单元（0 基）的正式标识。 */
    private static String officialUnitIdAt(JsonNode wholePreview, int index) {
        return wholePreview.path("units").get(index).path("unitId").asText();
    }

    /** 读库中的当前世代。 */
    private long epochInDatabase() {
        Long epoch = jdbc.queryForObject(
                "SELECT epoch FROM toolbox_curriculum_catalog WHERE catalog_key = 'current'", Long.class);
        return epoch == null ? 0L : epoch;
    }

    /** 读库中的当前目录版本号。 */
    private String catalogVersionInDatabase() {
        return jdbc.queryForObject(
                "SELECT catalog_version FROM toolbox_curriculum_catalog WHERE catalog_key = 'current'",
                String.class);
    }

    /** 读草稿当前修订号。 */
    private long revisionOf(HttpHeaders headers, String editionId) {
        ResponseEntity<String> response = rest.exchange("/admin/v1/textbooks/" + editionId + "/draft",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return TestJson.parse(response.getBody()).path("data").path("revision").asLong();
    }

    /** 发起一次预览请求，返回完整响应体。 */
    private JsonNode preview(HttpHeaders headers, String editionId, String query) {
        ResponseEntity<String> response = rest.exchange(
                "/admin/v1/textbooks/" + editionId + "/draft/preview" + query,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).as("query=" + query).isEqualTo(200);
        return TestJson.parse(response.getBody());
    }

    /** 建一册草稿（两个单元、两篇课文、四条条目）。 */
    private void createDraft(HttpHeaders headers, String editionId) {
        HttpHeaders write = writeHeaders(headers);
        ResponseEntity<String> created = rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(multiUnitDraft(editionId), write), String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
    }

    /** 发布一册，返回发布响应的 data。 */
    private JsonNode publish(HttpHeaders headers, String editionId) {
        HttpHeaders write = writeHeaders(headers);
        ResponseEntity<String> published = rest.exchange("/admin/v1/textbooks/" + editionId + "/publish",
                HttpMethod.POST, new HttpEntity<>(write), String.class);
        assertThat(published.getStatusCode().value())
                .as("发布必须成功：" + published.getBody()).isEqualTo(200);
        return TestJson.parse(published.getBody()).path("data");
    }

    /** 写操作头：会话 + CSRF + 新鲜幂等键。 */
    private static HttpHeaders writeHeaders(HttpHeaders session) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(session);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return headers;
    }

    /** 两份 a–z0–9 随机后缀，避免同一内存库里教材标识相撞。 */
    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 两个单元、两篇课文、四条条目的最小草稿。
     *
     * 结构刻意做成"单元 2 里既有课文条目、又有直属条目"，
     * 这样单元范围与课文范围过滤出的条目数不同（2 vs 2，但内容不同），
     * 能区分"按单元过滤"与"按课文过滤"两种实现。
     *
     * @param editionId 教材标识
     * @return 草稿 JSON
     */
    private static String multiUnitDraft(String editionId) {
        return """
                {"editionId":"%s","subject":"chinese","grade":1,"semester":"first",
                 "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
                 "sourceNote":"纸质教材附录，第 78 页","coverageDeclaration":"partial",
                 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1},
                          {"unitId":null,"clientUnitId":"cu2","title":"第二单元","order":2}],
                 "lessons":[{"lessonId":null,"clientLessonId":"cl1","unitId":null,"clientUnitId":"cu1",
                             "title":"课文一","order":1},
                            {"lessonId":null,"clientLessonId":"cl2","unitId":null,"clientUnitId":"cu2",
                             "title":"课文二","order":1}],
                 "entries":[{"rowId":"row-1","entryId":null,"unitId":null,"clientUnitId":"cu1",
                             "lessonId":null,"clientLessonId":"cl1","text":"山","pinyin":"shān",
                             "meaning":null,"listTypes":["writing"],"confirmed":true},
                            {"rowId":"row-2","entryId":null,"unitId":null,"clientUnitId":"cu1",
                             "lessonId":null,"clientLessonId":"cl1","text":"水","pinyin":"shuǐ",
                             "meaning":null,"listTypes":["writing"],"confirmed":true},
                            {"rowId":"row-3","entryId":null,"unitId":null,"clientUnitId":"cu2",
                             "lessonId":null,"clientLessonId":"cl2","text":"火","pinyin":"huǒ",
                             "meaning":null,"listTypes":["writing"],"confirmed":true},
                            {"rowId":"row-4","entryId":null,"unitId":null,"clientUnitId":"cu2",
                             "lessonId":null,"clientLessonId":null,"text":"土","pinyin":"tǔ",
                             "meaning":null,"listTypes":["writing"],"confirmed":true}]}
                """.formatted(editionId);
    }

    /** 走完整登录流程（CSRF → 登录），返回带会话 Cookie 与 CSRF 头的请求头。 */
    private HttpHeaders loginAndHeaders() {
        ResponseEntity<String> csrf = rest.getForEntity("/admin/v1/auth/csrf", String.class);
        assertThat(csrf.getStatusCode().value()).isEqualTo(200);
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.add(HttpHeaders.COOKIE,
                "toolbox_admin_login_local=" + cookieOf(csrf, "toolbox_admin_login_local"));
        loginHeaders.add("X-Toolbox-Admin-Csrf",
                TestJson.parse(csrf.getBody()).path("data").path("csrfToken").asText());
        ResponseEntity<String> login = rest.exchange("/admin/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"account\":\"someone\",\"password\":\"secret\"}", loginHeaders),
                String.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, "toolbox_admin_local=" + cookieOf(login, "toolbox_admin_local"));
        headers.add("X-Toolbox-Admin-Csrf",
                TestJson.parse(login.getBody()).path("data").path("csrfToken").asText());
        return headers;
    }

    /** 从响应中取指定 Cookie 值。 */
    private static String cookieOf(ResponseEntity<String> response, String name) {
        for (String header : response.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE, List.of())) {
            if (header.startsWith(name + "=")) {
                return header.substring(name.length() + 1).split(";")[0];
            }
        }
        throw new AssertionError("cookie not found: " + name);
    }
}
