import { appConfig } from '../config/env';
import { adminRequest } from './admin-api';

/**
 * 管理端教材目录的**版本历史 / 导出 / 恢复**接口客户端。
 *
 * 与 `curriculum-api.ts` 分开：那条是**公开只读**接口（`/public/v1`，匿名访客），
 * 本文件走 `/admin/v1`，带独立会话与 CSRF。
 *
 * 后端契约见 `docs/verification/toolbox-admin-completion-2026-10-07/source-snapshot-chain.md` §5。
 */

/** 导出包里的一个版本；字段与后端 `AdminTextbookService.ExportPackageEntry` 一一对应。 */
export interface AdminExportPackageVersion {
  editionId: string;
  contentVersion: string;
  contentSha256: string;
  /** 原始字节长度；恢复时服务端会先核对它，不符即拒绝该版本。 */
  byteLength: number;
  contentBase64: string;
  /** 是否为该册当前指针指向的版本（恢复时据此重建 current 指针）。 */
  current: boolean;
  editionMetadataJson: string | null;
  sourceMetadataJson: string | null;
  sourceSnapshotSha256: string | null;
}

/** 完整导出包；它就是恢复接口的请求体。 */
export interface AdminExportPackage {
  format: string;
  formatVersion: number;
  exportedBy: string;
  exportedAt: string;
  versionCount: number;
  packageSha256: string;
  versions: AdminExportPackageVersion[];
}

/** 库内现状清单里的一个历史版本。 */
export interface AdminExportManifestVersion {
  contentVersion: string;
  contentSha256: string;
  publishedBy?: string;
  byteLength?: number;
  current: boolean;
  hasSourceSnapshot?: boolean;
  sourceSnapshotSha256?: string;
  sourceMetadataJson?: string;
}

/** 库内现状清单里的一册。 */
export interface AdminExportManifestEdition {
  editionId: string;
  currentVersion: string;
  currentContentSha256: string;
  history: AdminExportManifestVersion[];
}

/** 库内现状清单（只读）；恢复前用它做冲突预计。 */
export interface AdminExportManifest {
  format: string;
  formatVersion: number;
  exportedBy: string;
  exportedAt: string;
  editions: AdminExportManifestEdition[];
}

/** 单册对账结果；与后端 `AdminCurriculumRestoreService.EditionReconciliation` 一致。 */
export interface AdminRestoreReconciliation {
  editionId: string;
  currentVersionId: string;
  expectedCurrentVersion: string;
  currentPointerRestored: boolean;
  historyCount: number;
  catalogVersionBaseline: string;
  contentSha256Matches: boolean;
  sourceSnapshotMatches: boolean;
}

/** 恢复报告；与后端 `AdminCurriculumRestoreService.RestoreReport` 一致。 */
export interface AdminRestoreReport {
  restoredEditions: number;
  restoredVersions: number;
  skippedVersions: number;
  /** 冲突项，形如 `editionId@contentVersion:CONFLICT_HASH_MISMATCH`；**必须展示，不得静默跳过**。 */
  conflicts: string[];
  reconciliations: AdminRestoreReconciliation[];
  ok: boolean;
}

/**
 * 某册的一条历史版本记录。
 *
 * 字段与后端 `AdminTextbookService.VersionSummary` **一一对应**（实测响应见
 * `docs/verification/toolbox-admin-completion-2026-10-07/version-history-and-keyboard.md`）。
 */
export interface AdminVersionSummary {
  editionId: string;
  contentVersion: string;
  contentSha256: string;
  byteLength: number;
  publishedBy: string;
  /** 发布时刻（毫秒）；**0 表示未知**（不是 1970 年）。 */
  publishedAt: number;
  hasSourceSnapshot: boolean;
  sourceSnapshotSha256: string | null;
  /**
   * 该版本的修改摘要（设计 160／166 行「版本记录含修改摘要」）。
   *
   * 发布请求体里的 `changeNote`（见 `admin-publish-request.ts`）现在由后端
   * 写入发布审计的 `summary`，并在本字段返回：关联键是
   * `(edition_id, action='textbook.publish', to_version=contentVersion)`。
   *
   * **发布时未填写、或本次改造之前发布的版本仍是 null／缺席**，界面必须如实显示"未记录"，
   * 不得用空串或旧审计文本冒充摘要。
   */
  changeNote?: string | null;
}

/**
 * 某版本**保存的**来源快照。
 *
 * 对应后端 `AdminTextbookService.VersionSourceSnapshot`：`snapshotJson` 是**字符串化的 JSON**，
 * 界面要展示时需自行 `JSON.parse`（失败时按原文显示，不能假装没有）。
 */
export interface AdminVersionSourceSnapshot {
  editionId: string;
  contentVersion: string;
  snapshotJson: string;
  sha256: string;
}

/**
 * 列出某册的**全部历史版本**（设计 3.6：版本记录放右侧抽屉）。
 *
 * 公开 API 只给当前版本（方案 C），因此这是管理员查看历史的唯一入口。
 * 只读，不改变任何状态；正文不在这个响应里（用 `fetchVersionBytes` / `buildVersionBytesUrl` 单独取）。
 *
 * @param editionId 教材标识
 * @param signal    取消信号；抽屉关闭时用来丢弃过期响应
 * @returns 版本元数据列表（服务端按版本号排序，界面自行按时间倒序）
 */
export async function fetchVersionHistory(
  editionId: string,
  signal?: AbortSignal
): Promise<AdminVersionSummary[]> {
  return adminRequest<AdminVersionSummary[]>(
    `/curriculum/versions/${encodeURIComponent(editionId)}`, { signal }
  );
}

