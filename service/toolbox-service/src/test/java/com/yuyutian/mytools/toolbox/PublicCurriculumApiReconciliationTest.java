package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 通过**公开接口**逐册对账（设计文档 D01 的最后一环）。
 *
 * 与 {@link CurriculumBaselineReconciliationTest} 的区别：那边直接读字节验证仓库内容，
 * 这边走完整 HTTP 链路验证"用户实际能拿到的数据"——两者缺一不可：
 * 文件对但接口错（过滤、分页、截断）在字节对账里查不出来。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PublicCurriculumApiReconciliationTest {

    /**
     * 把目录来源指向**真实内容目录**。
     *
     * 测试配置里的 `catalog-path` 是模块内的空目录，若不覆盖，公开接口会因目录为空而全部 404——
     * 那样这个测试就变成了"验证空目录"，恰好失去它存在的意义。
     *
     * @param registry 属性注册器
     */
    @org.springframework.test.context.DynamicPropertySource
    static void catalogPath(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("toolbox.catalog-path", () -> repositoryRootStatic().resolve("toolbox-web/content").toString());
    }

    /** 与实例方法同逻辑的静态版本，供属性注册使用。 */
    private static Path repositoryRootStatic() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("toolbox-web/content"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未找到仓库根目录");
    }

    /** 单次请求条数；用于验证分页在真实规模下不漏行。 */
    private static final int PAGE_LIMIT = 200;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    /** 仓库根目录：向上查找标记目录（测试配置的 catalog-path 是模块内空目录，不能用来定位）。 */
    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("toolbox-web/content"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未找到仓库根目录");
    }

    /** 读取只读基线。 */
    private JsonNode baseline() throws Exception {
        Path file = repositoryRoot()
                .resolve("docs/verification/toolbox-admin-textbooks-2026-10-05/curriculum-baseline.json");
        assertThat(Files.exists(file)).as("基线文件必须存在：%s", file).isTrue();
        return objectMapper.readTree(Files.readString(file));
    }

    @Test
    @DisplayName("公开接口返回的册数、单元数、课文数与条目数与基线一致（D01）")
    void versionMetadataMatchesBaseline() throws Exception {
        JsonNode editions = baseline().path("editions");
        assertThat(editions.size()).isGreaterThan(0);

        List<String> mismatches = new ArrayList<>();
        for (JsonNode edition : editions) {
            String editionId = edition.path("editionId").asText();
            String version = edition.path("contentVersion").asText();
            JsonNode counts = edition.path("counts");

            var response = rest.getForEntity(
                    "/public/v1/textbooks/" + editionId + "/versions/" + version, String.class);
            if (response.getStatusCode().value() != 200) {
                mismatches.add(editionId + "：HTTP " + response.getStatusCode().value());
                continue;
            }
            JsonNode data = TestJson.parse(response.getBody()).path("data");
            compare(mismatches, editionId, "units", counts.path("units").asInt(), data.path("units").size());
            compare(mismatches, editionId, "lessons", counts.path("lessons").asInt(), data.path("lessons").size());
            compare(mismatches, editionId, "entryCount", counts.path("entries").asInt(),
                    data.path("entryCount").asInt());
        }
        assertThat(mismatches).as("接口元数据差异").isEmpty();
    }

    @Test
    @DisplayName("公开接口分页遍历能取到全部条目，且基线样本 ID 都在（D01）")
    void pagedEntriesMatchBaseline() throws Exception {
        JsonNode editions = baseline().path("editions");
        List<String> mismatches = new ArrayList<>();
        int totalEntries = 0;

        for (JsonNode edition : editions) {
            String editionId = edition.path("editionId").asText();
            String version = edition.path("contentVersion").asText();
            int expected = edition.path("counts").path("entries").asInt();

            Set<String> seenIds = new HashSet<>();
            String cursor = null;
            int guard = 0;
            while (guard < 100) {
                guard += 1;
                String url = "/public/v1/textbooks/" + editionId + "/versions/" + version
                        + "/entries?limit=" + PAGE_LIMIT + (cursor == null ? "" : "&cursor=" + cursor);
                var response = rest.getForEntity(url, String.class);
                if (response.getStatusCode().value() != 200) {
                    mismatches.add(editionId + "：条目接口 HTTP " + response.getStatusCode().value());
                    break;
                }
                JsonNode data = TestJson.parse(response.getBody()).path("data");
                for (JsonNode item : data.path("entries")) {
                    seenIds.add(item.path("entryId").asText());
                }
                String next = data.path("nextCursor").asText("");
                if (next.isEmpty()) {
                    break;
                }
                cursor = next;
            }

            if (seenIds.size() != expected) {
                mismatches.add(editionId + "：接口取到 " + seenIds.size() + " 条，基线 " + expected + " 条");
            }
            // 基线记录的样本 ID 必须都能取到——它们代表具体条目而非仅数量。
            for (JsonNode sample : edition.path("idSamples").path("entryIds")) {
                if (!seenIds.contains(sample.asText())) {
                    mismatches.add(editionId + "：缺少样本条目 " + sample.asText());
                }
            }
            totalEntries += seenIds.size();
        }

        int expectedTotal = baseline().path("totals").path("entries").asInt();
        assertThat(totalEntries).as("接口条目总数").isEqualTo(expectedTotal);
        assertThat(mismatches).as("接口条目差异").isEmpty();
    }

    /** 比较一项并在不一致时记录。 */
    private static void compare(List<String> mismatches, String editionId, String field, int expected, int actual) {
        if (expected != actual) {
            mismatches.add(editionId + "：" + field + " 基线=" + expected + " 接口=" + actual);
        }
    }
}
