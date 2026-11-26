package com.yuyutian.mytools.toolbox.service.admin.curriculum;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.common.BusinessException;
import com.yuyutian.mytools.toolbox.common.ErrorCode;
import com.yuyutian.mytools.toolbox.config.CurriculumProperties;
import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import com.yuyutian.mytools.toolbox.model.TextbookEdition;
import com.yuyutian.mytools.toolbox.service.curriculum.CatalogValidationException;
import com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 教材发布器：把草稿修订发布为不可变版本，并让新版本**无需重启**对公开接口可见。
 *
 * 顺序遵循设计文档 §5.5：先做内容预校验与派生数据准备，再在事务内分配版本、写不可变版本、
 * 移动指针、递增目录世代；事务提交成功后才重建并交换目录快照。
 * 重建失败时**不交换**快照，并把教材接口标记为未就绪，绝不"宣称发布成功却一直读旧快照"。
 */
@Service
public class CurriculumPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(CurriculumPublisher.class);

    private final AdminTextbookRepository repository;
    private final CurriculumCatalog catalog;
    private final CurriculumSnapshotLoader loader;
    private final ObjectMapper objectMapper;
    private final CurriculumProperties curriculumProperties;
    private final com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder snapshotBuilder;
    private final com.yuyutian.mytools.toolbox.config.ToolboxProperties properties;

    /** 快照重建是否健康；false 表示本实例不应继续提供教材读取。 */
    private volatile boolean ready = true;

    /**
     * 创建发布器。
     *
     * @param repository   教材仓储
     * @param catalog      目录快照
     * @param loader       数据库快照加载器
     * @param objectMapper JSON 序列化器
     * @param curriculumProperties 目录来源配置（F11 发布闸门）
     * @param snapshotBuilder 来源快照构建器
     * @param properties   门面配置（提供内容根目录，用于来源快照）
     */
    public CurriculumPublisher(AdminTextbookRepository repository, CurriculumCatalog catalog,
                               CurriculumSnapshotLoader loader, ObjectMapper objectMapper,
                               CurriculumProperties curriculumProperties,
                               com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder snapshotBuilder,
                               com.yuyutian.mytools.toolbox.config.ToolboxProperties properties) {
        this.repository = repository;
        this.catalog = catalog;
        this.loader = loader;
        this.objectMapper = objectMapper;
        this.curriculumProperties = curriculumProperties;
        this.snapshotBuilder = snapshotBuilder;
        this.properties = properties;
    }

    /**
     * 发布的前置条件：目录来源必须是**可写的数据库**（F11）。
     *
     * 这是写公开目录的**唯一入口**，因此闸门也放在这里：
     * 即使将来出现绕过 `AdminTextbookService` 的调用方，也不会在 files 模式下
     * "发布成功而公开目录不变"。
     *
     * @throws BusinessException 目录来源为只读文件时抛出 `TOOLBOX_ADMIN_011`
     */
    private void requireWritableRepository() {
        if (!curriculumProperties.usesDatabase()) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_011, Map.of("reason", "repositoryIsFiles"));
        }
    }

    /**
     * 发布一册的新版本。
     *
     * @param editionId      教材标识
     * @param contentBytes   已生成的确定性正文字节
     * @param declared       声明元数据；其 contentVersion 与 contentSha256 由本方法覆盖
     * @param changeNote     变更说明，写入审计
     * @param actor          操作者用户标识
     * @return 发布结果
     */
    public PublishResult publish(String editionId, byte[] contentBytes, TextbookEdition declared,
                                 String changeNote, String actor) {
        // F11：目录来源是只读文件时**没有可写的公开目录**，明确拒绝而不是静默写库。
        requireWritableRepository();
        // F07：**先读当前版本作为 CAS 期望值**。
        // 本方法由 `AdminTextbookService.publishLocked` 在**按册锁内**调用，
        // 所以这次读与下面的写之间本进程不会插入别的发布；
        // 而**别的实例**改了它会被 CAS 发现，而不是被静默覆盖。
        String expectedCurrentVersionId = repository.findCurrentVersionId(editionId).orElse(null);
        String contentVersion = repository.nextContentVersion(editionId);
        String sha256 = sha256Hex(contentBytes);
        // 版本号与哈希由服务端计算，不接受调用方传入，避免"管理员手填版本号或哈希"。
        TextbookEdition finalised = withServerAssignedIdentity(declared, contentVersion, sha256);
        String metadataJson = serialize(finalised);
        // 来源快照在**写入版本之前**构建：它冻结的是"这一刻的来源事实"，随后随不可变版本一起落库。
        String sourceSnapshotJson = buildSourceSnapshot(editionId, contentVersion, sha256,
                snapshotBuilder.entryLocatorsFromBookBytes(contentBytes),
                declared == null ? null : declared.sourceIds(), actor);
        String versionId = repository.publishVersion(editionId, contentVersion, contentBytes, sha256,
                metadataJson, sourceSnapshotJson, actor, expectedCurrentVersionId, true);
        // F07：**在这里推进草稿基线，而不是在服务层**。
        //
        // 第 43 轮我把这一步放在 `AdminTextbookService` 里，结果经本方法
        // **直接发布**的路径（测试与其它调用方）基线永远不推进，
        // 于是随后加的基线校验把它们一律判为"过期"。
        // 放在这里才能覆盖**所有**发布路径。
        // F07：**写 versionId，不是 contentVersion**。
        //
        // 事实（第 46 轮测出来的）：`current_version_id` 存的是
        // `editionId@contentVersion`（versionId），而基线此前写的是裸的
        // `contentVersion`——**两者永远不可能相等**，
        // 于是任何"基线 == 当前版本"的校验都会无条件触发。
        // 这就是第 43、45 轮那两次失败的真正原因。
        repository.updateDraftBaseVersion("draft-" + editionId, versionId);
        // 事务已提交：目录世代与版本号已在同一事务内推进，这里**读列**取回本次发布的结果。
        String catalogVersion = repository.currentCatalogVersion();
        long catalogEpoch = repository.currentEpoch();
        // 事务已提交：重建目录快照并原子交换。
        boolean reloaded = scheduleSnapshotReload(catalogVersion);
        return new PublishResult(editionId, contentVersion, versionId, sha256, reloaded,
                catalogVersion, catalogEpoch);
    }

    /**
     * 由草稿内容生成正文字节并发布。
     *
     * 这是唯一正确的入口：正文里的 `edition.contentVersion` 必须是**服务端分配的最终版本**，
     * 调用方无法提前得知，因此不能由调用方传入成品字节。正文内 `contentSha256` 按契约留空，
     * 真实哈希单独保存在版本元数据与响应中，避免自引用哈希。
     *
     * @param editionId 教材标识
     * @param declared  声明元数据；其 contentVersion 与 contentSha256 由本方法覆盖
     * @param units     单元
     * @param lessons   课文
     * @param entries   条目
     * @param changeNote 变更说明
     * @param actor     操作者用户标识
     * @return 发布结果
     */
    public PublishResult publishDraft(String editionId, TextbookEdition declared,
                                      List<com.yuyutian.mytools.toolbox.model.CurriculumUnit> units,
                                      List<com.yuyutian.mytools.toolbox.model.CurriculumLesson> lessons,
                                      List<com.yuyutian.mytools.toolbox.model.CurriculumEntry> entries,
                                      String contentVersion, String changeNote, String actor) {
        // F11：目录来源是只读文件时**没有可写的公开目录**，明确拒绝而不是静默写库。
        requireWritableRepository();
        // F07：**版本号由调用方分配并传进来，这里不再自己分一次**。
        //
        // 此前服务和发布器各调用一次 `nextContentVersion`，于是同一次发布里
        // 出现了**两个独立的版本号**：一个写进正文的 `entryVersion`，
        // 一个写进元数据的 `contentVersion`。两者可能不同，
        // 而且两次分配之间没有任何互斥——并发发布同一册时可能拿到同一个号。
        //
        // 现在只有**一个**分配点（`AdminTextbookService.publish`），
        // 正文版本与内容版本必然一致。
        // 先分配版本，再生成正文：正文里的 edition 块必须与将要写入的元数据完全一致。
        TextbookEdition finalised = withServerAssignedIdentity(declared, contentVersion, "");
        byte[] contentBytes = serializeBook(finalised, units, lessons, entries);
        String sha256 = sha256Hex(contentBytes);
        // 真实哈希写入元数据（正文内保持为空，避免自引用）。
        TextbookEdition metadata = withServerAssignedIdentity(declared, contentVersion, sha256);
        // 条目定位取自**已映射的公开条目**：这样快照里的 `{sourceId, locator, page}` 与
        // 紧接着写进正文的那一份逐字一致（同一次映射的产物）。
        String sourceSnapshotJson = buildSourceSnapshot(editionId, contentVersion, sha256,
                snapshotBuilder.entryLocatorsFromEntries(entries),
                declared == null ? null : declared.sourceIds(), actor);
        String versionId = repository.publishVersion(editionId, contentVersion, contentBytes, sha256,
                serialize(metadata), sourceSnapshotJson, actor);
        // F07：与 `publish` 一致——**所有**发布路径都要推进草稿基线，
        // 否则随后加的基线校验会把这条路径判为"过期"。
        // F07：**写 versionId，不是 contentVersion**。
        //
        // 事实（第 46 轮测出来的）：`current_version_id` 存的是
        // `editionId@contentVersion`（versionId），而基线此前写的是裸的
        // `contentVersion`——**两者永远不可能相等**，
        // 于是任何"基线 == 当前版本"的校验都会无条件触发。
        // 这就是第 43、45 轮那两次失败的真正原因。
        repository.updateDraftBaseVersion("draft-" + editionId, versionId);
        // 与 `publish` 同理：发布响应里的目录版本与世代必须来自**本次发布后的库值**，
        // 而不是由 epoch 现算（迁移来的初始 catalogVersion 与 epoch 没有派生关系）。
        String catalogVersion = repository.currentCatalogVersion();
        long catalogEpoch = repository.currentEpoch();
        boolean reloaded = scheduleSnapshotReload(catalogVersion);
        return new PublishResult(editionId, contentVersion, versionId, sha256, reloaded,
                catalogVersion, catalogEpoch);
    }

    /**
     * 构建版本级来源快照 JSON。
     *
     * 目录基线**只从上一版本的快照继承**，绝不在这里读本机 manifest：
     * 新发布的管理教材并不来自文件清单，若把当前文件清单的 `catalogVersion`
     * 贴到它身上，就成了一次"看起来有据、实际无据"的归属。
     *
     * @param editionId      教材标识
     * @param contentVersion 内容版本号
     * @param sha256         正文哈希
     * @param locators       条目定位
     * @param sourceIds      教材级来源标识
     * @param actor          操作者
     * @return 确定性快照 JSON
     */
    private String buildSourceSnapshot(String editionId, String contentVersion, String sha256,
                                       List<com.yuyutian.mytools.toolbox.service.admin.migration
                                               .SourceSnapshotBuilder.EntryLocator> locators,
                                       List<String> sourceIds, String actor) {
        var capture = new com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder
                .SnapshotCapture(java.time.Instant.now().toString(), actor,
                "publish:" + editionId + "@" + contentVersion, "publish", null);
        var request = new com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder
                .SnapshotRequest(java.nio.file.Path.of(properties.catalogPath()), editionId, contentVersion,
                sha256, sourceIds, locators, manualSourcesFromDraft(editionId), capture,
                inheritedBaseline(editionId));
        return snapshotBuilder.build(request).json();
    }

    /**
     * 从草稿级快照里取出管理员**手工填写**的来源，并入版本快照。
     *
     * 手工来源（界面上直接录入的标题／链接／页码）在草稿保存时已经冻结；
     * 发布必须把它们带进不可变版本，否则"草稿里有、发布后就没了"。
     * 只取 `origin=manual` 的记录：内容根目录来源会在版本快照里按当时的文件重新解析并冻结。
     *
     * @param editionId 教材标识
     * @return 手工来源；没有时为空表
     */
    private List<com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.ManualSource>
            manualSourcesFromDraft(String editionId) {
        String snapshot = repository.findDraft("draft-" + editionId)
                .map(AdminTextbookRepository.DraftRow::sourceMetadataJson).orElse(null);
        if (snapshot == null || snapshot.isBlank()) {
            return List.of();
        }
        try {
            List<com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.ManualSource> out =
                    new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode node : objectMapper.readTree(snapshot).path("sources")) {
                if (!"manual".equals(node.path("origin").asText())) {
                    continue;
                }
                com.fasterxml.jackson.databind.JsonNode record = node.path("record");
                out.add(new com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.ManualSource(
                        textOf(record, "sourceId"), textOf(record, "kind"), textOf(record, "title"),
                        textOf(record, "url"), textOf(record, "publisher"), textOf(record, "isbn"),
                        textOf(record, "editionLabel"), textOf(record, "locator"),
                        record.path("page").isNumber() ? record.path("page").asInt() : null,
                        textOf(record, "notes")));
            }
            return out;
        } catch (Exception unparsable) {
            // 草稿快照读不出来不应阻断发布：版本快照仍会按内容根目录与正文重建。
            return List.of();
        }
    }

    /** 取节点文本；缺失或空串返回 null。 */
    private static String textOf(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    /**
     * 从上一版本的来源快照继承目录基线。
     *
     * @param editionId 教材标识
     * @return 继承到的基线；没有可继承内容时返回"明确缺失"的基线
     */
    private com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.ManifestBaseline
            inheritedBaseline(String editionId) {
        String currentVersionId = repository.findCurrentVersionId(editionId).orElse(null);
        if (currentVersionId == null) {
            return absentBaseline("noPublishedBaseline");
        }
        String prefix = editionId + "@";
        String contentVersion = currentVersionId.startsWith(prefix)
                ? currentVersionId.substring(prefix.length()) : currentVersionId;
        String previous = repository.findVersionSourceMetadata(editionId, contentVersion).orElse(null);
        if (previous == null || previous.isBlank()) {
            return absentBaseline("previousVersionHasNoSnapshot");
        }
        try {
            var node = objectMapper.readTree(previous).path("manifestBaseline");
            if (node.isMissingNode() || node.isNull()) {
                return absentBaseline("previousVersionHasNoManifestBaseline");
            }
            return objectMapper.treeToValue(node,
                    com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.ManifestBaseline.class);
        } catch (Exception unparsable) {
            // 继承失败不是发布失败：按"没有基线"处理并留下原因，不阻断写入。
            return absentBaseline("previousSnapshotUnparsable");
        }
    }

    /** 构造"明确缺失"的目录基线。 */
    private static com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.ManifestBaseline
            absentBaseline(String reason) {
        return new com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.ManifestBaseline(
                false, reason, null, null, null, null, 0, null, new java.util.TreeMap<>());
    }

    /**
     * 生成确定性正文字节。
     *
     * @param edition 已定稿的版本元数据
     * @param units   单元
     * @param lessons 课文
     * @param entries 条目
     * @return UTF-8 正文字节
     */
    private byte[] serializeBook(TextbookEdition edition,
                                 List<com.yuyutian.mytools.toolbox.model.CurriculumUnit> units,
                                 List<com.yuyutian.mytools.toolbox.model.CurriculumLesson> lessons,
                                 List<com.yuyutian.mytools.toolbox.model.CurriculumEntry> entries) {
        try {
            return objectMapper.writeValueAsBytes(new com.yuyutian.mytools.toolbox.model.PublishedBook(
                    edition, units == null ? List.of() : units,
                    lessons == null ? List.of() : lessons, entries == null ? List.of() : entries));
        } catch (Exception failure) {
            throw new CatalogValidationException("book serialization failed", failure);
        }
    }

    /**
     * 由数据库重建目录快照并交换。
     * 失败时保留旧快照、把 ready 置为 false，并抛出异常让调用方看到真实结果。
     *
     * @param catalogVersion 目录版本号
     * @return 重建成功时为 true
     */
    /**
     * 把"交换内存目录快照"**推迟到外层事务提交之后**。
     *
     * **修的是什么**：此前发布路径在事务内直接 `reloadSnapshot`。
     * 如果同一事务随后失败回滚，**数据库回滚了、内存快照没有回退** ——
     * 公开接口会继续返回 200 并提供那个**从未提交**的版本
     * （2026-10-07 实测：served `catalog-5` 而库里是 `catalog-4`，
     * 按它读 1.0.0 反而 404）。这是"失败后对外仍然可用"的一致性缺陷，
     * 比"发布失败"本身更危险。
     *
     * **为什么返回 boolean 仍然准确**：注册了 afterCommit 之后，
     * 能走到 `return` 就说明事务会提交（提交失败会直接抛给调用方，
     * 不返回结果）；提交后若交换失败，`reloadSnapshot` 自己会
     * `markNotReady()`，公开接口转为 503 而不是继续服务旧内容。
     *
     * 没有活动事务时（例如单元测试直接调用）行为与原来一致：立即交换。
     *
     * @param catalogVersion 本次发布后的目录版本
     * @return 是否已安排（或已完成）快照交换
     */
    private boolean scheduleSnapshotReload(String catalogVersion) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    reloadSnapshot(catalogVersion);
                }
            });
            return true;
        }
        return reloadSnapshot(catalogVersion);
    }

    public synchronized boolean reloadSnapshot(String catalogVersion) {
        try {
            catalog.reloadFromVersions(catalogVersion, loader.loadAll());
            ready = true;
            // 恢复公开读取：目录自己持有这个状态，读端才能真的消费它。
            catalog.markReady();
            return true;
        } catch (Exception failure) {
            // 提交成功但本实例重载失败：停止教材接口就绪，等待重建，不假装成功。
            ready = false;
            // 让读端真的看到不可用：此前只设了发布器自己的 ready，
            // 而公开读取走的是目录、根本不看它——于是失败后系统继续服务旧快照。
            catalog.markNotReady();
            LOG.error("curriculum snapshot reload failed, marking catalog not ready", failure);
            throw new CatalogValidationException("curriculum snapshot reload failed", failure);
        }
    }

    /**
     * 返回本实例的教材读取是否就绪。
     *
     * @return 就绪时为 true
     */
    public boolean isReady() {
        return ready;
    }

    /** 覆盖为服务端分配的版本号与哈希。 */
    private static TextbookEdition withServerAssignedIdentity(TextbookEdition declared, String contentVersion,
                                                             String sha256) {
        return new TextbookEdition(declared.editionId(), declared.subject(), declared.grade(), declared.semester(),
                declared.regionCodes(), declared.publisher(), declared.series(), declared.editionLabel(),
                declared.curriculumYear(), declared.isbn(), declared.sourceIds(), declared.schemaVersion(),
                contentVersion, sha256, declared.reviewStatus(), declared.coverageStatus(), declared.verifiedAt(),
                declared.publishedAt(), declared.availableEntryCount(), declared.expectedEntryCount(),
                declared.missingScopes());
    }

    /** 序列化元数据。 */
    private String serialize(TextbookEdition edition) {
        try {
            return objectMapper.writeValueAsString(edition);
        } catch (Exception failure) {
            throw new CatalogValidationException("edition metadata serialization failed", failure);
        }
    }

    /** 计算字节级 SHA-256 十六进制。 */
    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /**
     * 发布结果。
     *
     * @param editionId      教材标识
     * @param contentVersion 服务端分配的内容版本号
     * @param versionId      版本标识
     * @param contentSha256  正文哈希
     * @param snapshotReloaded 目录快照是否已重建成功
     * @param catalogVersion 本次发布后 `toolbox_curriculum_catalog.catalog_version` 的**库值**
     *                       （设计第 391 行：与 `catalogEpoch` 并存于发布响应）
     * @param catalogEpoch   本次发布后 `toolbox_curriculum_catalog.epoch` 的**库值**
     *                       （设计第 392 行：以字符串形式出现在响应里）
     */
    public record PublishResult(String editionId, String contentVersion, String versionId, String contentSha256,
                                boolean snapshotReloaded, String catalogVersion, long catalogEpoch) {
    }
}
