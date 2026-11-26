package com.yuyutian.mytools.toolbox.controller.admin.curriculum;

import com.yuyutian.mytools.toolbox.common.ApiResponse;
import com.yuyutian.mytools.toolbox.common.BusinessException;
import com.yuyutian.mytools.toolbox.common.ErrorCode;
import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.AdminIdempotencyService;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.AdminTextbookService;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.CurriculumPublisher;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminSession;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端教材编辑接口（设计文档 §6）。
 * 权限、CSRF 与 no-store 由 `AdminAuthFilter` 统一处理；本控制器只做参数与并发前提校验。
 * 并发写要求 `If-Match: "draft-<revision>"`；缺失返回 428，修订冲突返回 409。
 */
@RestController
@RequestMapping("/admin/v1/textbooks")
public final class AdminTextbookController {

    private final AdminTextbookService service;
    private final AdminIdempotencyService idempotency;

    /**
     * 创建教材管理控制器。
     *
     * @param service     管理端教材服务
     * @param idempotency 幂等回执服务
     */
    public AdminTextbookController(AdminTextbookService service, AdminIdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    /**
     * 解析必需的幂等键。
     *
     * @param key Idempotency-Key 头
     * @return 幂等键
     */
    private static String requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_010, Map.of("header", "Idempotency-Key"));
        }
        return key;
    }

    /**
     * 创建教材草稿。
     *
     * @param draft   草稿
     * @param request HTTP 请求，用于读取当前管理员身份
     * @return 草稿标识与修订号
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AdminTextbookDraft draft,
            HttpServletRequest request) {
        String key = requireIdempotencyKey(idempotencyKey);
        String actor = actor(request);
        Map<String, Object> created = idempotency.execute(actor, "textbook.create", key, draft,
                Map.class, () -> {
                    AdminTextbookRepository.DraftRef ref = service.create(draft, actor);
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("editionId", draft.editionId());
                    value.put("draftId", ref.draftId());
                    value.put("revision", ref.revision());
                    return value;
                });
        Map<String, Object> data = new LinkedHashMap<>(created);
        Object revision = data.get("revision");
        return ResponseEntity.status(201).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.ETAG, etag(revision instanceof Number number ? number.longValue() : 0L))
                .body(ApiResponse.ok(data));
    }

    /**
     * 分页查询教材列表。
     *
     * @param keyword  关键字
     * @param subject  学科
     * @param grade    年级
     * @param semester 册次
     * @param status   状态
     * @param page     页码
     * @param pageSize 每页条数
     * @return 列表与总数
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "subject", required = false) String subject,
            @RequestParam(name = "grade", required = false) Integer grade,
            @RequestParam(name = "semester", required = false) String semester,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        var result = service.list(new com.yuyutian.mytools.toolbox.service.admin.curriculum.AdminTextbookQuery(
                keyword, subject, grade, semester, status, page, pageSize));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items());
        data.put("total", result.total());
        data.put("page", result.page());
        data.put("pageSize", result.pageSize());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(ApiResponse.ok(data));
    }

    /**
     * 读取草稿；GET 不写库。
     *
     * @param editionId 教材标识
     * @return 草稿与修订号
     */
    @GetMapping("/{editionId}/draft")
    public ResponseEntity<ApiResponse<Map<String, Object>>> draft(@PathVariable String editionId) {
        AdminTextbookService.StoredDraft stored = service.findDraft(editionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOLBOX_ADMIN_006, Map.of("reason", "draftMissing")));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("draft", stored.draft());
        data.put("revision", stored.revision());
        data.put("baseContentVersion", stored.baseContentVersion());
        // 草稿级来源快照随详情返回：管理界面据此显示"当前草稿登记了哪些来源"，
        // 而不是只能从一个笼统的来源说明里猜。未保存过时为 null。
        data.put("sourceMetadata", stored.sourceMetadataJson());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.ETAG, etag(stored.revision())).body(ApiResponse.ok(data));
    }

    /**
     * 保存草稿；必须带 If-Match 与幂等键。
     *
     * @param editionId 教材标识
     * @param ifMatch   If-Match 头
     * @param draft     草稿内容
     * @param request   HTTP 请求
     * @return 新修订号
     */
    @PutMapping("/{editionId}/draft")
    public ResponseEntity<ApiResponse<Map<String, Object>>> save(
            @PathVariable String editionId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AdminTextbookDraft draft,
            HttpServletRequest request) {
        long expected = parseIfMatch(ifMatch);
        String key = requireIdempotencyKey(idempotencyKey);
        String actor = actor(request);
        // 幂等键的载荷包含期望 revision 与草稿内容：只有"完全相同的重试"才复用回执。
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("editionId", editionId);
        payload.put("expectedRevision", expected);
        payload.put("draft", draft);
        Map<String, Object> result = idempotency.execute(actor, "draft.save:" + editionId, key, payload,
                Map.class, () -> Map.of("revision", service.saveDraft(editionId, expected, draft, actor)));
        long revision = ((Number) result.get("revision")).longValue();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.ETAG, etag(revision))
                .body(ApiResponse.ok(Map.of("revision", revision)));
    }

    /** 同步已保存草稿与最新教材，要求修订前提及幂等键，返回完整合并后的草稿。 */
    @PostMapping("/{editionId}/draft/rebase")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rebase(
            @PathVariable String editionId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        long expected = parseIfMatch(ifMatch);
        String key = requireIdempotencyKey(idempotencyKey);
        String actor = actor(request);
        Map<String, Object> result = idempotency.execute(actor, "draft.rebase:" + editionId, key,
                Map.of("editionId", editionId, "expectedRevision", expected), Map.class, () -> {
                    var stored = service.rebaseDraft(editionId, expected, actor);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("draft", stored.draft());
                    data.put("revision", stored.revision());
                    data.put("baseContentVersion", stored.baseContentVersion());
                    data.put("sourceMetadata", stored.sourceMetadataJson());
                    return data;
                });
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.ETAG, etag(((Number) result.get("revision")).longValue()))
                .body(ApiResponse.ok(result));
    }

    /**
     * 校验已保存的草稿修订。
     *
     * @param editionId 教材标识
     * @return 校验报告
     */
    @PostMapping("/{editionId}/validate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> validate(
            @PathVariable String editionId,
            @RequestParam(name = "draftRevision", required = false) Long draftRevision) {
        var result = service.validate(editionId, draftRevision);
        var report = result.report();
        Map<String, Object> data = new LinkedHashMap<>();
        // 报告必须带上**它是哪一版**的结论，界面才能判断是否仍然适用。
        data.put("draftRevision", result.draftRevision());
        data.put("publishable", report.publishable());
        data.put("issues", report.issues());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(ApiResponse.ok(data));
    }

    /**
     * 草稿预览载荷；只读，不写入任何表。
     *
     * 支持按**已选范围**生成（设计 164 行）：可选 `nodeKind`／`nodeKey`。
     * 两者都缺省时行为与改造前**完全一致**（整册）。
     *
     * @param editionId 教材标识
     * @param nodeKind  范围类型：`unit`／`lesson`；缺省表示整册
     * @param nodeKey   范围标识：单元的 clientUnitId／unitId，或课文的 clientLessonId／lessonId
     * @param draftRevision 可选：调用方声称的草稿修订号；与库中不一致时 409（与 `/validate` 同语义）
     * @return 与公开导入同形的预览内容，外加 `draftRevision` 与 `scope` 回显
     */
    @GetMapping("/{editionId}/draft/preview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> preview(
            @PathVariable String editionId,
            @RequestParam(name = "nodeKind", required = false) String nodeKind,
            @RequestParam(name = "nodeKey", required = false) String nodeKey,
            @RequestParam(name = "draftRevision", required = false) Long draftRevision) {
        var payload = service.preview(editionId, nodeKind, nodeKey, draftRevision);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("edition", payload.edition());
        data.put("units", payload.units());
        data.put("lessons", payload.lessons());
        data.put("entries", payload.entries());
        // 明确标注这是草稿预览，前端据此显示提示而不是误导为已发布内容。
        data.put("draftPreview", true);
        // 回显"这次预览生成自哪一版草稿"：客户端先 flush 自动保存再预览，
        // 就能据此校验"预览的就是我保存的那一版"，而不是界面上的未保存内容。
        data.put("draftRevision", payload.draftRevision());
        // 回显实际生效的范围：`filtered=false` 表示整册。
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("nodeKind", payload.scope().nodeKind());
        scope.put("nodeKey", payload.scope().nodeKey());
        scope.put("filtered", payload.scope().filtered());
        data.put("scope", scope);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(ApiResponse.ok(data));
    }

    /**
     * 发布草稿；服务端会重新校验，不信任先前的校验结果。
     *
     * 设计第 362 行的请求体是 `{draftRevision,baseContentVersion,changeNote}`。
     * 当前实现里 `draftRevision` 仍从**查询串**读（前端两处都发，见
     * `toolbox-web/src/services/admin-textbooks-api.ts`），正文只取 `changeNote`：
     * 这样既不破坏只发查询串的既有调用，也让正文里的修改摘要真正被接收。
     * `changeNote` **允许为空**（设计未规定必填）——为空时审计摘要保持原文本。
     *
     * @param editionId 教材标识
     * @param idempotencyKey 幂等键
     * @param draftRevision 调用方声称的草稿修订号；从查询串读
     * @param body      请求体；只读取 `changeNote`，无正文时为 null
     * @param request   HTTP 请求
     * @return 发布结果
     */
    @PostMapping("/{editionId}/publish")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publish(
            @PathVariable String editionId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(name = "draftRevision", required = false) Long draftRevision,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String key = requireIdempotencyKey(idempotencyKey);
        String actor = actor(request);
        // 修改摘要是可选文本：非字符串一律按文本处理，不因为类型意外而拒绝整次发布。
        Object rawChangeNote = body == null ? null : body.get("changeNote");
        String changeNote = rawChangeNote == null ? null : String.valueOf(rawChangeNote);
        // F07：幂等载荷必须包含**完整意图**——教材标识**加上**被确认的草稿修订。
        //
        // 此前载荷只有 `{editionId}`，于是"改完草稿再发布一次"与"原地重试"被当成同一意图，
        // 第二次会直接返回第一次的回执，**新内容根本发不出去**。
        //
        // 修改摘要也是意图的一部分（设计 362 行把它和 draftRevision 并列）：
        // 同一个键配不同摘要属于两次不同意图，不能被当成重放。
        Map<String, Object> intent = new java.util.LinkedHashMap<>();
        intent.put("editionId", editionId);
        intent.put("draftRevision", draftRevision);
        intent.put("changeNote", changeNote);
        Map<String, Object> result = idempotency.execute(actor, "textbook.publish:" + editionId, key,
                intent, Map.class, () -> {
                    CurriculumPublisher.PublishResult published =
                            service.publish(editionId, actor, draftRevision, changeNote);
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("editionId", published.editionId());
                    value.put("contentVersion", published.contentVersion());
                    value.put("versionId", published.versionId());
                    value.put("contentSha256", published.contentSha256());
                    value.put("snapshotReloaded", published.snapshotReloaded());
                    // 设计第 391-392 行：发布响应里 `catalogVersion` 与 `catalogEpoch` **并存**。
                    // 两者都读 `toolbox_curriculum_catalog` 的列（不由 epoch 现算）：
                    // 初始迁移保留的旧 catalogVersion 与 epoch 没有派生关系。
                    // `catalogEpoch` 按设计示例用字符串（"42"），避免大整数在前端 JSON 里失真。
                    value.put("catalogVersion", published.catalogVersion());
                    value.put("catalogEpoch", String.valueOf(published.catalogEpoch()));
                    return value;
                });
        Map<String, Object> data = new LinkedHashMap<>(result);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(ApiResponse.ok(data));
    }

    /** 取当前管理员标识；过滤器已保证存在。 */
    private static String actor(HttpServletRequest request) {
        Object session = request.getAttribute(AdminSession.class.getName());
        return session instanceof AdminSession admin ? admin.mytoolsUserId() : "unknown";
    }

    /** 构造 ETag。 */
    private static String etag(long revision) {
        return "\"draft-" + revision + "\"";
    }

    /** 解析 If-Match；缺失或格式非法返回 428。 */
    private static long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_010, Map.of("header", "If-Match"));
        }
        String trimmed = ifMatch.replace("\"", "").trim();
        String digits = trimmed.startsWith("draft-") ? trimmed.substring("draft-".length()) : trimmed;
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException invalid) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_010, Map.of("header", "If-Match"));
        }
    }
}
