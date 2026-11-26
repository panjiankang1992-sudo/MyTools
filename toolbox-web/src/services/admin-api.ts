import { appConfig } from '../config/env';

/**
 * 管理接口客户端。
 *
 * 刻意与公开的 `publicRequest` 分离：管理端使用独立的 Cookie 会话与独立的 CSRF 头，
 * 不能把 MyTools 的 Authorization 带进公开请求，也不能让匿名 Guest 的 CSRF 令牌混用。
 * 浏览器**不保存**任何上游令牌；令牌只存在于服务端会话中，前端只持有随机 Cookie。
 */

/** 统一响应信封。 */
interface AdminEnvelope<T> {
  code: string;
  data: T;
  requestId: string;
}

/** 管理接口错误。 */
export class AdminApiError extends Error {
  /** 使用稳定错误码创建异常。 */
  constructor(
    public readonly code: string,
    public readonly messageKey: string,
    public readonly details: Record<string, unknown> | undefined,
    public readonly status: number
  ) {
    super(`${code}:${messageKey}`);
    this.name = 'AdminApiError';
  }

  /** 会话缺失或过期：应打开登录并暂停写入。 */
  get isUnauthenticated(): boolean {
    return this.status === 401;
  }

  /** 无管理员权限：应销毁本地管理状态。 */
  get isForbidden(): boolean {
    return this.status === 403;
  }

  /** 依赖不可用：锁定写入但不谎称已退出。 */
  get isUnavailable(): boolean {
    return this.status === 503;
  }
}

const DEFAULT_TIMEOUT_MS = 15000;

let csrfToken: string | null = null;

/** 设置当前管理会话的 CSRF 令牌；只保存在内存中。 */
export function setAdminCsrfToken(token: string | null): void {
  csrfToken = token;
}

/** 清除管理 CSRF 令牌。 */
export function clearAdminCsrfToken(): void {
  csrfToken = null;
}

/** 拼接管理接口地址。 */
function adminUrl(path: string): string {
  return `${appConfig.adminApiBase}${path}`;
}

/** 解析响应体；非 2xx 或非 OK 信封时抛出可展示的错误。 */
async function parse<T>(response: Response): Promise<T> {
  let payload: AdminEnvelope<T> | Record<string, unknown> | null = null;
  try {
    payload = (await response.json()) as AdminEnvelope<T>;
  } catch {
    payload = null;
  }
  const body = payload as { code?: string; messageKey?: string; details?: Record<string, unknown> } | null;
  if (!response.ok || !payload || body?.code !== 'OK') {
    throw new AdminApiError(
      body?.code ?? `HTTP_${response.status}`,
      body?.messageKey ?? 'request.failed',
      body?.details,
      response.status
    );
  }
  return (payload as AdminEnvelope<T>).data;
}

/**
 * 发起一次管理接口请求。
 *
 * @param path 以斜杠开头的管理路径
 * @param init 请求配置；`csrf` 为真时携带独立的管理 CSRF 头
 * @returns 解包后的数据
 */
/**
 * 生成一个幂等键。
 *
 * 优先用 `crypto.randomUUID`（浏览器与 Node 都支持）；不可用时退回时间戳＋随机数，
 * **仍然保证不重复**——重复的键会让两次不同的操作互相顶掉。
 *
 * @returns 幂等键
 */
