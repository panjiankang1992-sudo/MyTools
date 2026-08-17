import { ProfileIdentity } from './ProfileModels';

/** 对用户资料接口返回的昵称和头像执行有界校验。 */
export class ProfileResponseNormalizer {
  private static readonly MAX_INLINE_AVATAR_LENGTH: number = 512 * 1024;

  /** 将不可信用户资料转换为可安全展示的身份字段。 */
  normalize(value: Object | undefined): ProfileIdentity {
    if (value === undefined || value === null || Array.isArray(value) || typeof value !== 'object') {
      throw new Error('服务端用户资料格式无效');
    }
    const record = value as Record<string, Object>;
    return {
      nickname: this.optionalText(record['nickname'], 256),
      avatar: this.avatar(record['avatar'])
    };
  }

  private optionalText(value: Object | undefined, maximum: number): string {
    if (value === undefined || value === null) return '';
    if (typeof value !== 'string' || value.length > maximum || /[\u0000-\u001F\u007F]/.test(value)) return '';
    return value;
  }

  private avatar(value: Object | undefined): string {
    if (typeof value !== 'string' || /[\u0000-\u001F\u007F]/.test(value)) return '';
    if (value.startsWith('data:image/')) {
      if (value.length > ProfileResponseNormalizer.MAX_INLINE_AVATAR_LENGTH) return '';
      return /^data:image\/(?:png|jpeg|webp);base64,[A-Za-z0-9+/]+={0,2}$/.test(value) ? value : '';
    }
    if (value.length > 2048) return '';
    if (value.startsWith('https://')) return value;
    if (value.startsWith('/') && !value.startsWith('//')) return value;
    return '';
  }
}
