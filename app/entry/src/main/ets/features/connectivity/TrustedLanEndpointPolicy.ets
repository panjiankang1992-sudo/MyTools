/**
 * 约束通过服务发现使用的局域网IPv4地址。
 */
export class TrustedLanEndpointPolicy {
  /** 判断地址是否属于RFC1918私网范围。 */
  isPrivateIpv4(value: string): boolean {
    const parts = value.split('.');
    if (parts.length !== 4 || !parts.every((part: string) => /^\d{1,3}$/.test(part) &&
      part === `${Number(part)}` && Number(part) <= 255)) {
      return false;
    }
    const first = Number(parts[0]);
    const second = Number(parts[1]);
    return first === 10 || (first === 172 && second >= 16 && second <= 31) ||
      (first === 192 && second === 168);
  }

  /** 规范化固定端口的局域网HTTP地址。 */
  normalize(value: string): string {
    const matched = /^http:\/\/(\d{1,3}(?:\.\d{1,3}){3}):23110\/?$/.exec(value.trim());
    if (matched === null || !this.isPrivateIpv4(matched[1])) throw new Error('局域网服务地址无效');
    return `http://${matched[1]}:23110`;
  }
}
