/** 校验由可信服务签发的 IPv6 HTTPS 入口与解析地址。 */
export class Ipv6EndpointPolicy {
  /** 只接受普通 DNS 主机的 HTTPS 来源，不接受路径、凭据或裸 IP。 */
  normalizeOrigin(value: string): string {
    const match = /^https:\/\/((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{1,62})(?::([0-9]{1,5}))?$/.exec(value);
    if (match === null || value.length > 300) throw new Error('Invalid IPv6 direct origin');
    const port = match[2] === undefined ? 443 : Number(match[2]);
    if (port < 1 || port > 65535) throw new Error('Invalid IPv6 direct port');
    return value;
  }

  /** 提取用于系统 DNS 解析的主机名。 */
  hostname(origin: string): string {
    return this.normalizeOrigin(origin).substring(8).split(':')[0];
  }

  /** 验证可用于公网直连的保守全球单播地址范围。 */
  isGlobalIpv6(value: string): boolean {
    if (!/^[0-9a-fA-F:]+$/.test(value) || !value.includes(':')) return false;
    const halves = value.toLowerCase().split('::');
    if (halves.length > 2) return false;
    const left = halves[0].length === 0 ? [] : halves[0].split(':');
    const right = halves.length === 1 || halves[1].length === 0 ? [] : halves[1].split(':');
    const count = left.length + right.length;
    if ((halves.length === 1 && count !== 8) || (halves.length === 2 && count >= 8)) return false;
    if (!left.concat(right).every((part: string): boolean => /^[0-9a-f]{1,4}$/.test(part))) return false;
    const words = left.concat(new Array<string>(halves.length === 2 ? 8 - count : 0).fill('0')).concat(right);
    const first = Number.parseInt(words[0], 16);
    // 排除 ULA、链路本地、组播、映射地址及文档用途前缀。
    return first >= 0x2000 && first <= 0x3fff &&
      !(first === 0x2001 && Number.parseInt(words[1], 16) === 0x0db8) &&
      first !== 0x3fff;
  }
}
