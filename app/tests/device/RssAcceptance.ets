import { RssHomePage } from '../features/rss/RssHomePage';
import { AuthSessionManager } from '../features/auth/AuthSessionManager';

/** 只存在于临时验收包，使用本机测试身份，不触及生产会话。 */
class RssAcceptanceAuth extends AuthSessionManager {
  /** 本地 Gateway 的固定测试身份。 */
  async validAccessToken(): Promise<string> { return 'rss-device-acceptance-only'; }
  /** 测试包仅通过 HDC 回环转发访问本机。 */
  normalizeBusinessBaseUrl(value: string): string {
    if (value !== 'http://127.0.0.1:23460') throw new Error('ACCEPTANCE_ENDPOINT');
    return value;
  }
}

/** 复用生产组件，测试入口绝不加入生产页面清单。 */
@Entry
@Component
struct RssAcceptance {
  private auth: AuthSessionManager = new RssAcceptanceAuth();
  private back: () => void = () => {};
  /** 转发系统返回。 */
  onBackPress(): boolean { this.back(); return true; }
  build() {
    Column() {
      RssHomePage({ baseUrl: 'http://127.0.0.1:23460', accountKey: 'rss-device-acceptance-only', authManager: this.auth,
        onBackReady: (handler: () => void): void => { this.back = handler; } })
    }.width('100%').height('100%')
  }
}
