package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import com.yuyutian.mytools.toolbox.service.admin.migration.AdminCurriculumImportService;
import com.yuyutian.mytools.toolbox.service.admin.migration.CurriculumFileImporter.ImportReport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 把文件仓储的已发布教材导入数据库（部署手册第 2 步）。
 *
 * 这是"把公开来源切到 database"的**前提**：不先导入，切换的瞬间公开站会变空。
 *
 * 三条性质必须成立：**能导入**、**可重复运行**、**哈希不符时拒绝**。
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminCurriculumImportServiceTest {

    private static final String EDITION = "import-fixture-book";
    private static final Path ROOT = buildFixture();

    /** 建一份能被目录校验通过的夹具：清单里的哈希必须等于正文字节的 SHA-256。 */
    private static Path buildFixture() {
        try {
            Path root = Files.createTempDirectory("tb-import-fixture");
            String book = """
                    {"edition":{"editionId":"%s","subject":"chinese","grade":1,"semester":"first",
                      "regionCodes":[],"publisher":"夹具出版社","series":"夹具语文","editionLabel":"夹具版",
                      "curriculumYear":null,"isbn":null,"sourceIds":[],"schemaVersion":1,
                      "contentVersion":"1.0.0","contentSha256":"","reviewStatus":"published",
                      "coverageStatus":"partial","missingScopes":[],
                      "publishedAt":"2026-10-05T00:00:00.000Z","availableEntryCount":1},
                     "units":[{"unitId":"%s-u01","editionId":"%s","order":1,"title":"第一单元"}],
                     "lessons":[],
                     "entries":[{"entryId":"%s-0001","entryVersion":"1.0.0","editionId":"%s",
                       "unitId":"%s-u01","lessonId":null,"order":0,"form":"character",
                       "listTypes":["writing"],"text":"山","pinyinDisplay":null,"pinyinSegments":[],
                       "meaning":"山的义项","partOfSpeech":"名词","senseId":"sense-1",
                       "context":"远上寒山石径斜","promptContext":"山 prompts",
                       "acceptableAnswers":["山岳"],"sourceRefs":[{"sourceId":"src-1","locator":"p.12","page":12}],
                       "reviewStatus":"reviewed","blockingIssues":[]}]}
                    """.formatted(EDITION, EDITION, EDITION, EDITION, EDITION, EDITION);
            byte[] bytes = book.getBytes(StandardCharsets.UTF_8);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            Path bookFile = root.resolve("textbooks").resolve(EDITION).resolve("1.0.0").resolve("book.json");
            Files.createDirectories(bookFile.getParent());
            Files.write(bookFile, bytes);
            String manifest = """
                    {"schemaVersion":1,"catalogVersion":"2026-10-05.1","publishedAt":"2026-10-05T00:00:00.000Z",
                     "note":"夹具","sourceCount":1,"editions":[
                       {"editionId":"%s","subject":"chinese","grade":1,"semester":"first",
                        "regionCodes":[],"publisher":"夹具出版社","series":"夹具语文","editionLabel":"夹具版",
                        "curriculumYear":null,"isbn":null,"sourceIds":[],"schemaVersion":1,
                        "contentVersion":"1.0.0","contentSha256":"%s","reviewStatus":"published",
                        "coverageStatus":"partial","missingScopes":[],
                        "publishedAt":"2026-10-05T00:00:00.000Z","availableEntryCount":1}]}
                    """.formatted(EDITION, sha);
            Files.createDirectories(root.resolve("catalog"));
            Files.writeString(root.resolve("catalog").resolve("published.json"), manifest, StandardCharsets.UTF_8);
            return root;
        } catch (Exception failure) {
            throw new IllegalStateException("夹具建立失败", failure);
        }
    }

    @DynamicPropertySource
    static void catalogPath(DynamicPropertyRegistry registry) {
        registry.add("toolbox.catalog-path", () -> ROOT.toString());
    }

    @Autowired
    private AdminCurriculumImportService service;

    @Autowired
    private AdminTextbookRepository repository;

    @Test
    @DisplayName("导入把文件仓储的已发布教材写进数据库（部署手册第 2 步）")
    void importInsertsPublishedEditions() {
        ImportReport report = service.importFromFiles("migration-test");
        assertThat(report.ok()).as("哈希一致时导入应当成功").isTrue();
        assertThat(report.conflicts()).isEmpty();
        assertThat(report.outcomes()).hasSize(1);
        assertThat(report.outcomes().get(0).editionId()).isEqualTo(EDITION);
    }

    @Test
    @DisplayName("F03：导入后草稿必须含有正文，而不是空 {}")
    void importLeavesEditableDraft() {
        // 审查复现：现有教材导入 DB 后，管理草稿是 `{}`——**不是原教材的可编辑内容**。
        // 于是管理员打开这册教材想改一个字，看到的是空白页，
        // 而且 base_content_version 为空，连"基于哪一版在改"都不知道。
        //
        // 这条断言的是**迁移之后能不能真的编辑**，不是"草稿行存在"。
        service.importFromFiles("migration-test");
        var draft = repository.findDraft("draft-" + EDITION).orElseThrow();
        String editable = draft.editableJson();
        assertThat(editable)
                .as("导入必须留下可编辑的正文，而不是空对象")
                .isNotNull()
                .isNotEqualTo("{}");
        assertThat(editable)
                .as("草稿必须带上原教材的单元，否则管理员看到的是空白页")
                .contains(EDITION + "-u01");
    }

    @Test
    @DisplayName("F03：编辑器暂不暴露的条目字段必须原样保留，不能被清空")
    void importPreservesUneditedEntryFields() {
        // 这些字段当前界面上没有编辑入口，正确语义是"**原样往返**"。
        // 此前它们既不在草稿里、映射回正文时又被写死为 null／空表——
        // 于是管理员改一个字并发布，就把整册书的词性、义项、上下文、
        // 答案与来源**静默清空**了，而且不会报错。
        service.importFromFiles("migration-test");
        var draft = repository.findDraft("draft-" + EDITION).orElseThrow();
        String editable = draft.editableJson();
        for (String expected : new String[]{
                "山的义项", "名词", "sense-1", "远上寒山石径斜", "山 prompts", "山岳", "src-1"}) {
            assertThat(editable)
                    .as("字段值「%s」在导入后必须还在草稿里", expected)
                    .contains(expected);
        }
    }

    @Test
    @DisplayName("导入可重复运行：第二次同一份内容不会重复写入")
    void importIsIdempotent() {
        service.importFromFiles("migration-test");
        ImportReport again = service.importFromFiles("migration-test");
        // 幂等是生产上的必要性质：第一次若失败，运维要能放心重跑。
        assertThat(again.ok()).isTrue();
        assertThat(again.outcomes().get(0).state().name()).isEqualTo("SKIPPED_IDENTICAL");
    }

    @Test
    @DisplayName("正文与清单哈希不符时拒绝导入，不把未校验的内容写进库")
    void importRefusesWhenHashDoesNotMatch() throws Exception {
        // 只改正文、不改清单哈希：这正是"另一条读取路径"可能绕过校验的情形。
        Path bookFile = ROOT.resolve("textbooks").resolve(EDITION).resolve("1.0.0").resolve("book.json");
        byte[] original = Files.readAllBytes(bookFile);
        try {
            Files.writeString(bookFile, new String(original, StandardCharsets.UTF_8).replace("山", "水"),
                    StandardCharsets.UTF_8);
            ImportReport report = service.importFromFiles("migration-test");
            assertThat(report.outcomes().get(0).state().name())
                    .as("哈希不符必须被拒绝").isEqualTo("CONFLICT_HASH_MISMATCH");
            assertThat(report.ok()).as("出现冲突时整体不通过").isFalse();
        } finally {
            Files.write(bookFile, original);
        }
    }
}
