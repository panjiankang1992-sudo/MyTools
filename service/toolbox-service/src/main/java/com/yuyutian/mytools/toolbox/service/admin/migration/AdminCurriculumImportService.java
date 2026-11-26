package com.yuyutian.mytools.toolbox.service.admin.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.config.ToolboxProperties;
import com.yuyutian.mytools.toolbox.model.CatalogManifest;
import com.yuyutian.mytools.toolbox.model.TextbookEdition;
import com.yuyutian.mytools.toolbox.service.admin.migration.CurriculumFileImporter.ImportOutcome;
import com.yuyutian.mytools.toolbox.service.admin.migration.CurriculumFileImporter.ImportReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 把**文件仓储里已发布的教材**导入数据库。
 *
 * 为什么需要它：管理端发布写数据库，而公开来源默认读文件；
 * 要切换到数据库来源（让管理端发布对外可见），必须先让库里有现有内容——
 * 否则切换的瞬间公开站会变空。
 *
 * `CurriculumFileImporter` 有导入能力与测试，但**此前没有任何生产入口**，
 * 因此这条迁移路径实际不可执行。本服务提供那个入口。
 *
 * **安全性靠自算哈希**：读进来的字节自己算 SHA-256，与清单声明的比对，
 * 不一致就拒绝导入。这样"另一条读取路径"不会成为绕过目录校验的洞。
 */
@Service
public class AdminCurriculumImportService {

    private static final Logger LOG = LoggerFactory.getLogger(AdminCurriculumImportService.class);

    private final CurriculumFileImporter importer;
    private final ToolboxProperties properties;
    private final ObjectMapper objectMapper;
    private final com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository repository;
    private final SourceSnapshotBuilder snapshotBuilder;

    /**
     * 创建导入服务。
     *
     * @param importer     逐册导入器
     * @param properties   门面配置（提供内容目录）
     * @param objectMapper JSON 解析器
     * @param repository   教材仓储（F14：安装迁移包的目录版本号）
     * @param snapshotBuilder 来源快照构建器（冻结导入目录基线与来源记录）
     */
    public AdminCurriculumImportService(CurriculumFileImporter importer, ToolboxProperties properties,
                                        ObjectMapper objectMapper,
                                        com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository repository,
                                        SourceSnapshotBuilder snapshotBuilder) {
        this.importer = importer;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.snapshotBuilder = snapshotBuilder;
    }

    /**
     * 把文件仓储中清单声明的每一册导入数据库。
     *
     * **可重复运行**：`importEdition` 对同版本同哈希返回 `SKIPPED_IDENTICAL`，
     * 对同版本异哈希返回冲突而**不覆盖**已发布内容。
     *
     * F14：迁移还必须**保留清单里的 `catalogVersion`**。
     * 否则导入之后数据库里的目录版本号是计数器派生的 `catalog-0`，
     * 与文件包声明的版本号脱钩——公开目录会对外报一个从未存在过的版本。
     * 保留只在**尚未发生真实发布**时生效（见 `installInitialCatalogVersion`），
     * 因此不会把迁移行为泄漏到发布路径。
     *
     * @param actor 操作者（写入审计与版本记录）
     * @return 迁移报告
     */
    public ImportReport importFromFiles(String actor) {
        Path root = Path.of(properties.catalogPath());
        Path manifestFile = root.resolve("catalog").resolve("published.json");
        CatalogManifest manifest;
        try {
            manifest = objectMapper.readValue(manifestFile.toFile(), CatalogManifest.class);
        } catch (Exception failure) {
            throw new IllegalStateException("无法读取已发布清单：" + manifestFile, failure);
        }
        List<ImportOutcome> outcomes = new ArrayList<>();
        for (TextbookEdition declared : manifest.editions()) {
            outcomes.add(importOne(root, declared, actor));
        }
        ImportReport report = importer.summarize(outcomes);
        // 只有**整批导入都没有冲突**时才认领清单版本号：
        // 部分冲突意味着库里的内容并不等于清单描述的那个包，
        // 此时把清单版本号写上去等于对外宣称了一个并不完整的目录版本。
        if (report.ok() && manifest.catalogVersion() != null && !manifest.catalogVersion().isBlank()) {
            int adopted = repository.installInitialCatalogVersion(manifest.catalogVersion());
            if (adopted == 0) {
                // 已经真实发布过：保持发布生成的新版本号，不倒退。
                LOG.info("curriculum catalog version already advanced by publish, keep database value, manifest={}",
                        manifest.catalogVersion());
            }
        }
        return report;
    }

    /** 导入一册；哈希不符时拒绝，而不是把未经校验的内容写进库。 */
    private ImportOutcome importOne(Path root, TextbookEdition declared, String actor) {
        Path bookFile = root.resolve("textbooks").resolve(declared.editionId())
                .resolve(declared.contentVersion()).resolve("book.json");
        try {
            byte[] bytes = Files.readAllBytes(bookFile);
            String actual = sha256Hex(bytes);
            if (!actual.equalsIgnoreCase(declared.contentSha256())) {
                // 声明与实物不符：停下来让人判断，绝不写入。
                LOG.warn("curriculum import hash mismatch, editionId={}, version={}",
                        declared.editionId(), declared.contentVersion());
                return new ImportOutcome(declared.editionId(), declared.contentVersion(),
                        CurriculumFileImporter.ImportState.CONFLICT_HASH_MISMATCH, actual);
            }
            // 导入正是"导入目录基线"最该被冻结的场合：清单的 catalogVersion、原始 manifest
            // 文本与 SHA-256、以及各条来源对象都随版本落库，日后可证明这批内容来自哪一版目录。
            var capture = new SourceSnapshotBuilder.SnapshotCapture(java.time.Instant.now().toString(), actor,
                    "import:" + declared.editionId() + "@" + declared.contentVersion(), "import", null);
            SourceSnapshotBuilder.SnapshotResult snapshot = snapshotBuilder.build(
                    new SourceSnapshotBuilder.SnapshotRequest(root, declared.editionId(), declared.contentVersion(),
                            actual, declared.sourceIds(), snapshotBuilder.entryLocatorsFromBookBytes(bytes),
                            List.of(), capture, null));
            return importer.importEdition(declared.editionId(), declared.contentVersion(), bytes, actual,
                    objectMapper.writeValueAsString(declared), snapshot.json(), actor);
        } catch (Exception failure) {
            throw new IllegalStateException("导入失败：" + declared.editionId(), failure);
        }
    }

    /** 计算字节的 SHA-256 十六进制值。 */
    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
