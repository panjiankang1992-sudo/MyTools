package com.yuyutian.mytools.toolbox.mapper.admin;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 教材草稿与不可变版本的仓储。
 *
 * 三条硬保证：
 * 1. **草稿保存是 CAS**：只有期望修订号与库中一致才更新，冲突返回 false，调用方据此回 409；
 * 2. **已发布版本不可变**：本类不提供任何更新正文的方法，版本行只插入；
 * 3. **幂等回执**：同键同摘要返回原结果，同键不同摘要视为冲突。
 */
@Repository
public class AdminTextbookRepository {

    /** 列表与总数共用关联，未发布教材的元数据来自草稿。 */
    private static final String TEXTBOOK_QUERY_FROM = """
            FROM toolbox_textbook t
            LEFT JOIN toolbox_textbook_version v ON v.version_id = t.current_version_id
            LEFT JOIN toolbox_textbook_draft d ON d.draft_id = t.draft_id
            WHERE 1 = 1
            """;

    private final JdbcTemplate jdbc;

    /**
     * 创建教材仓储。
     *
     * @param jdbc JDBC 模板
     */
    public AdminTextbookRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建教材记录（只建草稿，不公开）。
     *
     * @param editionId 教材标识
     * @param actor     操作者用户标识
     * @return 新建草稿的标识与初始修订号
     */
    @Transactional
    public DraftRef createTextbook(String editionId, String actor) {
        long now = System.currentTimeMillis();
        String draftId = "draft-" + editionId;
        jdbc.update("""
                INSERT INTO toolbox_textbook (edition_id, current_version_id, draft_id, created_by, created_at,
                    updated_by, updated_at)
                VALUES (?, NULL, ?, ?, ?, ?, ?)
                """, editionId, draftId, actor, new Timestamp(now), actor, new Timestamp(now));
        jdbc.update("""
                INSERT INTO toolbox_textbook_draft (draft_id, edition_id, base_content_version, revision,
                    editable_json, source_metadata_json, updated_by, updated_at)
                VALUES (?, ?, NULL, 1, '{}', NULL, ?, ?)
                """, draftId, editionId, actor, new Timestamp(now));
        return new DraftRef(draftId, 1L);
    }

    /**
     * 按期望修订号保存草稿（CAS）。
     *
     * @param draftId         草稿标识
     * @param expectedRevision 调用方持有的修订号
     * @param editableJson    草稿正文
     * @param actor           操作者用户标识
     * @return 成功时返回新修订号；修订号不一致时为空
     */
    @Transactional
    public Optional<Long> saveDraft(String draftId, long expectedRevision, String editableJson, String actor) {
        return saveDraft(draftId, expectedRevision, editableJson, null, actor);
    }

    /**
     * 按期望修订号保存草稿（CAS），并可选写入**草稿级来源快照**。
     *
     * 快照用 `COALESCE(?, source_metadata_json)` 写入：调用方没给快照时**保留已有的非空快照**，
     * 不会因为一次"只改正文"的保存就把来源记录抹掉。
     *
     * @param draftId            草稿标识
     * @param expectedRevision   调用方持有的修订号
     * @param editableJson       草稿正文
     * @param sourceMetadataJson 草稿级来源快照；为 null 时保留既有值
     * @param actor              操作者用户标识
     * @return 成功时返回新修订号；修订号不一致时为空
     */
    @Transactional
    public Optional<Long> saveDraft(String draftId, long expectedRevision, String editableJson,
                                    String sourceMetadataJson, String actor) {
        jdbc.query("SELECT edition_id FROM toolbox_textbook WHERE draft_id = ? FOR UPDATE",
                (rs, row) -> rs.getString(1), draftId);
        long next = expectedRevision + 1;
        int updated = jdbc.update("""
                UPDATE toolbox_textbook_draft
                SET revision = ?, editable_json = ?, source_metadata_json = COALESCE(?, source_metadata_json),
                    updated_by = ?, updated_at = ?
                WHERE draft_id = ? AND revision = ?
                """, next, editableJson, sourceMetadataJson, actor, new Timestamp(System.currentTimeMillis()),
                draftId, expectedRevision);
        if (updated != 1) {
            return Optional.empty();
        }
        jdbc.update("UPDATE toolbox_textbook SET updated_by = ?, updated_at = ? WHERE draft_id = ?",
                actor, new Timestamp(System.currentTimeMillis()), draftId);
        return Optional.of(next);
    }

    /**
     * 读取草稿。
     *
     * @param draftId 草稿标识
     * @return 草稿行；不存在时为空
     */
    /**
     * 安装**由已发布教材无损生成的**初始草稿（仅供迁移／开始编辑使用）。
     *
     * **只在草稿从未被编辑过时生效**：条件是 `base_content_version IS NULL`。
     * 那正是 `createTextbook` 留下的空草稿的标记。
     * 一旦管理员真的编辑过（保存会写入 base 与 revision），这里就不再动它——
     * **迁移必须修复空草稿，但不能覆盖人的劳动**。
     *
     * 也正因为带了这个条件，**重复导入是安全的**：第二次不会再把草稿重置回初始状态。
     *
     * @param draftId            草稿标识
     * @param baseContentVersion 草稿所基于的已发布版本
     * @param editableJson       从该版本无损生成的草稿正文
     * @param actor              操作者
     * @return 实际安装的行数；0 表示草稿已被编辑过、保持原样
     */
    public int installInitialDraft(String draftId, String baseContentVersion, String editableJson, String actor) {
        return installInitialDraft(draftId, baseContentVersion, editableJson, null, actor);
    }

