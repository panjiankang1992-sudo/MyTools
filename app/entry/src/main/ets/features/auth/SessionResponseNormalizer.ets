import { DeviceSession, SessionEnvelope } from './SessionModels';

/**
 * 对设备会话接口信封和列表执行有界归一化。
 */
export class SessionResponseNormalizer {
  /** 归一化新 Identity 设备会话列表。 */
  identitySessions(data: Object | undefined): DeviceSession[] {
    if (!Array.isArray(data) || data.length > 1000) throw new Error('服务端未返回会话列表');
    const sessions: DeviceSession[] = [];
    data.forEach((raw: Object) => {
      const item = this.record(raw);
      if (item === undefined) return;
      try {
        const id = this.uuid(item['id']);
        sessions.push({ id, name: this.optionalText(item['deviceId'], 255), tokenPrefix: '****',
          status: this.status(item['status']), createdTime: this.time(item['issuedAt']),
          expireTime: this.time(item['refreshExpiresAt']), current: item['current'] === true });
      } catch (_) {
      }
    });
    const current = sessions.find((item: DeviceSession): boolean => item.current);
    const others = sessions.filter((item: DeviceSession): boolean => !item.current);
    return current === undefined ? others.slice(0, 200) : [current].concat(others).slice(0, 200);
  }
  /**
   * 解析最多1 MB的会话JSON信封。
   */
  parseEnvelope(text: string): SessionEnvelope {
    if (text.length === 0 || text.length > 1024 * 1024) throw new Error('会话响应为空或超过1 MB限制');
    const parsed = JSON.parse(text) as Record<string, Object>;
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('会话响应格式无效');
    }
    const code = parsed['code'];
    const message = parsed['message'];
    const data = parsed['data'];
    if (typeof code !== 'string' || code.length < 1 || code.length > 32 ||
      /[\u0000-\u001F\u007F]/.test(code) ||
      (message !== undefined && (typeof message !== 'string' || message.length > 1024 ||
        /[\u0000-\u001F\u007F]/.test(message))) ||
      (data !== undefined && (data === null || typeof data !== 'object' || Array.isArray(data)))) {
      throw new Error('会话响应格式无效');
    }
    return {
      code: code,
      message: typeof message === 'string' ? message : '',
      data: data === undefined ? undefined : data as Record<string, Object>
    };
  }

  /**
   * 提取当前会话的雪花ID。
   */
  currentId(data: Object | undefined): string {
    const record = this.record(data);
    if (record === undefined) throw new Error('服务端未返回当前会话');
    return this.id(record['id']);
  }

  /**
   * 最多检查1000项、按ID去重并保留200个有效设备会话。
   */
  sessions(data: Object | undefined, currentId: string): DeviceSession[] {
    const record = this.record(data);
    if (record === undefined || !Array.isArray(record['list'])) throw new Error('服务端未返回会话列表');
    const safeCurrentId = this.id(currentId);
    const seen: Record<string, boolean> = {};
    const normalized: DeviceSession[] = [];
    (record['list'] as Object[]).slice(0, 1000).forEach((raw: Object) => {
      if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) return;
      try {
        const item = raw as Record<string, Object>;
        const id = this.id(item['id']);
        if (seen[id] === true) return;
        seen[id] = true;
        normalized.push({
          id: id,
          name: this.optionalText(item['tokenName'], 64),
          tokenPrefix: this.prefix(item['tokenPrefix']),
          status: this.status(item['status']),
          createdTime: this.time(item['createdTime']),
          expireTime: this.time(item['expireTime']),
          current: id === safeCurrentId
        });
      } catch (_) {
        // 单个损坏会话隔离丢弃，其他设备仍可管理。
      }
    });
    const current = normalized.find((item: DeviceSession) => item.current);
    const others = normalized.filter((item: DeviceSession) => !item.current);
    return current === undefined ? others.slice(0, 200) : [current].concat(others).slice(0, 200);
  }

  /**
   * 校验下线请求中的会话ID。
   */
  revokeId(value: string): string {
    return this.uuid(value);
  }

  private uuid(value: Object | undefined): string {
    const text = typeof value === 'string' ? value : '';
    if (!/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$/.test(text)) {
      throw new Error('设备会话标识无效');
    }
    return text.toLowerCase();
  }

  private id(value: Object | undefined): string {
    const text = typeof value === 'string' || typeof value === 'number' ? `${value}` : '';
    if (!/^\d{1,20}$/.test(text) || text === '0') throw new Error('设备会话标识无效');
    return text;
  }

  private record(value: Object | undefined): Record<string, Object> | undefined {
    return value !== undefined && value !== null && typeof value === 'object' && !Array.isArray(value) ?
      value as Record<string, Object> : undefined;
  }

  private optionalText(value: Object | undefined, maximum: number): string {
    if (value === undefined || value === null) return '';
    if (typeof value !== 'string' || value.length > maximum || /[\u0000-\u001F\u007F]/.test(value)) {
      throw new Error('设备会话名称无效');
    }
    return value;
  }

  private prefix(value: Object | undefined): string {
    if (typeof value !== 'string' || !/^(?:[A-Za-z0-9_-]{4,16}|\*{4})$/.test(value)) {
      throw new Error('设备会话令牌前缀无效');
    }
    return value;
  }

  private status(value: Object | undefined): string {
    if (typeof value === 'string' && (value === 'ACTIVE' || value === 'INVALID' || value === 'EXPIRED')) return value;
    throw new Error('设备会话状态无效');
  }

  private time(value: Object | undefined): string {
    if (value === undefined || value === null) return '';
    if (typeof value !== 'string' || value.length > 40 ||
      !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})?$/.test(value)) {
      throw new Error('设备会话时间无效');
    }
    return value;
  }
}
