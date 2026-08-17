/**
 * 提供OPDS凭据同源匹配与HTTP Basic编码所需的无状态安全策略。
 */
export interface NormalizedOpdsCredential {
  origin: string;
  username: string;
  password: string;
}

export class OpdsCredentialPolicy {
  /**
   * 提取安全HTTPS地址的规范化origin。
   */
  originOf(value: string): string {
    const trimmed = value.trim();
    const match = trimmed.match(/^(https:\/\/[^/?#@]+)(?:[/?#]|$)/i);
    if (match === null || trimmed.length > 4096 || /[\r\n]/.test(trimmed)) {
      throw new Error('OPDS仅允许安全的HTTPS地址');
    }
    return match[1].toLowerCase();
  }

  /**
   * 将UTF-8凭据字节编码为Base64，不保留明文副本。
   */
  encodeBase64(bytes: Uint8Array): string {
    const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    let output = '';
    for (let index = 0; index < bytes.length; index += 3) {
      const first = bytes[index];
      const second = index + 1 < bytes.length ? bytes[index + 1] : 0;
      const third = index + 2 < bytes.length ? bytes[index + 2] : 0;
      output += alphabet.charAt(first >> 2);
      output += alphabet.charAt(((first & 3) << 4) | (second >> 4));
      output += index + 1 < bytes.length ? alphabet.charAt(((second & 15) << 2) | (third >> 6)) : '=';
      output += index + 2 < bytes.length ? alphabet.charAt(third & 63) : '=';
    }
    return output;
  }

  /** 统一校验新保存或从Asset Store恢复的凭据。 */
  normalize(originValue: Object, usernameValue: Object, passwordValue: Object): NormalizedOpdsCredential | undefined {
    if (typeof originValue !== 'string' || typeof usernameValue !== 'string' || typeof passwordValue !== 'string') {
      return undefined;
    }
    try {
      const origin = this.originOf(originValue);
      if (originValue.trim().toLowerCase() !== origin) return undefined;
      const username = usernameValue.trim();
      if (username.length === 0 || username.length > 256 || passwordValue.length === 0 ||
        passwordValue.length > 1024 || /[\r\n\u0000\u007F]/.test(username) ||
        /[\r\n\u0000\u007F]/.test(passwordValue)) return undefined;
      return { origin: origin, username: username, password: passwordValue };
    } catch (_) {
      return undefined;
    }
  }

  /** 从不可信JSON对象恢复凭据。 */
  restore(value: Object): NormalizedOpdsCredential | undefined {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) return undefined;
    const record = value as Record<string, Object>;
    return this.normalize(record['origin'], record['username'], record['password']);
  }
}
