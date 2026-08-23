import { ApiEnvelope } from './AuthorizedApiModels';

/**
 * 统一校验受认证JSON接口的路径、载荷和响应信封。
 */
export class AuthorizedApiPolicy {
  /**
   * 仅允许应用内部构造的绝对API路径和查询参数。
   */
  path(value: string): string {
    if (typeof value !== 'string' || value.length < 6 || value.length > 8192 || !value.startsWith('/api/') ||
      value.includes('#') || value.includes('\\') || /[\u0000-\u001F\u007F]/.test(value)) {
      throw new Error('认证API路径无效');
    }
    const pathname = value.split('?')[0];
    if (pathname.includes('//') || pathname.split('/').some((part: string) => part === '.' || part === '..')) {
      throw new Error('认证API路径无效');
    }
    return value;
  }

  /**
   * 限制JSON请求体为最多2 MB的UTF-8数据。
   */
  requestBody(body: string | undefined, bytes: number): void {
    if (body === undefined) return;
    if (!Number.isSafeInteger(bytes) || bytes < 0 || bytes > 2 * 1024 * 1024) {
      throw new Error('认证API请求超过2 MB限制');
    }
  }

  /**
   * 解析最多64 MB并具有有界错误元数据的通用JSON信封。
   */
  envelope(text: string, bytes: number): ApiEnvelope {
    if (!Number.isSafeInteger(bytes) || bytes < 1 || bytes > 64 * 1024 * 1024 || text.length === 0) {
      throw new Error('认证API响应为空或超过64 MB限制');
    }
    const parsed = JSON.parse(text) as Record<string, Object>;
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('认证API响应信封无效');
    }
    const rawCode = parsed['code'] ?? parsed['status'];
    const code = typeof rawCode === 'number' && Number.isSafeInteger(rawCode) ? `${rawCode}` : rawCode;
    const rawMessage = parsed['message'] ?? parsed['error'];
    const message = typeof rawMessage === 'string' ? rawMessage
      .replace(/[\u0000-\u001F\u007F]/g, ' ').trim().substring(0, 4096) : '';
    const traceId = parsed['traceId'];
    if (typeof code !== 'string' || code.length < 1 || code.length > 32 ||
      /[\u0000-\u001F\u007F]/.test(code) ||
      (traceId !== undefined && typeof traceId !== 'string')) throw new Error('认证API响应信封无效');
    const data = parsed['data'];
    return {
      code: code,
      message: message,
      data: data === undefined || data === null ? undefined : data,
      traceId: typeof traceId === 'string' && traceId.length <= 128 && /^[A-Za-z0-9_.:-]*$/.test(traceId) ?
        traceId : undefined
    };
  }
}
