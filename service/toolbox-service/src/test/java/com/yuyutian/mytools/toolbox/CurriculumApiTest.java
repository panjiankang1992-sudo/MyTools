package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yuyutian.mytools.toolbox.service.curriculum.CatalogValidationException;
import com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 教材接口测试：临时目录构造小规模内容包，覆盖目录、版本、ETag、分页、过滤与启动校验。
 */
class CurriculumApiTest extends ToolboxApiTestSupport {

    /** 测试用 JSON 序列化器，必须先于内容包初始化。 */
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    /** 正常内容包根目录。 */
    private static final Path CATALOG_ROOT = buildCatalog();

    /** 哈希不一致的内容包根目录，用于验证启动失败。 */
    private static final Path BROKEN_CATALOG = buildBrokenCatalog();

    /**
     * 把教材目录指向临时构造的内容包。
     *
     * @param registry 动态属性注册器
     */
    @DynamicPropertySource
    static void catalogPath(DynamicPropertyRegistry registry) {
        registry.add("toolbox.catalog-path", CATALOG_ROOT::toString);
    }

    @Test
    @DisplayName("catalog lists published editions and 20 required coverage slots")
    void catalogListsEditionsAndCoverage() {
        ResponseEntity<String> response = rest.getForEntity("/public/v1/textbooks", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode data = json(response).path("data");
        assertThat(data.path("catalogVersion").asText()).isEqualTo("2026-10-02T00:00:00Z");
        JsonNode editions = data.path("editions");
        assertThat(editions).hasSize(2);
        JsonNode coverage = data.path("coverage");
        assertThat(coverage).hasSize(20);

        int covered = 0;
        for (JsonNode slot : coverage) {
            boolean isTarget = "chinese".equals(slot.path("subject").asText())
                    && slot.path("grade").asInt() == 1
                    && "first".equals(slot.path("semester").asText());
            if (isTarget) {
                covered++;
                assertThat(slot.path("coverageStatus").asText()).isEqualTo("complete");
                assertThat(slot.path("editionIds")).hasSize(2);
            } else {
                assertThat(slot.path("coverageStatus").asText()).isEqualTo("missing");
                assertThat(slot.path("editionIds")).isEmpty();
            }
        }
        assertThat(covered).isEqualTo(1);
    }

    @Test
    @DisplayName("catalog filters intersect across dimensions and union regions")
    void catalogFiltersApplyIntersectionAndRegion() {
        JsonNode english = json(rest.getForEntity("/public/v1/textbooks?subject=english", String.class)).path("data");
        assertThat(english.path("editions")).isEmpty();
        assertThat(english.path("coverage")).hasSize(20);

        JsonNode chineseGradeOne = json(rest.getForEntity(
                "/public/v1/textbooks?subject=chinese&grade=1&semester=first", String.class)).path("data");
        assertThat(chineseGradeOne.path("editions")).hasSize(2);

        JsonNode regionMatches = json(rest.getForEntity("/public/v1/textbooks?region=CN", String.class)).path("data");
        assertThat(regionMatches.path("editions")).hasSize(2);

        JsonNode regionMisses = json(rest.getForEntity("/public/v1/textbooks?region=ZZ", String.class)).path("data");
        assertThat(regionMisses.path("editions")).isEmpty();

        JsonNode badSubject = json(rest.getForEntity("/public/v1/textbooks?subject=math", String.class));
        assertThat(badSubject.path("code").asText()).isEqualTo("TOOLBOX_001");
        assertThat(badSubject.path("details").path("field").asText()).isEqualTo("subject");
    }

    @Test
    @DisplayName("version endpoint returns structure, counts and ETag")
    void versionReturnsStructureAndEtag() {
        ResponseEntity<String> response = rest.getForEntity(
                "/public/v1/textbooks/chinese-g1-first-a/versions/1.0.0", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode data = json(response).path("data");
        assertThat(data.path("edition").path("editionId").asText()).isEqualTo("chinese-g1-first-a");
        assertThat(data.path("edition").path("subject").asText()).isEqualTo("chinese");
        assertThat(data.path("edition").path("semester").asText()).isEqualTo("first");
        assertThat(data.path("edition").path("availableEntryCount").asInt()).isEqualTo(5);
        assertThat(data.path("units")).hasSize(2);
        assertThat(data.path("lessons")).hasSize(2);
        assertThat(data.path("entryCount").asInt()).isEqualTo(5);
        assertThat(data.path("counts").path("units").asInt()).isEqualTo(2);
        assertThat(data.path("counts").path("entries").asInt()).isEqualTo(5);
        List<String> listTypes = new ArrayList<>();
        data.path("listTypes").forEach(node -> listTypes.add(node.asText()));
        assertThat(listTypes).containsExactly("recognition", "writing", "vocabulary", "expression");

        String sha256 = data.path("sha256").asText();
        assertThat(sha256).hasSize(64);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"" + sha256 + "\"");
        assertThat(response.getHeaders().getCacheControl()).contains("immutable");
    }

    @Test
    @DisplayName("unknown version returns TOOLBOX_003")
    void unknownVersionIsNotFound() {
        ResponseEntity<String> response = rest.getForEntity(
                "/public/v1/textbooks/chinese-g1-first-a/versions/9.9.9", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(json(response).path("code").asText()).isEqualTo("TOOLBOX_003");
    }

    @Test
    @DisplayName("conditional request with matching ETag returns 304 without body")
    void conditionalRequestReturnsNotModified() {
        ResponseEntity<String> first = rest.getForEntity(
                "/public/v1/textbooks/chinese-g1-first-a/versions/1.0.0", String.class);
        String etag = first.getHeaders().getETag();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.IF_NONE_MATCH, etag);

        ResponseEntity<String> second = rest.exchange(
                "/public/v1/textbooks/chinese-g1-first-a/versions/1.0.0", HttpMethod.GET,
                new HttpEntity<>(null, headers), String.class);

        assertThat(second.getStatusCode().value()).isEqualTo(304);
        assertThat(second.getBody()).isNull();
        assertThat(second.getHeaders().getETag()).isEqualTo(etag);
    }

    @Test
    @DisplayName("paging total equals full scan and order is deterministic")
    void entriesPagingTotalsMatchFullScan() {
        List<String> collected = new ArrayList<>();
        String cursor = null;
        int total = -1;
        do {
            String path = "/public/v1/textbooks/chinese-g1-first-a/versions/1.0.0/entries?limit=2"
                    + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode data = json(rest.getForEntity(path, String.class)).path("data");
            assertThat(data.path("contentVersion").asText()).isEqualTo("1.0.0");
            total = data.path("total").asInt();
            for (JsonNode entry : data.path("entries")) {
                collected.add(entry.path("entryId").asText());
            }
            cursor = data.path("nextCursor").isNull() ? null : data.path("nextCursor").asText();
        } while (cursor != null);

        assertThat(total).isEqualTo(5);
        assertThat(collected).hasSize(5).doesNotHaveDuplicates();
        // 单元顺序、课文顺序、条目顺序、条目 ID 依次决定稳定输出。
        assertThat(collected).containsExactly("e1", "e2", "e3", "e4", "e5");
    }

    @Test
    @DisplayName("unitIds, lessonIds and listTypes support union and intersection")
    void entriesFiltersCombineUnionAndIntersection() {
        assertThat(totalOf("?unitIds=u1&unitIds=u2")).isEqualTo(5);
        assertThat(totalOf("?unitIds=u1")).isEqualTo(3);
        assertThat(totalOf("?unitIds=u1&listTypes=writing")).isEqualTo(1);
        assertThat(totalOf("?listTypes=recognition,writing")).isEqualTo(3);
        assertThat(totalOf("?lessonIds=l2")).isEqualTo(2);
        assertThat(totalOf("")).isEqualTo(5);
    }

    @Test
    @DisplayName("invalid unitId, listType and limit return TOOLBOX_001")
    void invalidEntryParametersAreRejected() {
        ResponseEntity<String> unknownUnit = rest.getForEntity(entriesPath("?unitIds=nope"), String.class);
        assertThat(unknownUnit.getStatusCode().value()).isEqualTo(422);
        JsonNode unknownUnitBody = json(unknownUnit);
        assertThat(unknownUnitBody.path("code").asText()).isEqualTo("TOOLBOX_001");
        assertThat(unknownUnitBody.path("details").path("field").asText()).isEqualTo("unitIds");
        assertThat(unknownUnitBody.path("details").path("reason").asText()).isEqualTo("unknownUnitId");

        ResponseEntity<String> unknownLesson = rest.getForEntity(entriesPath("?lessonIds=nope"), String.class);
        assertThat(unknownLesson.getStatusCode().value()).isEqualTo(422);
        assertThat(json(unknownLesson).path("details").path("field").asText()).isEqualTo("lessonIds");

        ResponseEntity<String> badListType = rest.getForEntity(entriesPath("?listTypes=bogus"), String.class);
        assertThat(badListType.getStatusCode().value()).isEqualTo(422);
        assertThat(json(badListType).path("details").path("field").asText()).isEqualTo("listTypes");

        ResponseEntity<String> tooLarge = rest.getForEntity(entriesPath("?limit=501"), String.class);
        assertThat(tooLarge.getStatusCode().value()).isEqualTo(422);
        JsonNode tooLargeBody = json(tooLarge);
        assertThat(tooLargeBody.path("details").path("field").asText()).isEqualTo("limit");
        assertThat(tooLargeBody.path("details").path("max").asInt()).isEqualTo(500);

        ResponseEntity<String> notNumeric = rest.getForEntity(entriesPath("?limit=abc"), String.class);
        assertThat(notNumeric.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    @DisplayName("cursor from another version or filter set is rejected")
    void cursorFromAnotherVersionOrFilterIsRejected() {
        String cursor = json(rest.getForEntity(entriesPath("?limit=2"), String.class))
                .path("data").path("nextCursor").asText();

        ResponseEntity<String> otherVersion = rest.getForEntity(
                "/public/v1/textbooks/chinese-g1-first-b/versions/1.0.0/entries?limit=2&cursor=" + cursor, String.class);
        assertThat(otherVersion.getStatusCode().value()).isEqualTo(422);
        JsonNode body = json(otherVersion);
        assertThat(body.path("details").path("reason").asText()).isEqualTo("cursorNotBoundToRequest");

        ResponseEntity<String> otherFilter = rest.getForEntity(
                entriesPath("?limit=2&unitIds=u1&cursor=" + cursor), String.class);
        assertThat(otherFilter.getStatusCode().value()).isEqualTo(422);

        ResponseEntity<String> garbage = rest.getForEntity(entriesPath("?cursor=not-a-cursor"), String.class);
        assertThat(garbage.getStatusCode().value()).isEqualTo(422);
        assertThat(json(garbage).path("details").path("reason").asText()).isEqualTo("invalidCursor");
    }

    @Test
    @DisplayName("startup fails when manifest hash mismatches the book file")
    void startupFailsWhenContentHashMismatches() {
        // 直接构造目录即可复现启动期的校验失败。
        assertThatThrownBy(() -> new CurriculumCatalog(BROKEN_CATALOG, MAPPER).load())
                .isInstanceOf(CatalogValidationException.class)
                .hasMessageContaining("contentSha256 mismatch");

        // 通过真实 Spring 启动确认进程会失败退出，而不是带病提供服务。
        // 命令行参数优先级最高，确保覆盖 application-test.yml 中的测试目录。
        SpringApplication application = new SpringApplication(ToolboxServiceApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("test");
        assertThatThrownBy(() -> application.run("--toolbox.catalog-path=" + BROKEN_CATALOG))
                .hasRootCauseInstanceOf(CatalogValidationException.class);
    }

    /**
     * 查询条目总数。
     *
     * @param query 查询字符串，可为空
     * @return total 字段
     */
    private int totalOf(String query) {
        return json(rest.getForEntity(entriesPath(query), String.class)).path("data").path("total").asInt();
    }

    /**
     * 构造条目接口路径。
     *
     * @param query 查询字符串
     * @return 完整路径
     */
    private String entriesPath(String query) {
        return "/public/v1/textbooks/chinese-g1-first-a/versions/1.0.0/entries" + query;
    }

    /**
     * 构造完整内容包。
     *
     * @return 内容包根目录
     */
    private static Path buildCatalog() {
        try {
            Path root = Files.createTempDirectory("toolbox-curriculum");
            Path catalog = root.resolve("catalog");
            Files.createDirectories(catalog);
            writeJson(catalog.resolve("requirements.json"), requirements());
            Map<String, Object> editionA = edition("chinese-g1-first-a", "complete", 5, "1.0.0");
            Map<String, Object> editionB = edition("chinese-g1-first-b", "partial", 2, "1.0.0");
            writeBook(root, editionA, unitsA(), lessonsA(), entriesA());
            writeBook(root, editionB, unitsB(), lessonsB(), entriesB());
            writeJson(catalog.resolve("published.json"), manifest(List.of(
                    manifestEdition(editionA, root), manifestEdition(editionB, root))));
            return root;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 构造哈希与清单不一致的内容包。
     *
     * @return 内容包根目录
     */
    private static Path buildBrokenCatalog() {
        try {
            Path root = Files.createTempDirectory("toolbox-curriculum-broken");
            Path catalog = root.resolve("catalog");
            Files.createDirectories(catalog);
            writeJson(catalog.resolve("requirements.json"), requirements());
            Map<String, Object> editionA = edition("chinese-g1-first-a", "complete", 5, "1.0.0");
            writeBook(root, editionA, unitsA(), lessonsA(), entriesA());
            Map<String, Object> declared = new LinkedHashMap<>(editionA);
            declared.put("contentSha256", "0".repeat(64));
            declared.put("availableEntryCount", 5);
            writeJson(catalog.resolve("published.json"), manifest(List.of(declared)));
            return root;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 构造 20 个必需槽位清单。
     *
     * @return 清单
     */
    private static Map<String, Object> requirements() {
        List<Map<String, Object>> slots = new ArrayList<>();
        for (String subject : List.of("chinese", "english")) {
            int from = "chinese".equals(subject) ? 1 : 3;
            for (int grade = from; grade <= 6; grade++) {
                for (String semester : List.of("first", "second")) {
                    slots.add(Map.of("subject", subject, "grade", grade, "semester", semester));
                }
            }
        }
        return Map.of("requirementsVersion", "1", "slots", slots);
    }

    /**
     * 构造教材元数据。
     *
     * @param editionId  版本标识
     * @param coverage   覆盖状态
     * @param entryCount 条目数量
     * @param version    内容版本
     * @return 元数据映射
     */
    private static Map<String, Object> edition(String editionId, String coverage, int entryCount, String version) {
        Map<String, Object> edition = new LinkedHashMap<>();
        edition.put("editionId", editionId);
        edition.put("subject", "chinese");
        edition.put("grade", 1);
        edition.put("semester", "first");
        edition.put("regionCodes", List.of("CN"));
        edition.put("publisher", "Publisher");
        edition.put("series", "Series");
        edition.put("editionLabel", "Edition Label");
        edition.put("curriculumYear", 2024);
        edition.put("isbn", null);
        edition.put("sourceIds", List.of("src-1"));
        edition.put("schemaVersion", 1);
        edition.put("contentVersion", version);
        edition.put("contentSha256", "0".repeat(64));
        edition.put("reviewStatus", "published");
        edition.put("coverageStatus", coverage);
        edition.put("verifiedAt", "2026-09-01T00:00:00Z");
        edition.put("publishedAt", "2026-09-02T00:00:00Z");
        edition.put("availableEntryCount", entryCount);
        edition.put("expectedEntryCount", entryCount);
        edition.put("missingScopes", List.of());
        return edition;
    }

    /**
     * 构造发布清单中的版本条目，正文哈希取自真实文件。
     *
     * @param edition 元数据
     * @param root    内容包根目录
     * @return 清单条目
     * @throws IOException 读取正文失败
     */
    private static Map<String, Object> manifestEdition(Map<String, Object> edition, Path root) throws IOException {
        Path bookFile = bookPath(root, edition);
        Map<String, Object> declared = new LinkedHashMap<>(edition);
        declared.put("contentSha256", sha256Hex(Files.readAllBytes(bookFile)));
        return declared;
    }

    /**
     * 构造清单文件内容。
     *
     * @param editions 版本条目
     * @return 清单映射
     */
    private static Map<String, Object> manifest(List<Map<String, Object>> editions) {
        return Map.of("catalogVersion", "2026-10-02T00:00:00Z", "editions", editions);
    }

    /**
     * 写入正文文件。
     *
     * @param root    内容包根目录
     * @param edition 元数据
     * @param units   单元
     * @param lessons 课文
     * @param entries 条目
     * @throws IOException 写入失败
     */
    private static void writeBook(Path root, Map<String, Object> edition, List<Map<String, Object>> units,
                                  List<Map<String, Object>> lessons, List<Map<String, Object>> entries)
            throws IOException {
        Map<String, Object> book = new LinkedHashMap<>();
        book.put("edition", edition);
        book.put("units", units);
        book.put("lessons", lessons);
        book.put("entries", entries);
        writeJson(bookPath(root, edition), book);
    }

    /**
     * 计算正文文件路径。
     *
     * @param root    内容包根目录
     * @param edition 元数据
     * @return 正文文件路径
     */
    private static Path bookPath(Path root, Map<String, Object> edition) {
        return root.resolve("textbooks")
                .resolve(String.valueOf(edition.get("editionId")))
                .resolve(String.valueOf(edition.get("contentVersion")))
                .resolve("book.json");
    }

    /**
     * 写入 JSON 文件。
     *
     * @param path  目标路径
     * @param value 内容
     * @throws IOException 写入失败
     */
    private static void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }

    /**
     * 计算十六进制 SHA-256。
     *
     * @param bytes 字节数组
     * @return 十六进制摘要
     */
    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 构造第一个版本的单元。
     *
     * @return 单元列表
     */
    private static List<Map<String, Object>> unitsA() {
        return List.of(unit("u1", "chinese-g1-first-a", 1), unit("u2", "chinese-g1-first-a", 2));
    }

    /**
     * 构造第二个版本的单元。
     *
     * @return 单元列表
     */
    private static List<Map<String, Object>> unitsB() {
        return List.of(unit("u1", "chinese-g1-first-b", 1));
    }

    /**
     * 构造单元。
     *
     * @param unitId    单元标识
     * @param editionId 版本标识
     * @param order     顺序
     * @return 单元映射
     */
    private static Map<String, Object> unit(String unitId, String editionId, int order) {
        return Map.of("unitId", unitId, "editionId", editionId, "order", order, "title", "Unit " + order);
    }

    /**
     * 构造第一个版本的课文。
     *
     * @return 课文列表
     */
    private static List<Map<String, Object>> lessonsA() {
        return List.of(lesson("l1", "u1", 1), lesson("l2", "u2", 1));
    }

    /**
     * 构造第二个版本的课文。
     *
     * @return 课文列表
     */
    private static List<Map<String, Object>> lessonsB() {
        return List.of(lesson("l1", "u1", 1));
    }

    /**
     * 构造课文。
     *
     * @param lessonId 课文标识
     * @param unitId   单元标识
     * @param order    顺序
     * @return 课文映射
     */
    private static Map<String, Object> lesson(String lessonId, String unitId, int order) {
        return Map.of("lessonId", lessonId, "unitId", unitId, "order", order, "title", "Lesson " + order);
    }

    /**
     * 构造第一个版本的条目。
     *
     * @return 条目列表
     */
    private static List<Map<String, Object>> entriesA() {
        return List.of(
                entry("e1", "chinese-g1-first-a", "u1", "l1", 1, "character", List.of("recognition"), "alpha"),
                entry("e2", "chinese-g1-first-a", "u1", "l1", 2, "word", List.of("writing"), "beta"),
                entry("e3", "chinese-g1-first-a", "u1", null, 3, "phrase", List.of("vocabulary"), "gamma"),
                entry("e4", "chinese-g1-first-a", "u2", "l2", 1, "character", List.of("recognition"), "delta"),
                entry("e5", "chinese-g1-first-a", "u2", "l2", 2, "word", List.of("expression"), "epsilon"));
    }

    /**
     * 构造第二个版本的条目。
     *
     * @return 条目列表
     */
    private static List<Map<String, Object>> entriesB() {
        return List.of(
                entry("b1", "chinese-g1-first-b", "u1", "l1", 1, "character", List.of("recognition"), "zeta"),
                entry("b2", "chinese-g1-first-b", "u1", "l1", 2, "word", List.of("writing"), "eta"));
    }

    /**
     * 构造条目。
     *
     * @param entryId   条目标识
     * @param editionId 版本标识
     * @param unitId    单元标识
     * @param lessonId  课文标识，可为空
     * @param order     顺序
     * @param form      形态
     * @param listTypes 清单类别
     * @param text      正文
     * @return 条目映射
     */
    private static Map<String, Object> entry(String entryId, String editionId, String unitId, String lessonId,
                                             int order, String form, List<String> listTypes, String text) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("entryId", entryId);
        entry.put("entryVersion", "1");
        entry.put("editionId", editionId);
        entry.put("unitId", unitId);
        entry.put("lessonId", lessonId);
        entry.put("order", order);
        entry.put("form", form);
        entry.put("listTypes", listTypes);
        entry.put("text", text);
        entry.put("pinyinDisplay", "pin-" + text);
        entry.put("pinyinSegments", List.of(Map.of("grapheme", text.substring(0, 1), "syllable", text)));
        entry.put("meaning", "meaning-" + text);
        entry.put("partOfSpeech", "noun");
        entry.put("senseId", "sense-" + entryId);
        entry.put("context", null);
        entry.put("promptContext", "prompt-" + text);
        entry.put("acceptableAnswers", List.of(text));
        entry.put("sourceRefs", List.of(Map.of("sourceId", "src-1", "locator", "p.1", "page", 1)));
        entry.put("reviewStatus", "published");
        entry.put("blockingIssues", List.of());
        return entry;
    }
}
