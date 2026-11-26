package com.yuyutian.mytools.toolbox.controller.admin.curriculum;

import com.yuyutian.mytools.toolbox.common.ApiResponse;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminSession;
import jakarta.servlet.http.HttpServletRequest;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.CurriculumReconciliationService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.AdminTextbookService;
import com.yuyutian.mytools.toolbox.service.admin.migration.AdminCurriculumImportService;
import com.yuyutian.mytools.toolbox.service.admin.migration.CurriculumFileImporter;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端教材目录维护接口。
 * 权限、CSRF 与 no-store 由 `AdminAuthFilter` 统一处理。
 */
@RestController
@RequestMapping("/admin/v1/curriculum")
public final class AdminCurriculumController {

    private final CurriculumReconciliationService reconciliation;

    private final AdminTextbookService textbooks;

    private final AdminCurriculumImportService curriculumImport;

    private final com.yuyutian.mytools.toolbox.service.admin.migration.AdminCurriculumRestoreService restoreService;

    private final com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotRepairService repairService;

    /**
     * 创建管理端目录控制器。
     *
     * @param reconciliation 双读对账服务
     * @param textbooks        管理端教材服务（导出清单用）
     * @param curriculumImport 文件仓储 → 数据库的导入服务
     * @param restoreService   导出包 → 数据库的恢复服务
     * @param repairService    历史版本来源补录服务
     */
    public AdminCurriculumController(CurriculumReconciliationService reconciliation,
                                     AdminTextbookService textbooks,
                                     AdminCurriculumImportService curriculumImport,
                                     com.yuyutian.mytools.toolbox.service.admin.migration
                                             .AdminCurriculumRestoreService restoreService,
                                     com.yuyutian.mytools.toolbox.service.admin.migration
                                             .SourceSnapshotRepairService repairService) {
        this.reconciliation = reconciliation;
        this.textbooks = textbooks;
        this.curriculumImport = curriculumImport;
        this.restoreService = restoreService;
        this.repairService = repairService;
    }

