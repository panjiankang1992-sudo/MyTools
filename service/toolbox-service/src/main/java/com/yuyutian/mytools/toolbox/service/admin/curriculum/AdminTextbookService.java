package com.yuyutian.mytools.toolbox.service.admin.curriculum;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.toolbox.common.BusinessException;
import com.yuyutian.mytools.toolbox.common.ErrorCode;
import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import com.yuyutian.mytools.toolbox.model.CoverageStatus;
import com.yuyutian.mytools.toolbox.model.ReviewStatus;
import com.yuyutian.mytools.toolbox.model.Semester;
import com.yuyutian.mytools.toolbox.model.Subject;
import com.yuyutian.mytools.toolbox.model.TextbookEdition;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft;
import com.yuyutian.mytools.toolbox.service.admin.validation.CurriculumValidator;
import com.yuyutian.mytools.toolbox.service.admin.validation.CurriculumValidator.Level;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 管理端教材编辑服务：创建草稿、CAS 保存、校验与发布。
 * 权限由过滤器统一校验；本层只处理业务规则与并发前提。
 */
@Service
public class AdminTextbookService {

    private final AdminTextbookRepository repository;
    private final CurriculumValidator validator;
    private final CurriculumPublisher publisher;

    private final com.yuyutian.mytools.toolbox.config.CurriculumProperties curriculumProperties;

    /** 按教材的发布锁；见 `publish` 的说明。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> publishLocks =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final DraftToBookMapper mapper;
    private final ObjectMapper objectMapper;
    private final com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder snapshotBuilder;
    private final com.yuyutian.mytools.toolbox.config.ToolboxProperties toolboxProperties;

    /**
     * 创建管理端教材服务。
     *
     * @param repository   教材仓储
     * @param validator    统一校验器
     * @param publisher    发布器
     * @param curriculumProperties 目录来源配置
     * @param mapper       草稿→公开结构映射器
     * @param objectMapper JSON 序列化器
     * @param snapshotBuilder 来源快照构建器
     * @param toolboxProperties 门面配置（提供内容根目录）
     */
    public AdminTextbookService(AdminTextbookRepository repository, CurriculumValidator validator,
                                CurriculumPublisher publisher,
                                com.yuyutian.mytools.toolbox.config.CurriculumProperties curriculumProperties,
                                DraftToBookMapper mapper,
                                ObjectMapper objectMapper,
                                com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder snapshotBuilder,
                                com.yuyutian.mytools.toolbox.config.ToolboxProperties toolboxProperties) {
        this.repository = repository;
        this.validator = validator;
        this.publisher = publisher;
        this.curriculumProperties = curriculumProperties;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.snapshotBuilder = snapshotBuilder;
        this.toolboxProperties = toolboxProperties;
    }

