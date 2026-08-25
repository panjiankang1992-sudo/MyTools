import { http } from '@kit.NetworkKit';
import util from '@ohos.util';
import url from '@ohos.url';
import { AuthEnvelope, AuthSession, LoginRequest } from './AuthModels';
import { AuthNetworkResponsePolicy } from './AuthNetworkResponsePolicy';
import { AuthResponseNormalizer } from './AuthResponseNormalizer';
import { TrustedLanEndpointPolicy } from '../connectivity/TrustedLanEndpointPolicy';

interface HttpResult {
  status: number;
  envelope: AuthEnvelope;
}

interface IdentityLoginRequest {
  username: string;
  password: string;
  deviceId: string;
}

interface IdentityRefreshRequest {
  refreshToken: string;
}

/**
 * 表示服务端已明确拒绝当前会话，而非暂时性网络故障。
 */
export class AuthRejectedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AuthRejectedError';
  }
}

/**
 * MyTools 后端认证 API。
 */
export class AuthApi {
  private baseUrl: string = '';
  private readonly networkPolicy: AuthNetworkResponsePolicy = new AuthNetworkResponsePolicy();
  private readonly normalizer: AuthResponseNormalizer = new AuthResponseNormalizer();

  /**
   * 设置并校验服务地址。
   */
  setBaseUrl(value: string): void {
    this.baseUrl = AuthApi.normalizeBaseUrl(value);
  }

  /**
   * 返回当前已规范化的服务地址。
   */
  getBaseUrl(): string {
    return this.baseUrl;
  }

  /**
   * 校验生产服务地址，只允许 HTTPS。
   */
  static normalizeBaseUrl(value: string): string {
    const trimmed = value.trim();
    if (trimmed.length === 0 || trimmed.length > 2048 || /[\u0000-\u001F\u007F]/.test(trimmed)) {
      throw new Error('服务地址为空、过长或包含控制字符');
    }
    try {
      const parsed = url.URL.parseURL(trimmed);
      if (parsed.protocol !== 'https:') throw new Error('服务地址必须使用 HTTPS');
      if (parsed.hostname.length === 0) throw new Error('服务地址缺少主机名');
      if (parsed.username.length > 0 || parsed.password.length > 0) throw new Error('服务地址不能包含账号或密码');
      if (parsed.search.length > 0 || parsed.hash.length > 0) throw new Error('服务地址不能包含查询参数或片段');
      if (/%2f|%5c/i.test(parsed.pathname) || parsed.pathname.includes('//')) {
        throw new Error('服务地址路径包含不安全的分隔符');
      }
      let pathname = parsed.pathname;
      while (pathname.length > 1 && pathname.endsWith('/')) pathname = pathname.substring(0, pathname.length - 1);
      return `https://${parsed.host}${pathname === '/' ? '' : pathname}`;
    } catch (error) {
      if (error instanceof Error && error.message.startsWith('服务地址')) throw error;
      throw new Error('服务地址格式无效');
    }
  }

  /** 校验业务请求地址，公网使用HTTPS，局域网仅允许固定端口的私网IPv4。 */
  static normalizeBusinessBaseUrl(value: string): string {
    const trimmed = value.trim();
    if (trimmed.startsWith('https://')) return AuthApi.normalizeBaseUrl(trimmed);
    return new TrustedLanEndpointPolicy().normalize(trimmed);
  }

  /**
   * 通过公开API路由探测后端可达性并返回响应耗时。
   */
  async testConnection(baseUrl: string): Promise<number> {
    this.setBaseUrl(baseUrl);
    const request = http.createHttp();
    const startedAt = Date.now();
    let responseError = '';
    try {
      request.on('headersReceive', (headers: Object) => {
        responseError = this.networkPolicy.headerError(headers, 1024 * 1024);
        if (responseError.length > 0) request.destroy();
      });
      const response = await request.request(`${this.baseUrl}/actuator/health`, {
        method: http.RequestMethod.GET,
        header: { 'Accept': 'application/json' },
        connectTimeout: 8000,
        readTimeout: 8000
      });
      if (responseError.length > 0) throw new Error(responseError);
      // 未携带凭证时返回401或403也证明后端API路由可达。
      if ((response.responseCode < 200 || response.responseCode >= 300) &&
        response.responseCode !== 401 && response.responseCode !== 403) {
        throw new Error(`服务不可用：HTTP ${response.responseCode}`);
      }
      return Date.now() - startedAt;
    } catch (error) {
      if (responseError.length > 0) throw new Error(responseError);
      const message = error instanceof Error ? error.message : '连接失败';
      throw new Error(message);
    } finally {
      request.off('headersReceive');
      request.destroy();
    }
  }

  /**
   * 使用账户和密码登录。
   */
  async login(requestBody: LoginRequest): Promise<AuthSession> {
    this.validateLoginRequest(requestBody);
    const identityRequest: IdentityLoginRequest = {
      username: requestBody.account,
      password: requestBody.password,
      deviceId: requestBody.deviceName
    };
    const result = await this.post('/api/app/v1/identity/login', identityRequest);
    if (result.status < 200 || result.status >= 300 || result.envelope.data === undefined) {
      const message = typeof result.envelope.message === 'string' ? result.envelope.message : '';
      throw new Error(message.length > 0 ? message : `登录失败：HTTP ${result.status}`);
    }
    return this.normalizer.normalizeLogin(result.envelope.data);
  }