    /**
     * 从**导出包**恢复到数据库（F14 的另一半）。
     *
     * 用于隔离空库恢复演练：包内每个版本的原始字节、声明元数据与来源快照都会先校验后写入，
     * 并重建 current 指针与历史留存。**不改动发布路径的 catalogVersion 分配**。
     *
     * @param pkg     导出包（由 `/export-package` 产出）
     * @param request HTTP 请求，用于读取当前管理员身份
     * @return 恢复报告与逐册对账
     */
    @PostMapping("/restore-package")
    public ResponseEntity<ApiResponse<com.yuyutian.mytools.toolbox.service.admin.migration
            .AdminCurriculumRestoreService.RestoreReport>> restorePackage(
            @org.springframework.web.bind.annotation.RequestBody AdminTextbookService.ExportPackage pkg,
            HttpServletRequest request) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(restoreService.restore(pkg, actor(request))));
    }

    /**
     * 读取某版本**保存的**来源快照（管理详情）。
     *
     * 读取的是版本自己那一列，不会回读本机 `sources/` 目录——
     * 因此发布后改动本地来源不会改变历史版本的来源事实。
     *
     * @param editionId      教材标识
     * @param contentVersion 版本号
     * @return 来源快照 JSON 与其 SHA-256
     */
    @GetMapping("/versions/{editionId}/{contentVersion}/source-snapshot")
    public ResponseEntity<ApiResponse<AdminTextbookService.VersionSourceSnapshot>> versionSourceSnapshot(
            @PathVariable String editionId, @PathVariable String contentVersion) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(ApiResponse.ok(
                textbooks.versionSourceSnapshot(editionId, contentVersion)
                        .orElseThrow(() -> new com.yuyutian.mytools.toolbox.common.BusinessException(
                                com.yuyutian.mytools.toolbox.common.ErrorCode.TOOLBOX_ADMIN_006,
                                Map.of("reason", "sourceSnapshotMissing")))));
    }

    /**
     * 为历史版本**显式补录**来源快照。
     *
     * 只在确有可核实的同版留存文件、且该版本尚无快照时生效；补录会记录补录时间与依据，
     * 不改原正文、也不谎称原始发布时已保存。
     *
     * @param editionId      教材标识
     * @param contentVersion 版本号
     * @param basis          补录依据（必填）
     * @param request        HTTP 请求，用于读取当前管理员身份
     * @return 补录结果
     */
    @PostMapping("/versions/{editionId}/{contentVersion}/repair-source-snapshot")
    public ResponseEntity<ApiResponse<com.yuyutian.mytools.toolbox.service.admin.migration
            .SourceSnapshotRepairService.RepairResult>> repairSourceSnapshot(
            @PathVariable String editionId, @PathVariable String contentVersion,
            @org.springframework.web.bind.annotation.RequestParam(name = "basis", required = false) String basis,
            HttpServletRequest request) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(repairService.repair(editionId, contentVersion, basis, actor(request))));
    }

    /**
     * 把文件仓储中已发布的教材导入数据库。
     *
     * 用途：把公开来源从 `files` 切到 `database` **之前**，先让库里有现有内容——
     * 否则切换的瞬间公开站会变空。
     *
     * **可重复运行**：同版本同哈希会被跳过，同版本异哈希会被报为冲突而**不覆盖**。
     * 读进来的字节会**自算 SHA-256 与清单比对**，不符即拒绝。
     *
     * @param actor 操作者（由会话提供，不采信请求体）
     * @return 迁移报告
     */
    /**
     * 下载某版本的**原始字节**（F14：可落盘的恢复包）。
     *
     * 公开 API 只提供当前版本（C04 方案 C）；历史正文与来源只能从这个管理员接口取，
     * 因此**不会**因为支持导出而对公众开放历史。
     *
     * @param editionId      教材标识
     * @param contentVersion 版本号
     * @return 原始字节；哈希放在 `X-Content-Sha256` 头里，便于落盘后核对
     */
    /**
     * 下载**完整的导出包**（F14）：带上每个版本（含被取代的历史版本）的原始字节。
     *
     * 用途：在隔离环境里恢复并逐字段对账。清单接口只能证明"有什么"，
     * 这个包才能**把内容带回去**。
     *
     * 仅管理员可见；公开 API 仍只提供当前版本（C04 方案 C）。
     *
     * @param actor 操作者（记入包内）
     * @return 导出包
     */
    @GetMapping("/export-package")
    public ResponseEntity<ApiResponse<AdminTextbookService.ExportPackage>> exportPackage(
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                textbooks.buildExportPackage(actor(request))));
    }

    /**
     * 列出某教材的全部历史版本（F14）。
     *
     * 公开 API 只给当前版本（C04 方案 C），因此**回退所需的入口只在这里**。
     *
     * @param editionId 教材标识
     * @return 版本元数据列表
     */
    @GetMapping("/versions/{editionId}")
    public ResponseEntity<ApiResponse<java.util.List<AdminTextbookService.VersionSummary>>> listVersions(
            @PathVariable String editionId) {
        return ResponseEntity.ok(ApiResponse.ok(textbooks.listVersions(editionId)));
    }

    @GetMapping("/versions/{editionId}/{contentVersion}/bytes")
    public ResponseEntity<byte[]> versionBytes(@PathVariable String editionId,
                                               @PathVariable String contentVersion) {
        AdminTextbookService.ExportVersion version = textbooks.versionBytes(editionId, contentVersion);
        return ResponseEntity.ok()
                .header("X-Content-Sha256", version.contentSha256())
                .header("X-Content-Version", version.contentVersion())
                .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .body(version.contentBytes());
    }

    @PostMapping("/import-from-files")
    public ResponseEntity<ApiResponse<CurriculumFileImporter.ImportReport>> importFromFiles(
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                curriculumImport.importFromFiles(actor(request))));
    }

    /**
     * 回退导出清单。
     *
     * 设计第 8 节第 7 点：回退前需导出「最新 current manifest、**全部曾发布历史的独立索引**、
     * 原始版本字节和来源」。本接口给出清单与历史索引；**原始字节按清单逐条取**，
     * 不在一次响应里搬运全部历史正文。
     *
     * 只读接口，不改变任何状态。
     *
     * @return 导出清单
     */
    @GetMapping("/export-manifest")
    public ResponseEntity<ApiResponse<ObjectNode>> exportManifest(
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(textbooks.buildExportManifest(actor(request))));
    }

    /**
     * 文件仓储与数据库的双读对账。
     *
     * 切换公开读取来源前必须让本接口给出 `switchSafe=true`；
     * 否则切换会让公开目录多出、缺少或改变内容。
     *
     * @return 对账报告
     */
    @GetMapping("/reconciliation")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reconciliation() {
        var report = reconciliation.reconcile();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("comparisons", report.comparisons());
        data.put("identicalCount", report.identicalCount());
        data.put("totalCount", report.totalCount());
        data.put("switchSafe", report.switchSafe());
        data.put("notes", report.notes());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(ApiResponse.ok(data));
    }

    /**
     * 读取当前管理员身份，用于审计里"谁做的"。
     *
     * **这里此前读的是 `@RequestAttribute("adminUserId")`——而 `AdminAuthFilter` 从不设置该属性**
     * （它只设 `AdminSession.class.getName()`）。于是这三处的操作者**永远是 null**，
     * 审计里被写成 `"unknown"`：导出包与导入报告**说不出是谁导的**。
     *
     * 现在与 `AdminTextbookController` 用**同一个**取法，不再有两套读身份的方式。
     *
     * @param request 当前请求
     * @return MyTools 用户标识；未认证时为 unknown
     */
    private static String actor(HttpServletRequest request) {
        Object session = request.getAttribute(AdminSession.class.getName());
        return session instanceof AdminSession admin ? admin.mytoolsUserId() : "unknown";
    }
}
