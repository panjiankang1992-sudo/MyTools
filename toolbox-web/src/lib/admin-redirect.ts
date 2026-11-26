/**
 * 管理登录后的站内跳转校验。
 *
 * 用途：守卫把未授权的直达请求挡回公共页面时会带上 `redirect` 查询参数，
 * 登录成功后需要回到原目标。**直接使用用户传入的值会形成开放重定向**，
 * 因此只接受本站已注册的路由前缀，并拒绝协议相对地址与外部地址。
 */

/** 允许跳转的站内路径前缀。 */
const ALLOWED_PREFIXES = ['/admin/textbooks', '/copybook', '/words', '/worksheet-v2', '/pdf', '/'];

/**
 * 判断目标是否为本站已注册路由。
 *
 * @param target 待校验的目标路径，通常来自 `route.query.redirect`
 * @returns 允许跳转时为 true
 */
export function isAllowedRedirect(target: unknown): target is string {
  if (typeof target !== 'string' || target.length === 0) return false;
  // 必须是单斜杠开头的站内绝对路径：`//evil.com` 与 `https://evil.com` 都不允许。
  if (!target.startsWith('/') || target.startsWith('//')) return false;
  // 不允许反斜杠变体（部分浏览器会把 `/\evil.com` 当作协议相对地址）。
  if (target.startsWith('/\\')) return false;
  // 去掉查询与哈希后再比较，避免 `/adminx` 误命中 `/admin`。
  const path = (target.split('?')[0] ?? '').split('#')[0] ?? '';
  return ALLOWED_PREFIXES.some(prefix => (prefix === '/' ? path === '/' : path === prefix || path.startsWith(`${prefix}/`)));
}
