import type { AdminApiError } from '../services/admin-api';

/** 简洁录入页的冲突提示及恢复动作。 */
export interface EntryConflictRecovery {
  message: string;
  requiresReload: boolean;
  renewRetryKey?: boolean;
}

/** 按服务端冲突原因决定恢复方式，不把所有 409 都归因于其他人编辑。 */
export function entryConflictRecovery(error: AdminApiError, entityTitles: Readonly<Record<string, string>> = {}): EntryConflictRecovery {
  const reason = error.details?.reason;
  if (error.code === 'TOOLBOX_ADMIN_011' || error.messageKey === 'admin.publish.disabled' || reason === 'repositoryIsFiles') {
    return { message: '当前站点尚未启用教材发布。当前输入已保留，请启用发布后重试。', requiresReload: false };
  }
  if (reason === 'baselineMismatch') {
    return { message: '教材草稿与已发布版本尚未同步。当前输入已保留，请重新读取最新教材后继续。', requiresReload: true };
  }
  if (reason === 'rebaseConflict') {
    const fields: Readonly<Record<string, string>> = { text: '正文', pinyin: '拼音', meaning: '中文释义', listTypes: '所属表', title: '名称', order: '顺序' };
    const conflicts = Array.isArray(error.details?.conflicts) ? error.details.conflicts : [];
    const items = conflicts.slice(0, 3).map(value => {
      if (!value || typeof value !== 'object') return '同一条内容';
      const item = value as { entityId?: unknown; field?: unknown };
      const title = typeof item.entityId === 'string' ? entityTitles[item.entityId] : undefined;
      const field = typeof item.field === 'string' ? fields[item.field] : undefined;
      return `${title ? `“${title}”` : '同一条内容'}${field ? `的${field}` : ''}`;
    });
    return { message: `草稿与已发布教材存在无法自动合并的修改${items.length ? `：${items.join('、')}` : ''}。当前输入已保留，请核对这些内容后再发布。`, requiresReload: true };
  }
  if (reason === 'baselineUnavailable') {
    return { message: '暂时无法读取教材的原始版本，不能安全合并。当前输入已保留，请稍后重试。', requiresReload: false };
  }
  if (reason === 'revisionConflict' || reason === 'revisionMismatch') {
    return { message: '教材草稿版本已变化，暂未覆盖保存。当前输入已保留，请保留需要的修改后重新读取。', requiresReload: true };
  }
  if (reason === 'idempotencyKeyReused') {
    return { message: '本次请求标识与之前的操作重复，当前输入已保留，请再次点击“保存发布”重试。', requiresReload: false, renewRetryKey: true };
  }
  if (reason === 'editionExists') {
    return { message: '这本教材已经存在，当前输入已保留。请重新选择已有教材后录入。', requiresReload: false };
  }
  // 未知版本冲突继续阻止覆盖，避免用最新修订号强行重发旧正文。
  return { message: '教材当前状态与本次请求不一致。当前输入已保留，请保留需要的修改后重新读取。', requiresReload: true };
}