  /**
   * 使用刷新令牌获取新的访问令牌。
   */
  async refresh(session: AuthSession): Promise<AuthSession> {
    const refreshRequest: IdentityRefreshRequest = { refreshToken: session.refreshToken };
    const result = await this.post('/api/app/v1/identity/refresh', refreshRequest);
    if (result.status < 200 || result.status >= 300 || result.envelope.data === undefined) {
      const responseMessage = typeof result.envelope.message === 'string' ? result.envelope.message : '';
      const message = responseMessage.length > 0 ? responseMessage : '会话刷新失败';
      if (result.status === 401 || result.status === 403) throw new AuthRejectedError(message);
      throw new Error(message);
    }
    return this.normalizer.normalizeRefresh(result.envelope.data, session);
  }

  /**
   * 通知服务端注销当前访问令牌。
   */
  async logout(accessToken: string): Promise<void> {
    await this.post('/api/app/v1/identity/logout', undefined, accessToken);
  }

  /**
   * 向服务端验证访问令牌当前仍对应有效设备会话。
   */
  async validate(accessToken: string): Promise<boolean> {
    if (this.baseUrl.length === 0) throw new Error('请先设置 MyTools 服务地址');
    const request = http.createHttp();
    let responseError = '';
    try {
      request.on('headersReceive', (headers: Object) => {
        responseError = this.networkPolicy.headerError(headers, 1024 * 1024);
        if (responseError.length > 0) request.destroy();
      });
      const response = await request.request(`${this.baseUrl}/api/app/v1/identity/me`, {
        method: http.RequestMethod.GET,
        header: { 'Accept': 'application/json', 'Authorization': `Bearer ${accessToken}` },
        connectTimeout: 10000,
        readTimeout: 15000
      });
      if (responseError.length > 0) throw new Error(responseError);
      if (response.responseCode === 401 || response.responseCode === 403) return false;
      if (response.responseCode < 200 || response.responseCode >= 300) {
        throw new Error(`会话验证失败：HTTP ${response.responseCode}`);
      }
      const text = typeof response.result === 'string' ? response.result : `${response.result}`;
      const bytes = new util.TextEncoder().encodeInto(text).length;
      const envelope = this.networkPolicy.envelope(text, bytes);
      if (envelope.code === '20001' || envelope.code === '20002' || envelope.code === '20004' ||
        envelope.code === '401') return false;
      if (envelope.code !== '0000' || envelope.data === undefined ||
        this.stringValue(envelope.data, 'userId').length === 0) {
        const message = typeof envelope.message === 'string' ? envelope.message : '';
        throw new Error(message.length > 0 ? message : '服务端未返回有效会话');
      }
      return true;
    } catch (error) {
      if (responseError.length > 0) throw new Error(responseError);
      if (error instanceof Error) throw error;
      throw new Error('会话验证请求失败');
    } finally {
      request.off('headersReceive');
      request.destroy();
    }
  }

  private async post(path: string, body?: Object, bearerToken?: string): Promise<HttpResult> {
    if (this.baseUrl.length === 0) {
      throw new Error('请先设置 MyTools 服务地址');
    }
    const request = http.createHttp();
    const headers: Record<string, string> = {
      'Accept': 'application/json',
      'Content-Type': 'application/json; charset=utf-8'
    };
    if (bearerToken !== undefined && bearerToken.length > 0) {
      headers.Authorization = `Bearer ${bearerToken}`;
    }
    // HarmonyOS真机不接受POST请求的空extraData；无业务正文时发送合法空JSON对象。
    const serialized = body === undefined ? '{}' : JSON.stringify(body);
    const serializedBytes = new util.TextEncoder().encodeInto(serialized).length;
    this.networkPolicy.requestBody(serializedBytes);
    let responseError = '';
    try {
      request.on('headersReceive', (responseHeaders: Object) => {
        responseError = this.networkPolicy.headerError(responseHeaders, 1024 * 1024);
        if (responseError.length > 0) request.destroy();
      });
      const response = await request.request(`${this.baseUrl}${path}`, {
        method: http.RequestMethod.POST,
        header: headers,
        extraData: serialized,
        connectTimeout: 10000,
        readTimeout: 15000
      });
      if (responseError.length > 0) throw new Error(responseError);
      const text = typeof response.result === 'string' ? response.result : `${response.result}`;
      const bytes = new util.TextEncoder().encodeInto(text).length;
      const envelope = this.networkPolicy.envelope(text, bytes);
      return { status: response.responseCode, envelope: envelope };
    } catch (error) {
      if (responseError.length > 0) throw new Error(responseError);
      const message = error instanceof Error ? error.message : '网络请求失败';
      throw new Error(message);
    } finally {
      request.off('headersReceive');
      request.destroy();
    }
  }

  private stringValue(data: Record<string, Object>, key: string): string {
    const value = data[key];
    return value === undefined || value === null ? '' : `${value}`;
  }

  private validateLoginRequest(requestBody: LoginRequest): void {
    if (requestBody.account.trim().length < 1 || requestBody.account.length > 320 ||
      /[\u0000-\u001F\u007F]/.test(requestBody.account)) throw new Error('账号格式无效');
    if (requestBody.password.trim().length < 1 || requestBody.password.length > 1024 ||
      /[\u0000\u000A\u000D]/.test(requestBody.password)) throw new Error('密码格式无效');
    if (requestBody.deviceName.length < 1 || requestBody.deviceName.length > 64 ||
      /[\u0000-\u001F\u007F]/.test(requestBody.deviceName)) throw new Error('设备名称格式无效');
  }

}
