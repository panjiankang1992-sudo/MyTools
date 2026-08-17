export type BookSourceCredentialHeader = 'Authorization' | 'Cookie' | 'X-API-Key';

/** 通过恢复校验的书源敏感凭据。 */
export interface NormalizedBookSourceCredential {
  sourceUrl: string;
  origin: string;
  headerName: BookSourceCredentialHeader;
  value: string;
}

/**
 * 校验书源敏感请求头及其HTTPS同源使用边界。
 */
export class BookSourceCredentialPolicy {
  private static readonly MAX_RECORDS: number = 64;
  /**
   * 提取书源地址的规范HTTPS origin。
   */
  originOf(value: string): string {
    const trimmed = value.trim();
    const match = trimmed.match(/^(https:\/\/[^/?#@]+)(?:[/?#]|$)/i);
    if (match === null || trimmed.length > 4096 || /[\r\n]/.test(trimmed)) {
      throw new Error('敏感书源凭据仅允许绑定安全HTTPS地址');
    }
    return match[1].toLowerCase();
  }

  /**
   * 校验受支持的敏感请求头名称。
   */
  normalizeHeaderName(value: string): BookSourceCredentialHeader {
    const lower = value.trim().toLowerCase();
    if (lower === 'authorization') return 'Authorization';
    if (lower === 'cookie') return 'Cookie';
    if (lower === 'x-api-key') return 'X-API-Key';
    throw new Error('仅支持Authorization、Cookie或X-API-Key凭据');
  }

  /**
   * 校验敏感请求头值，阻止换行注入及异常大凭据。
   */
  normalizeValue(value: string): string {
    const trimmed = value.trim();
    if (trimmed.length === 0 || trimmed.length > 4096 || /[\r\n]/.test(trimmed)) {
      throw new Error('书源凭据为空、过长或包含换行符');
    }
    return trimmed;
  }

  /** 从不可信Asset Store JSON逐项恢复并重新验证凭据。 */
  restore(value: Object): NormalizedBookSourceCredential[] {
    if (!Array.isArray(value)) return [];
    const records: Map<string, NormalizedBookSourceCredential> = new Map<string, NormalizedBookSourceCredential>();
    value.slice(0, 256).forEach((item: Object) => {
      if (item === null || typeof item !== 'object' || Array.isArray(item)) return;
      const input = item as Record<string, Object>;
      if (typeof input['sourceUrl'] !== 'string' || typeof input['origin'] !== 'string' ||
        typeof input['headerName'] !== 'string' || typeof input['value'] !== 'string') return;
      try {
        const sourceUrl = input['sourceUrl'].trim();
        const origin = this.originOf(sourceUrl);
        if (input['origin'].trim().toLowerCase() !== origin) return;
        const record: NormalizedBookSourceCredential = {
          sourceUrl: sourceUrl,
          origin: origin,
          headerName: this.normalizeHeaderName(input['headerName']),
          value: this.normalizeValue(input['value'])
        };
        records.set(sourceUrl, record);
      } catch (_) {
      }
    });
    return Array.from(records.values()).slice(-BookSourceCredentialPolicy.MAX_RECORDS);
  }
}
