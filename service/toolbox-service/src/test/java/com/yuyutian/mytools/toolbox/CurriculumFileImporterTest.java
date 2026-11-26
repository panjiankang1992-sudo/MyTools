package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import com.yuyutian.mytools.toolbox.service.admin.migration.CurriculumFileImporter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 初始数据迁移的幂等与对账行为（设计文档 §8 第 2、3 点）。
 * 重点：重复导入不新增；哈希不同必须停止并报告，绝不覆盖已发布内容。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CurriculumFileImporterTest {

    private static final String MIGRATION_ACTOR = "migration";

    @Autowired
    private CurriculumFileImporter importer;

    @Autowired
    private AdminTextbookRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    /** 取某册某版本的版本行数。 */
    private int versionRows(String editionId, String contentVersion) {
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM toolbox_textbook_version WHERE edition_id = ? AND content_version = ?",
                Integer.class, editionId, contentVersion);
        return rows == null ? 0 : rows;
    }

    @Test
    @DisplayName("迁移包缺 sourceNote 时必须派生一条**事实性**说明（否则发布 422 SOURCE_REQUIRED）")
    void importDerivesFactualSourceNoteWhenMissing() {
        // 真实内容包里 `edition.sourceNote` 是空的（实测 777/140 两册都是 None），
        // 而 sourceNote 是**发布必填**（设计第 106 行）→ 迁移进来的草稿一发布就 422。
        // 这里只从已冻结的真实数据（sourceIds + 条目 sourceRefs 计数与页码）派生，
        // 并**显式标注"迁移自动生成"**，便于管理员替换为真实描述。
        String editionId = "import-book-nonote";
        byte[] bytes = ("{\"edition\":{\"subject\":\"chinese\",\"grade\":3,\"semester\":\"first\","
                + "\"publisher\":\"人民教育出版社\",\"series\":\"统编语文\",\"editionLabel\":\"2024 年修订版\","
                + "\"sourceIds\":[\"smartedu-ebook-cn-3-first-2024\"]},"
                + "\"units\":[],\"lessons\":[],"
                + "\"entries\":[{\"text\":\"绒\",\"sourceRefs\":[{\"sourceId\":\"smartedu-ebook-cn-3-first-2024\","
                + "\"locator\":\"row 2\",\"page\":78}]},"
                + "{\"text\":\"球\",\"sourceRefs\":[{\"sourceId\":\"smartedu-ebook-cn-3-first-2024\","
                + "\"locator\":\"row 3\",\"page\":82}]}]}")
                .getBytes(StandardCharsets.UTF_8);
        importer.importEdition(editionId, "1.0.0", bytes, "hash-nonote", "{}", MIGRATION_ACTOR);

        String draftJson = jdbc.queryForObject(
                "SELECT editable_json FROM toolbox_textbook_draft WHERE edition_id = ?", String.class, editionId);
        assertThat(draftJson).contains("sourceNote");
        assertThat(draftJson).contains("迁移自动生成");
        assertThat(draftJson).contains("smartedu-ebook-cn-3-first-2024");
        assertThat(draftJson).contains("第 78—82 页");
        assertThat(draftJson).contains("2 处");
    }

    @Test
    @DisplayName("迁移包自带 sourceNote 时必须原样保留，不得被派生说明覆盖")
    void importKeepsDeclaredSourceNote() {
        String editionId = "import-book-withnote";
        byte[] bytes = ("{\"edition\":{\"subject\":\"chinese\",\"grade\":1,\"semester\":\"first\","
                + "\"publisher\":\"人民教育出版社\",\"series\":\"统编语文\",\"editionLabel\":\"2024 年修订版\","
                + "\"sourceNote\":\"纸质教材附录词语表，第 78—82 页\"},"
                + "\"units\":[],\"lessons\":[],\"entries\":[]}").getBytes(StandardCharsets.UTF_8);
        importer.importEdition(editionId, "1.0.0", bytes, "hash-withnote", "{}", MIGRATION_ACTOR);

        String draftJson = jdbc.queryForObject(
                "SELECT editable_json FROM toolbox_textbook_draft WHERE edition_id = ?", String.class, editionId);
        assertThat(draftJson).contains("纸质教材附录词语表");
        assertThat(draftJson).doesNotContain("迁移自动生成");
    }

    @Test
    @DisplayName("迁移必须把适用地区／课标年份／ISBN 搬进草稿（否则发布后地区筛选就看不到这册）")
    void importCarriesRegionYearAndIsbnIntoTheDraft() {
        // 此前 `draftJsonFromBook` 不写这三个字段，于是迁移草稿里它们恒为 null；
        // `toEdition` 虽然已改成传真实值却**拿不到值**，发布出去就丢成 null。
        //
        // 实测后果（真实 5 册迁移，2026-10-07）：
        // `GET /public/v1/textbooks?region=jiangsu` 只剩 2 册 ——
        // **迁移后重新发布的教材在地区筛选下消失**。
        String editionId = "import-book-region";
        byte[] bytes = ("{\"edition\":{\"subject\":\"chinese\",\"grade\":2,\"semester\":\"first\","
                + "\"publisher\":\"人民教育出版社\",\"series\":\"统编语文\",\"editionLabel\":\"2024 年修订版\","
                + "\"regionCodes\":[\"jiangsu\"],\"curriculumYear\":2024,\"isbn\":\"9787575300377\"},"
                + "\"units\":[],\"lessons\":[],\"entries\":[]}").getBytes(StandardCharsets.UTF_8);
        importer.importEdition(editionId, "1.0.0", bytes, "hash-region", "{}", MIGRATION_ACTOR);

        String draftJson = jdbc.queryForObject(
                "SELECT editable_json FROM toolbox_textbook_draft WHERE edition_id = ?", String.class, editionId);
        assertThat(draftJson).contains("\"regionCodes\"").contains("jiangsu");
        assertThat(draftJson).contains("\"curriculumYear\"").contains("2024");
        assertThat(draftJson).contains("\"isbn\"").contains("9787575300377");
    }

    @Test
    @DisplayName("迁移时年份缺失必须留空，不能用 0 冒充（0 是合法但错误的年份）")
    void importKeepsMissingCurriculumYearNull() {
        String editionId = "import-book-noyear";
        byte[] bytes = ("{\"edition\":{\"subject\":\"english\",\"grade\":3,\"semester\":\"first\","
                + "\"publisher\":\"译林出版社\",\"series\":\"译林版英语\",\"editionLabel\":\"2024\","
                + "\"regionCodes\":[\"jiangsu\"]},\"units\":[],\"lessons\":[],\"entries\":[]}")
                .getBytes(StandardCharsets.UTF_8);
        importer.importEdition(editionId, "1.0.0", bytes, "hash-noyear", "{}", MIGRATION_ACTOR);

        String draftJson = jdbc.queryForObject(
                "SELECT editable_json FROM toolbox_textbook_draft WHERE edition_id = ?", String.class, editionId);
        assertThat(draftJson).contains("jiangsu");
        assertThat(draftJson).doesNotContain("\"curriculumYear\":0");
    }

    @Test
    @DisplayName("首次导入插入版本，并把当前指针指向它")
    void firstImportInserts() {
        String editionId = "import-book-a";
        byte[] bytes = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        CurriculumFileImporter.ImportOutcome outcome =
                importer.importEdition(editionId, "1.0.0", bytes, "hash-a", "{}", MIGRATION_ACTOR);
        assertThat(outcome.state()).isEqualTo(CurriculumFileImporter.ImportState.INSERTED);
        assertThat(versionRows(editionId, "1.0.0")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM toolbox_textbook WHERE edition_id = ?",
                String.class, editionId)).isEqualTo(editionId + "@1.0.0");
    }

    @Test
    @DisplayName("同哈希重复导入被跳过，不新增第二行")
    void identicalReimportIsSkipped() {
        String editionId = "import-book-b";
        byte[] bytes = "{\"b\":1}".getBytes(StandardCharsets.UTF_8);
        importer.importEdition(editionId, "1.0.0", bytes, "hash-b", "{}", MIGRATION_ACTOR);
        CurriculumFileImporter.ImportOutcome second =
                importer.importEdition(editionId, "1.0.0", bytes, "hash-b", "{}", MIGRATION_ACTOR);
        assertThat(second.state()).isEqualTo(CurriculumFileImporter.ImportState.SKIPPED_IDENTICAL);
        assertThat(versionRows(editionId, "1.0.0")).isEqualTo(1);
    }

    @Test
    @DisplayName("同版本号不同哈希必须停止并报告，且库中内容保持原样")
    void hashMismatchStopsAndKeepsOriginal() {
        String editionId = "import-book-c";
        byte[] original = "原始".getBytes(StandardCharsets.UTF_8);
        importer.importEdition(editionId, "1.0.0", original, "hash-original", "{}", MIGRATION_ACTOR);

        CurriculumFileImporter.ImportOutcome conflict = importer.importEdition(
                editionId, "1.0.0", "被改过".getBytes(StandardCharsets.UTF_8), "hash-changed", "{}", MIGRATION_ACTOR);
        assertThat(conflict.state()).isEqualTo(CurriculumFileImporter.ImportState.CONFLICT_HASH_MISMATCH);
        assertThat(conflict.storedSha256()).isEqualTo("hash-original");

        // 原字节与哈希都没有被覆盖
        AdminTextbookRepository.VersionRow row = repository.findVersion(editionId, "1.0.0").orElseThrow();
        assertThat(row.contentBytes()).isEqualTo(original);
        assertThat(row.contentSha256()).isEqualTo("hash-original");
        assertThat(versionRows(editionId, "1.0.0")).isEqualTo(1);
    }

    @Test
    @DisplayName("汇总报告把哈希冲突单独列出并判定整体失败")
    void reportSeparatesConflicts() {
        String editionId = "import-book-d";
        importer.importEdition(editionId, "1.0.0", "x".getBytes(StandardCharsets.UTF_8), "hash-d", "{}",
                MIGRATION_ACTOR);
        var outcomes = List.of(
                importer.importEdition("import-book-e", "1.0.0", "y".getBytes(StandardCharsets.UTF_8), "hash-e", "{}",
                        MIGRATION_ACTOR),
                importer.importEdition(editionId, "1.0.0", "z".getBytes(StandardCharsets.UTF_8), "hash-conflict", "{}",
                        MIGRATION_ACTOR));
        CurriculumFileImporter.ImportReport report = importer.summarize(outcomes);
        assertThat(report.ok()).isFalse();
        assertThat(report.conflicts()).hasSize(1);
        assertThat(report.conflicts().get(0).editionId()).isEqualTo(editionId);
    }
}