    /**
     * 安装初始草稿，并可选写入草稿级来源快照。
     *
     * 与 4 参版本同样只在 `base_content_version IS NULL`（草稿从未被编辑过）时生效；
     * 快照同样用 `COALESCE` 保留既有非空值。
     *
     * @param draftId            草稿标识
     * @param baseContentVersion 草稿所基于的已发布版本
     * @param editableJson       从该版本无损生成的草稿正文
     * @param sourceMetadataJson 草稿级来源快照；为 null 时保留既有值
     * @param actor              操作者
     * @return 实际安装的行数；0 表示草稿已被编辑过、保持原样
     */
    public int installInitialDraft(String draftId, String baseContentVersion, String editableJson,
                                   String sourceMetadataJson, String actor) {
        return jdbc.update("""
                UPDATE toolbox_textbook_draft
                   SET base_content_version = ?, editable_json = ?,
                       source_metadata_json = COALESCE(?, source_metadata_json),
                       updated_by = ?, updated_at = ?
                 WHERE draft_id = ?
                   AND base_content_version IS NULL
                """, baseContentVersion, editableJson, sourceMetadataJson, actor,
                new Timestamp(System.currentTimeMillis()), draftId);
    }

    /** 锁定教材行；所有教材写入使用相同锁顺序，保护三方同步与发布的当前指针。 */
    public void lockTextbook(String editionId) {
        jdbc.query("SELECT edition_id FROM toolbox_textbook WHERE edition_id = ? FOR UPDATE",
                (rs, row) -> rs.getString(1), editionId);
    }

    /** 在同一事务内校验草稿修订、旧基线及当前教材指针，并整体保存合并结果。 */
    public Optional<Long> rebaseDraft(String draftId, long expectedRevision, String expectedBase,
                                      String currentVersion, String editableJson,
                                      String sourceMetadataJson, String actor) {
        long next = expectedRevision + 1;
        int updated = jdbc.update("""
                UPDATE toolbox_textbook_draft
                   SET revision = ?, base_content_version = ?, editable_json = ?,
                       source_metadata_json = ?, updated_by = ?, updated_at = ?
                 WHERE draft_id = ? AND revision = ? AND base_content_version = ?
                   AND EXISTS (SELECT 1 FROM toolbox_textbook t
                                WHERE t.draft_id = ? AND t.current_version_id = ?)
                """, next, currentVersion, editableJson, sourceMetadataJson, actor,
                new Timestamp(System.currentTimeMillis()), draftId, expectedRevision, expectedBase,
                draftId, currentVersion);
        return updated == 1 ? Optional.of(next) : Optional.empty();
    }

    /**
     * 把草稿的**基线版本**推进到刚发布的版本（F07）。
     *
     * 这一列原本只在 `installInitialDraft` 写入一次，含义是
     * "草稿最初是从哪一版生成的"；于是**发布之后它永远是 NULL**。
     *
     * 要让"锁内基线校验"成为可能，它的含义必须变成
     * **"草稿的内容基于哪一版"**——因此每次发布成功后都要把它推进。
     *
     * **本方法是纯增量**：不改任何既有判断，只是让这一列此后有意义。
     *
     * @param draftId            草稿标识
     * @param baseContentVersion 刚发布的版本号
     * @return 实际更新的行数
     */
    public int updateDraftBaseVersion(String draftId, String baseContentVersion) {
        return jdbc.update("""
                UPDATE toolbox_textbook_draft
                   SET base_content_version = ?
                 WHERE draft_id = ?
                """, baseContentVersion, draftId);
    }

    /** 读 `published_at`；列缺失或为空时返回 0。 */
    private static long publishedAtOf(java.sql.ResultSet rs) {
        try {
            java.sql.Timestamp value = rs.getTimestamp("published_at");
            return value == null ? 0L : value.getTime();
        } catch (java.sql.SQLException absent) {
            // 有的查询不取这一列：按"未知"处理，而不是让整条查询失败。
            return 0L;
        }
    }

