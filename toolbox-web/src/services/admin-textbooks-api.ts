import { adminRequest } from './admin-api';
import type { TranslationResult } from './public-api';
import { buildTextbookQueryString } from '../lib/admin-textbook-view';
import type { PublishIntent } from '../lib/admin-publish-request';

/**
 * 管理端教材接口。
 * 只做参数与响应映射；权限、CSRF 与错误分类由 `adminRequest` 统一负责。
 */

/** 列表项。 */
export interface AdminTextbookListItem {
  editionId: string;
  contentVersion: string | null;
  published: boolean;
  updatedAt: number;
  updatedBy: string;
  metadataJson: string | null;
  /**
   * 是否"已发布·有修改"（F12）。
   *
   * 后端算好给前端：判据是"草稿修订号 > 最后一次发布的修订号"，
   * **不是** `baseContentVersion` 与当前版本的比较（编辑草稿不改基线，那样永远相等）。
   */
  hasUnpublishedChanges: boolean;
}

/** 列表查询条件。 */
export interface AdminTextbookListQuery {
  keyword?: string;
  subject?: string;
  grade?: number;
  semester?: string;
  status?: string;
  page?: number;
  pageSize?: number;
}

/** 列表结果。 */
export interface AdminTextbookListResult {
  items: AdminTextbookListItem[];
  total: number;
  page: number;
  pageSize: number;
}

/**
 * 查询教材列表。
 *
 * @param query 查询条件
 * @returns 当前页与总数
 */
export async function fetchTextbookList(query: AdminTextbookListQuery = {}): Promise<AdminTextbookListResult> {
  return adminRequest<AdminTextbookListResult>(`/textbooks?${buildTextbookQueryString(query)}`);
}

/** 草稿读取结果。 */
export interface AdminDraftResult {
  draft: Record<string, unknown>;
  revision: number;
  baseContentVersion: string | null;
}

/** 读取草稿；GET 不写库。 */
export async function fetchDraft(editionId: string): Promise<AdminDraftResult> {
  return adminRequest<AdminDraftResult>(`/textbooks/${encodeURIComponent(editionId)}/draft`);
}

/**
 * 保存草稿。
 * 必须带 `If-Match`：服务端据此做 CAS，过期修订返回 409 而不是覆盖他人改动。
 *
 * @param editionId 教材标识
 * @param revision  调用方当前持有的修订号
 * @param draft     草稿内容
 * @returns 新修订号
 */
export async function saveDraft(
  editionId: string,
  revision: number,
  draft: Record<string, unknown>,
  /**
   * 跨重试稳定的幂等键（F05）。
   *
   * 网络中断后用**同一个键与同一份正文**重发，服务端才会返回原结果；
   * 不传时由 `adminRequest` 现生成一个——那对"一次新保存"是对的，
   * 对"重试上一次失败的保存"是错的。
   */
  idempotencyKey?: string
): Promise<{ revision: number }> {
  return adminRequest<{ revision: number }>(`/textbooks/${encodeURIComponent(editionId)}/draft`, {
    method: 'PUT',
    csrf: true,
    headers: { 'If-Match': `"draft-${revision}"` },
    body: JSON.stringify(draft),
    idempotencyKey
  });
}

/** 安全合并旧基线之后的发布变更，修订号和幂等键共同保护已保存草稿。 */
export async function rebaseDraft(editionId: string, revision: number, idempotencyKey: string): Promise<AdminDraftResult> {
  return adminRequest<AdminDraftResult>(`/textbooks/${encodeURIComponent(editionId)}/draft/rebase`, {
    method: 'POST', csrf: true, headers: { 'If-Match': `"draft-${revision}"` }, idempotencyKey
  });
}

/** 创建教材草稿。 */
export async function createTextbook(draft: Record<string, unknown>, idempotencyKey?: string): Promise<{
  editionId: string; draftId: string; revision: number;
}> {
  return adminRequest<{ editionId: string; draftId: string; revision: number }>('/textbooks', {
    method: 'POST',
    csrf: true,
    body: JSON.stringify(draft),
    idempotencyKey
  });
}

