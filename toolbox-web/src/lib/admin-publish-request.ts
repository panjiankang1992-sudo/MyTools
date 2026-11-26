/**
 * 发布请求体的构造（设计文档 §6，第 362 行）。
 *
 * 契约：`POST /textbooks/{id}/publish` 的正文为
 * `{draftRevision, baseContentVersion, changeNote}`，并带 `Idempotency-Key`。
 *
 * 三件事必须**一起**发送：
 * - `draftRevision`：发布绑定"管理员确认过的那一版"，防止把别人刚改的草稿发出去；
 * - `baseContentVersion`：基线版本，服务端据此拒绝覆盖别人刚发布的版本；
 * - `changeNote`：写入版本记录的修改摘要（设计 166 行）。
 *
 * 抽成纯函数是为了让"字段名与形状"可以被单测锁住——
 * 这条契约的字段名写错时，服务端不会报错，只会忽略它，而功能悄悄失效。
 */

/** 发布意图。 */
export interface PublishIntent {
  /** 被确认的草稿修订号。 */
  draftRevision: number;
  /** 草稿所基于的已发布版本；从未发布时为 null。 */
  baseContentVersion: string | null;
  /** 修改摘要；设计**未规定必填**（166 行只说版本记录含修改摘要），空串表示未填写。 */
  changeNote: string;
}

/** 修改摘要的长度上限；超长会让版本记录与审计难以阅读。 */
export const CHANGE_NOTE_MAX_LENGTH = 200;

/**
 * 规范化修改摘要：去首尾空白、截断到上限。
 *
 * 不做"空串变 null"：契约里的字段是字符串，发空串比漏发字段更容易被服务端发现。
 *
 * @param value 原始输入
 * @returns 规范化后的摘要
 */
export function normalizeChangeNote(value: string | null | undefined): string {
  return (value ?? '').trim().slice(0, CHANGE_NOTE_MAX_LENGTH);
}

/**
 * 构造发布请求体。
 *
 * @param input.revision           当前草稿修订号
 * @param input.baseContentVersion 草稿基线版本
 * @param input.changeNote         管理员填写的修改摘要
 * @returns 与设计契约同形的请求体
 */
export function buildPublishIntent(input: {
  revision: number;
  baseContentVersion?: string | null;
  changeNote?: string | null;
}): PublishIntent {
  return {
    draftRevision: input.revision,
    baseContentVersion: input.baseContentVersion ?? null,
    changeNote: normalizeChangeNote(input.changeNote)
  };
}
