import { DshWebAuthPolicy } from './DshWebAuthPolicy';
import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import { DshHistory, DshSession, DshStatus } from './DshModels';
import { DshResponseNormalizer } from './DshResponseNormalizer';

interface DshCreateSessionBody {
  workspaceKey: string;
}

interface DshPromptBody {
  text: string;
  clientTimeZone: string;
}

interface DshApprovalBody {
  allow: boolean;
}

/**
 * MyTools 后端提供的 DSH 语义网关客户端。
 */
export class DshApi {
  private readonly client: AuthorizedApiClient;
  private readonly authManager: AuthSessionManager;
  private readonly normalizer: DshResponseNormalizer = new DshResponseNormalizer();

  constructor(baseUrl: string, authManager: AuthSessionManager) {
    this.authManager = authManager;
    this.client = new AuthorizedApiClient(baseUrl, authManager);
  }

  /** 使用当前 MyTools 登录态换取短时一次性网页连接票据。 */
  async webTicket(preferSecure: boolean = false, forcePublic: boolean = false,
    cancellation?: DownloadCancellationToken): Promise<string> {
    // 线路发现可以与换票并行，返回的票据只用于本次预先选定的网页入口。
    const publicBusinessBase = this.authManager.publicBusinessBaseUrl();
    const origin = DshWebAuthPolicy.webOrigin(this.authManager.activeBusinessBaseUrl(), publicBusinessBase,
      preferSecure, forcePublic);
    if (origin.length === 0) throw new Error('Invalid DSH endpoint');
    // 公网回退的换票请求也使用公网，避免网页绕开了失效的直连却仍从原线路取票。
    const client = forcePublic ? new AuthorizedApiClient(publicBusinessBase,
      this.authManager, false, 20000, true) : this.client;
    const data = (await client.post('/api/app/v1/dsh/web-ticket', cancellation)).data;
    if (data === undefined || data === null || typeof data !== 'object' || Array.isArray(data)) {
      throw new Error('Invalid DSH ticket response');
    }
    const url = (data as Record<string, Object>)['url'];
    if (typeof url !== 'string' || DshWebAuthPolicy.connectionUrl(url).length === 0) {
      throw new Error('Invalid DSH ticket URL');
    }
    return origin + url.substring(DshWebAuthPolicy.PUBLIC_ORIGIN.length);
  }

  /** 查询远程 DSH 状态。 */
  async status(): Promise<DshStatus> {
    return this.normalizer.status((await this.client.get('/api/app/v1/dsh/status')).data);
  }

  /** 查询当前账户会话。 */
  async sessions(): Promise<DshSession[]> {
    return this.normalizer.sessions((await this.client.get('/api/app/v1/dsh/sessions')).data);
  }

  /** 创建固定工作区内的新会话。 */
  async createSession(): Promise<DshSession> {
    const body: DshCreateSessionBody = { workspaceKey: 'default' };
    return this.normalizer.session((await this.client.postJson('/api/app/v1/dsh/sessions', body)).data);
  }

  /** 归档当前账户的会话。 */
  async archiveSession(sessionId: string): Promise<void> {
    await this.client.delete(`/api/app/v1/dsh/sessions/${encodeURIComponent(this.validSessionId(sessionId))}`);
  }

  /** 读取会话已过滤历史。 */
  async history(sessionId: string): Promise<DshHistory> {
    return this.normalizer.history((await this.client.get(`/api/app/v1/dsh/sessions/` +
      `${encodeURIComponent(this.validSessionId(sessionId))}/history`)).data);
  }

  /** 向 DSH 会话发送消息。 */
  async prompt(sessionId: string, text: string): Promise<void> {
    const normalized = text.trim();
    if (normalized.length === 0 || normalized.length > 32768) throw new Error('DSH消息长度无效');
    const body: DshPromptBody = { text: normalized, clientTimeZone: 'Asia/Shanghai' };
    await this.client.postJson(`/api/app/v1/dsh/sessions/${encodeURIComponent(this.validSessionId(sessionId))}` +
      '/messages', body);
  }

  /** 取消 DSH 会话当前轮次。 */
  async cancel(sessionId: string): Promise<void> {
    await this.client.post(`/api/app/v1/dsh/sessions/${encodeURIComponent(this.validSessionId(sessionId))}/cancel`);
  }

  /** 回复 DSH 下行授权。 */
  async replyApproval(sessionId: string, interactionId: string, allow: boolean): Promise<void> {
    if (!/^[0-9a-fA-F-]{36}$/.test(interactionId)) throw new Error('DSH授权标识无效');
    const body: DshApprovalBody = { allow: allow };
    await this.client.postJson(`/api/app/v1/dsh/sessions/${encodeURIComponent(this.validSessionId(sessionId))}` +
      `/approvals/${encodeURIComponent(interactionId)}`, body);
  }

  private validSessionId(sessionId: string): string {
    const normalized = sessionId.trim();
    if (!/^session-[A-Za-z0-9-]{1,96}$/.test(normalized)) throw new Error('DSH会话标识无效');
    return normalized;
  }
}
