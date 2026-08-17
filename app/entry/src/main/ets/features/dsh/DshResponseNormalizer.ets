import { DshHistory, DshHistoryMessage, DshRunStep, DshSession, DshStatus } from './DshModels';

/**
 * 将 DSH 网关响应限制为 App 使用的稳定字段。
 */
export class DshResponseNormalizer {
  /** 校验 DSH 状态。 */
  status(value: Object | undefined): DshStatus {
    const data = this.record(value);
    return {
      enabled: data['enabled'] === true,
      connected: data['connected'] === true,
      version: this.optionalText(data, 'version', 160),
      provider: this.optionalText(data, 'provider', 160),
      model: this.optionalText(data, 'model', 160),
      workspaceKey: this.text(data, 'workspaceKey', 64),
      attachedSessions: this.nonNegativeInteger(data, 'attachedSessions')
    };
  }

  /** 校验 DSH 会话列表。 */
  sessions(value: Object | undefined): DshSession[] {
    if (!Array.isArray(value) || value.length > 200) throw new Error('DSH会话列表格式无效');
    return value.map((entry: Object): DshSession => {
      const data = this.record(entry);
      return {
        sessionId: this.sessionId(data, 'sessionId'),
        title: this.text(data, 'title', 160),
        updatedAt: this.nonNegativeInteger(data, 'updatedAt'),
        running: data['running'] === true,
        blank: data['blank'] === true,
        workspaceKey: this.text(data, 'workspaceKey', 64),
        agentPreset: this.optionalText(data, 'agentPreset', 64)
      };
    });
  }

  /** 校验新建会话。 */
  session(value: Object | undefined): DshSession {
    const sessions = this.sessions([value] as Object[]);
    return sessions[0];
  }

  /** 校验已过滤的 DSH 历史。 */
  history(value: Object | undefined): DshHistory {
    const data = this.record(value);
    const rawMessages = data['messages'];
    const rawSteps = data['steps'];
    if (!Array.isArray(rawMessages) || rawMessages.length > 100 || !Array.isArray(rawSteps) ||
      rawSteps.length > 1000) throw new Error('DSH历史格式无效');
    const messages = rawMessages.map((entry: Object): DshHistoryMessage => {
      const item = this.record(entry);
      const role = this.text(item, 'role', 16);
      if (role !== 'user' && role !== 'assistant') throw new Error('DSH消息角色无效');
      return {
        id: this.text(item, 'id', 160),
        seq: this.integer(item, 'seq'),
        time: this.nonNegativeInteger(item, 'time'),
        role: role,
        text: this.text(item, 'text', 200000),
        status: this.optionalText(item, 'status', 32)
      };
    });
    const steps = rawSteps.map((entry: Object): DshRunStep => {
      const item = this.record(entry);
      return {
        id: this.text(item, 'id', 160),
        seq: this.integer(item, 'seq'),
        time: this.nonNegativeInteger(item, 'time'),
        type: this.text(item, 'type', 64),
        label: this.text(item, 'label', 160),
        status: this.text(item, 'status', 32)
      };
    });
    return {
      messages: messages,
      steps: steps,
      hasMore: data['hasMore'] === true,
      lastSeq: this.integer(data, 'lastSeq')
    };
  }

  private record(value: Object | undefined): Record<string, Object> {
    if (value === undefined || value === null || Array.isArray(value) || typeof value !== 'object') {
      throw new Error('DSH响应格式无效');
    }
    return value as Record<string, Object>;
  }

  private sessionId(data: Record<string, Object>, key: string): string {
    const value = `${data[key] ?? ''}`;
    if (!/^session-[A-Za-z0-9-]{1,96}$/.test(value)) throw new Error('DSH会话标识无效');
    return value;
  }

  private text(data: Record<string, Object>, key: string, limit: number): string {
    const value = data[key];
    if (typeof value !== 'string' || value.length === 0 || value.length > limit ||
      /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/.test(value)) throw new Error(`DSH字段${key}无效`);
    return value;
  }

  private optionalText(data: Record<string, Object>, key: string, limit: number): string {
    const value = data[key];
    if (value === undefined || value === null || value === '') return '';
    return this.text(data, key, limit);
  }

  private integer(data: Record<string, Object>, key: string): number {
    const value = Number(data[key]);
    if (!Number.isSafeInteger(value) || value < -1) throw new Error(`DSH字段${key}无效`);
    return value;
  }

  private nonNegativeInteger(data: Record<string, Object>, key: string): number {
    const value = Number(data[key]);
    if (!Number.isSafeInteger(value) || value < 0) throw new Error(`DSH字段${key}无效`);
    return value;
  }
}
