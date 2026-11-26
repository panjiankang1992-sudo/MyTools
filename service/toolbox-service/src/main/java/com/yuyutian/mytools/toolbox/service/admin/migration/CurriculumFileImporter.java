package com.yuyutian.mytools.toolbox.service.admin.migration;

import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 初始数据迁移：把已发布的文件仓储内容幂等导入数据库。
 *
 * 幂等规则（设计文档 §8 第 2 点）：
 * - 同 (editionId, contentVersion) 且哈希相同 → 跳过，不新增、不改动；
 * - 同 (editionId, contentVersion) 但哈希不同 → **停止并报告**，绝不覆盖既有已发布内容；
 * - 不存在 → 插入不可变版本，并建立教材记录与当前指针。
 */
@Service
public class CurriculumFileImporter {

    private final AdminTextbookRepository repository;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * 创建导入器。
     *
     * @param repository 教材仓储
     */
    public CurriculumFileImporter(AdminTextbookRepository repository,
                                  com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等导入一册的原始字节。
     *
     * @param editionId      教材标识
     * @param contentVersion 内容版本号
     * @param contentBytes   正文原始字节
     * @param contentSha256  正文哈希
     * @param metadataJson   版本元数据
     * @param actor          操作者标识；迁移时使用固定的迁移主体
     * @return 该册的导入结果
     */
    @Transactional
    public ImportOutcome importEdition(String editionId, String contentVersion, byte[] contentBytes,
                                       String contentSha256, String metadataJson, String actor) {
        return importEdition(editionId, contentVersion, contentBytes, contentSha256, metadataJson, null, actor);
    }

    /**
     * 幂等导入一册的原始字节，并写入**导入时的来源快照**。
     *
     * 迁移正是"导入目录基线"最该被记录的场合：清单的 `catalogVersion` 与原始 manifest
     * 必须随版本冻结，否则日后无法证明"这批内容来自哪一版目录"。
     *
     * @param editionId          教材标识
     * @param contentVersion     内容版本号
     * @param contentBytes       正文原始字节
     * @param contentSha256      正文哈希
     * @param metadataJson       版本元数据
     * @param sourceMetadataJson 来源快照；可为 null
     * @param actor              操作者标识；迁移时使用固定的迁移主体
     * @return 该册的导入结果
     */
    @Transactional
    public ImportOutcome importEdition(String editionId, String contentVersion, byte[] contentBytes,
                                       String contentSha256, String metadataJson, String sourceMetadataJson,
                                       String actor) {
        var existing = repository.findVersion(editionId, contentVersion);
        if (existing.isPresent()) {
            String storedHash = existing.get().contentSha256();
            if (storedHash.equals(contentSha256)) {
                return new ImportOutcome(editionId, contentVersion, ImportState.SKIPPED_IDENTICAL, storedHash);
            }
            // 哈希不同意味着同一版本号的正文已经变了：必须停下来让人判断，不能覆盖。
            return new ImportOutcome(editionId, contentVersion, ImportState.CONFLICT_HASH_MISMATCH, storedHash);
        }
        if (!repository.textbookExists(editionId)) {
            repository.createTextbook(editionId, actor);
        }
        // F14：用 `importVersion` 而不是 `publishVersion`——迁移导入的是**文件仓储里早已发布过**
        // 的内容，不能推进目录世代（否则清单声明的 catalogVersion 会被计数器覆盖）。
        repository.importVersion(editionId, contentVersion, contentBytes, contentSha256, metadataJson,
                sourceMetadataJson, actor);
        // F03：把**已发布的内容**无损地变成可编辑草稿。
        //
        // 此前这里只发布版本，草稿仍是 createTextbook 留下的 `{}`——
        // 管理员打开这册教材看到的是**空白页**，而且 base 为空，
        // 连"基于哪一版在改"都不知道。
        //
        // 这里能做得无损，是因为**原始字节就在手上**：不需要从模型反推，
        // 直接按结构把 book 转成 draft 即可。
        // 已经编辑过的草稿不会被覆盖（见 installInitialDraft 的条件）。
        // 基线与 `current_version_id` 必须是**同一种标识**（versionId）。
        //
        // 这里此前写的是裸 `contentVersion`，而 `publishLocked` 的锁内基线校验
        // 拿它与 `current_version_id`（`editionId@contentVersion`）比较——
        // 两者永远不相等，于是**任何迁移进来的教材一旦被编辑就再也发不出去**（409 baselineMismatch）。
        // 这与 `CurriculumPublisher.updateDraftBaseVersion` 写入 versionId 是同一条事实。
        repository.installInitialDraft("draft-" + editionId, editionId + "@" + contentVersion,
                initialDraft(editionId, contentBytes), sourceMetadataJson, actor);
        return new ImportOutcome(editionId, contentVersion, ImportState.INSERTED, contentSha256);
    }

    private String initialDraft(String editionId, byte[] contentBytes) {
        try {
            return new com.yuyutian.mytools.toolbox.service.admin.curriculum.PublishedBookDraftConverter(objectMapper)
                    .convert(editionId, contentBytes);
        } catch (IllegalArgumentException unreadable) {
            // 兼容旧导入的空草稿行为；三方同步另行严格校验不可变版本，绝不拿空草稿覆盖内容。
            return "{}";
        }
    }

    /**
     * 汇总多次导入的结果；只要出现哈希冲突即视为整体失败。
     *
     * @param outcomes 各册结果
     * @return 迁移报告
     */
    public ImportReport summarize(List<ImportOutcome> outcomes) {
        List<ImportOutcome> conflicts = new ArrayList<>();
        for (ImportOutcome outcome : outcomes) {
            if (outcome.state() == ImportState.CONFLICT_HASH_MISMATCH) {
                conflicts.add(outcome);
            }
        }
        return new ImportReport(List.copyOf(outcomes), List.copyOf(conflicts), conflicts.isEmpty());
    }

    /** 单册导入状态。 */
    public enum ImportState {
        /** 新插入。 */
        INSERTED,
        /** 已存在且哈希一致，跳过。 */
        SKIPPED_IDENTICAL,
        /** 已存在但哈希不同，停止并报告。 */
        CONFLICT_HASH_MISMATCH
    }

    /**
     * 单册导入结果。
     *
     * @param editionId      教材标识
     * @param contentVersion 内容版本号
     * @param state          导入状态
     * @param storedSha256   库中现有的哈希；未插入时为空
     */
    public record ImportOutcome(String editionId, String contentVersion, ImportState state, String storedSha256) {
    }

    /**
     * 迁移报告。
     *
     * @param outcomes  各册结果
     * @param conflicts 哈希冲突项
     * @param ok        无冲突时为 true
     */
    public record ImportReport(List<ImportOutcome> outcomes, List<ImportOutcome> conflicts, boolean ok) {
    }
}
