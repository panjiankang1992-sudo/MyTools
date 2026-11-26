package com.yuyutian.mytools.toolbox.service.admin.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import com.yuyutian.mytools.toolbox.model.TextbookEdition;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.AdminTextbookService;
import com.yuyutian.mytools.toolbox.service.admin.migration.CurriculumFileImporter.ImportState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 从**导出的完整包**恢复到数据库（F14 的另一半）。
 *
 * 为什么需要它：此前只有"能导出"和"文件仓储 → 数据库"的导入，
 * **没有任何代码能把一个导出包恢复进空库**——于是"回退有依据"这句话没有可执行的落点。
 *
 * 恢复逐项对账，而不是"写进去就算成功"：
 * - 字节哈希、长度、来源快照指纹全部**先校验后写入**，不符即记为冲突且**不覆盖**已存在版本；
 * - 恢复后重新读库，核对正文哈希、来源快照指纹、历史版本数量与 **current 指针**；
 * - 目录基线 `catalogVersion` 从包内快照读出并如实报告——本服务**不改动**
 *   发布路径的 `catalogVersion`／epoch 分配逻辑。
 */
@Service
public class AdminCurriculumRestoreService {

    private static final Logger LOG = LoggerFactory.getLogger(AdminCurriculumRestoreService.class);

