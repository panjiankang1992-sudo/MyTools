package com.yuyutian.mytools.toolbox.service.admin.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.common.BusinessException;
import com.yuyutian.mytools.toolbox.common.ErrorCode;
import com.yuyutian.mytools.toolbox.config.ToolboxProperties;
import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 历史版本的**显式来源补录**流程（设计原则 4）。
 *
 * 适用场景：某个版本在历史上根本没有保存来源快照，而现在确实还有**可核实的同版留存文件**
 * （内容根目录里的 `sources/*.json`）。此时可以补录，但必须诚实地留下痕迹：
 *
 * - 快照 `origin=repair`、`repairBasis` 记录**补录依据**，`capturedAt` 记录**补录时间**；
 * - **不回写原正文**：本服务不触碰 `content_bytes`／`content_sha256`；
 * - **不谎称原始发布时已保存**：`origin` 与依据都写明这是补录；
 * - **不覆盖任何既有快照**：写库条件是 `source_metadata_json IS NULL`，
 *   既有原始快照与已补录的快照都不会被改写；
 * - 对不上的来源保留缺失说明（由构建器的缺失标记给出），不编造。
 */
@Service
public class SourceSnapshotRepairService {

    private static final Logger LOG = LoggerFactory.getLogger(SourceSnapshotRepairService.class);

    private final AdminTextbookRepository repository;
    private final SourceSnapshotBuilder snapshotBuilder;
    private final ToolboxProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 创建补录服务。
     *
     * @param repository      教材仓储
     * @param snapshotBuilder 来源快照构建器
     * @param properties      门面配置（提供内容根目录）
     * @param objectMapper    JSON 解析器
     */
    public SourceSnapshotRepairService(AdminTextbookRepository repository, SourceSnapshotBuilder snapshotBuilder,
                                       ToolboxProperties properties, ObjectMapper objectMapper) {
        this.repository = repository;
        this.snapshotBuilder = snapshotBuilder;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 为某历史版本补录来源快照。
     *
     * @param editionId      教材标识
     * @param contentVersion 内容版本号
     * @param basis          补录依据（必填，例如"同版留存文件 sources/cn-1-first-2024.json"）
     * @param actor          操作者
     * @return 补录结果
     */
    public RepairResult repair(String editionId, String contentVersion, String basis, String actor) {
        if (basis == null || basis.isBlank()) {
            // 没有依据的"补录"就是一次谎称，必须拒绝。
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_005, Map.of("reason", "repairBasisRequired"));
        }
        AdminTextbookRepository.VersionRow version = repository.findVersion(editionId, contentVersion)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOLBOX_ADMIN_006,
                        Map.of("reason", "versionMissing")));
        String existing = repository.findVersionSourceMetadata(editionId, contentVersion).orElse(null);
        if (existing != null && !existing.isBlank()) {
            // 已有快照（原始或先前补录）：**不改写**，如实返回"未应用"。
            return new RepairResult(editionId, contentVersion, false, false, "snapshotAlreadyPresent",
                    SnapshotHashes.of(existing));
        }
        String snapshotJson = buildRepairSnapshot(version, basis, actor);
        int updated = repository.updateSourceMetadataIfAbsent(versionId(editionId, contentVersion), snapshotJson);
        boolean applied = updated == 1;
        if (!applied) {
            // 条件更新没命中：说明期间已有别的写入落了快照，保持不动。
            String current = repository.findVersionSourceMetadata(editionId, contentVersion).orElse(null);
            return new RepairResult(editionId, contentVersion, false, false, "snapshotAlreadyPresent",
                    current == null ? null : SnapshotHashes.of(current));
        }
        repository.recordAudit(actor, "version.sourceSnapshot.repair", editionId, null, null,
                contentVersion, contentVersion, "补录来源快照，依据：" + basis);
        LOG.info("source snapshot repaired for {}@{} by {}", editionId, contentVersion, actor);
        return new RepairResult(editionId, contentVersion, true, false, "repaired", SnapshotHashes.of(snapshotJson));
    }

    /**
     * 构建补录快照。
     *
     * 目录基线与来源记录都来自**当前仍在的留存文件**；条目定位来自版本自己保存的原始字节，
     * 因此补录冻结的是"今天能核实的同版来源"，而不是当年发布时的样子——快照里的
     * `origin=repair` 与 `repairBasis` 把这一点写清楚。
     *
     * @param version 版本行（提供原字节与哈希，不修改）
     * @param basis   补录依据
     * @param actor   操作者
     * @return 快照 JSON
     */
    private String buildRepairSnapshot(AdminTextbookRepository.VersionRow version, String basis, String actor) {
        List<String> sourceIds = declaredSourceIds(version);
        var capture = new SourceSnapshotBuilder.SnapshotCapture(Instant.now().toString(), actor,
                "repair:" + version.editionId() + "@" + version.contentVersion(), "repair", basis);
        var request = new SourceSnapshotBuilder.SnapshotRequest(Path.of(properties.catalogPath()),
                version.editionId(), version.contentVersion(), version.contentSha256(), sourceIds,
                snapshotBuilder.entryLocatorsFromBookBytes(version.contentBytes()), List.of(), capture, null);
        return snapshotBuilder.build(request).json();
    }

    /**
     * 从版本声明元数据里取教材级来源标识。
     *
     * @param version 版本行
     * @return 来源标识；缺失时为空表（构建器会标记"未声明"而不是编造）
     */
    private List<String> declaredSourceIds(AdminTextbookRepository.VersionRow version) {
        String metadata = repository.findVersionMetadata(version.editionId(), version.contentVersion()).orElse(null);
        if (metadata == null || metadata.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(metadata).path("sourceIds");
            if (!node.isArray()) {
                return null;
            }
            List<String> out = new ArrayList<>();
            for (JsonNode item : node) {
                out.add(item.asText());
            }
            return out;
        } catch (Exception unparsable) {
            return null;
        }
    }

    /** 版本标识。 */
    private static String versionId(String editionId, String contentVersion) {
        return editionId + "@" + contentVersion;
    }

    /**
     * 快照指纹。
     *
     * @param json   快照 JSON
     * @param sha256 快照 SHA-256
     */
    public record SnapshotHashes(String json, String sha256) {

        /**
         * 由快照 JSON 计算指纹。
         *
         * @param json 快照 JSON
         * @return 指纹
         */
        static SnapshotHashes of(String json) {
            return new SnapshotHashes(json, sha256Hex(json));
        }

        /** 计算字符串的 SHA-256 十六进制值。 */
        private static String sha256Hex(String text) {
            try {
                byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return java.util.HexFormat.of().formatHex(hash);
            } catch (Exception impossible) {
                throw new IllegalStateException("SHA-256 unavailable", impossible);
            }
        }
    }

    /**
     * 补录结果。
     *
     * @param editionId      教材标识
     * @param contentVersion 内容版本号
     * @param applied        是否真的写入了补录
     * @param overwroteOriginal 是否覆盖了既有快照；**恒为 false**
     * @param reason         结果原因
     * @param snapshot       补录后（或既有的）快照指纹；不可用时为 null
     */
    public record RepairResult(String editionId, String contentVersion, boolean applied,
                               boolean overwroteOriginal, String reason, SnapshotHashes snapshot) {
    }
}