    public Optional<DraftRow> findDraft(String draftId) {
        List<DraftRow> rows = jdbc.query("""
                SELECT draft_id, edition_id, base_content_version, revision, editable_json, source_metadata_json,
                       updated_by
                FROM toolbox_textbook_draft WHERE draft_id = ?
                """, (rs, rowNum) -> new DraftRow(
                rs.getString("draft_id"), rs.getString("edition_id"), rs.getString("base_content_version"),
                rs.getLong("revision"), rs.getString("editable_json"), rs.getString("source_metadata_json"),
                rs.getString("updated_by")), draftId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 事务内发布新版本：插入不可变版本、移动指针、递增目录世代。
     * 重复的 (editionId, contentVersion) 会被唯一键拒绝，从而不会产生第二个同号版本。
     *
     * @param editionId       教材标识
     * @param contentVersion  服务端分配的内容版本号
     * @param contentBytes    正文原始字节
     * @param contentSha256   正文哈希
     * @param metadataJson    版本元数据
     * @param actor           操作者用户标识
     * @return 新版本标识
     */
    @Transactional
    public String publishVersion(String editionId, String contentVersion, byte[] contentBytes, String contentSha256,
                                 String metadataJson, String actor) {
        return publishVersion(editionId, contentVersion, contentBytes, contentSha256, metadataJson, null, actor,
                null, false);
    }

    /**
     * 发布一版，并写入**版本级来源快照**。
     *
     * 与 6 参版本的区别只有一处：`source_metadata_json` 不再写死 NULL。
     * 这一列随不可变版本一起插入，**本类不提供任何后续修改它的方法**，
     * 因此"来源元数据跟随不可变版本保存、不能事后修改使旧发布审计失真"是由存储结构保证的。
     *
     * @param editionId          教材标识
     * @param contentVersion     服务端分配的内容版本号
     * @param contentBytes       正文原始字节
     * @param contentSha256      正文哈希
     * @param metadataJson       版本元数据
     * @param sourceMetadataJson 版本级来源快照；可为 null
     * @param actor              操作者用户标识
     * @return 新版本标识
     */
    @Transactional
    public String publishVersion(String editionId, String contentVersion, byte[] contentBytes, String contentSha256,
                                 String metadataJson, String sourceMetadataJson, String actor) {
        return publishVersion(editionId, contentVersion, contentBytes, contentSha256, metadataJson,
                sourceMetadataJson, actor, null, false);
    }

    /**
     * 发布一版，并可选地对"当前指向的版本"做 **CAS**（F07）。
     *
     * @param expectedCurrentVersionId 期望的当前版本标识；仅在 {@code casEnabled} 为 true 时有意义
     * @param casEnabled               是否启用 CAS；**关闭时不做任何版本比较**
     * @return 新版本标识
     * @throws IllegalStateException 教材行不存在，或启用 CAS 且当前版本已被改过
     */
    @Transactional
    public String publishVersion(String editionId, String contentVersion, byte[] contentBytes, String contentSha256,
                                 String metadataJson, String actor, String expectedCurrentVersionId,
                                 boolean casEnabled) {
        return publishVersion(editionId, contentVersion, contentBytes, contentSha256, metadataJson, null, actor,
                expectedCurrentVersionId, casEnabled);
    }

    /**
     * 发布一版，写入版本级来源快照，并可选地对"当前指向的版本"做 CAS（F07）。
     *
     * @param sourceMetadataJson       版本级来源快照；可为 null
     * @param expectedCurrentVersionId 期望的当前版本标识；仅在 {@code casEnabled} 为 true 时有意义
     * @param casEnabled               是否启用 CAS；**关闭时不做任何版本比较**
     * @return 新版本标识
     * @throws IllegalStateException 教材行不存在，或启用 CAS 且当前版本已被改过
     */
    @Transactional
    public String publishVersion(String editionId, String contentVersion, byte[] contentBytes, String contentSha256,
                                 String metadataJson, String sourceMetadataJson, String actor,
                                 String expectedCurrentVersionId, boolean casEnabled) {
        // 真实发布：必须推进目录世代（设计 274 行"publish 时行锁、单调递增"）。
        return storeVersion(editionId, contentVersion, contentBytes, contentSha256, metadataJson,
                sourceMetadataJson, actor, expectedCurrentVersionId, casEnabled, true);
    }

    /**
     * 导入一版**早已在文件仓储发布过**的内容，**不推进目录世代**（F14 初始迁移专用）。
     *
     * 为什么必须有这条路径：迁移复用了 `publishVersion`，而那个方法每次都会
     * `epoch = epoch + 1, catalog_version = CONCAT('catalog-', epoch + 1)`——
     * 于是"导入既有内容"被当成了一次真实发布：清单声明的 `catalogVersion` 还没写进去，
     * 就被计数器改写成 `catalog-1`，设计要求的"初始迁移保留旧 catalogVersion、
     * epoch 独立初始化、首次真实发布才生成新 catalogVersion"全部落空。
     *
     * 版本行、当前指针、草稿基线与真实发布**完全一致**，唯一差别就是这个世代计数：
     * 迁移导入的内容在文件仓储里**已经对外发布过**，此刻再推进世代等于
     * 凭空造出一个从未真实发生过的目录版本。
     *
     * @param editionId      教材标识
     * @param contentVersion 内容版本号（来自迁移清单）
     * @param contentBytes   正文原始字节
     * @param contentSha256  正文哈希
     * @param metadataJson   版本元数据
     * @param sourceMetadataJson 版本级来源快照；可为 null
     * @param actor          操作者用户标识
     * @return 新版本标识
     */
    @Transactional
    public String importVersion(String editionId, String contentVersion, byte[] contentBytes, String contentSha256,
                                String metadataJson, String sourceMetadataJson, String actor) {
        return storeVersion(editionId, contentVersion, contentBytes, contentSha256, metadataJson,
                sourceMetadataJson, actor, null, false, false);
    }

    /**
     * 写入一版的公共实现。
     *
     * `advanceCatalog` 是**唯一**区分"真实发布"与"迁移导入"的地方：
     * - true：真实发布，推进 epoch 与 catalog_version；
     * - false：初始迁移，保持 epoch 不动（F14）。
     *
     * @param sourceMetadataJson       版本级来源快照；可为 null
     * @param expectedCurrentVersionId 期望的当前版本标识；仅在 {@code casEnabled} 为 true 时有意义
     * @param casEnabled               是否启用 CAS；**关闭时不做任何版本比较**
     * @param advanceCatalog           是否推进目录世代
     * @return 新版本标识
     * @throws IllegalStateException 教材行不存在，或启用 CAS 且当前版本已被改过
     */
    private String storeVersion(String editionId, String contentVersion, byte[] contentBytes, String contentSha256,
                                String metadataJson, String sourceMetadataJson, String actor,
                                String expectedCurrentVersionId, boolean casEnabled, boolean advanceCatalog) {
        lockTextbook(editionId);
        String versionId = editionId + "@" + contentVersion;
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO toolbox_textbook_version (version_id, edition_id, content_version, content_bytes,
                    content_sha256, edition_metadata_json, source_metadata_json, published_by, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, versionId, editionId, contentVersion, contentBytes, contentSha256, metadataJson,
                sourceMetadataJson, actor, new Timestamp(now));
        // F07：**检查影响行数**。
        //
        // 此前这一句的返回值被丢弃。若 `toolbox_textbook` 里没有这一册的行，
        // 更新影响 0 行，**发布却继续往下走并"成功"返回**：
        // 调用方以为发布完成，公开目录却什么都没变——
        // 界面显示"已发布"、用户刷新看不到内容，**一个不会报错的失败**。
        // F07：CAS 是**可选**的。
        //
        // 我第一版把 `expectedCurrentVersionId == null` 当作"期望还没发布过"，
        // 于是既有调用方（传 null 表示"不用检查"）在**第二次发布**时全部失败——
        // 因为那时 current_version_id 已经不是 null 了。
        // **是语义设计错了，不是那些测试错了。** 现在用一个显式开关区分：
        // 关闭 = 不比较；开启 = 要求仍然是调用方读到的那一个。
        int attached = casEnabled
                ? jdbc.update("""
                        UPDATE toolbox_textbook
                           SET current_version_id = ?, updated_at = ?
                         WHERE edition_id = ?
                           AND (current_version_id = ? OR (current_version_id IS NULL AND ? IS NULL))
                        """, versionId, new Timestamp(now), editionId, expectedCurrentVersionId,
                        expectedCurrentVersionId)
                : jdbc.update("""
                        UPDATE toolbox_textbook
                           SET current_version_id = ?, updated_at = ?
                         WHERE edition_id = ?
                        """, versionId, new Timestamp(now), editionId);
        if (attached != 1) {
            // 两种原因都可能：教材行不存在，或当前版本已被别的实例改过。
            throw new IllegalStateException(
                    "publish aborted: textbook row missing or current version changed for edition "
                            + editionId);
        }
        // 世代与版本名在同一条语句内推进：若分两步，并发发布会用同一个旧值算出相同的 catalog_version。
        //
        // F14：**迁移导入不走这一步**。迁移导入的是文件仓储里早已发布过的内容，
        // 推进世代会凭空造出一个从未真实发生过的目录版本，并覆盖清单声明的 catalogVersion。
        if (advanceCatalog) {
            jdbc.update("""
                    UPDATE toolbox_curriculum_catalog
                    SET epoch = epoch + 1, catalog_version = CONCAT('catalog-', epoch + 1), updated_at = ?
                    WHERE catalog_key = 'current'
                    """, new Timestamp(now));
        }
        return versionId;
    }

    /**
     * 列出全部教材的当前已发布版本，供发布后重建目录快照使用。
     * 只取当前指针指向的版本；历史版本不进入公开目录。
     *
     * @return 当前版本行
     */
    public List<VersionRow> listCurrentVersions() {
        return jdbc.query("""
                SELECT v.version_id, v.edition_id, v.content_version, v.content_bytes, v.content_sha256, v.published_by,
                       v.published_at
                FROM toolbox_textbook t
                JOIN toolbox_textbook_version v ON v.version_id = t.current_version_id
                ORDER BY v.edition_id, v.content_version
                """, (rs, rowNum) -> new VersionRow(
                rs.getString("version_id"), rs.getString("edition_id"), rs.getString("content_version"),
                rs.getBytes("content_bytes"), rs.getString("content_sha256"), rs.getString("published_by"), publishedAtOf(rs)));
    }

    /**
     * 列出一册教材**曾经发布过的全部版本**（含历史），按版本号排序。
     *
     * 与 `listCurrentVersions()` 的区别：那个只返回当前指针指向的版本，
     * 而**回退导出**需要完整的历史索引——设计第 8 节要求导出包包含
     * 「全部曾发布历史的独立索引」，以便从数据库回退到文件仓储时不丢失新教材。
     *
     * @param editionId 教材标识
     * @return 全部版本行（不含正文，正文按需单独取）
     */
    /**
     * 读某教材**当前指向的版本标识**（F07）。
     *
     * 发布前先读一次，作为 CAS 的期望值——多实例下
     * "另一个实例已经先发布了"会被发现，而不是互相覆盖。
     *
     * @param editionId 教材标识
     * @return 当前版本标识；没有教材行或尚未发布时为空
     */
    public java.util.Optional<String> findCurrentVersionId(String editionId) {
        List<String> rows = jdbc.query("SELECT current_version_id FROM toolbox_textbook WHERE edition_id = ?",
                (rs, rowNum) -> rs.getString("current_version_id"), editionId);
        if (rows.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(rows.get(0));
    }

    public List<VersionRow> listAllVersions(String editionId) {
        return jdbc.query("""
                SELECT v.version_id, v.edition_id, v.content_version, v.content_bytes, v.content_sha256, v.published_by,
                       v.published_at
                FROM toolbox_textbook_version v
                WHERE v.edition_id = ?
                ORDER BY v.content_version
                """, (rs, rowNum) -> new VersionRow(
                rs.getString("version_id"), rs.getString("edition_id"), rs.getString("content_version"),
                rs.getBytes("content_bytes"), rs.getString("content_sha256"), rs.getString("published_by"), publishedAtOf(rs)),
                editionId);
    }

    /**
     * 读取某册版本元数据 JSON，用于重建时还原声明信息。
     *
     * @param editionId      教材标识
     * @param contentVersion 内容版本号
     * @return 元数据 JSON；不存在时为空
     */
    public Optional<String> findVersionMetadata(String editionId, String contentVersion) {
        List<String> rows = jdbc.queryForList("""
                SELECT edition_metadata_json FROM toolbox_textbook_version
                WHERE edition_id = ? AND content_version = ?
                """, String.class, editionId, contentVersion);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /**
     * 读取某版本的**来源快照**。
     *
     * 导出与管理详情必须走这里，而不是重新读本机 `sources/` 目录——
     * 磁盘上的来源随时可能被改动，只有这一列才是"当年发布时的那一份"。
     *
     * @param editionId      教材标识
     * @param contentVersion 内容版本号
     * @return 快照 JSON；版本不存在或该版本没有快照时为空
     */
    public Optional<String> findVersionSourceMetadata(String editionId, String contentVersion) {
        List<String> rows = jdbc.queryForList("""
                SELECT source_metadata_json FROM toolbox_textbook_version
                WHERE edition_id = ? AND content_version = ?
                """, String.class, editionId, contentVersion);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /**
     * **仅在快照为空时**为既有版本补录来源快照。
     *
     * 用于历史版本的显式修复流程（设计原则：只在确有可核实留存文件时补录，
     * 且不谎称原始发布时已保存）。条件里的 `source_metadata_json IS NULL`
     * 保证**不会覆盖任何既有快照**——原始快照与已补录的快照都不会被改写。
     *
     * 本方法**不触碰 `content_bytes`／`content_sha256`**：补录来源不改原正文。
     *
     * @param versionId          版本标识
     * @param sourceMetadataJson 补录的来源快照
     * @return 实际更新行数；0 表示该版本已有快照或不存在
     */
    public int updateSourceMetadataIfAbsent(String versionId, String sourceMetadataJson) {
        return jdbc.update("""
                UPDATE toolbox_textbook_version
                   SET source_metadata_json = ?
                 WHERE version_id = ?
                   AND source_metadata_json IS NULL
                """, sourceMetadataJson, versionId);
    }

    /**
     * 把某册的**当前版本指针**指向指定版本（仅供恢复／迁移使用）。
     *
     * 恢复时逐版导入，`publishVersion` 会把指针推到**最后导入**的那一版；
     * 而导出的"当前版本"未必是版本号最大的那一版，因此需要显式把它摆回去。
     *
     * 本方法**只移动指针**：不改写任何正文字节、不改哈希、不递增目录世代，
     * 因此不触碰发布路径的 catalogVersion 分配逻辑。
     *
     * @param editionId 教材标识
     * @param versionId 目标版本标识
     * @return 实际更新行数；教材行不存在时为 0
     */
    public int setCurrentVersion(String editionId, String versionId) {
        return jdbc.update("""
                UPDATE toolbox_textbook
                   SET current_version_id = ?, updated_at = ?
                 WHERE edition_id = ?
                """, versionId, new Timestamp(System.currentTimeMillis()), editionId);
    }

    /**
     * 计算某册的下一个内容版本号。
     * 取已存在的最大版本号并递增末位；没有历史时从 1.0.0 开始。版本号由服务端分配，管理员不手填。
     *
     * @param editionId 教材标识
     * @return 下一个内容版本号
     */
    public String nextContentVersion(String editionId) {
        List<String> existing = jdbc.queryForList(
                "SELECT content_version FROM toolbox_textbook_version WHERE edition_id = ?", String.class, editionId);
        if (existing.isEmpty()) {
            return "1.0.0";
        }
        int bestMajor = -1;
        int bestMinor = -1;
        int bestPatch = -1;
        for (String version : existing) {
            String[] parts = version.split("\\.");
            if (parts.length != 3) {
                continue;
            }
            try {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                int patch = Integer.parseInt(parts[2]);
                if (major > bestMajor || (major == bestMajor && minor > bestMinor)
                        || (major == bestMajor && minor == bestMinor && patch > bestPatch)) {
                    bestMajor = major;
                    bestMinor = minor;
                    bestPatch = patch;
                }
            } catch (NumberFormatException ignored) {
                // 非法历史版本号不参与比较，也不阻断发布。
            }
        }
        return bestMajor < 0 ? "1.0.0" : bestMajor + "." + bestMinor + "." + (bestPatch + 1);
    }

    /**
     * 按条件分页查询教材，附加当前版本的声明元数据。
     *
     * @param keyword  关键字；为 null 时不过滤
     * @param subject  学科；为 null 时不过滤
     * @param grade    年级；为 null 时不过滤
     * @param semester 册次；为 null 时不过滤
     * @param status   `draft` 只返回无当前版本的教材，`published` 只返回已发布的
     * @param limit    本次返回条数
     * @param offset   偏移量
     * @return 教材行（含元数据 JSON，可能为 null）
     */
    public List<TextbookListRow> listTextbooks(String keyword, String subject, Integer grade, String semester,
                                               String status, int limit, int offset) {
        // F12：筛选要能覆盖**草稿**。
        //
        // 此前关键词／学科／年级／册次都只查 `v.edition_metadata_json`——
        // 而草稿**没有当前版本**，那一列是 NULL，`LIKE` 自然不匹配。
        // 于是"刚建好的草稿在自己的学科里查不到"：用户筛一下会以为内容丢了。
        //
        // 这里把草稿表一并 LEFT JOIN，用 `COALESCE` 在"已发布的元数据"与
        // "草稿正文"之间取有值的那个——两者都是同一份 `subject/grade/semester` 的形状。
        StringBuilder sql = new StringBuilder("""
                SELECT t.edition_id, t.current_version_id, t.updated_at, t.updated_by,
                       v.content_version,
                       -- F12：草稿没有版本行，元数据在草稿正文里。
                       -- 上一轮我只改了 WHERE、漏了 SELECT，于是草稿行仍然拿到 NULL——
                       -- 列表就只能显示内部 ID，看不到真实名称/学科。
                       COALESCE(v.edition_metadata_json, d.editable_json) AS edition_metadata_json,
                       -- F12：判断"已发布·有修改"需要两个修订号。
                       --
                       -- **不要用** `base_content_version` 与 `current_version_id` 比较：
                       -- 编辑草稿**不会**改动基线，所以那两者在编辑前后始终相等
                       -- （它表达的是另一件事：有没有**别的实例**发布过新版本）。
                       -- 正确的信号是"草稿修订号 > 最后一次发布的修订号"。
                       d.revision AS draft_revision,
                       (SELECT MAX(a.to_revision) FROM toolbox_curriculum_audit a
                         WHERE a.edition_id = t.edition_id
                           AND a.action = 'textbook.publish') AS last_published_revision
                """);
        sql.append(TEXTBOOK_QUERY_FROM);
        List<Object> args = new ArrayList<>();
        appendTextbookFilters(sql, args, keyword, subject, grade, semester, status);
        // 同一毫秒创建的教材按标识稳定排序，防止翻页时重复或遗漏。
        sql.append(" ORDER BY t.updated_at DESC, t.edition_id ASC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), (rs, rowNum) -> new TextbookListRow(
                rs.getString("edition_id"),
                rs.getString("current_version_id"),
                rs.getString("content_version"),
                rs.getTimestamp("updated_at") == null ? 0L : rs.getTimestamp("updated_at").getTime(),
                rs.getString("updated_by"),
                rs.getString("edition_metadata_json"), rs.getObject("draft_revision") == null ? 0L : rs.getLong("draft_revision"),
                rs.getObject("last_published_revision") == null ? -1L : rs.getLong("last_published_revision")), args.toArray());
    }

    /**
     * 统计符合条件的教材总数，用于分页。
     *
     * @param keyword  关键字；为 null 时不过滤
     * @param subject  学科；为 null 时不过滤
     * @param grade    年级；为 null 时不过滤
     * @param semester 册次；为 null 时不过滤
     * @param status   状态；为 null 时不过滤
     * @return 总数
     */
    public int countTextbooks(String keyword, String subject, Integer grade, String semester, String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) ").append(TEXTBOOK_QUERY_FROM);
        List<Object> args = new ArrayList<>();
        appendTextbookFilters(sql, args, keyword, subject, grade, semester, status);
        Integer total = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
        return total == null ? 0 : total;
    }

    /** 列表与总数使用完全相同的元数据与筛选条件，避免总数漏算草稿。 */
    private static void appendTextbookFilters(StringBuilder sql, List<Object> args, String keyword,
                                             String subject, Integer grade, String semester, String status) {
        if (keyword != null) {
            sql.append(" AND (COALESCE(v.edition_metadata_json, d.editable_json) LIKE ? OR t.edition_id LIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        if (subject != null) {
            sql.append(" AND COALESCE(v.edition_metadata_json, d.editable_json) LIKE ?");
            args.add("%\"subject\":\"" + subject + "\"%");
        }
        if (grade != null) {
            sql.append(" AND COALESCE(v.edition_metadata_json, d.editable_json) LIKE ?");
            args.add("%\"grade\":" + grade + "%");
        }
        if (semester != null) {
            sql.append(" AND COALESCE(v.edition_metadata_json, d.editable_json) LIKE ?");
            args.add("%\"semester\":\"" + semester + "\"%");
        }
        if ("draft".equals(status)) {
            sql.append(" AND t.current_version_id IS NULL");
        } else if ("published".equals(status)) {
            sql.append(" AND t.current_version_id IS NOT NULL");
        }
    }

    /**
     * 判断教材记录是否已存在。
     *
     * @param editionId 教材标识
     * @return 存在时为 true
     */
    public boolean textbookExists(String editionId) {
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM toolbox_textbook WHERE edition_id = ?", Integer.class, editionId);
        return rows != null && rows > 0;
    }

    /**
     * 读取当前目录世代。
     *
     * @return 世代号；未初始化时为 0
     */
    public long currentEpoch() {
        Long epoch = jdbc.queryForObject(
                "SELECT epoch FROM toolbox_curriculum_catalog WHERE catalog_key = 'current'", Long.class);
        return epoch == null ? 0L : epoch;
    }

    /**
     * 读取当前**目录版本号**（F14）。
     *
     * 为什么必须读列而不是由 epoch 现算（此前是 `"catalog-" + currentEpoch()`）：
     * 设计第 8 节第 5 点要求**初始迁移保留旧 catalogVersion、catalog epoch 独立初始化**——
     * 迁移来的目录版本号（如 `catalog-7`）与世代号没有派生关系，
     * 现算会把它替换成 `catalog-0`，于是"文件包里到底是哪个版本"这件事在库里丢失。
     *
     * @return 目录版本号；值缺失时退回按世代号拼装（与改造前的行为一致）
     */
    public String currentCatalogVersion() {
        List<String> rows = jdbc.queryForList(
                "SELECT catalog_version FROM toolbox_curriculum_catalog WHERE catalog_key = 'current'",
                String.class);
        if (rows.isEmpty() || rows.get(0) == null) {
            return "catalog-" + currentEpoch();
        }
        return rows.get(0);
    }

    /**
     * 安装**初始迁移**带来的目录版本号（F14）。
     *
     * 限制：**只在从未发生真实发布时生效**（`epoch = 0`）。
     * - 迁移只导入既有内容、不发布新版本，因此这时 epoch 仍是 0；
     * - 一旦发生过真实发布，`catalog_version` 已经是那次发布生成的新版本号，
     *   此时再跑一次迁移**不能**把它倒退回文件包里的值——
     *   否则公开目录版本号会在重跑迁移后凭空回退。
     *
     * 本方法**不动 epoch**：世代号只由真实发布单调递增，与迁移包版本号无关。
     *
     * @param catalogVersion 迁移清单声明的目录版本号
     * @return 实际更新的行数；0 表示已经发布过，保持现状
     */
    public int installInitialCatalogVersion(String catalogVersion) {
        return jdbc.update("""
                UPDATE toolbox_curriculum_catalog
                   SET catalog_version = ?, updated_at = ?
                 WHERE catalog_key = 'current'
                   AND epoch = 0
                """, catalogVersion, new Timestamp(System.currentTimeMillis()));
    }

    /**
     * 读取已发布版本的原始字节与哈希，用于对账与历史读取。
     *
     * @param editionId      教材标识
     * @param contentVersion 内容版本号
     * @return 版本行；不存在时为空
     */
    public Optional<VersionRow> findVersion(String editionId, String contentVersion) {
        List<VersionRow> rows = jdbc.query("""
                SELECT version_id, edition_id, content_version, content_bytes, content_sha256, published_by,
                       published_at
                FROM toolbox_textbook_version WHERE edition_id = ? AND content_version = ?
                """, (rs, rowNum) -> new VersionRow(
                rs.getString("version_id"), rs.getString("edition_id"), rs.getString("content_version"),
                rs.getBytes("content_bytes"), rs.getString("content_sha256"), rs.getString("published_by"), publishedAtOf(rs)),
                editionId, contentVersion);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 记录一条审计。
     *
     * 审计是"谁在何时做了什么"的唯一权威记录：幂等回执会按幂等键去重，
     * 而审计必须是**每次操作一条**，否则无法回答"这次发布是谁触发的"。
     *
     * @param actor        操作者用户标识
     * @param action       动作，如 `draft.save`／`textbook.publish`
     * @param editionId    教材标识；不适用时为 null
     * @param fromRevision 变更前修订号；不适用时为 null
     * @param toRevision   变更后修订号；不适用时为 null
     * @param fromVersion  变更前内容版本；不适用时为 null
     * @param toVersion    变更后内容版本；不适用时为 null
     * @param summary      人类可读摘要
     */
    public void recordAudit(String actor, String action, String editionId, Long fromRevision, Long toRevision,
                            String fromVersion, String toVersion, String summary) {
        jdbc.update("""
                INSERT INTO toolbox_curriculum_audit (actor_user_id, action, edition_id, from_revision,
                    to_revision, from_version, to_version, summary, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, actor, action, editionId, fromRevision, toRevision, fromVersion, toVersion, summary,
                new Timestamp(System.currentTimeMillis()));
    }

    /**
     * 读取某册教材的审计记录，按时间正序，用于验证"谁在何时做了什么"。
     *
     * @param editionId 教材标识
     * @return 审计行
     */
    public List<Map<String, Object>> findAudit(String editionId) {
        return jdbc.queryForList("""
                SELECT actor_user_id, action, from_revision, to_revision, from_version, to_version, summary
                FROM toolbox_curriculum_audit WHERE edition_id = ? ORDER BY id
                """, editionId);
    }

    /**
     * 取某版本发布审计行里的 `summary`，用于把发布时填写的修改摘要回给版本记录。
     *
     * **关联键是 `(edition_id, action='textbook.publish', to_version)`**：
     * - `to_version` 是发布时**服务端分配的内容版本号**，版本表 `content_version` 就是同一个值，
     *   而同一次发布只写一条 `textbook.publish` 审计，因此二者一一对应且稳定；
     * - `to_revision` 是**草稿**修订号（发布写回 ID 映射之后的那个），与版本号没有派生关系，
     *   且同一次发布里 `textbook.confirm` 也复用同一个版本号——所以必须按 `action` 过滤，
     *   不能只用 `to_version`。
     *
     * 取最近一条（`ORDER BY id DESC`）：正常只有一条，万一历史数据重复也不会取到旧摘要。
     *
     * @param editionId      教材标识
     * @param contentVersion 内容版本号
     * @return 该版本的发布审计摘要；没有对应审计行或摘要为空时返回空
     */
    public Optional<String> findPublishAuditSummary(String editionId, String contentVersion) {
        List<String> rows = jdbc.query("""
                SELECT summary FROM toolbox_curriculum_audit
                WHERE edition_id = ? AND action = 'textbook.publish' AND to_version = ?
                ORDER BY id DESC LIMIT 1
                """, (resultSet, rowNumber) -> resultSet.getString(1), editionId, contentVersion);
        // summary 列可空：空值按"没有摘要"处理，绝不返回 null 元素给调用方。
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /**
     * 记录幂等回执。
     *
     * @param actor    操作者用户标识
     * @param scope    操作范围
     * @param key      幂等键
     * @param digest   请求摘要
     * @param result   结果 JSON
     */
    public void recordOperation(String actor, String scope, String key, String digest, String result) {
        jdbc.update("""
                INSERT INTO toolbox_admin_operation (actor_user_id, operation_scope, idempotency_key,
                    request_digest, result_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, actor, scope, key, digest, result, new Timestamp(System.currentTimeMillis()));
    }

    /**
     * 查询幂等回执。
     *
     * @param actor 操作者用户标识
     * @param scope 操作范围
     * @param key   幂等键
     * @return 回执；不存在时为空
     */
    public Optional<Map<String, Object>> findOperation(String actor, String scope, String key) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT request_digest, result_json FROM toolbox_admin_operation
                WHERE actor_user_id = ? AND operation_scope = ? AND idempotency_key = ?
                """, actor, scope, key);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 教材列表行。
     *
     * @param editionId      教材标识
     * @param currentVersionId 当前版本标识；未发布时为 null
     * @param contentVersion 内容版本号；未发布时为 null
     * @param updatedAt      最近更新时间（毫秒）
     * @param updatedBy      最近修改人
     * @param metadataJson   当前版本的声明元数据 JSON；未发布时为 null
     */
    public record TextbookListRow(String editionId, String currentVersionId, String contentVersion,
                                  long updatedAt, String updatedBy, String metadataJson,
                                  long draftRevision, long lastPublishedRevision) {
    }

    /**
     * 新建教材的返回。
     *
     * @param draftId  草稿标识
     * @param revision 初始修订号
     */
    public record DraftRef(String draftId, long revision) {
    }

    /**
     * 草稿行。
     *
     * @param draftId            草稿标识
     * @param editionId          教材标识
     * @param baseContentVersion 基线内容版本
     * @param revision           当前修订号
     * @param editableJson       草稿正文
     * @param sourceMetadataJson 草稿级来源快照；可为 null
     * @param updatedBy          最近修改人
     */
    public record DraftRow(String draftId, String editionId, String baseContentVersion, long revision,
                           String editableJson, String sourceMetadataJson, String updatedBy) {
    }

    /**
     * 版本行。
     *
     * @param versionId      版本标识
     * @param editionId      教材标识
     * @param contentVersion 内容版本号
     * @param contentBytes   正文原始字节
     * @param contentSha256  正文哈希
     * @param publishedBy    发布人
     */
    public record VersionRow(String versionId, String editionId, String contentVersion, byte[] contentBytes,
                             String contentSha256, String publishedBy, long publishedAt) {
    }
}
