import { DshWebAuthPolicy } from './DshWebAuthPolicy';

/** 网页能力只接受当前可信页面的同源请求，不向外部预览页面共享授权。 */
export class DshWebCapabilityPolicy {
  /** 主窗口保留同源页面、下载和严格票据入口，外链由 App 单独接管。 */
  static internalNavigation(value: string, page: string, base: string): boolean {
    if (value === 'about:blank' || DshWebAuthPolicy.trustedPage(value, base) ||
      DshWebAuthPolicy.connectionUrl(value).length > 0) return true;
    if ((base.startsWith('http://') || base.startsWith('https://')) && value.startsWith(`${base}/_mytools/connect?ticket=`)) {
      const publicValue = 'https://dsh-app.yuyutian.top' + value.substring(base.length);
      return DshWebAuthPolicy.routedConnectionUrl(publicValue, base, base.startsWith('https://')) === value;
    }
    return DshWebCapabilityPolicy.download(value, page, base);
  }

  /** 提取经过语法约束的 HTTP 来源，不接受用户名、反斜杠或控制字符。 */
  static origin(value: string): string {
    if (/[\s\\\u0000-\u001f\u007f]/.test(value)) return '';
    const match = /^(https?:\/\/[^/?#@]+)(?:[/?#]|$)/.exec(value);
    return match ? match[1] : '';
  }

  /** 录音只交给当前 HTTPS 主页面；实际授权仍须经过系统权限提示。 */
  static microphone(origin: string, page: string, base: string, resources: string[]): boolean {
    return resources.length === 1 && resources[0] === 'TYPE_AUDIO_CAPTURE' &&
      DshWebAuthPolicy.trustedPage(page, base) && origin === DshWebCapabilityPolicy.origin(page) &&
      origin.startsWith('https://');
  }

  /** 下载保留 ArkWeb 会话与 Blob 支持，拒绝外部来源和认证换票接口。 */
  static download(value: string, page: string, base: string): boolean {
    if (!DshWebAuthPolicy.trustedPage(page, base)) return false;
    const url = value.startsWith('blob:') ? value.substring(5) : value;
    const origin = DshWebCapabilityPolicy.origin(page);
    if (!origin || DshWebCapabilityPolicy.origin(url) !== origin) return false;
    const path = url.substring(origin.length);
    return path.startsWith('/') && !/^\/(?:_mytools|internal)(?:\/|\?|#|$)/.test(path);
  }

  /** 文件名只作为系统保存提示，不参与缓存路径拼接。 */
  static filename(value: string): string {
    const name = value.replace(/[\\/\u0000-\u001f\u007f<>:"|?*]/g, '_').replace(/^\.+/, '').trim();
    return name.length > 0 ? name.substring(0, 160) : 'dsh-download.bin';
  }
}