    /**
     * 创建教材草稿；这一步只建草稿，不公开。
     *
     * @param draft 草稿（至少包含教材标识与基本元数据）
     * @param actor 操作者用户标识
     * @return 草稿标识与初始修订号
     */
    public AdminTextbookRepository.DraftRef create(AdminTextbookDraft draft, String actor) {
        if (draft == null || draft.editionId() == null || draft.editionId().isBlank()) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_005, Map.of("field", "editionId"));
        }
        if (repository.textbookExists(draft.editionId())) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_004, Map.of("reason", "editionExists"));
        }
        AdminTextbookRepository.DraftRef ref = repository.createTextbook(draft.editionId(), actor);
        saveDraft(draft.editionId(), ref.revision(), draft, actor);
        return new AdminTextbookRepository.DraftRef(ref.draftId(), ref.revision() + 1);
    }

    /**
     * 读取草稿。
     *
     * @param editionId 教材标识
     * @return 草稿内容与修订号
     */
    public Optional<StoredDraft> findDraft(String editionId) {
        Optional<AdminTextbookRepository.DraftRow> row = repository.findDraft("draft-" + editionId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        AdminTextbookRepository.DraftRow draft = row.get();
        try {
            AdminTextbookDraft parsed = objectMapper.readValue(draft.editableJson(), AdminTextbookDraft.class);
            return Optional.of(new StoredDraft(parsed, draft.revision(), draft.baseContentVersion(),
                    draft.sourceMetadataJson()));
        } catch (Exception failure) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_009, Map.of("reason", "draftUnparsable"));
        }
    }

    /**
     * 保存草稿；修订号不一致返回冲突，不覆盖他人改动。
     *
     * @param editionId        教材标识
     * @param expectedRevision 调用方持有的修订号
     * @param draft            草稿内容
     * @param actor            操作者用户标识
     * @return 新修订号
     */
    public long saveDraft(String editionId, long expectedRevision, AdminTextbookDraft draft, String actor) {
        // 简化录入只要求教材选择与配对内容；未填写来源时记录本次操作的真实来源。
        draft = withManualSourceDefault(draft);
        // 草稿保存只做宽松校验：允许暂缺，但数据自相矛盾（如拼音数不符）仍然拒绝。
        var report = validator.validate(draft, Level.DRAFT);
        String json = serialize(draft);
        // 草稿级来源快照：与正文同一次写入，保证"草稿→发布→详情→导出"这条线上来源不丢。
        // 仓储用 `COALESCE` 写入，快照构建失败也不应让一次正常的正文保存失败——
        // 因此这里不抛异常，构建器内部已把缺失情况标成 missing。
        String sourceSnapshotJson = buildDraftSnapshot(editionId, draft, actor);
        long newRevision = repository.saveDraft("draft-" + editionId, expectedRevision, json,
                        sourceSnapshotJson, actor)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOLBOX_ADMIN_004,
                        Map.of("reason", "revisionConflict", "expectedRevision", expectedRevision)));
        // 审计在 CAS 成功之后写入：冲突的保存没有产生变更，不应留下"改过"的记录。
        repository.recordAudit(actor, "draft.save", editionId, expectedRevision, newRevision,
                null, null, "修订 " + expectedRevision + " → " + newRevision);
        return newRevision;
    }

    /**
     * 将已保存草稿无损同步到最新教材；旧版本、当前版本与草稿均参与比较。
     * 有冲突时事务不写入，管理员原稿保持完整。
     */
    @org.springframework.transaction.annotation.Transactional
    public StoredDraft rebaseDraft(String editionId, long expectedRevision, String actor) {
        requireWritableRepository();
        repository.lockTextbook(editionId);
        StoredDraft stored = findDraft(editionId).orElseThrow(() ->
                new BusinessException(ErrorCode.TOOLBOX_ADMIN_006, Map.of("reason", "draftMissing")));
        if (stored.revision() != expectedRevision) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_004,
                    Map.of("reason", "revisionConflict", "expectedRevision", expectedRevision));
        }
        String current = repository.findCurrentVersionId(editionId).orElse(null);
        if (java.util.Objects.equals(stored.baseContentVersion(), current)) {
            return stored;
        }
        String base = stored.baseContentVersion();
        if (base == null || current == null || !base.startsWith(editionId + "@")) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_004, Map.of("reason", "baselineUnavailable"));
        }
        var before = repository.findVersion(editionId, base.substring(editionId.length() + 1))
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOLBOX_ADMIN_004,
                        Map.of("reason", "baselineUnavailable")));
        var latest = repository.findVersion(editionId, current.substring(editionId.length() + 1))
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOLBOX_ADMIN_004,
                        Map.of("reason", "baselineUnavailable")));
        TextbookDraftMerger.MergeResult merged;
        try {
            merged = new TextbookDraftMerger(objectMapper).merge(editionId,
                    before.contentBytes(), latest.contentBytes(), stored.draft());
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_004, Map.of("reason", "baselineUnavailable"));
        }
        if (!merged.conflicts().isEmpty()) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_004,
                    Map.of("reason", "rebaseConflict", "conflicts", merged.conflicts()));
        }
        String snapshot = buildDraftSnapshot(editionId, merged.draft(), actor, draftBaseline(editionId, current));
        long revision = repository.rebaseDraft("draft-" + editionId, expectedRevision, base, current,
                serialize(merged.draft()), snapshot, actor).orElseThrow(() ->
                new BusinessException(ErrorCode.TOOLBOX_ADMIN_004, Map.of("reason", "revisionConflict")));
        repository.recordAudit(actor, "draft.rebase", editionId, expectedRevision, revision,
                base, current, "草稿与最新教材同步");
        return new StoredDraft(merged.draft(), revision, current, snapshot);
    }

    /**
     * 分页查询教材列表。
     *
     * @param query 查询条件
     * @return 当前页结果与总数
     */
    public ListPage list(AdminTextbookQuery query) {
        AdminTextbookQuery normalized = query.normalized();
        var rows = repository.listTextbooks(normalized.keyword(), normalized.subject(), normalized.grade(),
                normalized.semester(), normalized.status(), normalized.pageSize(), normalized.offset());
        int total = repository.countTextbooks(normalized.keyword(), normalized.subject(), normalized.grade(),
                normalized.semester(), normalized.status());
        List<ListItem> items = new java.util.ArrayList<>();
        for (AdminTextbookRepository.TextbookListRow row : rows) {
            // F12：**"已发布·有修改"**——已发布过，且草稿在那之后又改过。
            //
            // `lastPublishedRevision` 为 -1 表示从未发布过（那时谈不上"有修改"，
            // 它只是一份草稿）。这个判据不用 `base_content_version`：
            // 编辑草稿不会改动基线，那样比较永远相等。
            boolean hasUnpublishedChanges = row.lastPublishedRevision() >= 0
                    && row.draftRevision() > row.lastPublishedRevision();
            items.add(new ListItem(row.editionId(), row.contentVersion(), row.currentVersionId() != null,
                    row.updatedAt(), row.updatedBy(), row.metadataJson(), hasUnpublishedChanges));
        }
        return new ListPage(List.copyOf(items), total, normalized.page(), normalized.pageSize());
    }

    /**
     * 生成草稿预览载荷（设计文档 §3.6）。
     *
     * 关键点：预览**使用与发布相同的映射器**（`DraftToBookMapper`），因此
     * "预览里看到的内容"与"发布后会得到的内容"一致；预览不会写入任何表，
     * 也不会改变草稿的 ID 映射——它只做一次只读映射。
     *
     * @param editionId 教材标识
     * @return 与公开导入同形的载荷
     */
    public PreviewPayload preview(String editionId) {
        return preview(editionId, null, null, null);
    }

    /**
     * 生成**指定范围**的草稿预览载荷（设计 164 行：「预览字帖」使用当前草稿的**已选范围**）。
     *
     * 为什么预览必须支持范围：此前预览只能整册生成，管理员选中一个单元也只能拿到整册内容，
     * 界面因此**如实标注"仍按全册生成"**——那不是能力，是缺失。这里补上范围过滤。
     *
     * 契约（**缺省即整册，与改造前完全一致**）：
     * - `nodeKind` 与 `nodeKey` 都不给（或都为空）→ 整册，返回内容与改造前逐字一致；
     * - `nodeKind=unit` + `nodeKey=<clientUnitId|unitId>` → 只含该单元及其课文与条目；
     * - `nodeKind=lesson` + `nodeKey=<clientLessonId|lessonId>` → 只含该课文及其条目
     *   与它所属的单元；
     * - 只给其中一个、或 `nodeKind` 不是 `unit`／`lesson`、或标识在草稿里找不到
     *   → **404**（`TOOLBOX_ADMIN_006`，`details.reason=previewScopeNotFound`）。
     *   这里**故意不退回整册**：把"范围打错了"静默变成"给你整册"会让管理员以为
     *   自己看到的是选中范围，与"如实标注"是同一类错误。
     * - `expectedDraftRevision` 可选：给了就**必须**与库中当前修订一致，否则 409
     *   （与 `/validate` 的 `draftRevision` 同一语义）。不给则不比较——缺省行为与改造前一致。
     *
     * 范围过滤发生在**映射之后**：条目沿用与发布完全相同的正式 ID 与顺序号，
     * 因此"预览里选中的这一条"在发布后仍是同一条。
     *
     * @param editionId 教材标识
     * @param nodeKind  范围类型：`unit`／`lesson`；为空表示整册
     * @param nodeKey   范围标识：单元的 clientUnitId／unitId，或课文的 clientLessonId／lessonId
     * @param expectedDraftRevision 调用方声称的草稿修订号；为空时不比较
     * @return 与公开导入同形的载荷，附带本次生成所依据的草稿修订号与范围回显
     */
    public PreviewPayload preview(String editionId, String nodeKind, String nodeKey,
                                  Long expectedDraftRevision) {
        StoredDraft stored = findDraft(editionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOLBOX_ADMIN_006, Map.of("reason", "draftMissing")));
        // F07 同款前提：界面声称"预览的是我保存的第 N 版"时，服务端必须真的核对第 N 版，
        // 否则预览可能生成自**别人刚改过的**草稿，而界面仍标注成"N"。
        if (expectedDraftRevision != null && expectedDraftRevision != stored.revision()) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_004,
                    Map.of("reason", "revisionMismatch", "expected", expectedDraftRevision,
                            "actual", stored.revision()));
        }
        // 预览用 nextContentVersion 之外的占位版本号：预览不是发布，不能占用正式版本号。
        DraftToBookMapper.MappedBook mapped = mapper.map(editionId, stored.draft(), "preview");
        DraftToBookMapper.MappedBook scoped = applyPreviewScope(stored.draft(), mapped, nodeKind, nodeKey);
        // 预览也按真实内容统计条目数，保证预览与发布后的元数据一致。
        // 带范围时按**范围内的内容**统计：否则响应会谎报"包含 318 条"却只返回 12 条。
        TextbookEdition declared = toEdition(stored.draft(), scoped);
        PreviewScope scope = nodeKey == null || nodeKey.isBlank()
                ? PreviewScope.wholeBook()
                : new PreviewScope(nodeKind, nodeKey, true);
        return new PreviewPayload(declared, scoped.units(), scoped.lessons(), scoped.entries(),
                stored.revision(), scope);
    }

    /**
     * 按范围过滤已映射的公开结构。
     *
     * 命中规则同时接受**客户端临时标识**与**正式标识**：
     * 草稿里新建节点只有 `clientUnitId`／`clientLessonId`，而发布过的节点还带正式 `unitId`／`lessonId`；
     * 编辑器用的是客户端标识，接口不能因此只认一种。
     *
     * @param draft    草稿
     * @param mapped   已映射的公开结构
     * @param nodeKind 范围类型
     * @param nodeKey  范围标识
     * @return 过滤后的公开结构；整册范围原样返回
     * @throws BusinessException 请求了范围但该范围在草稿里不存在时抛出 404
     */
    private DraftToBookMapper.MappedBook applyPreviewScope(AdminTextbookDraft draft,
                                                           DraftToBookMapper.MappedBook mapped,
                                                           String nodeKind, String nodeKey) {
        // 缺省（两个参数都没有）＝整册：与改造前完全一致。
        boolean kindMissing = nodeKind == null || nodeKind.isBlank();
        boolean keyMissing = nodeKey == null || nodeKey.isBlank();
        if (kindMissing && keyMissing) {
            return mapped;
        }
        // 半截参数不是"整册"：范围没被完整指定，按"该范围不存在"如实拒绝。
        if (kindMissing || keyMissing) {
            throw scopeNotFound(nodeKind, nodeKey);
        }
        if ("unit".equals(nodeKind)) {
            return scopeToUnit(draft, mapped, nodeKey);
        }
        if ("lesson".equals(nodeKind)) {
            return scopeToLesson(draft, mapped, nodeKey);
        }
        // 未识别的范围类型：不猜、不退化成整册。
        throw scopeNotFound(nodeKind, nodeKey);
    }

    /** 过滤到单个单元：保留该单元、它名下的课文与条目。 */
    private DraftToBookMapper.MappedBook scopeToUnit(AdminTextbookDraft draft,
                                                     DraftToBookMapper.MappedBook mapped, String nodeKey) {
        String officialUnitId = resolveUnitId(draft, mapped, nodeKey);
        if (officialUnitId == null) {
            // 单元没有可用身份（既无正式标识也无映射）：无法与任何条目对齐，如实判为不存在。
            throw scopeNotFound("unit", nodeKey);
        }
        java.util.List<com.yuyutian.mytools.toolbox.model.CurriculumUnit> scopedUnits = new java.util.ArrayList<>();
        for (com.yuyutian.mytools.toolbox.model.CurriculumUnit unit : mapped.units()) {
            if (officialUnitId.equals(unit.unitId())) {
                scopedUnits.add(unit);
            }
        }
        java.util.List<com.yuyutian.mytools.toolbox.model.CurriculumLesson> scopedLessons =
                new java.util.ArrayList<>();
        for (com.yuyutian.mytools.toolbox.model.CurriculumLesson lesson : mapped.lessons()) {
            if (officialUnitId.equals(lesson.unitId())) {
                scopedLessons.add(lesson);
            }
        }
        java.util.List<com.yuyutian.mytools.toolbox.model.CurriculumEntry> scopedEntries =
                new java.util.ArrayList<>();
        for (com.yuyutian.mytools.toolbox.model.CurriculumEntry entry : mapped.entries()) {
            // 只按单元归属过滤：单元下的课文条目同样属于这个单元。
            if (officialUnitId.equals(entry.unitId())) {
                scopedEntries.add(entry);
            }
        }
        return new DraftToBookMapper.MappedBook(List.copyOf(scopedUnits), List.copyOf(scopedLessons),
                List.copyOf(scopedEntries), mapped.unitIdMapping(), mapped.lessonIdMapping(),
                mapped.entryIdMapping(), mapped.persistedMapping());
    }

    /** 过滤到单篇课文：保留该课文、它的父单元与它名下的条目。 */
    private DraftToBookMapper.MappedBook scopeToLesson(AdminTextbookDraft draft,
                                                       DraftToBookMapper.MappedBook mapped, String nodeKey) {
        String officialLessonId = resolveLessonId(draft, mapped, nodeKey);
        if (officialLessonId == null) {
            throw scopeNotFound("lesson", nodeKey);
        }
        // 父单元：草稿里的课文只有临时单元标识，因此同样要经过解析。
        String officialUnitId = null;
        java.util.List<AdminTextbookDraft.DraftLesson> lessons =
                draft.lessons() == null ? List.of() : draft.lessons();
        for (AdminTextbookDraft.DraftLesson lesson : lessons) {
            if (nodeKey.equals(lesson.clientLessonId()) || nodeKey.equals(lesson.lessonId())
                    || officialLessonId.equals(lesson.lessonId())) {
                String parentKey = lesson.unitId() != null && !lesson.unitId().isBlank()
                        ? lesson.unitId() : lesson.clientUnitId();
                officialUnitId = parentKey == null ? null : resolveUnitId(draft, mapped, parentKey);
                break;
            }
        }
        java.util.List<com.yuyutian.mytools.toolbox.model.CurriculumUnit> scopedUnits = new java.util.ArrayList<>();
        for (com.yuyutian.mytools.toolbox.model.CurriculumUnit unit : mapped.units()) {
            if (officialUnitId != null && officialUnitId.equals(unit.unitId())) {
                scopedUnits.add(unit);
            }
        }
        java.util.List<com.yuyutian.mytools.toolbox.model.CurriculumLesson> scopedLessons =
                new java.util.ArrayList<>();
        for (com.yuyutian.mytools.toolbox.model.CurriculumLesson lesson : mapped.lessons()) {
            if (officialLessonId.equals(lesson.lessonId())) {
                scopedLessons.add(lesson);
            }
        }
        java.util.List<com.yuyutian.mytools.toolbox.model.CurriculumEntry> scopedEntries =
                new java.util.ArrayList<>();
        for (com.yuyutian.mytools.toolbox.model.CurriculumEntry entry : mapped.entries()) {
            // 条目必须明确属于该课文；`lessonId` 为空的条目不属于任何课文，不能凭单元相同被算进来。
            if (officialLessonId.equals(entry.lessonId())) {
                scopedEntries.add(entry);
            }
        }
        return new DraftToBookMapper.MappedBook(List.copyOf(scopedUnits), List.copyOf(scopedLessons),
                List.copyOf(scopedEntries), mapped.unitIdMapping(), mapped.lessonIdMapping(),
                mapped.entryIdMapping(), mapped.persistedMapping());
    }

    /**
     * 把范围键解析为**单元的正式标识**。
     *
     * 为什么要同时认两套标识：草稿保存后，`DraftUnit.unitId` 往往仍是 null——
     * 正式标识只写在持久化映射（`u:<clientUnitId>`）与映射结果里。
     * 编辑器用的是客户端临时标识，而已发布教材的界面可能拿到正式标识，两者都必须能命中。
     *
     * @param draft    草稿
     * @param mapped   已映射的公开结构
     * @param nodeKey  范围键
     * @return 正式单元标识；无法解析时为 null
     */
    private static String resolveUnitId(AdminTextbookDraft draft, DraftToBookMapper.MappedBook mapped,
                                        String nodeKey) {
        // a) 范围键本身就是映射结果里的正式标识。
        for (com.yuyutian.mytools.toolbox.model.CurriculumUnit unit : mapped.units()) {
            if (nodeKey.equals(unit.unitId())) {
                return unit.unitId();
            }
        }
        // b) 范围键是草稿节点的临时标识（或草稿自己记下的正式标识）：经映射表换算。
        java.util.List<AdminTextbookDraft.DraftUnit> units =
                draft.units() == null ? List.of() : draft.units();
        for (AdminTextbookDraft.DraftUnit unit : units) {
            if (nodeKey.equals(unit.clientUnitId()) || nodeKey.equals(unit.unitId())) {
                if (unit.unitId() != null && !unit.unitId().isBlank()) {
                    return unit.unitId();
                }
                return unit.clientUnitId() == null ? null : mapped.unitIdMapping().get(unit.clientUnitId());
            }
        }
        return null;
    }

    /**
     * 把范围键解析为**课文的正式标识**（规则同 {@link #resolveUnitId}）。
     *
     * @param draft    草稿
     * @param mapped   已映射的公开结构
     * @param nodeKey  范围键
     * @return 正式课文标识；无法解析时为 null
     */
    private static String resolveLessonId(AdminTextbookDraft draft, DraftToBookMapper.MappedBook mapped,
                                          String nodeKey) {
        for (com.yuyutian.mytools.toolbox.model.CurriculumLesson lesson : mapped.lessons()) {
            if (nodeKey.equals(lesson.lessonId())) {
                return lesson.lessonId();
            }
        }
        java.util.List<AdminTextbookDraft.DraftLesson> lessons =
                draft.lessons() == null ? List.of() : draft.lessons();
        for (AdminTextbookDraft.DraftLesson lesson : lessons) {
            if (nodeKey.equals(lesson.clientLessonId()) || nodeKey.equals(lesson.lessonId())) {
                if (lesson.lessonId() != null && !lesson.lessonId().isBlank()) {
                    return lesson.lessonId();
                }
                return lesson.clientLessonId() == null
                        ? null : mapped.lessonIdMapping().get(lesson.clientLessonId());
            }
        }
        return null;
    }

    /** 构造"预览范围不存在"的 404；details 里如实带上请求的范围，便于前端定位。 */
    private static BusinessException scopeNotFound(String nodeKind, String nodeKey) {
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("reason", "previewScopeNotFound");
        details.put("nodeKind", nodeKind);
        details.put("nodeKey", nodeKey);
        return new BusinessException(ErrorCode.TOOLBOX_ADMIN_006, details);
    }

    /**
     * 校验已保存的草稿修订，返回是否可发布。
     *
     * @param editionId 教材标识
     * @return 校验报告
     */
    public CurriculumValidator.ValidationReport validate(String editionId) {
        return validate(editionId, null).report();
    }

    /**
     * 校验草稿，并把结果**绑定到具体的修订号**（F07）。
     *
     * 为什么要绑定：界面校验了第 5 版、用户在另一个标签页把它改到第 6 版，
     * 界面拿到的报告是**第 5 版的结论**却显示在第 6 版上——
     * 用户会以为"当前内容没问题"，而它其实**没被校验过**。
     *
     * @param editionId       教材标识
     * @param expectedRevision 期望的修订号；为 null 时不比较
     * @return 校验报告与**它所对应的修订号**
     */
    public ValidateResult validate(String editionId, Long expectedRevision) {
        StoredDraft stored = findDraft(editionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOLBOX_ADMIN_006, Map.of("reason", "draftMissing")));
        // 修订号不符：**拒绝**，而不是拿旧结论冒充当前结论。
        if (expectedRevision != null && expectedRevision != stored.revision()) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_004, Map.of("reason", "revisionMismatch"));
        }
        return new ValidateResult(stored.revision(), validator.validate(stored.draft(), Level.PUBLISH));
    }

    /**
     * 校验结果：报告 + 它对应的修订号。
     *
     * @param draftRevision 被校验的修订号
     * @param report        校验报告
     */
    public record ValidateResult(long draftRevision, CurriculumValidator.ValidationReport report) {
    }

    /**
     * 发布草稿；发布前由服务端重新校验，不信任先前的校验结果。
     *
     * @param editionId 教材标识
     * @param actor     操作者用户标识
     * @return 发布结果
     */
    @org.springframework.transaction.annotation.Transactional
    public CurriculumPublisher.PublishResult publish(String editionId, String actor, Long expectedRevision) {
        return publish(editionId, actor, expectedRevision, null);
    }

    /**
     * 发布草稿，并记录管理员填写的修改摘要（设计第 160／166／362 行）。
     *
     * 摘要是**可选**的：为空时审计摘要保持原文本，版本记录里该字段为空，
     * 界面据此显示"未记录"而不是空串。
     *
     * @param editionId      教材标识
     * @param actor          操作者用户标识
     * @param expectedRevision 调用方声称的草稿修订号；与库中不一致时 409
     * @param changeNote     管理员填写的修改摘要；可为 null 或空白
     * @return 发布结果
     */
    @org.springframework.transaction.annotation.Transactional
    public CurriculumPublisher.PublishResult publish(String editionId, String actor, Long expectedRevision,
                                                    String changeNote) {
        // F11：**目录来源是只读文件时禁止发布**。
        //
        // `repository=files` 下公开目录读文件目录，而发布写的是数据库——
        // 于是"发布成功"对外**什么都不会变**，而且不报错：管理员以为发布完成，
        // 用户刷新却看不到任何变化。这是审查 F11 复现的静默失败。
        //
        // 闸门放在**最前面**：被拒绝的发布不能留下任何痕迹
        // （写回 ID 映射、推进修订号、递增世代都属于"已经发生了变更"）。
        requireWritableRepository();
        // F07：**整段发布必须在"按教材"的锁内**。
        //
        // 此前这里一把锁都没有：读草稿、分配版本、写版本、换快照全是裸的。
        // 后果是两次并发发布同一册时会**各自分配到同一个版本号**
        // （`nextContentVersion` 是"查现有版本再推下一个"，两次读之间没有互斥），
        // 然后互相覆盖或撞主键。
        //
        // 锁按教材粒度：不同教材可以并行发布（审查要求"两册同时发布不丢其中一本"），
        // 同一册则严格串行。
        //
        // 进程内锁减少同一实例的重复工作；事务内另锁教材数据库行，
        // 与草稿保存、文件导入及三方同步使用同一锁顺序，防止跨进程更新当前版本。
        Object lock = publishLocks.computeIfAbsent(editionId, key -> new Object());
        synchronized (lock) {
            return publishLocked(editionId, actor, expectedRevision, changeNote);
        }
    }

    /**
     * 发布的前置条件：目录来源必须是**可写的数据库**（F11）。
     *
     * `repository` 既不是 `database`、也不是拼错的值时，`usesDatabase()` 为 false，
     * 公开目录走文件目录——此时发布写数据库对外不可见，必须**明确拒绝**而不是静默成功。
     *
     * @throws BusinessException 目录来源为只读文件时抛出 `TOOLBOX_ADMIN_011`
     */
    private void requireWritableRepository() {
        if (!curriculumProperties.usesDatabase()) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_011, Map.of("reason", "repositoryIsFiles"));
        }
    }

    /** 发布的实际执行体；**必须**在 `publish` 的按教材锁内调用。 */
    private CurriculumPublisher.PublishResult publishLocked(String editionId, String actor, Long expectedRevision,
                                                            String changeNote) {
        repository.lockTextbook(editionId);
        StoredDraft stored = findDraft(editionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOLBOX_ADMIN_006, Map.of("reason", "draftMissing")));
        // F07：**发布必须绑定"你确认过的那一版"**。
        //
        // 此前发布只带教材标识，服务端读取**此刻**的草稿——
        // 于是"甲看到 revision N、乙又改了草稿、甲点了发布"会把**乙的改动**一起发出去，
        // 而甲从界面上完全看不出来自己发的不是他核对过的内容。
        //
        // 带 `If-Match` 的保存已经用同样的思路保护写入了；发布是更重的一步，
        // **不能比保存还松**。
        if (expectedRevision != null && expectedRevision != stored.revision()) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_004,
                    Map.of("reason", "revisionMismatch", "expected", expectedRevision,
                            "actual", stored.revision()));
        }
        var report = validator.validate(stored.draft(), Level.PUBLISH);
        if (!report.publishable()) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_005, Map.of("issues", report.issues().size()));
        }
        requireStableIdentity(editionId, stored.draft());
        // F07：**锁内基线校验**。
        //
        // 场景：本实例的草稿基于 v1；另一个实例在此期间发布了新版本。
        // 此时直接发布会把**别人刚发的内容覆盖掉**，而本实例并不知道。
        //
        // 两条重要的边界：
        // 1. **只在基线已知时比较**——生产上已有的教材，其草稿的
        //    `base_content_version` 是 NULL。严格比较会让"当前版本非空 vs 基线为空"
        //    判为不一致，**所有既有教材将无法再发布**。
        // 2. 比较的**两边都是 versionId**（`editionId@contentVersion`）——
        //    这是第 46 轮测出的事实；此前一边写 contentVersion、一边存 versionId，
        //    校验必然无条件触发。
        String baseline = stored.baseContentVersion();
        if (baseline != null) {
            String current = repository.findCurrentVersionId(editionId).orElse(null);
            if (!baseline.equals(current)) {
                throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_004,
                        Map.of("reason", "baselineMismatch", "baseline", baseline,
                                "current", String.valueOf(current)));
            }
        }
        // 先分配条目版本号，再映射：正文里的 entryVersion 必须与内容版本一致。
        String entryVersion = repository.nextContentVersion(editionId);
        DraftToBookMapper.MappedBook mapped = mapper.map(editionId, stored.draft(), entryVersion);
        // 声明元数据必须在映射之后生成：条目数要按**实际写入正文的内容**统计。
        // 若沿用草稿里的占位值，发布出的元数据会谎报条目数，目录重建时会被校验拒绝。
        TextbookEdition declared = toEdition(stored.draft(), mapped);
        // 把合并后的映射写回草稿：这样下次发布即使改标题、调顺序或插入相邻节点，既有正式 ID 也不会变。
        // **记下这次写回之后的修订号**：审计里的 `to_revision` 必须是它，
        // 而不是发布**之前**的 `stored.revision()`。
        //
        // 否则列表的"已发布·有修改"会**刚发布完就显示为 true**——
        // 因为写回 ID 映射本身又把修订号推进了一格（F12，第 88 轮实测出来）。
        AdminTextbookDraft mappedDraft = stored.draft().withIdMapping(mapped.persistedMapping());
        long revisionAfterMapping = repository.saveDraft("draft-" + editionId, stored.revision(),
                serialize(mappedDraft), buildDraftSnapshot(editionId, mappedDraft, actor), actor)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOLBOX_ADMIN_004,
                        Map.of("reason", "revisionMismatch")));
        CurriculumPublisher.PublishResult published = publisher.publishDraft(editionId, declared,
                mapped.units(), mapped.lessons(), mapped.entries(), entryVersion,
                "发布修订 " + stored.revision(), actor);
        // 审计在发布成功之后写入：只有真正产生的变更才值得记录。
        // 幂等重试会在幂等服务层直接返回回执、不会走到这里，因此审计是"每次真实发布一条"。
        //
        // 修改摘要是**可选**的：为空时摘要保持原文本（不写出空括号），
        // 有值时以固定标记附在末尾，版本记录按 `(edition_id, action, to_version)` 取回。
        repository.recordAudit(actor, "textbook.publish", editionId, stored.revision(), revisionAfterMapping,
                stored.baseContentVersion(), published.contentVersion(),
                withChangeNote("发布修订 " + stored.revision() + "，" + mapped.entries().size() + " 条内容",
                        changeNote));
        // 核对主体必须来自**服务端会话**，不能采信客户端字段（客户端可以随便填"是谁确认的"）。
        // 发布正是让核对生效的动作，因此"谁发布"就是"谁为这些已核对内容负责"。
        long verifiedCount = mapped.entries().stream()
                .filter(entry -> entry.reviewStatus() == ReviewStatus.PUBLISHED)
                .count();
        if (verifiedCount > 0) {
            repository.recordAudit(actor, "textbook.confirm", editionId, null, null, null,
                    published.contentVersion(), verifiedCount + " 条内容以已核对状态发布");
        }
        return published;
    }

    /** 发布审计摘要里修改摘要的起始标记。它只由 `withChangeNote` 写入，基础摘要里不会出现。 */
    private static final String CHANGE_NOTE_MARKER = "（修改摘要：";

    /**
     * 修改摘要的长度上限，与前端 `CHANGE_NOTE_MAX_LENGTH` 一致。
     *
     * 后端不把它当"校验"（设计未规定必填，更未规定上限）：它只是保证
     * `toolbox_curriculum_audit.summary`（VARCHAR(512)）一定写得下，
     * 不会因为一条超长摘要让整次发布在落审计时失败回滚。
     */
    private static final int CHANGE_NOTE_MAX_LENGTH = 200;

    /**
     * 把修改摘要附加到发布审计摘要末尾。
     *
     * 摘要为空（null／空白）时**返回原文本**，不写出空括号——审计里"没填"就应当看不出来。
     *
     * @param summary    基础摘要，如 `发布修订 7，12 条内容`
     * @param changeNote 管理员填写的修改摘要；可为空
     * @return 供写入审计 `summary` 列的文本
     */
    private static String withChangeNote(String summary, String changeNote) {
        String note = normalizeChangeNote(changeNote);
        return note == null ? summary : summary + CHANGE_NOTE_MARKER + note + "）";
    }

    /**
     * 规范化修改摘要：去首尾空白、截断到上限；空白视为"没有填写"并返回 null。
     *
     * @param changeNote 原始摘要
     * @return 规范化后的摘要；空白时为 null
     */
    private static String normalizeChangeNote(String changeNote) {
        if (changeNote == null) {
            return null;
        }
        String trimmed = changeNote.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() <= CHANGE_NOTE_MAX_LENGTH) {
            return trimmed;
        }
        int end = CHANGE_NOTE_MAX_LENGTH;
        // 不要把代理对截成半个字符：那会写不进 utf8mb4 列，也会显示成乱码。
        if (Character.isHighSurrogate(trimmed.charAt(end - 1))) {
            end--;
        }
        return trimmed.substring(0, end);
    }

    /**
     * 从发布审计摘要里取回管理员填写的修改摘要。
     *
     * 标记只写一次（基础摘要里没有），所以 `indexOf` 命中的一定是生成的那一处；
     * 末尾的 `）` 也是生成的那个，因此摘要正文里出现 `）` 不会被截错。
     * 格式不完整时返回 null——**不猜**。
     *
     * @param summary 审计 `summary` 列的原文
     * @return 修改摘要；没有填写或格式不认识时为 null
     */
    private static String changeNoteFromAuditSummary(String summary) {
        if (summary == null) {
            return null;
        }
        int start = summary.indexOf(CHANGE_NOTE_MARKER);
        if (start < 0) {
            return null;
        }
        String tail = summary.substring(start + CHANGE_NOTE_MARKER.length());
        if (!tail.endsWith("）")) {
            return null;
        }
        String note = tail.substring(0, tail.length() - 1).trim();
        return note.isEmpty() ? null : note;
    }

    /**
     * 把草稿元数据转换为声明元数据。
     *
     * 版本号与哈希留空由发布器分配；**条目数按映射后的真实内容统计**。
     * `expectedEntryCount` 保持为空：草稿没有声明"应该有多少条"，
     * 填入实际值会把"没有预期"伪装成"预期恰好等于实际"。
     *
     * @param draft  草稿
     * @param mapped 已映射的公开内容
     * @return 声明元数据
     */
    private static TextbookEdition toEdition(AdminTextbookDraft draft, DraftToBookMapper.MappedBook mapped) {
        int entryCount = mapped.entries().size();
        return new TextbookEdition(draft.editionId(), Subject.fromJson(draft.subject()), draft.grade(),
                Semester.fromJson(draft.semester()), draft.regionCodes() == null ? List.of() : draft.regionCodes(),
                draft.publisher() == null ? "" : draft.publisher(), draft.series(),
                // F03：这三个值过去被**硬编码丢掉**（`List.of()` / `null` / `null`），
                // 于是管理端采集了也传不出去。设计 104/105 行规定它们**可选**，
                // 默认地区为江苏；存代码、显示名称。
                draft.editionLabel() == null ? "" : draft.editionLabel(), draft.curriculumYear(), draft.isbn(),
                // 教材级来源标识过去被硬编码成 `List.of()`：管理员采集了来源、发布时却全部丢掉，
                // 快照也就无从解析。这里改为传入真实值；未声明时为空表（空不是错误）。
                draft.sourceIds() == null ? List.of() : draft.sourceIds(),
                1, "", "",
                ReviewStatus.PUBLISHED,
                "complete".equals(draft.coverageDeclaration()) ? CoverageStatus.COMPLETE : CoverageStatus.PARTIAL,
                Instant.now(), Instant.now(), entryCount, null, List.of());
    }

    /** 未填写来源说明时只记录管理员录入，不推断出版社、版次、页码或核对证据。 */
    private static AdminTextbookDraft withManualSourceDefault(AdminTextbookDraft draft) {
        if (draft == null || (draft.sourceNote() != null && !draft.sourceNote().isBlank())) {
            return draft;
        }
        return new AdminTextbookDraft(draft.editionId(), draft.subject(), draft.grade(), draft.semester(),
                draft.publisher(), draft.series(), draft.editionLabel(), "管理员手工录入",
                draft.coverageDeclaration(), draft.units(), draft.lessons(), draft.entries(), draft.idMapping(),
                draft.regionCodes(), draft.curriculumYear(), draft.isbn(), draft.sourceIds(), draft.sources());
    }

    /**
     * 构建草稿级来源快照。
     *
     * 草稿没有"已发布正文"，因此版本层的版本号与哈希为空——这是**如实的**，
     * 而不是拿基线版本号去冒充草稿自己的版本。目录基线从基线版本的快照继承，
     * 没有基线时明确标记"草稿没有已发布基线"，不把当前文件清单的 catalogVersion 贴上来。
     *
     * @param editionId 教材标识
     * @param draft     草稿
     * @param actor     操作者
     * @return 确定性快照 JSON
     */
    private String buildDraftSnapshot(String editionId, AdminTextbookDraft draft, String actor) {
        return buildDraftSnapshot(editionId, draft, actor, draftBaseline(editionId));
    }

    /** 同步时显式使用新基线，来源快照与即将保存的 base 保持同一版本。 */
    private String buildDraftSnapshot(String editionId, AdminTextbookDraft draft, String actor,
            com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.ManifestBaseline baseline) {
        List<com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.EntryLocator> locators =
                new java.util.ArrayList<>();
        List<AdminTextbookDraft.DraftEntry> entries = draft.entries() == null ? List.of() : draft.entries();
        for (AdminTextbookDraft.DraftEntry entry : entries) {
            // 未发布的行没有正式 entryId，用 rowId 定位；两者都没有时保留空标识而不是猜一个。
            String key = entry.entryId() != null && !entry.entryId().isBlank() ? entry.entryId() : entry.rowId();
            locators.add(new com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder
                    .EntryLocator(key, entry.sourceRefs() == null ? List.of() : entry.sourceRefs()));
        }
        List<com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.ManualSource> manual =
                new java.util.ArrayList<>();
        List<AdminTextbookDraft.DraftSource> declared = draft.sources() == null ? List.of() : draft.sources();
        for (AdminTextbookDraft.DraftSource source : declared) {
            manual.add(new com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.ManualSource(
                    source.sourceId(), source.kind(), source.title(), source.url(), source.publisher(),
                    source.isbn(), source.editionLabel(), source.locator(), source.page(), source.note()));
        }
        // 教材级"来源说明"也是一条管理员填写的来源事实，转成显式记录而不是让它只留在备注里。
        if (draft.sourceNote() != null && !draft.sourceNote().isBlank()
                && manual.stream().noneMatch(item -> draft.sourceNote().equals(item.title()))) {
            manual.add(new com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.ManualSource(
                    null, "manualNote", draft.sourceNote(), null, null, null, null, null, null, null));
        }
        var capture = new com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder
                .SnapshotCapture(Instant.now().toString(), actor, "draft:" + editionId, "draft", null);
        var request = new com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.SnapshotRequest(
                java.nio.file.Path.of(toolboxProperties.catalogPath()), editionId, null, null,
                draft.sourceIds(), locators, manual, capture, baseline);
        return snapshotBuilder.build(request).json();
    }

    /**
     * 取草稿的目录基线：从草稿基线的**已发布版本快照**继承。
     *
     * @param editionId 教材标识
     * @return 继承到的基线；没有可继承内容时返回明确缺失的基线
     */
    private com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.ManifestBaseline
            draftBaseline(String editionId) {
        String baseVersionId = repository.findDraft("draft-" + editionId)
                .map(AdminTextbookRepository.DraftRow::baseContentVersion).orElse(null);
        return draftBaseline(editionId, baseVersionId);
    }

    /** 从明确指定的不可变版本继承来源基线。 */
    private com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.ManifestBaseline
            draftBaseline(String editionId, String baseVersionId) {
        if (baseVersionId == null) {
            return absentBaseline("draftHasNoPublishedBaseline");
        }
        String prefix = editionId + "@";
        String contentVersion = baseVersionId.startsWith(prefix)
                ? baseVersionId.substring(prefix.length()) : baseVersionId;
        String snapshot = repository.findVersionSourceMetadata(editionId, contentVersion).orElse(null);
        if (snapshot == null || snapshot.isBlank()) {
            return absentBaseline("baselineVersionHasNoSnapshot");
        }
        try {
            var node = objectMapper.readTree(snapshot).path("manifestBaseline");
            if (node.isMissingNode() || node.isNull()) {
                return absentBaseline("baselineVersionHasNoManifestBaseline");
            }
            return objectMapper.treeToValue(node,
                    com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.ManifestBaseline.class);
        } catch (Exception unparsable) {
            return absentBaseline("baselineSnapshotUnparsable");
        }
    }

    /** 构造"明确缺失"的目录基线。 */
    private static com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.ManifestBaseline
            absentBaseline(String reason) {
        return new com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.ManifestBaseline(
                false, reason, null, null, null, null, 0, null, new java.util.TreeMap<>());
    }

    /** 序列化草稿。 */
    private String serialize(AdminTextbookDraft draft) {
        try {
            return objectMapper.writeValueAsString(draft);
        } catch (Exception failure) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_005, Map.of("reason", "draftSerializationFailed"));
        }
    }

    /**
     * 草稿预览载荷；结构与公开导入一致，便于前端复用同一条渲染管线。
     *
     * @param edition    声明的版本元数据；带范围时条目数按**范围内**的内容统计
     * @param units      单元
     * @param lessons    课文
     * @param entries    条目
     * @param draftRevision 本次预览所依据的草稿修订号（服务端回显，供"预览的就是我保存的那一版"校验）
     * @param scope      本次预览实际使用的范围；整册时为 {@link PreviewScope#wholeBook()}
     */
    public record PreviewPayload(TextbookEdition edition,
                                 List<com.yuyutian.mytools.toolbox.model.CurriculumUnit> units,
                                 List<com.yuyutian.mytools.toolbox.model.CurriculumLesson> lessons,
                                 List<com.yuyutian.mytools.toolbox.model.CurriculumEntry> entries,
                                 long draftRevision,
                                 PreviewScope scope) {
    }

    /**
     * 预览范围回显。
     *
     * @param nodeKind 范围类型：`unit`／`lesson`；整册时为 null
     * @param nodeKey  范围标识；整册时为 null
     * @param filtered 是否真的做了范围过滤
     */
    public record PreviewScope(String nodeKind, String nodeKey, boolean filtered) {

        /**
         * 整册范围：不带任何过滤，与改造前的预览行为一致。
         *
         * @return 整册范围
         */
        public static PreviewScope wholeBook() {
            return new PreviewScope(null, null, false);
        }
    }

    /**
     * 列表项。
     *
     * @param editionId      教材标识
     * @param contentVersion 内容版本号；草稿为 null
     * @param published      是否已发布
     * @param updatedAt      最近更新时间（毫秒）
     * @param updatedBy      最近修改人
     * @param metadataJson   当前版本元数据 JSON；草稿为 null
     */
    public record ListItem(String editionId, String contentVersion, boolean published, long updatedAt,
                           String updatedBy, String metadataJson, boolean hasUnpublishedChanges) {
    }

    /**
     * 列表分页结果。
     *
     * @param items    当前页
     * @param total    总条数
     * @param page     页码
     * @param pageSize 每页条数
     */
    public record ListPage(List<ListItem> items, int total, int page, int pageSize) {
    }

    /**
     * 已保存的草稿。
     *
     * @param draft              草稿内容
     * @param revision           当前修订号
     * @param baseContentVersion 基线内容版本
     * @param sourceMetadataJson 草稿级来源快照；从未保存过时为 null
     */
    public record StoredDraft(AdminTextbookDraft draft, long revision, String baseContentVersion,
                              String sourceMetadataJson) {
    }

    /**
     * 生成**回退导出清单**：设计第 8 节第 7 点要求导出包包含
     * 「最新 current manifest、**全部曾发布历史的独立索引**、原始版本字节和来源」。
     *
     * 本方法产出**清单与索引**；原始字节由 `listAllVersions` 已带回的 `contentBytes` 提供
     * （导出实现按清单逐条取即可，不在接口里一次性搬运全部历史正文）。
     *
     * 为什么必须有历史索引：回退到文件仓储时，若只带"当前版本"，
     * **曾经发布过、后来被新版本取代的内容会全部丢失**——而那些内容仍是用户的数据。
     *
     * @return 导出清单
     */
    /**
     * 取某版本的**原始字节**（F14）。
     *
     * 为什么需要它：现有的导出清单只有 `hash`／`长度`／版本号——
     * 那是**校验用的元数据，不是可恢复的内容**。
     * 要能真正"落盘一个包、在隔离环境恢复对账"，就必须能拿到**当时的原字节**。
     *
     * 这是**管理员接口**：公开 API 仍然只提供当前版本（C04 方案 C），
     * 历史正文只能从这里取，不会因此对公众开放。
     *
     * @param editionId      教材标识
     * @param contentVersion 版本号
     * @return 原字节、哈希与来源元数据
     */
    /**
     * 构建**可落盘、可自校验**的完整导出包（F14）。
     *
     * 与 {@link #buildExportManifest} 的区别：清单只有 hash／长度，
     * **拿不回内容**；这个包带上每个版本的**原始字节**（Base64），
     * 因此可以在隔离环境里恢复并对账。
     *
     * **包级摘要 `packageSha256`** 覆盖全部条目（按 `版本 → 哈希` 的稳定顺序），
     * 用于证明"这个包从生成到现在没有被改动过"——
     * 逐个版本的哈希只能证明单个 blob 完好，**证明不了包本身完整**。
     *
     * @param actor 导出人（记入包内，便于审计）
     * @return 导出包
     */
    public ExportPackage buildExportPackage(String actor) {
        java.util.List<ExportPackageEntry> entries = new java.util.ArrayList<>();
        java.util.List<String> digestLines = new java.util.ArrayList<>();
        // 先取各册的当前版本（即有哪些册），再取该册的**全部历史版本**——
        // 只导当前版本会丢掉被取代的正文，那样回退就没有依据。
        for (AdminTextbookRepository.VersionRow current : repository.listCurrentVersions()) {
          for (AdminTextbookRepository.VersionRow row : repository.listAllVersions(current.editionId())) {
            String base64 = java.util.Base64.getEncoder().encodeToString(row.contentBytes());
            // 来源快照从**该版本自己的列**读：绝不重新读本机 sources 目录冒充当年的来源。
            String sourceSnapshot = repository.findVersionSourceMetadata(row.editionId(), row.contentVersion())
                    .orElse(null);
            entries.add(new ExportPackageEntry(row.editionId(), row.contentVersion(), row.contentSha256(),
                    row.contentBytes().length, base64,
                    row.contentVersion().equals(current.contentVersion()),
                    // 声明元数据必须**随包单独带**：设计第 8 节要求"原字节、声明元数据与来源核验记录分别保存"。
                    // 若靠恢复方从正文反推，发布时刻／核验时间这类字段就再也回不来。
                    repository.findVersionMetadata(row.editionId(), row.contentVersion()).orElse(null),
                    sourceSnapshot, sourceSnapshot == null ? null : sha256Of(sourceSnapshot)));
            // 摘要行按固定格式拼装：任何一位字节变化都会改变包级摘要。
            // **不改这个格式**：既有验证方就是按它独立重算包级摘要的。
            digestLines.add(row.editionId() + "@" + row.contentVersion() + ":" + row.contentSha256());
          }
        }
        // 排序后再算：数据库返回顺序变化不应该影响摘要。
        java.util.Collections.sort(digestLines);
        String packageSha256 = sha256Of(String.join("\n", digestLines));
        return new ExportPackage("toolbox-curriculum-package", 1, actor,
                java.time.Instant.now().toString(), entries.size(), packageSha256, entries);
    }

    /** 计算字符串的 SHA-256 十六进制值。 */
    private static String sha256Of(String text) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception failure) {
            throw new IllegalStateException("sha256 unavailable", failure);
        }
    }

    /**
     * 导出包。
     *
     * @param format        格式标识
     * @param formatVersion 格式版本
     * @param exportedBy    导出人
     * @param exportedAt    导出时刻
     * @param versionCount  版本数
     * @param packageSha256 包级摘要；见 {@link #buildExportPackage}
     * @param versions      各版本及其原始字节
     */
    public record ExportPackage(String format, int formatVersion, String exportedBy, String exportedAt,
                                int versionCount, String packageSha256,
                                java.util.List<ExportPackageEntry> versions) {
    }

    /**
     * 导出包里的一个版本。
     *
     * @param editionId      教材标识
     * @param contentVersion 版本号
     * @param contentSha256  原字节哈希
     * @param byteLength     字节长度（便于恢复时先做低成本核对）
     * @param contentBase64  原始字节的 Base64
     * @param current        是否为该册当前指针指向的版本（恢复时据此重建 current 指针）
     * @param editionMetadataJson 该版本的声明元数据 JSON；随包单独保存
     * @param sourceMetadataJson 该版本**保存的**来源快照；从未保存过时为 null
     * @param sourceSnapshotSha256 来源快照 JSON 的 SHA-256；无快照时为 null（恢复时据此校验快照未被改动）
     */
    public record ExportPackageEntry(String editionId, String contentVersion, String contentSha256,
                                     int byteLength, String contentBase64, boolean current,
                                     String editionMetadataJson, String sourceMetadataJson,
                                     String sourceSnapshotSha256) {
    }

    /**
     * 列出某教材的**全部历史版本**（F14）。
     *
     * 为什么需要它：审查指出"管理历史 API 缺失"——
     * 于是想回退时**只能靠公开的旧版接口**，而那条路对公众是关着的
     * （C04 方案 C：公开 API 只提供当前版本）。管理员因此**没有可用的入口**。
     *
     * 这里只返回**元数据**（版本号、哈希、长度、发布人）——
     * 正文由 `versionBytes` 单独取，避免列一次历史就把所有正文都传一遍。
     *
     * @param editionId 教材标识
     * @return 该册的全部版本；按版本号排序
     */
    public java.util.List<VersionSummary> listVersions(String editionId) {
        java.util.List<VersionSummary> summaries = new java.util.ArrayList<>();
        for (AdminTextbookRepository.VersionRow row : repository.listAllVersions(editionId)) {
            byte[] bytes = row.contentBytes();
            String sourceSnapshot = repository.findVersionSourceMetadata(row.editionId(), row.contentVersion())
                    .orElse(null);
            // 修改摘要不在版本行里，而在该版本发布审计的 `summary` 里；
            // 关联键见 `AdminTextbookRepository.findPublishAuditSummary`。
            // 取不到（本次改造之前发布的历史版本，或发布时未填写）就是 null，
            // 界面据此显示"未记录"，绝不编造。
            String changeNote = repository.findPublishAuditSummary(row.editionId(), row.contentVersion())
                    .map(AdminTextbookService::changeNoteFromAuditSummary)
                    .orElse(null);
            summaries.add(new VersionSummary(row.editionId(), row.contentVersion(), row.contentSha256(),
                    bytes == null ? 0 : bytes.length, row.publishedBy(), row.publishedAt(),
                    sourceSnapshot != null, sourceSnapshot == null ? null : sha256Of(sourceSnapshot),
                    changeNote));
        }
        return java.util.List.copyOf(summaries);
    }

    /**
     * 读取某版本**保存的**来源快照。
     *
     * 管理详情用；导出与恢复同样只认这一份，不回读磁盘。
     *
     * @param editionId      教材标识
     * @param contentVersion 版本号
     * @return 快照 JSON 与其 SHA-256；该版本没有快照时为空
     */
    public Optional<VersionSourceSnapshot> versionSourceSnapshot(String editionId, String contentVersion) {
        return repository.findVersionSourceMetadata(editionId, contentVersion)
                .map(json -> new VersionSourceSnapshot(editionId, contentVersion, json, sha256Of(json)));
    }

    /**
     * 历史版本列表里的一项。
     *
     * @param editionId      教材标识
     * @param contentVersion 版本号
     * @param contentSha256  正文哈希
     * @param byteLength     正文长度
     * @param publishedBy    发布人
     * @param publishedAt    发布时刻（毫秒）；0 表示未知
     * @param hasSourceSnapshot 该版本是否保存了来源快照
     * @param sourceSnapshotSha256 来源快照的 SHA-256；无快照时为 null
     * @param changeNote     发布时填写的修改摘要（设计 160／166 行）；未填写时**为 null**，
     *                       界面据此显示"未记录"而不是空串
     */
    public record VersionSummary(String editionId, String contentVersion, String contentSha256,
                                 int byteLength, String publishedBy, long publishedAt,
                                 boolean hasSourceSnapshot, String sourceSnapshotSha256, String changeNote) {
    }

    /**
     * 某版本保存的来源快照。
     *
     * @param editionId      教材标识
     * @param contentVersion 版本号
     * @param snapshotJson   快照 JSON
     * @param sha256         快照 JSON 的 SHA-256
     */
    public record VersionSourceSnapshot(String editionId, String contentVersion, String snapshotJson,
                                        String sha256) {
    }

    public ExportVersion versionBytes(String editionId, String contentVersion) {
        AdminTextbookRepository.VersionRow row = repository.findVersion(editionId, contentVersion)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOLBOX_ADMIN_006,
                        java.util.Map.of("reason", "versionMissing")));
        return new ExportVersion(row.editionId(), row.contentVersion(), row.contentSha256(),
                row.contentBytes());
    }

    /**
     * 导出用的一个版本。
     *
     * @param editionId      教材标识
     * @param contentVersion 版本号
     * @param contentSha256  原字节的哈希
     * @param contentBytes   原始字节
     */
    public record ExportVersion(String editionId, String contentVersion, String contentSha256,
                                byte[] contentBytes) {
    }

    public ObjectNode buildExportManifest(String actor) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("format", "toolbox-curriculum-export");
        root.put("formatVersion", 1);
        root.put("exportedBy", actor);
        root.put("exportedAt", java.time.Instant.now().toString());
        var editions = root.putArray("editions");
        for (AdminTextbookRepository.VersionRow current : repository.listCurrentVersions()) {
            ObjectNode edition = editions.addObject();
            edition.put("editionId", current.editionId());
            edition.put("currentVersion", current.contentVersion());
            edition.put("currentContentSha256", current.contentSha256());
            var history = edition.putArray("history");
            for (AdminTextbookRepository.VersionRow version : repository.listAllVersions(current.editionId())) {
                ObjectNode item = history.addObject();
                item.put("contentVersion", version.contentVersion());
                item.put("contentSha256", version.contentSha256());
                item.put("publishedBy", version.publishedBy());
                item.put("byteLength", version.contentBytes() == null ? 0 : version.contentBytes().length);
                item.put("current", version.contentVersion().equals(current.contentVersion()));
                // 清单里带上快照指纹：恢复方据此判断"这个版本的来源快照是否随包带全"。
                String sourceSnapshot = repository.findVersionSourceMetadata(version.editionId(),
                        version.contentVersion()).orElse(null);
                item.put("hasSourceSnapshot", sourceSnapshot != null);
                if (sourceSnapshot != null) {
                    item.put("sourceSnapshotSha256", sha256Of(sourceSnapshot));
                    // 清单也带上**完整快照**：这样"清单 + 各版本原始字节"本身就能完成恢复，
                    // 不必依赖导出包之外的任何东西，也不必回读本机 sources 目录。
                    item.put("sourceMetadataJson", sourceSnapshot);
                }
            }
        }
        return root;
    }

    /**
     * 校验**已发布教材的身份字段没有被改成另一册**。
     *
     * 设计第 109 行：「已发布后，学科、年级、册次和真实教材版本身份**不得改成另一册**……
     * 换实际出版社、系列或教材版次应『新增教材』」。
     *
     * 这里只强制**无歧义**的三项：学科、年级、册次。
     * 出版社／系列／显示名的"笔误 vs 真换版本"需要人的判断（设计也允许在前者情形下修订），
     * 因此不在此处拦截——**把无法自动判定的事留给人，而不是猜一个规则**。
     *
     * @param editionId 教材标识
     * @param draft     待发布的草稿
     */
    private void requireStableIdentity(String editionId, AdminTextbookDraft draft) {
        ObjectNode metadata = publishedMetadata(editionId);
        if (metadata == null) {
            // 首次发布：没有可比对的已发布身份。
            return;
        }
        if (!draft.subject().equals(metadata.path("subject").asText(null))
                || draft.grade() != metadata.path("grade").asInt(Integer.MIN_VALUE)
                || !draft.semester().equals(metadata.path("semester").asText(null))) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_005, Map.of("reason", "identityChanged"));
        }
    }

    /**
     * 读取该册当前已发布版本的声明元数据。
     *
     * @param editionId 教材标识
     * @return 元数据；从未发布过时为 null
     */
    private ObjectNode publishedMetadata(String editionId) {
        for (AdminTextbookRepository.VersionRow row : repository.listCurrentVersions()) {
            if (!editionId.equals(row.editionId())) {
                continue;
            }
            return repository.findVersionMetadata(editionId, row.contentVersion())
                    .map(json -> {
                        try {
                            return (ObjectNode) objectMapper.readTree(json);
                        } catch (Exception ignored) {
                            return null;
                        }
                    })
                    .orElse(null);
        }
        return null;
    }
}