/**
 * 读取某版本保存的来源快照。
 *
 * 该版本没有快照时服务端返回 404 `TOOLBOX_ADMIN_006`（`details.reason=sourceSnapshotMissing`），
 * `adminRequest` 会抛出 `AdminApiError`——调用方**必须显示这个错误**，不能静默当作"没有快照"。
 *
 * @param editionId      教材标识
 * @param contentVersion 版本号
 * @param signal         取消信号
 * @returns 快照 JSON 与其 SHA-256
 */
export async function fetchVersionSourceSnapshot(
  editionId: string,
  contentVersion: string,
  signal?: AbortSignal
): Promise<AdminVersionSourceSnapshot> {
  return adminRequest<AdminVersionSourceSnapshot>(
    `/curriculum/versions/${encodeURIComponent(editionId)}/${encodeURIComponent(contentVersion)}/source-snapshot`,
    { signal }
  );
}

/**
 * 补录结果；与后端 `SourceSnapshotRepairService.RepairResult` **一一对应**。
 *
 * `overwroteOriginal` 服务端**恒为 false**（写库条件是"已有快照即不动"）。
 * 界面不能把它当作"可能为真"的正常分支：一旦为 true，说明发生了本界面明确不提供的覆盖，
 * 必须显示为异常而不是成功。
 */
export interface AdminSourceSnapshotRepairResult {
  editionId: string;
  contentVersion: string;
  /** 是否真的写入了补录；已有快照时为 false。 */
  applied: boolean;
  /** 是否覆盖了既有快照；**恒为 false**，为 true 即异常。 */
  overwroteOriginal: boolean;
  /** 结果原因，如 `repaired` / `snapshotAlreadyPresent`。 */
  reason: string;
  /** 补录后（或既有的）快照指纹；不可用时为 null。 */
  snapshot: { json: string; sha256: string } | null;
}

/**
 * 为**没有来源快照**的历史版本补录来源快照（设计第 297 行：来源元数据随不可变版本保存）。
 *
 * 语义是"**只补空**"：服务端写库条件是 `source_metadata_json IS NULL`，
 * 既有原始快照与先前补录都不会被改写，原正文也不回写。因此**本函数不提供任何覆盖开关**。
 *
 * 依据（`basis`）必填：没有依据的补录就是一次谎称，服务端缺 `basis` 返回 422。
 * 这里在发请求之前**再拦一次**空依据——界面上的按钮 `disabled` 只是提示，
 * 真正的闸门必须落在会发请求的地方（组件里的提交函数是第一道）。
 *
 * @param editionId      教材标识
 * @param contentVersion 版本号
 * @param basis          补录依据（必填，例如"同版留存文件 sources/cn-1-first-2024.json"）
 * @param signal         取消信号
 * @returns 补录结果
 */
export async function repairVersionSourceSnapshot(
  editionId: string,
  contentVersion: string,
  basis: string,
  signal?: AbortSignal
): Promise<AdminSourceSnapshotRepairResult> {
  const trimmed = basis.trim();
  if (!trimmed) {
    // 空依据绝不发请求；抛出去让调用方显示，而不是静默当成"补录成功"。
    throw new Error('补录依据必填：没有依据的补录等于一次谎称，未发送请求。');
  }
  return adminRequest<AdminSourceSnapshotRepairResult>(
    `/curriculum/versions/${encodeURIComponent(editionId)}/${encodeURIComponent(contentVersion)}`
    + `/repair-source-snapshot?basis=${encodeURIComponent(trimmed)}`,
    { method: 'POST', csrf: true, signal }
  );
}

/**
 * 某版本原始字节的下载地址（设计 3.6：只读历史里能取回该版本内容）。
 *
 * **刻意不经过 `adminRequest`**：`/bytes` 返回的是 `application/octet-stream` 原始字节
 * （带 `X-Content-Sha256` 头），不是管理端的 `{code,data}` 信封——
 * 用 `adminRequest` 解析会把一份正常的历史正文判成 `HTTP_200` 错误。
 * 交给浏览器按普通导航下载即可：管理会话是**同源 HttpOnly Cookie**，会随请求自动带上。
 *
 * @param editionId      教材标识
 * @param contentVersion 版本号
 * @returns 可直接放进 `<a href>` 的下载地址
 */
export function buildVersionBytesUrl(editionId: string, contentVersion: string): string {
  return `${appConfig.adminApiBase}/curriculum/versions/${encodeURIComponent(editionId)}/${encodeURIComponent(contentVersion)}/bytes`;
}

/** 读取库内现状清单；只读接口，不改变任何状态。 */
export async function fetchExportManifest(): Promise<AdminExportManifest> {
  // 全库清单可能较大，放宽超时。
  return adminRequest<AdminExportManifest>('/curriculum/export-manifest', { timeoutMs: 30000 });
}

/**
 * 从导出包恢复。
 *
 * **破坏性写操作**：调用方必须先取得用户二次确认。
 * 服务端的行为是"新增或跳过"，同版本不同内容会报冲突而**不覆盖**，
 * 因此这里不提供任何"强制覆盖"开关——不默认覆盖是产品要求，不是缺功能。
 *
 * @param pkg 导出包（`GET /curriculum/export-package` 的 `data`）
 * @returns 恢复报告与逐册对账
 */
export async function restorePackage(pkg: AdminExportPackage): Promise<AdminRestoreReport> {
  return adminRequest<AdminRestoreReport>('/curriculum/restore-package', {
    method: 'POST',
    csrf: true,
    // 整包可能很大，恢复本身也要写库：给足时间，避免正常操作被 15 秒默认值掐断。
    timeoutMs: 180000,
    body: JSON.stringify(pkg)
  });
}
