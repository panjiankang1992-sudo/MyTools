/**
 * 管理端错误 messageKey → 用户可读提示。
 *
 * **为什么需要它**：界面此前直接把 `messageKey` 显示给用户：
 *
 * ```
 * 操作失败（admin.disabled）。当前编辑内容仍保留在本页。
 * ```
 *
 * 用户看到的是**机器标识符**，既不知道发生了什么，也不知道下一步该做什么。
 * 服务端返回 `messageKey` 的本意就是"由前端翻成人的话"——这里补上这一步。
 *
 * **未收录的 key 原样显示**，不静默换成"未知错误"：否则新加的错误会被藏起来。
 */

/** 管理端错误 key 对应的中文提示。 */
export const ADMIN_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  // 会话与权限
  'admin.session.required': '登录状态已失效，请重新登录后再操作。',
  'admin.forbidden': '当前账号没有管理员权限，请联系管理员分配后再试。',
  'admin.csrf.invalid': '安全校验未通过（页面可能已过期），请刷新页面后重试。',
  'admin.precondition.required': '请求缺少必要的前提条件，请刷新页面后重试。',
  'admin.notFound': '目标不存在，可能已被其他管理员删除。',
  // 冲突与校验
  'admin.conflict': '本次编辑与教材当前版本不一致。请在下方冲突面板查看差异或读取最新，以免覆盖已有内容。',
  'admin.publish.disabled': '当前站点尚未启用教材发布，当前输入仍保留在本页。',
  'admin.publish.invalid': '内容未通过校验，请按下方问题清单逐条修正后再试。',
  // 限额与依赖
  'admin.limitExceeded': '内容超出大小限制，请拆分后再保存。',
  'admin.rateLimited': '操作过于频繁，请稍后再试。',
  'admin.unavailable': '管理功能暂时不可用。当前编辑内容仍保留在本页，可稍后重试。',
  'admin.disabled': '本站未启用管理功能。',
  'admin.identityUnavailable': '账号服务暂时不可用，无法确认登录状态。请稍后重试（本次不会被当作退出）。',
  // 网络层
  'admin.request.timeout': '请求超时。当前编辑内容仍保留在本页，请重试。',
  'admin.request.network': '网络连接失败。当前编辑内容仍保留在本页，请检查网络后重试。',
  'request.failed': '操作失败，请重试。'
};

/**
 * 取某个错误 key 的用户可读提示。
 *
 * @param key 服务端或前端给出的 messageKey
 * @returns 中文提示；未收录时返回原 key（**不隐藏**）
 */
export function adminErrorMessage(key: string | null | undefined): string {
  if (!key) {
    return '操作失败，请重试。';
  }
  return ADMIN_ERROR_MESSAGES[key] ?? key;
}
