import { TrustedLanEndpointPolicy } from '../connectivity/TrustedLanEndpointPolicy';
import { Ipv6EndpointPolicy } from '../connectivity/Ipv6EndpointPolicy';

/** DSH 网页连接只接受固定公网入口或当前已验证的局域网入口。 */
export class DshWebAuthPolicy {
  static readonly PUBLIC_ORIGIN: string = 'https://dsh-app.yuyutian.top';

  /** 拒绝旧启动口令、任意跳转、非 HTTPS 或带额外参数的地址。 */
  static connectionUrl(value: string): string {
    return /^https:\/\/dsh-app\.yuyutian\.top\/_mytools\/connect\?ticket=[A-Za-z0-9_-]{43}$/.test(value) ? value : '';
  }

  /** 根据已验证的业务线路和 HTTPS 偏好确定本次网页连接入口。 */
  static webOrigin(verifiedBase: string, publicBusinessBase: string, preferSecure: boolean,
    forcePublic: boolean): string {
    const ipv6Direct = verifiedBase.startsWith('https://') && verifiedBase !== publicBusinessBase;
    if (forcePublic || (!ipv6Direct && (preferSecure || !verifiedBase.startsWith('http://')))) {
      return DshWebAuthPolicy.PUBLIC_ORIGIN;
    }
    return DshWebAuthPolicy.directOrigin(verifiedBase, ipv6Direct);
  }

  /** 仅将已验证的公网一次性票据映射到本会话验证过的 Gateway。 */
  static routedConnectionUrl(value: string, verifiedBaseUrl: string, ipv6Direct: boolean = false): string {
    if (DshWebAuthPolicy.connectionUrl(value).length === 0) return '';
    if (!verifiedBaseUrl.startsWith('http://') && !ipv6Direct) return value;
    const origin = DshWebAuthPolicy.directOrigin(verifiedBaseUrl, ipv6Direct);
    return origin.length === 0 ? '' : origin + value.substring(DshWebAuthPolicy.PUBLIC_ORIGIN.length);
  }

  private static directOrigin(verifiedBaseUrl: string, ipv6Direct: boolean): string {
    try {
      return ipv6Direct ? new Ipv6EndpointPolicy().normalizeOrigin(verifiedBaseUrl) :
        new TrustedLanEndpointPolicy().normalize(verifiedBaseUrl);
    } catch (_) {
      return '';
    }
  }

  /** 外部窗口只允许普通 HTTPS 站点，拒绝凭据、控制字符和原生协议。 */
  static externalUrl(value: string): string {
    if (value.length > 8192 || /[\s\\\u0000-\u001f\u007f]/.test(value)) return '';
    return /^https:\/\/[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+(?:\/[^\s]*)?$/.test(value) ? value : '';
  }

  /** 只从已验证的 DSH 主页面接收新窗口，票据换取阶段不开放此能力。 */
  static trustedPage(value: string, verifiedBaseUrl: string = ''): boolean {
    if (/^https:\/\/dsh-app\.yuyutian\.top\/(?!_mytools\/)[^\s]*$/.test(value)) return true;
    try {
      const baseUrl = verifiedBaseUrl.startsWith('https://') ? new Ipv6EndpointPolicy().normalizeOrigin(verifiedBaseUrl) :
        new TrustedLanEndpointPolicy().normalize(verifiedBaseUrl);
      if (!value.startsWith(`${baseUrl}/`) || /[\s\\\u0000-\u001f\u007f]/.test(value)) return false;
      const path = value.substring(baseUrl.length);
      return !/^\/(?:_mytools|internal|api)(?:\/|\?|$)/.test(path);
    } catch (_) {
      return false;
    }
  }
}
