import { AuthSession } from './AuthModels';

/**
 * 对登录、刷新与安全存储中的认证数据执行失败关闭的字段校验。
 */
export class AuthResponseNormalizer {
  /**
   * 将不可信登录响应转换为可持久化会话。
   */
  normalizeLogin(data: Record<string, Object>, now: number = Date.now()): AuthSession {
    const expiresIn = this.expiresIn(data['expiresIn']);
    return {
      userId: this.requiredString(data['userId'], '用户标识', 20, /^\d{1,20}$/),
      username: this.requiredString(data['username'], '用户名', 128),
      nickname: this.optionalString(data['nickname'], '昵称', 256),
      avatar: this.avatar(data['avatar']),
      role: this.requiredString(data['role'], '用户角色', 64, /^[A-Za-z][A-Za-z0-9_-]{0,63}$/),
      accessToken: this.token(data['accessToken'], '访问令牌'),
      refreshToken: this.token(data['refreshToken'], '刷新令牌'),
      expiresAt: this.expiry(now, expiresIn)
    };
  }

  /**
   * 校验轮换后的两个令牌并返回身份字段不变的新会话。
   */
  normalizeRefresh(data: Record<string, Object>, previous: AuthSession, now: number = Date.now()): AuthSession {
    const identity = this.normalizeStored(previous, now, true);
    // 兼容线上旧版MyTools刷新协议：未轮换刷新令牌时继续使用已校验的原令牌。
    const responseRefreshToken = data['refreshToken'];
    const refreshToken = responseRefreshToken === undefined || responseRefreshToken === null ?
      identity.refreshToken : this.token(responseRefreshToken, '刷新令牌');
    return {
      userId: identity.userId,
      username: identity.username,
      nickname: identity.nickname,
      avatar: identity.avatar,
      role: identity.role,
      accessToken: this.token(data['accessToken'], '访问令牌'),
      refreshToken: refreshToken,
      expiresAt: this.expiry(now, this.expiresIn(data['expiresIn']))
    };
  }

  /**
   * 重新校验Asset Store中的会话；允许已过期会话进入刷新流程。
   */
  normalizeStored(session: AuthSession, now: number = Date.now(), allowExpired: boolean = true): AuthSession {
    if (session === undefined || session === null || typeof session !== 'object') {
      throw new Error('安全会话格式无效');
    }
    const expiresAt = session.expiresAt;
    if (!Number.isSafeInteger(expiresAt) || expiresAt > now + 366 * 24 * 60 * 60 * 1000 ||
      expiresAt < now - 366 * 24 * 60 * 60 * 1000 ||
      (!allowExpired && expiresAt < now - 5 * 60 * 1000) || expiresAt < 0) {
      throw new Error('安全会话过期时间无效');
    }
    return {
      userId: this.requiredString(session.userId, '用户标识', 20, /^\d{1,20}$/),
      username: this.requiredString(session.username, '用户名', 128),
      nickname: this.optionalString(session.nickname, '昵称', 256),
      avatar: this.avatar(session.avatar),
      role: this.requiredString(session.role, '用户角色', 64, /^[A-Za-z][A-Za-z0-9_-]{0,63}$/),
      accessToken: this.token(session.accessToken, '访问令牌'),
      refreshToken: this.token(session.refreshToken, '刷新令牌'),
      expiresAt: expiresAt
    };
  }

  private expiresIn(value: Object | undefined): number {
    const seconds = typeof value === 'number' ? value : Number(`${value ?? ''}`);
    if (!Number.isSafeInteger(seconds) || seconds < 1 || seconds > 366 * 24 * 60 * 60) {
      throw new Error('服务端令牌有效期无效');
    }
    return seconds;
  }

  private expiry(now: number, seconds: number): number {
    if (!Number.isSafeInteger(now) || now < 0) throw new Error('客户端时间无效');
    const expiresAt = now + seconds * 1000;
    if (!Number.isSafeInteger(expiresAt)) throw new Error('服务端令牌有效期无效');
    return expiresAt;
  }

  private token(value: Object | undefined, label: string): string {
    const token = typeof value === 'string' ? value : '';
    if (token.length < 32 || token.length > 8192 || /[\u0000-\u0020\u007F]/.test(token) ||
      !/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(token)) {
      throw new Error(`服务端${label}格式无效`);
    }
    return token;
  }

  private avatar(value: Object | undefined): string {
    // 头像仅是可选展示字段，任何异常都回退为账号首字符，不得阻断有效会话。
    if (typeof value !== 'string' || value.length > 2048 || /[\u0000-\u001F\u007F]/.test(value)) return '';
    const avatar = value;
    const safeInlineImage = /^data:image\/(?:png|jpeg|webp);base64,[A-Za-z0-9+/]+={0,2}$/.test(avatar);
    // 后端默认头像为SVG Data URL；不将可执行图形载荷传入UI，改用账号首字符回退。
    if (/^data:image\/svg\+xml;base64,[A-Za-z0-9+/]+={0,2}$/.test(avatar)) return '';
    if (avatar.length > 0 && !avatar.startsWith('https://') && !avatar.startsWith('/') && !safeInlineImage) {
      return '';
    }
    return avatar;
  }

  private requiredString(value: Object | undefined, label: string, maximum: number,
    pattern?: RegExp): string {
    const text = typeof value === 'string' || typeof value === 'number' ? `${value}` : '';
    if (text.length < 1 || text.length > maximum || /[\u0000-\u001F\u007F]/.test(text) ||
      (pattern !== undefined && !pattern.test(text))) throw new Error(`服务端${label}格式无效`);
    return text;
  }

  private optionalString(value: Object | undefined, label: string, maximum: number): string {
    if (value === undefined || value === null) return '';
    if (typeof value !== 'string' || value.length > maximum || /[\u0000-\u001F\u007F]/.test(value)) {
      throw new Error(`服务端${label}格式无效`);
    }
    return value;
  }
}
