import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
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
  private readonly normalizer: DshResponseNormalizer = new DshResponseNormalizer();

  constructor(baseUrl: string, authManager: AuthSessionManager) {
    this.client = new AuthorizedApiClient(baseUrl, authManager);
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
