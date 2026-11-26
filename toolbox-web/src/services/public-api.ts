import { appConfig } from '../config/env';

/**
 * 公开接口客户端。
 * 固定同域访问 /public/v1，使用 same-origin cookie，不携带 MyTools 的 Authorization 或 X-Owner-Id。
 */

/** 统一响应信封。 */
export interface ApiEnvelope<T> {
  code: string;
  data: T;
  requestId: string;
}

/** 错误响应体。 */
export interface ApiErrorPayload {
  code?: string;
  messageKey?: string;
  details?: Record<string, unknown>;
  requestId?: string;
}

/** 公开接口错误，携带稳定错误码与文案键。 */
export class PublicApiError extends Error {
  /** 使用稳定错误码创建异常。 */
  constructor(
    public readonly code: string,
    public readonly messageKey: string,
    public readonly details: Record<string, unknown> | undefined,
    public readonly requestId: string | undefined,
    public readonly status: number
  ) {
    super(`${code}:${messageKey}`);
    this.name = 'PublicApiError';
  }
}

/** 会话信息。 */
export interface SessionInfo {
  expiresAt: string;
  csrfToken: string;
}

const DEFAULT_TIMEOUT_MS = 12000;
let csrfToken: string | null = null;
let sessionExpiresAt = 0;
let pendingSession: Promise<SessionInfo> | null = null;

/** 读取当前会话的 CSRF 令牌，仅保存在内存中。 */
export function currentCsrfToken(): string | null {
  return csrfToken;
}

/** 将会话失效，使下一次请求重新建立匿名会话。 */
export function invalidateSession(): void {
  csrfToken = null;
  sessionExpiresAt = 0;
}

/** 拼接同域接口地址。 */
function apiUrl(path: string): string {
  return `${appConfig.apiBase}${path}`;
}

/** 解析响应体，网络或解析失败时抛出可展示的错误。 */
async function parseResponse<T>(response: Response): Promise<T> {
  let payload: ApiEnvelope<T> | ApiErrorPayload | null = null;
  try {
    payload = (await response.json()) as ApiEnvelope<T> | ApiErrorPayload;
  } catch {
    payload = null;
  }
  const body = payload as ApiErrorPayload | null;
  if (!response.ok || !payload || body?.code !== 'OK') {
    const code = body?.code ?? `HTTP_${response.status}`;
    throw new PublicApiError(
      code,
      body?.messageKey ?? 'request.failed',
      body?.details,
      body?.requestId,
      response.status
    );
  }
  return (payload as ApiEnvelope<T>).data;
}

/** 发起一次公开接口请求，支持超时与可选 CSRF 头。 */
export async function publicRequest<T>(path: string, init: RequestInit & { csrf?: boolean; timeoutMs?: number } = {}): Promise<T> {
  const controller = new AbortController();
  const abortFromCaller = (): void => controller.abort();
  // 页面离开或原文变化时及时取消请求，不能被内部超时信号覆盖。
  if (init.signal?.aborted) controller.abort();
  else init.signal?.addEventListener('abort', abortFromCaller, { once: true });
  const timeout = setTimeout(() => controller.abort(), init.timeoutMs ?? DEFAULT_TIMEOUT_MS);
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (init.body) headers.set('Content-Type', 'application/json');
  if (init.csrf && csrfToken) headers.set('X-Toolbox-Csrf', csrfToken);
  try {
    const response = await fetch(apiUrl(path), {
      ...init,
      headers,
      credentials: 'same-origin',
      signal: controller.signal
    });
    return await parseResponse<T>(response);
  } catch (cause) {
    if (cause instanceof PublicApiError) throw cause;
    const aborted = cause instanceof DOMException && cause.name === 'AbortError';
    throw new PublicApiError(aborted ? 'TOOLBOX_TIMEOUT' : 'TOOLBOX_NETWORK', aborted ? 'request.timeout' : 'request.network', undefined, undefined, 0);
  } finally {
    clearTimeout(timeout);
    init.signal?.removeEventListener('abort', abortFromCaller);
  }
}

/** 单项自动翻译结果，原文仅用于校验回包归属。 */
export interface TranslationResult {
  id: string;
  sourceText: string;
  englishText: string | null;
  meaning: string | null;
  status: 'translated' | 'failed';
  pinyinSegments?: Array<{ grapheme: string; syllable: string }>;
}

/** 在匿名会话中批量翻译词条，不发送到使用统计接口。 */
export async function translateEntries(items: ReadonlyArray<{ id: string; text: string }>, signal: AbortSignal, subject?: 'chinese' | 'english'): Promise<TranslationResult[]> {
  if (!items.length || signal.aborted) return [];
  const result = await withSession(async () => {
    if (signal.aborted) return { items: [] as TranslationResult[] };
    return publicRequest<{ items: TranslationResult[] }>('/translations', {
      method: 'POST', csrf: true, signal, timeoutMs: subject === 'chinese' ? 12000 : 45000, body: JSON.stringify({ items, ...(subject ? { subject } : {}) })
    });
  });
  return result.items;
}

/** 建立匿名会话；并发调用共享同一个请求。 */
export function ensureSession(force = false): Promise<SessionInfo> {
  if (!force && csrfToken && Date.now() < sessionExpiresAt - 1000) {
    return Promise.resolve({ expiresAt: new Date(sessionExpiresAt).toISOString(), csrfToken });
  }
  pendingSession ??= publicRequest<SessionInfo>('/session', { method: 'POST', body: '{}' })
    .then(info => {
      csrfToken = info.csrfToken;
      sessionExpiresAt = Date.parse(info.expiresAt) || (Date.now() + 60 * 60 * 1000);
      return info;
    })
    .finally(() => { pendingSession = null; });
  return pendingSession;
}

/** 在会话过期时重建一次会话，其他错误原样抛出。 */
async function withSession<T>(task: () => Promise<T>): Promise<T> {
  await ensureSession();
  try {
    return await task();
  } catch (cause) {
    if (cause instanceof PublicApiError && cause.code === 'TOOLBOX_002') {
      invalidateSession();
      await ensureSession(true);
      return await task();
    }
    throw cause;
  }
}

/** 发送一批最小化使用事件，返回每项处理结果。 */
export async function postEvents(events: readonly UsageEventPayload[]): Promise<EventResultPayload[]> {
  if (!events.length) return [];
  const data = await withSession(() => publicRequest<{ results: EventResultPayload[] }>('/events', {
    method: 'POST',
    csrf: true,
    body: JSON.stringify({ events })
  }));
  return data.results;
}

/** 上报事件的最小字段集合。 */
export interface UsageEventPayload {
  eventId: string;
  eventType: 'entry_click' | 'worksheet_success' | 'print_request';
  category: 'pdf' | 'copybook' | 'words';
  toolId: string | null;
  occurredAt: string;
  operationId: string | null;
  schemaVersion: 1;
}

/** 单条事件的处理结果。 */
export interface EventResultPayload {
  eventId: string;
  status: 'accepted' | 'duplicate' | 'rejected';
  reason?: string;
}
