import { AuthApi, AuthRejectedError } from './AuthApi';
import { AuthSession, LoginRequest } from './AuthModels';
import { SecureSessionStore } from './SecureSessionStore';

/**
 * 管理登录状态并串行化刷新操作。
 */
export class AuthSessionManager {
  onSessionInvalidated?: () => void;
  onBusinessEndpointUnavailable?: (baseUrl: string) => void;
  private readonly api: AuthApi = new AuthApi();
  private readonly store: SecureSessionStore = new SecureSessionStore();
  private session?: AuthSession;
  private refreshTask?: Promise<AuthSession>;
  private authenticationRevision: number = 0;
  private authenticationCommitTask: Promise<void> = Promise.resolve();

  /**
   * 校验并规范化供所有业务模块复用的 MyTools 服务地址。
   */
  normalizeBaseUrl(baseUrl: string): string {
    return AuthApi.normalizeBaseUrl(baseUrl);
  }

  /** 校验经过发现流程使用的业务服务地址。 */
  normalizeBusinessBaseUrl(baseUrl: string): string {
    return AuthApi.normalizeBusinessBaseUrl(baseUrl);
  }

  /** 返回当前登录会话绑定的可信公网服务地址。 */
  publicBusinessBaseUrl(): string {
    return this.api.getBaseUrl();
  }

  /** 通知页面活动局域网地址已不可用。 */
  reportBusinessEndpointUnavailable(baseUrl: string): void {
    try {
      this.onBusinessEndpointUnavailable?.(baseUrl);
    } catch (_) {
    }
  }

  /**
   * 测试 MyTools 服务连通性。
   */
  async testConnection(baseUrl: string): Promise<number> {
    return new AuthApi().testConnection(baseUrl);
  }

  /**
   * 恢复安全存储中的会话。
   */
  async restore(baseUrl: string): Promise<AuthSession | undefined> {
    const revision = ++this.authenticationRevision;
    const normalizedBaseUrl = AuthApi.normalizeBaseUrl(baseUrl);
    const api = new AuthApi();
    api.setBaseUrl(normalizedBaseUrl);
    let restored: AuthSession | undefined = undefined;
    try {
      restored = await this.store.load(normalizedBaseUrl);
      this.assertAuthenticationCurrent(revision);
      if (restored !== undefined) {
        // 未过期的本地会话可直接恢复，避免启动阶段网络抖动导致界面被错误降级为未登录。
        if (restored.expiresAt > Date.now() + 30000) {
          this.assertAuthenticationCurrent(revision);
        } else {
          try {
            restored = await api.refresh(restored);
            this.assertAuthenticationCurrent(revision);
            await this.commitAuthentication(revision, normalizedBaseUrl, restored);
            this.assertAuthenticationCurrent(revision);
          } catch (error) {
            if (error instanceof AuthRejectedError) throw error;
            // 网络暂时不可用时仍恢复本机身份；后续受保护请求会再次尝试刷新令牌。
            this.assertAuthenticationCurrent(revision);
          }
        }
      }
      this.api.setBaseUrl(normalizedBaseUrl);
      this.session = restored;
      return restored;
    } catch (caught) {
      const error = caught instanceof Error ? caught : new Error('会话恢复失败');
      if (error instanceof AuthRejectedError) {
        await this.invalidateRejectedSession(revision);
        this.assertAuthenticationCurrent(revision);
        // 服务端明确拒绝后不得重新挂回刚清除的过期会话，否则每次请求都会重复刷新并再次失败。
        throw error;
      }
      if (restored !== undefined) {
        this.api.setBaseUrl(normalizedBaseUrl);
        this.session = restored;
        return restored;
      }
      throw error;
    }
  }

  /**
   * 登录并安全保存会话。
   */
  async login(baseUrl: string, request: LoginRequest): Promise<AuthSession> {
    const revision = ++this.authenticationRevision;
    const normalizedBaseUrl = AuthApi.normalizeBaseUrl(baseUrl);
    const api = new AuthApi();
    api.setBaseUrl(normalizedBaseUrl);
    const session = await api.login(request);
    this.assertAuthenticationCurrent(revision);
    await this.commitAuthentication(revision, normalizedBaseUrl, session);
    this.assertAuthenticationCurrent(revision);
    this.api.setBaseUrl(normalizedBaseUrl);
    this.session = session;
    return session;
  }

  /**
   * 返回有效访问令牌，临近过期时只发起一次刷新。
   */
  async validAccessToken(): Promise<string> {
    if (this.session === undefined) {
      throw new Error('当前未登录');
    }
    if (this.session.expiresAt > Date.now() + 30000) {
      return this.session.accessToken;
    }
    return (await this.refresh()).accessToken;
  }

  /**
   * 服务端返回未授权时强制刷新访问令牌。
   */
  async forceRefresh(rejectedAccessToken?: string): Promise<string> {
    // 并发请求可能稍后才收到旧令牌的401；已有请求完成轮换后直接复用新令牌，避免重复轮换雪崩。
    if (rejectedAccessToken !== undefined && this.session !== undefined &&
      this.session.accessToken !== rejectedAccessToken) return this.session.accessToken;
    return (await this.refresh()).accessToken;
  }

  /**
   * 注销服务端会话并清理本地凭证。
   */
  async logout(): Promise<void> {
    const revision = ++this.authenticationRevision;
    const accessToken = this.session?.accessToken ?? '';
    try {
      if (accessToken.length > 0) {
        await this.api.logout(accessToken);
      }
    } catch (_) {
      // 服务端不可达或会话已失效时仍必须优先清除本机安全凭证。
    }
    await this.clearLocalSession(revision);
  }

  private async refresh(): Promise<AuthSession> {
    if (this.session === undefined) {
      throw new Error('当前未登录');
    }
    if (this.refreshTask === undefined) {
      const revision = this.authenticationRevision;
      this.refreshTask = this.api.refresh(this.session).then(async (session) => {
        await this.commitAuthentication(revision, this.api.getBaseUrl(), session);
        this.assertAuthenticationCurrent(revision);
        this.session = session;
        return session;
      }).catch(async (error: Error) => {
        if (error instanceof AuthRejectedError) {
          await this.invalidateRejectedSession(revision);
        }
        throw error;
      }).finally(() => {
        this.refreshTask = undefined;
      });
    }
    return this.refreshTask;
  }

  private async clearLocalSession(revision: number): Promise<void> {
    const previous = this.authenticationCommitTask;
    const current = previous.catch(() => {}).then(async () => {
      this.assertAuthenticationCurrent(revision);
      await this.store.clear();
      this.assertAuthenticationCurrent(revision);
    });
    this.authenticationCommitTask = current;
    await current;
    this.session = undefined;
    this.refreshTask = undefined;
  }

  private async invalidateRejectedSession(revision: number): Promise<void> {
    await this.clearLocalSession(revision);
    this.assertAuthenticationCurrent(revision);
    try {
      this.onSessionInvalidated?.();
    } catch (_) {
    }
  }

  private assertAuthenticationCurrent(revision: number): void {
    if (revision !== this.authenticationRevision) throw new Error('认证操作已被新的请求替代');
  }

  private async commitAuthentication(revision: number, baseUrl: string, session: AuthSession): Promise<void> {
    const previous = this.authenticationCommitTask;
    const current = previous.catch(() => {}).then(async () => {
      this.assertAuthenticationCurrent(revision);
      await this.store.save(baseUrl, session);
      this.assertAuthenticationCurrent(revision);
    });
    this.authenticationCommitTask = current;
    await current;
  }
}
