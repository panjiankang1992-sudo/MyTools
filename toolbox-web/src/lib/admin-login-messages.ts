/**
 * 登录弹窗的错误提示。
 *
 * **为什么单独一个模块**：登录场景的措辞与其他地方不同——
 * 上游返回 401 时它要说"账号或密码不正确"，而同样的 key 在编辑页意味着"登录已失效"。
 * 把这份映射抽成纯函数，是为了**能测**（本仓库没有组件挂载测试工具）。
 */

/** 登录失败时的中文提示。 */
const LOGIN_MESSAGES: Readonly<Record<string, string>> = {
  // 上游拒绝了这次登录：在登录场景下就是"账号或密码不正确"。
  'admin.session.required': '账号或密码不正确。',
  // 设计 A03：凭据有效但不是管理员，必须与"密码错了"区分开。
  'admin.forbidden': '当前账号没有管理员权限。',
  'admin.rateLimited': '尝试过于频繁，请稍后再试。',
  // 设计要求区分"功能未启用"与"未登录"——否则管理员会一遍遍重输密码。
  'admin.disabled': '本站未启用管理功能。',
  'admin.unavailable': '服务暂时不可用，请稍后重试。',
  'admin.request.network': '服务暂时不可用，请稍后重试。',
  'admin.request.timeout': '服务暂时不可用，请稍后重试。'
};

/**
 * 取登录失败的用户可读提示。
 *
 * @param key 本次失败对应的 messageKey
 * @returns 中文提示；key 为空时返回空串（表示"还没有失败过"）
 */
export function loginErrorMessage(key: string | null | undefined): string {
  if (!key) {
    return '';
  }
  return LOGIN_MESSAGES[key] ?? '登录失败，请重试。';
}
