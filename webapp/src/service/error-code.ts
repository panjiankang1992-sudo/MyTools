import type { Locale } from 'naive-ui';

export interface ErrorCodeConfig {
  /** Whether to show as modal */
  isModal: boolean;
  /** Whether to trigger logout */
  isLogout: boolean;
}

export const ErrorCodeConfigMap: Record<string, ErrorCodeConfig> = {
  // Auth errors - require modal/logout
  '20001': { isModal: true, isLogout: true },  // token expired
  '20002': { isModal: true, isLogout: true },  // token invalid
  '20003': { isModal: false, isLogout: false }, // permission denied
  '20005': { isModal: true, isLogout: true },  // account locked
  // Token errors
  '40001': { isModal: false, isLogout: false },
  '40002': { isModal: false, isLogout: false },
};

export const ErrorMessageMap: Record<Locale['zh-CN'] | 'en', Record<string, string>> = {
  'zh-CN': {
    // User errors
    'user.not_found': '用户不存在',
    'user.username.exists': '用户名已存在',
    'user.password.invalid': '密码必须包含大小写字母和数字',
    'user.email.format.invalid': '邮箱格式不正确',
    'user.username.or.password.wrong': '用户名或密码错误',
    'user.account.disabled': '账户已禁用',
    'user.email.exists': '邮箱已被使用',
    'user.old.password.wrong': '旧密码错误',
    'user.status.invalid': '无效的用户状态',
    // Auth errors
    'auth.token.expired': '登录已过期，请重新登录',
    'auth.token.invalid': '无效的登录凭证',
    'auth.permission.denied': '权限不足',
    'auth.token.format.error': 'Token格式错误',
    'auth.account.locked': '账户已锁定',
    // File errors
    'file.not_found': '文件不存在',
    'file.preview.unsupported': '文件类型不支持预览',
    'file.delete.failed': '文件删除失败',
    'file.filename.exists': '文件名已存在',
    'file.path.invalid': '无效的文件路径',
    'file.tag.not_found': '标签不存在',
    'file.tag.name.exists': '标签名称已存在',
    'file.tagging.service.unavailable': '打标签服务不可用',
    'file.type.unsupported': '文件类型不支持',
    'file.dir.not_found': '目录不存在或无权限访问',
    'file.scan.in_progress': '扫描任务执行中',
    // Role errors
    'role.code.exists': '角色编码已存在',
    'role.assigned.to.users': '该角色已分配给用户，无法删除',
    'role.not_found': '角色不存在',
    // Token errors
    'token.not_found': 'Token不存在',
    'token.operation.denied': '无权限操作此Token',
    'token.name.invalid': 'Token名称无效',
    'token.disabled': 'Token已禁用',
    'token.verify.failed': 'Token验证失败',
    // System errors
    'sys.server.error': '服务器内部错误',
    'sys.validation.failed': '参数校验失败',
    'sys.database.error': '数据库操作失败',
  },
  'en': {
    // User errors
    'user.not_found': 'User not found',
    'user.username.exists': 'Username already exists',
    'user.password.invalid': 'Password must contain uppercase, lowercase letters and numbers',
    'user.email.format.invalid': 'Email format is invalid',
    'user.username.or.password.wrong': 'Username or password is incorrect',
    'user.account.disabled': 'Account has been disabled',
    'user.email.exists': 'Email has already been used',
    'user.old.password.wrong': 'Old password is incorrect',
    'user.status.invalid': 'Invalid user status',
    // Auth errors
    'auth.token.expired': 'Session expired, please login again',
    'auth.token.invalid': 'Invalid credentials',
    'auth.permission.denied': 'Permission denied',
    'auth.token.format.error': 'Token format error',
    'auth.account.locked': 'Account has been locked',
    // File errors
    'file.not_found': 'File not found',
    'file.preview.unsupported': 'File type does not support preview',
    'file.delete.failed': 'File deletion failed',
    'file.filename.exists': 'Filename already exists',
    'file.path.invalid': 'Invalid file path',
    'file.tag.not_found': 'Tag not found',
    'file.tag.name.exists': 'Tag name already exists',
    'file.tagging.service.unavailable': 'Tagging service is unavailable',
    'file.type.unsupported': 'File type not supported',
    'file.dir.not_found': 'Directory not found or access denied',
    'file.scan.in_progress': 'Scan task is in progress',
    // Role errors
    'role.code.exists': 'Role code already exists',
    'role.assigned.to.users': 'Cannot delete role assigned to users',
    'role.not_found': 'Role not found',
    // Token errors
    'token.not_found': 'Token not found',
    'token.operation.denied': 'No permission to operate this token',
    'token.name.invalid': 'Token name is invalid',
    'token.disabled': 'Token has been disabled',
    'token.verify.failed': 'Token verification failed',
    // System errors
    'sys.server.error': 'Internal server error',
    'sys.validation.failed': 'Parameter validation failed',
    'sys.database.error': 'Database operation failed',
  }
};

/**
 * Get i18n message by message key
 */
export function getI18nMessage(messageKey: string, locale: Locale['zh-CN'] | 'en' = 'zh-CN'): string {
  return ErrorMessageMap[locale][messageKey] || messageKey;
}
