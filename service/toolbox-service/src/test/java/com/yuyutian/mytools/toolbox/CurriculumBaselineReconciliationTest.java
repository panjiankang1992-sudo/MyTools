package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.service.curriculum.CatalogQuery;
import com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 迁移前后全量对账（设计文档 D01）。
 *
 * 对账对象是**只读基线**：第 1 轮生成并冻结的逐册字节哈希与计数。
 * 断言的是"当前文件仓储中的每个已发布版本，与基线逐字节一致"——
 * 这比"接口能返回数据"强得多：它能发现内容被改动、被截断或被静默重排。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CurriculumBaselineReconciliationTest {

    /**
     * 仓库根目录：从当前工作目录向上查找标记目录。
     *
     * **不能用配置的 `catalog-path` 推导**——测试配置里它是模块内的相对空目录
     * （`./target/test-content-empty`），与真实内容目录无关。
     *
     * @return 仓库根目录
     */
    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("toolbox-web/content"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未找到仓库根目录（缺少 toolbox-web/content）");
    }

    /** 真实内容目录；对账必须针对它，而不是测试用的空目录。 */
    private Path realContentPath() {
        return repositoryRoot().resolve("toolbox-web/content");
    }

    /** 只读基线文件路径。 */
    private Path baselinePath() {
        return repositoryRoot().resolve("docs/verification/toolbox-admin-textbooks-2026-10-05/curriculum-baseline.json");
    }

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("每一册已发布内容的字节哈希与计数都与基线一致（D01）")
    void everyEditionMatchesBaselineByteForByte() throws Exception {
        // 基线不可用时明确失败，而不是跳过——否则对账会变成"没跑也算过"。
        Path baselineFile = baselinePath();
        assertThat(Files.exists(baselineFile)).as("基线文件必须存在：%s", baselineFile).isTrue();
        JsonNode baseline = objectMapper.readTree(Files.readString(baselineFile));
        JsonNode editions = baseline.path("editions");
        assertThat(editions.isArray()).isTrue();
        assertThat(editions.size()).as("基线必须包含教材").isGreaterThan(0);

        List<String> mismatches = new ArrayList<>();
        int checked = 0;
        for (JsonNode edition : editions) {
            String editionId = edition.path("editionId").asText();
            String expectedSha = edition.path("sha256").asText();
            int expectedEntries = edition.path("counts").path("entries").asInt();

            // 按基线记录的相对路径直接读字节并重算哈希：不经过任何可能改变内容的转换。
            Path bookPath = repositoryRoot().resolve("toolbox-web").resolve(edition.path("path").asText());
            if (!Files.exists(bookPath)) {
                mismatches.add(editionId + "：文件不存在 " + bookPath);
                continue;
            }
            byte[] bytes = Files.readAllBytes(bookPath);
            String actualSha = sha256Hex(bytes);
            if (!MessageDigest.isEqual(expectedSha.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    actualSha.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                mismatches.add(editionId + "：字节哈希变化 " + expectedSha + " → " + actualSha);
            }
            // 计数从同一份字节里解析，确保"哈希没变但计数口径变了"也能被发现。
            int actualEntries = objectMapper.readTree(bytes).path("entries").size();
            if (actualEntries != expectedEntries) {
                mismatches.add(editionId + "：条目数 " + expectedEntries + " → " + actualEntries);
            }
            checked += 1;
        }

        assertThat(mismatches).as("对账差异").isEmpty();
        assertThat(checked).isEqualTo(editions.size());
    }

    @Test
    @DisplayName("目录快照包含基线全部册次，且这些册次的条目计数合计与基线一致（D01）")
    void catalogTotalsMatchBaseline() throws Exception {
        JsonNode baseline = objectMapper.readTree(Files.readString(baselinePath()));
        JsonNode baselineEditions = baseline.path("editions");
        int expectedEntries = baseline.path("totals").path("entries").asInt();

        // 对账必须加载**真实**内容目录：测试配置指向的是空目录。
        CurriculumCatalog catalog = new CurriculumCatalog(realContentPath(), objectMapper);
        catalog.load();
        var loaded = catalog.catalog(new CatalogQuery(null, null, null, null)).editions();

        // **不再断言"总册数恰好等于基线的册数"**：基线是 2026-10-05 那一轮的快照，
        // 之后**新增**教材（如 2026-10-18 的 en-yl-4-1-yilin-2024，156 条）属正常演进，
        // 让这条 D01 对账失败是**口径错**，不是内容错。D01 关心的是
        // **基线里那些册次的内容没被改动**，所以这里改为：
        // 基线每一册仍然加载得到，且**它们的计数合计**仍等于基线合计。
        java.util.Set<String> baselineIds = new java.util.HashSet<>();
        baselineEditions.forEach(node -> baselineIds.add(node.path("editionId").asText()));
        assertThat(loaded.stream().map(edition -> edition.editionId()).toList())
                .as("基线每一册都必须仍能加载")
                .containsAll(baselineIds);

        int baselineEntries = loaded.stream()
                .filter(edition -> baselineIds.contains(edition.editionId()))
                .mapToInt(edition -> edition.availableEntryCount() == null ? 0 : edition.availableEntryCount())
                .sum();
        assertThat(baselineEntries).as("基线册次的条目合计").isEqualTo(expectedEntries);
    }

    /** 计算 SHA-256 十六进制。 */
    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }
}