export function newIdempotencyKey(): string {
  const uuid = globalThis.crypto?.randomUUID?.();
  if (uuid) {
    return uuid;
  }
  return `k-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

export async function adminRequest<T>(
  path: string,
  init: RequestInit & { csrf?: boolean; timeoutMs?: number; idempotencyKey?: string } = {}
): Promise<T> {
  const controller = new AbortController();
  if (init.signal?.aborted) controller.abort();
  else init.signal?.addEventListener('abort', () => controller.abort(), { once: true });
  const timeout = setTimeout(() => controller.abort(), init.timeoutMs ?? DEFAULT_TIMEOUT_MS);
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (init.body) headers.set('Content-Type', 'application/json');
  // 写请求必须带独立的管理 CSRF 头；匿名 Guest 的令牌在这里不被使用。
  if (init.csrf && csrfToken) headers.set('X-Toolbox-Admin-Csrf', csrfToken);
  // **写请求必须带幂等键**：服务端对缺少它的写请求返回 428 `TOOLBOX_ADMIN_010`。
  //
  // 放在这里而不是各个调用点，是为了让"忘记带"在结构上不可能发生——
  // 此前前端**一处都没有**发送它，导致管理端的保存与发布在真实环境下全部失败，
  // 而两端各自的测试都是通过的（后端测试自己造键、前端测试不发请求）。
  //
  // 调用方可用 `init.idempotencyKey` 传入一个**跨重试稳定**的键：
  // 网络中断后重试同一个逻辑操作时复用它，服务端才会返回原结果而不是再写一次。
  const method = (init.method ?? 'GET').toUpperCase();
  if (method !== 'GET' && method !== 'HEAD' && !headers.has('Idempotency-Key')) {
    headers.set('Idempotency-Key', init.idempotencyKey ?? newIdempotencyKey());
  }
  try {
    const response = await fetch(adminUrl(path), {
      ...init,
      headers,
      credentials: 'same-origin',
      cache: 'no-store',
      signal: controller.signal
    });
    return await parse<T>(response);
  } catch (cause) {
    if (cause instanceof AdminApiError) throw cause;
    const aborted = cause instanceof DOMException && cause.name === 'AbortError';
    throw new AdminApiError(
      aborted ? 'TOOLBOX_ADMIN_TIMEOUT' : 'TOOLBOX_ADMIN_NETWORK',
      aborted ? 'admin.request.timeout' : 'admin.request.network',
      undefined,
      0
    );
  } finally {
    clearTimeout(timeout);
  }
}

/** 管理会话状态。 */
export interface AdminSessionState {
  authenticated: boolean;
  adminEnabled: boolean;
  username?: string;
  userId?: string;
  /**
   * 服务端还给本会话的 CSRF 令牌，用于**刷新／新标签页后恢复写入能力**。
   *
   * 它不是凭据：单独拿到它无法做任何事（仍需要 HttpOnly 会话 Cookie，
   * 且管理路径受来源校验保护）。但它**只应来自本会话的响应**，不要落到持久存储里。
   */
  csrfToken?: string;
}

/** 查询当前管理会话；未登录时服务端匿名返回 false，不创建任何会话。 */
export async function fetchAdminSession(): Promise<AdminSessionState> {
  return adminRequest<AdminSessionState>('/session', { timeoutMs: 8000 });
}

/** 登录表单所需的 nonce 与预认证 Cookie。 */
export async function fetchAdminCsrf(): Promise<{ csrfToken: string }> {
  return adminRequest<{ csrfToken: string }>('/auth/csrf');
}

/** 登录结果。 */
export interface AdminLoginResult {
  authenticated: boolean;
  username?: string;
  userId?: string;
  csrfToken: string;
}

/**
 * 使用 MyTools 管理员账号登录工具箱。
 *
 * @param account 用户名或邮箱
 * @param password 密码；只在本次请求中使用，不落任何前端存储
 * @param nonce 登录表单 nonce
 * @returns 登录结果
 */
export async function adminLogin(account: string, password: string, nonce: string): Promise<AdminLoginResult> {
  return adminRequest<AdminLoginResult>('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ account, password }),
    csrf: true,
    headers: { 'X-Toolbox-Admin-Csrf': nonce }
  });
}

/** 退出管理会话；即使上游不可用也完成本地清理。 */
export async function adminLogout(): Promise<void> {
  await adminRequest<{ authenticated: boolean }>('/auth/logout', { method: 'POST', csrf: true });
}
