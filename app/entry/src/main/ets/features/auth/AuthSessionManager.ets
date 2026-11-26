import { HomeChannelOrigin } from '../connectivity/HomeChannelOrigin';
import { MediaTransferRoutes } from '../../shared/network/MediaTransferRoutes';
import { AuthApi, AuthRejectedError } from './AuthApi';
import { AuthSession, LoginRequest } from './AuthModels';
import { SecureSessionStore } from './SecureSessionStore';
import { BusinessEndpointState } from '../../shared/network/BusinessEndpointState';
import { BusinessResponseError, BusinessTransportError } from '../../shared/network/BusinessTransportError';
import util from '@ohos.util';

/**
 * 管理登录状态并串行化刷新操作。
 */
export class AuthSessionManager {
  onSessionInvalidated?: () => void;
  onHomeChannelUnavailable?: () => void;
  onBusinessEndpointUnavailable?: (baseUrl: string) => void;
  private readonly api: AuthApi = new AuthApi();
  private readonly store: SecureSessionStore = new SecureSessionStore();
  readonly mediaRoutes: MediaTransferRoutes = new MediaTransferRoutes();
  private readonly endpoints: BusinessEndpointState = new BusinessEndpointState();
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

  /** 返回认证操作代次，隔离旧会话中途失败的通道请求。 */
  sessionRevision(): number {
    return this.authenticationRevision;
  }

  /** 返回当前网络和业务线路代次，避免旧故障覆盖新线路。 */
  endpointRevision(): number {
    return this.endpoints.endpointRevision();
  }

  /** 返回当前登录会话绑定的可信公网服务地址。 */
  publicBusinessBaseUrl(): string {
    return this.api.getBaseUrl();
  }

  /** 返回当前会话经过验证的活动业务端点。 */
  activeBusinessBaseUrl(): string {
    return this.endpoints.activeBusinessBaseUrl() || this.publicBusinessBaseUrl();
  }

  /** 让已选端点的旧客户端跟随全局路由，同时保留待验证候选。 */
  resolveBusinessBaseUrl(requested: string): string {
    const direct = this.endpoints.resolveBusinessBaseUrl(this.normalizeBusinessBaseUrl(requested));
    const channel = HomeChannelOrigin.forService(this.publicBusinessBaseUrl());
    return channel && direct === this.activeBusinessBaseUrl() && direct.startsWith('https://') ? channel : direct;
  }

  /** 让已知业务来源的资源 URL 跟随会话路由，第三方与本地资源保持原样。 */
  resolveBusinessResourceUrl(value: string): string {
    const resolved = this.endpoints.resolveBusinessResourceUrl(value);
    const active = this.activeBusinessBaseUrl();
    const channel = HomeChannelOrigin.forService(this.publicBusinessBaseUrl());
    return channel && active.startsWith('https://') && resolved.startsWith(active + '/api/') ?
      channel + resolved.substring(active.length) : resolved;
  }

  /** 在服务身份和首次认证探针通过后激活业务端点。 */
  activateBusinessEndpoint(baseUrl: string): void {
    this.endpoints.activateBusinessEndpoint(this.normalizeBusinessBaseUrl(baseUrl));
  }

  /** 将全部后续业务请求统一恢复为可信公网连接。 */
  resetBusinessEndpoint(): void {
    this.endpoints.resetBusinessEndpoint();
  }

  /** 注册由连接协商完成验证的 IPv6 备用入口。 */
  registerVerifiedIpv6Endpoints(origins: string[], expiresAt: number): void {
    this.endpoints.registerVerifiedIpv6Endpoints(origins, expiresAt);
  }

  /** 判断候选是否仍在当前网络的失败冷却期。 */
  isBusinessEndpointCoolingDown(baseUrl: string): boolean {
    return this.endpoints.isEndpointCoolingDown(baseUrl);
  }

  /** 网络变化后清除旧备用线路的健康结论。 */
  invalidateNetworkCandidates(): void {
    this.mediaRoutes.clear();
    this.endpoints.invalidateNetworkCandidates();
  }