    private final CurriculumFileImporter importer;
    private final AdminTextbookRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * 创建恢复服务。
     *
     * @param importer     逐册导入器（幂等：同版本同哈希跳过、异哈希冲突）
     * @param repository   教材仓储
     * @param objectMapper JSON 解析器
     */
    public AdminCurriculumRestoreService(CurriculumFileImporter importer, AdminTextbookRepository repository,
                                         ObjectMapper objectMapper) {
        this.importer = importer;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 把导出包恢复到当前数据库。
     *
     * @param pkg   导出包
     * @param actor 操作者
     * @return 恢复报告与逐册对账
     */
    @Transactional
    public RestoreReport restore(AdminTextbookService.ExportPackage pkg, String actor) {
        if (pkg == null || pkg.versions() == null) {
            // 空包不是"无可恢复内容"，而是调用方给错了东西：明确拒绝而不是静默返回空报告。
            throw new com.yuyutian.mytools.toolbox.common.BusinessException(
                    com.yuyutian.mytools.toolbox.common.ErrorCode.TOOLBOX_ADMIN_005,
                    Map.of("reason", "exportPackageEmpty"));
        }
        Map<String, List<AdminTextbookService.ExportPackageEntry>> byEdition = new LinkedHashMap<>();
        for (AdminTextbookService.ExportPackageEntry entry : pkg.versions()) {
            byEdition.computeIfAbsent(entry.editionId(), key -> new ArrayList<>()).add(entry);
        }
        int restoredVersions = 0;
        int skippedVersions = 0;
        List<String> conflicts = new ArrayList<>();
        List<EditionReconciliation> reconciliations = new ArrayList<>();
        for (Map.Entry<String, List<AdminTextbookService.ExportPackageEntry>> group : byEdition.entrySet()) {
            List<AdminTextbookService.ExportPackageEntry> versions = new ArrayList<>(group.getValue());
            // 按版本号升序导入：这样最后导入的是最高版本，current 指针只需在必要时再摆一次。
            versions.sort(Comparator.comparing(AdminTextbookService.ExportPackageEntry::contentVersion,
                    AdminCurriculumRestoreService::compareVersions));
            for (AdminTextbookService.ExportPackageEntry entry : versions) {
                RestoreOutcome outcome = restoreOne(entry, actor);
                if (outcome.conflict()) {
                    conflicts.add(entry.editionId() + "@" + entry.contentVersion() + ":" + outcome.reason());
                } else if (outcome.inserted()) {
                    restoredVersions += 1;
                } else {
                    skippedVersions += 1;
                }
            }
            reconciliations.add(reconcile(group.getKey(), versions));
        }
        boolean ok = conflicts.isEmpty();
        return new RestoreReport(byEdition.size(), restoredVersions, skippedVersions, List.copyOf(conflicts),
                List.copyOf(reconciliations), ok);
    }

    /**
     * 恢复一册的一个版本：先校验，再幂等导入。
     *
     * @param entry 包内条目
     * @param actor 操作者
     * @return 单版结果
     */
    private RestoreOutcome restoreOne(AdminTextbookService.ExportPackageEntry entry, String actor) {
        if (entry.contentBase64() == null || entry.contentSha256() == null) {
            return new RestoreOutcome(false, true, "packageEntryIncomplete");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(entry.contentBase64());
        } catch (IllegalArgumentException malformed) {
            return new RestoreOutcome(false, true, "contentBase64Malformed");
        }
        String actualSha = sha256Hex(bytes);
        if (!actualSha.equalsIgnoreCase(entry.contentSha256()) || bytes.length != entry.byteLength()) {
            // 包内字节与声明不符：停下来，绝不把未经校验的内容写进库。
            return new RestoreOutcome(false, true, "contentHashOrLengthMismatch");
        }
        if (entry.sourceMetadataJson() != null && entry.sourceSnapshotSha256() != null
                && !sha256Hex(entry.sourceMetadataJson().getBytes(StandardCharsets.UTF_8))
                .equalsIgnoreCase(entry.sourceSnapshotSha256())) {
            return new RestoreOutcome(false, true, "sourceSnapshotHashMismatch");
        }
        String metadataJson = entry.editionMetadataJson() != null
                ? entry.editionMetadataJson() : metadataFromBook(bytes, entry);
        var outcome = importer.importEdition(entry.editionId(), entry.contentVersion(), bytes, actualSha,
                metadataJson, entry.sourceMetadataJson(), actor);
        // SKIPPED_IDENTICAL 表示库里已有同哈希版本；它同样算"对上了"，不算冲突。
        return new RestoreOutcome(outcome.state() == ImportState.INSERTED,
                outcome.state() == ImportState.CONFLICT_HASH_MISMATCH, outcome.state().name());
    }

    /**
     * 旧包没有单独带声明元数据时，从正文的 `edition` 块重建（版本号与哈希以包为准）。
     *
     * @param bytes 正文字节
     * @param entry 包内条目
     * @return 声明元数据 JSON；无法重建时返回空对象
     */
    private String metadataFromBook(byte[] bytes, AdminTextbookService.ExportPackageEntry entry) {
        try {
            JsonNode edition = objectMapper.readTree(bytes).path("edition");
            TextbookEdition declared = objectMapper.treeToValue(edition, TextbookEdition.class);
            TextbookEdition withIdentity = new TextbookEdition(declared.editionId(), declared.subject(),
                    declared.grade(), declared.semester(), declared.regionCodes(), declared.publisher(),
                    declared.series(), declared.editionLabel(), declared.curriculumYear(), declared.isbn(),
                    declared.sourceIds(), declared.schemaVersion(), entry.contentVersion(), entry.contentSha256(),
                    declared.reviewStatus(), declared.coverageStatus(), declared.verifiedAt(),
                    declared.publishedAt(), declared.availableEntryCount(), declared.expectedEntryCount(),
                    declared.missingScopes());
            return objectMapper.writeValueAsString(withIdentity);
        } catch (Exception unparsable) {
            LOG.warn("cannot rebuild edition metadata for {}_{}", entry.editionId(), entry.contentVersion());
            return "{}";
        }
    }

    /**
     * 恢复后逐项对账：正文、来源快照、历史留存与 current 指针。
     *
     * @param editionId 教材标识
     * @param entries   包内该册的全部条目
     * @return 对账结果
     */
    private EditionReconciliation reconcile(String editionId,
                                           List<AdminTextbookService.ExportPackageEntry> entries) {
        boolean contentMatches = true;
        boolean snapshotMatches = true;
        String expectedCurrentVersion = null;
        String catalogVersionBaseline = null;
        for (AdminTextbookService.ExportPackageEntry entry : entries) {
            if (entry.current()) {
                expectedCurrentVersion = entry.contentVersion();
            }
            var stored = repository.findVersion(editionId, entry.contentVersion()).orElse(null);
            if (stored == null || !entry.contentSha256().equalsIgnoreCase(stored.contentSha256())) {
                contentMatches = false;
                continue;
            }
            String snapshot = repository.findVersionSourceMetadata(editionId, entry.contentVersion()).orElse(null);
            if (entry.sourceMetadataJson() == null) {
                if (snapshot != null) {
                    snapshotMatches = false;
                }
            } else if (snapshot == null || !sha256Hex(snapshot.getBytes(StandardCharsets.UTF_8))
                    .equalsIgnoreCase(entry.sourceSnapshotSha256())) {
                snapshotMatches = false;
            }
            if (catalogVersionBaseline == null && snapshot != null) {
                catalogVersionBaseline = catalogVersionOf(snapshot);
            }
        }
        // 导入顺序已保证最后写入的是最高版本；若导出的 current 不是最高版，显式摆回。
        String expectedVersionId = expectedCurrentVersion == null ? null : editionId + "@" + expectedCurrentVersion;
        String actualCurrent = repository.findCurrentVersionId(editionId).orElse(null);
        if (expectedVersionId != null && !expectedVersionId.equals(actualCurrent)) {
            repository.setCurrentVersion(editionId, expectedVersionId);
            actualCurrent = repository.findCurrentVersionId(editionId).orElse(null);
        }
        int historyCount = repository.listAllVersions(editionId).size();
        boolean currentRestored = expectedVersionId == null || expectedVersionId.equals(actualCurrent);
        return new EditionReconciliation(editionId, actualCurrent, expectedCurrentVersion, currentRestored,
                historyCount, catalogVersionBaseline, contentMatches, snapshotMatches);
    }

    /** 从快照 JSON 里读目录基线；读不到返回 null（不编造版本号）。 */
    private String catalogVersionOf(String snapshotJson) {
        try {
            JsonNode node = objectMapper.readTree(snapshotJson).path("manifestBaseline").path("catalogVersion");
            return node.isMissingNode() || node.isNull() ? null : node.asText();
        } catch (Exception unparsable) {
            return null;
        }
    }

    /** 版本号比较：按点分段的数值比较，避免 `1.0.10` 被排在 `1.0.9` 之前。 */
    private static int compareVersions(String left, String right) {
        String[] leftParts = left == null ? new String[0] : left.split("\\.");
        String[] rightParts = right == null ? new String[0] : right.split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index += 1) {
            int leftValue = index < leftParts.length ? parseIntOrZero(leftParts[index]) : 0;
            int rightValue = index < rightParts.length ? parseIntOrZero(rightParts[index]) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    /** 非数字段按 0 参与比较，不抛异常。 */
    private static int parseIntOrZero(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notNumeric) {
            return 0;
        }
    }

    /** 计算字节的 SHA-256 十六进制值。 */
    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /**
     * 单版恢复结果。
     *
     * @param inserted 是否新插入
     * @param conflict 是否冲突（未写入）
     * @param reason   状态或原因
     */
    private record RestoreOutcome(boolean inserted, boolean conflict, String reason) {
    }

    /**
     * 恢复报告。
     *
     * @param restoredEditions 处理的教材数
     * @param restoredVersions 新插入的版本数
     * @param skippedVersions  同哈希跳过的版本数
     * @param conflicts        冲突项（`版本:原因`）
     * @param reconciliations  逐册对账
     * @param ok               无冲突时为 true
     */
    public record RestoreReport(int restoredEditions, int restoredVersions, int skippedVersions,
                                List<String> conflicts, List<EditionReconciliation> reconciliations,
                                boolean ok) {
    }

    /**
     * 单册对账结果。
     *
     * @param editionId             教材标识
     * @param currentVersionId      恢复后的 current 版本标识
     * @param expectedCurrentVersion 包内声明的 current 版本号
     * @param currentPointerRestored current 指针是否与包一致
     * @param historyCount          恢复后的历史版本数
     * @param catalogVersionBaseline 包内快照记录的目录基线版本号
     * @param contentSha256Matches  正文哈希是否逐版一致
     * @param sourceSnapshotMatches 来源快照指纹是否逐版一致
     */
    public record EditionReconciliation(String editionId, String currentVersionId, String expectedCurrentVersion,
                                        boolean currentPointerRestored, int historyCount,
                                        String catalogVersionBaseline, boolean contentSha256Matches,
                                        boolean sourceSnapshotMatches) {
    }
}