/** 校验结果。 */
export interface AdminValidationResult {
  publishable: boolean;
  issues: Array<{
    severity: 'BLOCKING' | 'WARNING';
    code: string;
    fieldPath: string;
    rowId: string | null;
    detail: string | null;
  }>;
}

/** 校验已保存的草稿修订。 */
export async function validateDraft(editionId: string): Promise<AdminValidationResult> {
  return adminRequest<AdminValidationResult>(`/textbooks/${encodeURIComponent(editionId)}/validate`, {
    method: 'POST',
    csrf: true
  });
}

/** 发布结果。 */
export interface AdminPublishResult {
  editionId: string;
  contentVersion: string;
  contentHash?: string;
  contentSha256: string;
  snapshotReloaded: boolean;
}

/**
 * 发布草稿；服务端会重新校验。
 *
 * 请求体按设计第 362 行的契约发送 `{draftRevision,baseContentVersion,changeNote}`：
 * - `draftRevision` 让发布**绑定管理员确认过的那一版**（否则会把别人刚改的草稿一起发出去）；
 * - `baseContentVersion` 让服务端拒绝覆盖别人刚发布的版本；
 * - `changeNote` 写入版本记录的修改摘要（设计 166 行；设计未规定必填）。
 *
 * **同一个 `draftRevision` 也放在查询串里**：当前服务端是用
 * `@RequestParam(name="draftRevision")` 读它的（`AdminTextbookController.publish`），
 * 只发请求体会被静默忽略——"发布绑定修订"就会失效且不报错。
 * 两处都发对当前实现与目标契约都成立：服务端改为读正文后，查询参数被忽略即可。
 *
 * @param editionId 教材标识
 * @param intent    发布意图（修订号、基线版本、修改摘要）
 * @param idempotencyKey 跨重试稳定的幂等键
 * @returns 发布结果
 */
export async function publishDraft(
  editionId: string,
  intent: PublishIntent,
  idempotencyKey?: string
): Promise<AdminPublishResult> {
  const query = `?draftRevision=${encodeURIComponent(String(intent.draftRevision))}`;
  return adminRequest<AdminPublishResult>(`/textbooks/${encodeURIComponent(editionId)}/publish${query}`, {
    method: 'POST',
    csrf: true,
    body: JSON.stringify(intent),
    idempotencyKey
  });
}

/**
 * 管理端的自动补全请求（F06）。
 *
 * **为什么不复用公开的 `translateEntries`**：那条走的是匿名访客会话
 * （`withSession` + `publicRequest`），于是**管理员补全消耗的是访客额度** ✗。
 * 审查要求"管理端专用接口与独立额度"——后端已提供 `/admin/v1/translations`，
 * 它在服务端按 `admin:<管理员标识>` 记账，与访客主体互不影响。
 *
 * 用 `adminRequest` 而不是自己拼请求：它会带上管理 CSRF 头与幂等键，
 * 并转发 `signal`（编辑器靠它取消仍在途的补全）。
 *
 * @param items 待补全的条目（id 用于把结果匹配回当前行）
 * @param signal 取消信号；已取消时直接返回空结果
 * @returns 逐条结果，顺序与身份由服务端保持
 */
export async function translateEntriesForAdmin(
  items: ReadonlyArray<{ id: string; text: string }>,
  signal: AbortSignal,
  subject?: 'chinese' | 'english'
): Promise<TranslationResult[]> {
  if (!items.length || signal.aborted) return [];
  const result = await adminRequest<{ items: TranslationResult[] }>('/translations', {
    method: 'POST', csrf: true, signal, timeoutMs: subject === 'chinese' ? 12000 : 45000, body: JSON.stringify({ items, ...(subject ? { subject } : {}) })
  });
  return result.items;
}
