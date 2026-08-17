import connection from '@ohos.net.connection';

interface CachedHostDecision {
  checkedAt: number;
  safe: boolean;
}

/**
 * 限制用户导入书源可访问的网络地址，阻止私网、回环和系统保留地址。
 */
export class SourceUrlPolicy {
  private static readonly CACHE_TTL_MS: number = 5 * 60 * 1000;
  private readonly decisions: Map<string, CachedHostDecision> = new Map<string, CachedHostDecision>();

  /**
   * 校验HTTP(S) URL及其当前全部DNS解析结果。
   */
  async assertSafe(url: string): Promise<void> {
    const host = this.extractHost(url);
    const cached = this.decisions.get(host);
    if (cached !== undefined && Date.now() - cached.checkedAt < SourceUrlPolicy.CACHE_TTL_MS) {
      if (!cached.safe) throw new Error('书源地址指向受保护网络');
      return;
    }
    if (this.isIpLiteral(host)) {
      const safe = this.isPublicAddress(host);
      this.decisions.set(host, { checkedAt: Date.now(), safe: safe });
      if (!safe) throw new Error('书源地址指向受保护网络');
      return;
    }
    let addresses: connection.NetAddress[];
    try {
      addresses = await connection.getAddressesByName(host);
    } catch (_) {
      throw new Error('书源域名无法解析');
    }
    const safe = addresses.length > 0 && addresses.every((value: connection.NetAddress) =>
      this.isPublicAddress(value.address));
    this.decisions.set(host, { checkedAt: Date.now(), safe: safe });
    if (!safe) throw new Error('书源域名解析到受保护网络');
  }

  private extractHost(url: string): string {
    const match = /^(https?):\/\/([^\/?#]+)(?:[\/?#]|$)/i.exec(url.trim());
    if (match === null || match[2].includes('@')) throw new Error('书源地址格式无效');
    const authority = match[2];
    let host = '';
    if (authority.startsWith('[')) {
      const closing = authority.indexOf(']');
      if (closing < 0) throw new Error('书源IPv6地址格式无效');
      host = authority.substring(1, closing);
      const suffix = authority.substring(closing + 1);
      if (suffix.length > 0 && !/^:\d{1,5}$/.test(suffix)) throw new Error('书源端口格式无效');
      if (suffix.length > 0 && (Number(suffix.substring(1)) <= 0 || Number(suffix.substring(1)) > 65535)) {
        throw new Error('书源端口超出范围');
      }
    } else {
      const colon = authority.lastIndexOf(':');
      host = colon > 0 ? authority.substring(0, colon) : authority;
      if (colon > 0 && !/^\d{1,5}$/.test(authority.substring(colon + 1))) {
        throw new Error('书源端口格式无效');
      }
      if (colon > 0 && (Number(authority.substring(colon + 1)) <= 0 ||
        Number(authority.substring(colon + 1)) > 65535)) throw new Error('书源端口超出范围');
    }
    host = host.toLowerCase().replace(/\.$/, '');
    if (host.length === 0 || host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local') ||
      host.endsWith('.internal')) throw new Error('书源主机名不可访问');
    return host;
  }

  private isIpLiteral(host: string): boolean {
    if (host.includes(':')) return true;
    if (/^[0-9.]+$/.test(host)) {
      if (!/^\d{1,3}(?:\.\d{1,3}){3}$/.test(host)) throw new Error('书源IPv4地址必须使用标准点分格式');
      return true;
    }
    return false;
  }

  private isPublicAddress(address: string): boolean {
    const normalized = address.toLowerCase().split('%')[0];
    if (normalized.includes(':')) return this.isPublicIpv6(normalized);
    const parts = normalized.split('.').map((value: string) => Number(value));
    if (parts.length !== 4 || parts.some((value: number) => !Number.isInteger(value) || value < 0 || value > 255)) {
      return false;
    }
    const first = parts[0];
    const second = parts[1];
    if (first === 0 || first === 10 || first === 127 || first >= 224) return false;
    if (first === 100 && second >= 64 && second <= 127) return false;
    if (first === 169 && second === 254) return false;
    if (first === 172 && second >= 16 && second <= 31) return false;
    if (first === 192 && (second === 0 || second === 168)) return false;
    if (first === 192 && second === 0 && parts[2] === 2) return false;
    if (first === 192 && second === 88 && parts[2] === 99) return false;
    if (first === 198 && (second === 18 || second === 19 || second === 51)) return false;
    if (first === 203 && second === 0 && parts[2] === 113) return false;
    return true;
  }

  private isPublicIpv6(address: string): boolean {
    if (address.includes('.')) return false;
    const doubleIndex = address.indexOf('::');
    if (doubleIndex !== address.lastIndexOf('::')) return false;
    const left = doubleIndex < 0 ? address.split(':') :
      address.substring(0, doubleIndex).split(':').filter((value: string) => value.length > 0);
    const right = doubleIndex < 0 ? [] :
      address.substring(doubleIndex + 2).split(':').filter((value: string) => value.length > 0);
    if (doubleIndex < 0 && left.length !== 8) return false;
    if (doubleIndex >= 0 && left.length + right.length >= 8) return false;
    const groups: number[] = [];
    for (let index = 0; index < left.length; index++) {
      if (!/^[0-9a-f]{1,4}$/.test(left[index])) return false;
      groups.push(parseInt(left[index], 16));
    }
    if (doubleIndex >= 0) {
      const missing = 8 - left.length - right.length;
      for (let index = 0; index < missing; index++) groups.push(0);
    }
    for (let index = 0; index < right.length; index++) {
      if (!/^[0-9a-f]{1,4}$/.test(right[index])) return false;
      groups.push(parseInt(right[index], 16));
    }
    if (groups.length !== 8) return false;
    const firstSevenZero = groups.slice(0, 7).every((value: number) => value === 0);
    if (firstSevenZero && (groups[7] === 0 || groups[7] === 1)) return false;
    if (groups.slice(0, 6).every((value: number) => value === 0)) return false;
    if ((groups[0] & 0xFE00) === 0xFC00 || (groups[0] & 0xFF00) === 0xFE00 ||
      (groups[0] & 0xFF00) === 0xFF00) return false;
    if (groups[0] === 0x2001 && groups[1] === 0x0DB8) return false;
    return true;
  }
}
