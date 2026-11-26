package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import com.yuyutian.mytools.toolbox.model.CoverageStatus;
import com.yuyutian.mytools.toolbox.model.ReviewStatus;
import com.yuyutian.mytools.toolbox.model.Semester;
import com.yuyutian.mytools.toolbox.model.Subject;
import com.yuyutian.mytools.toolbox.model.TextbookEdition;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.CurriculumPublisher;
import com.yuyutian.mytools.toolbox.service.admin.migration.AdminCurriculumImportService;
import com.yuyutian.mytools.toolbox.service.admin.migration.CurriculumFileImporter.ImportReport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * F14：**初始迁移必须保留清单里的 `catalogVersion`，而不是被目录计数器覆盖**。
 *
 * 设计第 8 节第 5 点原话：
 *
 * > 初始迁移保留旧 catalogVersion，catalog epoch 独立初始化，首次真实发布才生成新 catalogVersion。
 *
 * 修复前的行为：导入只遍历 `manifest.editions()`，`manifest.catalogVersion()` 被直接丢弃；
 * 数据库里的目录版本号仍是计数器派生的 `catalog-0`，于是公开目录对外报出的版本号
 * 与"当时真正发布的那个内容包"脱钩——运维拿包版本号去核对时对不上。
 *
 * 用例顺序是**语义顺序**（迁移 → 可见 → 首次发布），因此显式声明顺序：
 * 后一步依赖前一步已经在同一个库里发生。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "toolbox.curriculum.repository=database")
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CurriculumCatalogVersionMigrationTest {

    /** 夹具教材标识。 */
    private static final String EDITION = "catalog-version-book";

    /** 迁移包里声明的目录版本号；与数据库初始的 `catalog-0` 刻意不同。 */
    private static final String MANIFEST_CATALOG_VERSION = "catalog-7";

    /** 测试操作者标识。 */
    private static final String ACTOR = "migration-test";

    /** 迁移夹具根目录：内容包格式与生产完全一致。 */
    private static final Path ROOT = buildFixture();

    /**
     * 建一份能被目录校验通过的夹具：清单里的哈希必须等于正文字节的 SHA-256。
     *
     * @return 夹具根目录
     */
    private static Path buildFixture() {
        try {
            Path root = Files.createTempDirectory("tb-catalog-version-fixture");
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
                       "listTypes":["writing"],"text":"山","pinyinDisplay":"shān","pinyinSegments":[],
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
            // 清单里的 catalogVersion 是"内容包的版本"，由发布者填写；本用例用它验证是否被保留。
            String manifest = """
                    {"schemaVersion":1,"catalogVersion":"%s","publishedAt":"2026-10-05T00:00:00.000Z",
                     "note":"夹具","sourceCount":1,"editions":[
                       {"editionId":"%s","subject":"chinese","grade":1,"semester":"first",
                        "regionCodes":[],"publisher":"夹具出版社","series":"夹具语文","editionLabel":"夹具版",
                        "curriculumYear":null,"isbn":null,"sourceIds":[],"schemaVersion":1,
                        "contentVersion":"1.0.0","contentSha256":"%s","reviewStatus":"published",
                        "coverageStatus":"partial","missingScopes":[],
                        "publishedAt":"2026-10-05T00:00:00.000Z","availableEntryCount":1}]}
                    """.formatted(MANIFEST_CATALOG_VERSION, EDITION, sha);
            Files.createDirectories(root.resolve("catalog"));
            Files.writeString(root.resolve("catalog").resolve("published.json"), manifest, StandardCharsets.UTF_8);
            return root;
        } catch (Exception failure) {
            throw new IllegalStateException("夹具建立失败", failure);
        }
    }

    /**
     * 独立内存库 + 夹具内容包：本类的三个用例讲同一条时间线，不能与其它用例共享状态。
     *
     * @param registry 动态属性注册器
     */
    @DynamicPropertySource
    static void isolatedDatabaseAndCatalog(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:catalog-version-migration;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("toolbox.catalog-path", ROOT::toString);
    }

    @Autowired
    private AdminCurriculumImportService importService;

    @Autowired
    private AdminTextbookRepository repository;

    @Autowired
    private CurriculumPublisher publisher;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Order(1)
    @DisplayName("F14：迁移后库里的 catalogVersion 仍是清单里的那个值，epoch 独立保持 0")
    void migrationPreservesManifestCatalogVersion() {
        ImportReport report = importService.importFromFiles(ACTOR);

        assertThat(report.ok()).as("夹具哈希一致，导入应当成功").isTrue();
        // 修复前这里是派生出来的 `catalog-0`，清单声明的 `catalog-7` 被丢弃。
        assertThat(repository.currentCatalogVersion())
                .as("迁移必须保留清单声明的目录版本号")
                .isEqualTo(MANIFEST_CATALOG_VERSION);
        // 迁移**不是**发布：世代号必须独立初始化，不能因为导入而前进。
        assertThat(repository.currentEpoch())
                .as("epoch 独立初始化：迁移不递增世代").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT epoch FROM toolbox_curriculum_catalog WHERE catalog_key = 'current'", Long.class))
                .as("库里的 epoch 也必须是 0").isZero();
    }

    @Test
    @Order(2)
    @DisplayName("F14：公开目录接口读到的 catalogVersion 与库中一致")
    void publicCatalogServesMigratedCatalogVersion() {
        // 迁移只改数据库；公开快照要等一次重载（发布后重载或进程重启）才反映库里的值。
        publisher.reloadSnapshot(repository.currentCatalogVersion());

        ResponseEntity<String> response = rest.getForEntity("/public/v1/textbooks", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String served = TestJson.parse(response.getBody()).path("data").path("catalogVersion").asText();

        assertThat(served)
                .as("公开目录必须报出库里保存的版本号，而不是现算的 catalog-0")
                .isEqualTo(MANIFEST_CATALOG_VERSION);
        assertThat(served).isEqualTo(repository.currentCatalogVersion());
    }

    @Test
    @Order(3)
    @DisplayName("F14：首次真实发布才生成新的 catalog-N，且不退回迁移包版本号")
    void firstRealPublishGeneratesNewCatalogVersion() {
        // 标识刻意保持较短：`toolbox_textbook_draft.base_content_version` 是 VARCHAR(32)，
        // 而发布器写入的是 `editionId@contentVersion`（见下方用例说明）。
        String editionId = "catalog-ver-pub-book";
        repository.createTextbook(editionId, ACTOR);

        CurriculumPublisher.PublishResult published = publisher.publishDraft(editionId, declared(editionId),
                List.of(), List.of(), List.of(), repository.nextContentVersion(editionId), "首次真实发布", ACTOR);

        assertThat(published.snapshotReloaded()).isTrue();
        // 发布是唯一推进世代的动作：迁移保留的版本号不参与派生。
        assertThat(repository.currentEpoch()).isEqualTo(1L);
        String generated = repository.currentCatalogVersion();
        assertThat(generated).as("发布生成的新版本号按世代号拼装").isEqualTo("catalog-1");
        assertThat(generated)
                .as("发布生成的是新值，不能沿用（也不能退回）迁移包里的版本号")
                .isNotEqualTo(MANIFEST_CATALOG_VERSION);

        // 发布后公开目录立即报出新版本号，且与库一致。
        ResponseEntity<String> response = rest.getForEntity("/public/v1/textbooks", String.class);
        assertThat(TestJson.parse(response.getBody()).path("data").path("catalogVersion").asText())
                .isEqualTo(generated);
    }

    /** 构造声明元数据；版本号与哈希会被发布器覆盖。 */
    private static TextbookEdition declared(String editionId) {
        return new TextbookEdition(editionId, Subject.CHINESE, 1, Semester.FIRST, List.of(),
                "人民教育出版社", "统编语文", "2024 年修订版", 2024, null, List.of(),
                1, "0.0.0",
                "0000000000000000000000000000000000000000000000000000000000000000",
                ReviewStatus.PUBLISHED, CoverageStatus.PARTIAL, Instant.now(), Instant.now(), 0, null, List.of());
    }
}