  /** 通知页面活动局域网地址已不可用。 */
  reportBusinessEndpointUnavailable(baseUrl: string, expectedRevision: number = this.endpointRevision()): void {
    // 先更新共享路由，再通知界面，保证并发请求立即看到同一个公网端点。
    if (!this.endpoints.reportBusinessEndpointUnavailable(baseUrl, expectedRevision)) return;
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
      console.info(`Auth restore: ${restored === undefined ? 'EMPTY' : 'STORED'}`);
      this.assertAuthenticationCurrent(revision);
      if (restored !== undefined) {
        // 未过期的本地会话可直接恢复，避免启动阶段网络抖动导致界面被错误降级为未登录。
        if (restored.expiresAt > Date.now() + 30000) {
          this.assertAuthenticationCurrent(revision);
        } else {
          try {
            await this.prepareRefresh(revision, normalizedBaseUrl, restored);
            restored = await api.refresh(restored);
            this.assertAuthenticationCurrent(revision);
            await this.commitAuthentication(revision, normalizedBaseUrl, restored);
            console.info('Auth restore: REFRESHED');
            this.assertAuthenticationCurrent(revision);
          } catch (error) {
            if (error instanceof AuthRejectedError) throw error;
            console.info('Auth restore: DEFERRED');
            // 网络暂时不可用时仍恢复本机身份；后续受保护请求会再次尝试刷新令牌。
            this.assertAuthenticationCurrent(revision);
          }
        }
      }
      this.api.setBaseUrl(normalizedBaseUrl);
      this.mediaRoutes.clear();
      this.endpoints.bindPublicBaseUrl(normalizedBaseUrl);
      this.session = restored;
      return restored;
    } catch (caught) {
      // 旧恢复的延迟结果不得通过异常兜底重新绑定已被新认证操作替换的账号。
      this.assertAuthenticationCurrent(revision);
      const error = caught instanceof Error ? caught : new Error('会话恢复失败');
      if (error instanceof AuthRejectedError) {
        console.info('Auth restore: REJECTED');
        await this.invalidateRejectedSession(revision);
        this.assertAuthenticationCurrent(revision);
        // 服务端明确拒绝后不得重新挂回刚清除的过期会话，否则每次请求都会重复刷新并再次失败。
        throw error;
      }
      if (restored !== undefined) {
        this.api.setBaseUrl(normalizedBaseUrl);
        this.mediaRoutes.clear();
        this.endpoints.bindPublicBaseUrl(normalizedBaseUrl);
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
    this.mediaRoutes.clear();
    this.endpoints.bindPublicBaseUrl(normalizedBaseUrl);
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
        await this.api.logout(accessToken, this.activeBusinessBaseUrl());
      }
    } catch (error) {
      if (error instanceof BusinessTransportError || error instanceof BusinessResponseError) {
        this.reportBusinessEndpointUnavailable(error.baseUrl);
      }
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
      const current = this.session;
      this.refreshTask = this.prepareRefresh(revision, this.api.getBaseUrl(), current).then(() => {
        // 操作标识已安全落盘后才请求轮换，网络超时或进程退出后仍可恢复同一次结果。
        this.assertAuthenticationCurrent(revision);
        return this.api.refresh(current, this.activeBusinessBaseUrl());
      }).then(async (session) => {
        await this.commitAuthentication(revision, this.api.getBaseUrl(), session);
        this.assertAuthenticationCurrent(revision);
        this.session = session;
        return session;
      }).catch(async (error: Error) => {
        if (error instanceof BusinessTransportError || error instanceof BusinessResponseError) {
          this.reportBusinessEndpointUnavailable(error.baseUrl);
        }
        if (error instanceof AuthRejectedError) {
          console.info('Auth refresh: REJECTED');
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
    this.mediaRoutes.clear();
    this.session = undefined;
    this.refreshTask = undefined;
    this.endpoints.resetBusinessEndpoint();
  }

  private async prepareRefresh(revision: number, baseUrl: string, session: AuthSession): Promise<void> {
    this.assertAuthenticationCurrent(revision);
    if (session.refreshOperationId === undefined) session.refreshOperationId = util.generateRandomUUID(true);
    // 每次尝试均确认意图写入成功，禁止在上次安全存储失败后直接发送请求。
    await this.commitAuthentication(revision, baseUrl, session);
    this.assertAuthenticationCurrent(revision);
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
