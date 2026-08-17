import { AuthEnvelope } from './AuthModels';

/**
 * 登录、刷新、注销、会话验证和健康检查共用的认证网络响应策略。
 */
export class AuthNetworkResponsePolicy {
  /** 校验认证端点响应头并返回可直接展示的失败原因。 */
  headerError(headers: Object, maximumBytes: number): string {
    if (!Number.isSafeInteger(maximumBytes) || maximumBytes < 1) return '认证响应限制无效';
    const values = headers as Record<string, Object>;
    let contentType = '';
    for (const key of Object.keys(values)) {
      const lower = key.toLowerCase();
      if (lower === 'location') return '认证接口不允许重定向';
      if (lower === 'content-length') {
        const length = Number(values[key]);
        if (!Number.isSafeInteger(length) || length < 0 || length > maximumBytes) {
          return '认证响应长度无效或超过1 MB限制';
        }
      } else if (lower === 'content-type') {
        contentType = `${values[key]}`.toLowerCase();
      }
    }
    if (contentType.length > 0 && !contentType.includes('application/json') &&
      !contentType.includes('+json')) return '认证响应类型无效';
    return '';
  }

  /** 按UTF-8字节解析有界认证信封。 */
  envelope(text: string, bytes: number): AuthEnvelope {
    if (!Number.isSafeInteger(bytes) || bytes < 1 || bytes > 1024 * 1024 || text.length === 0) {
      throw new Error('认证响应为空或超过1 MB限制');
    }
    const parsed = JSON.parse(text) as Record<string, Object>;
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('认证响应格式无效');
    }
    const codeValue = parsed['code'];
    const messageValue = parsed['message'];
    if (typeof codeValue !== 'string' || codeValue.length < 1 || codeValue.length > 32 ||
      /[\u0000-\u001F\u007F]/.test(codeValue) ||
      (messageValue !== undefined && (typeof messageValue !== 'string' || messageValue.length > 1024 ||
        /[\u0000-\u001F\u007F]/.test(messageValue)))) throw new Error('认证响应格式无效');
    const dataValue = parsed['data'];
    if (dataValue !== undefined && (dataValue === null || typeof dataValue !== 'object' || Array.isArray(dataValue))) {
      throw new Error('认证响应数据格式无效');
    }
    return { code: codeValue, message: typeof messageValue === 'string' ? messageValue : '',
      data: dataValue === undefined ? undefined : dataValue as Record<string, Object> };
  }

  /** 限制认证JSON请求体，防止绕过字段校验构造异常载荷。 */
  requestBody(bytes: number): void {
    if (!Number.isSafeInteger(bytes) || bytes < 0 || bytes > 16 * 1024) {
      throw new Error('认证请求超过16 KB限制');
    }
  }
}
