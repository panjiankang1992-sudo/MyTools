import { AppFonts } from '../theme/AppFonts';
import { AppTheme } from '../theme/AppTheme';
import { AppEqualActionRow, AppGroupedButton } from '../components/AppControls';
import { AuthSessionManager } from '../features/auth/AuthSessionManager';
import { DownloadToolsPage } from '../features/tools/download/DownloadToolsPage';
import { PdfToolsPage } from '../features/tools/pdf/PdfToolsPage';
import { RssHomePage } from '../features/rss/RssHomePage';

/** 仅允许临时验收包访问本机只读夹具，不加载真实登录会话。 */
class SubpageAcceptanceAuth extends AuthSessionManager {
  /** 本机夹具专用测试令牌，没有生产权限。 */
  async validAccessToken(): Promise<string> { return 'ui-subpage-acceptance-only'; }
  /** 夹具不执行刷新或访问真实身份服务。 */
  async forceRefresh(_rejected?: string): Promise<string> { return 'ui-subpage-acceptance-only'; }
  /** 固定本机端点，不接受公网或其它私网地址。 */
  normalizeBusinessBaseUrl(value: string): string {
    if (value !== 'http://127.0.0.1:23460') throw new Error('ACCEPTANCE_ENDPOINT');
    return value;
  }
  /** 活动端点固定为本机夹具。 */
  activeBusinessBaseUrl(): string { return 'http://127.0.0.1:23460'; }
  /** 公网降级同样不能离开本机夹具。 */
  publicBusinessBaseUrl(): string { return 'http://127.0.0.1:23460'; }
  /** 每次传输都再次校验固定端点。 */
  resolveBusinessBaseUrl(value: string): string { return this.normalizeBusinessBaseUrl(value); }
}

/** 独立测试入口复用生产组件，不改变正式页面清单或认证门禁。 */
@Entry
@Component
struct SubpageUiAcceptance {
  @State mode: string = 'Actions';
  @State panelWidth: string | number = 280;
  @State event: string = '';
  @State probeLoading: boolean = false;
  @State probeAvailable: boolean = true;
  @State probeLongLabel: boolean = false;
  @State probeRefreshVisible: boolean = true;
  @State uploadClicks: number = 0;
  @State refreshClicks: number = 0;
  private auth: AuthSessionManager = new SubpageAcceptanceAuth();

  /** 在首屏前加载与正式应用相同的本地字体。 */
  aboutToAppear(): void {
    AppFonts.register(this.getUIContext());
    AppStorage.setOrCreate('appForeground', true);
  }

  /** 系统返回只回到本地测试菜单，不进入真实账户页面。 */
  onBackPress(): boolean { this.mode = 'Actions'; return true; }

  @Builder
  private Menu() {
    Row({ space: 4 }) {
      ForEach(['Actions', 'Download', 'PDF', 'RSS'], (value: string) => {
        Button(value).fontFamily(AppTheme.fontMedium).fontSize(12).padding({ left: 8, right: 8 })
          .height(36).onClick(() => { this.mode = value; this.event = ''; })
      })
    }.width('100%').justifyContent(FlexAlign.Center)
    Row({ space: 4 }) {
      Button('Width 280').fontFamily(AppTheme.fontMedium).fontSize(12).height(36)
        .onClick(() => this.panelWidth = 280)
      Button('Width 320').fontFamily(AppTheme.fontMedium).fontSize(12).height(36)
        .onClick(() => this.panelWidth = 320)
      Button('Full').fontFamily(AppTheme.fontMedium).fontSize(12).height(36)
        .onClick(() => this.panelWidth = '100%')
    }.width('100%').justifyContent(FlexAlign.Center)
  }

  @Builder
  private Actions() {
    Column({ space: 16 }) {
      Text('REAL NATIVE TWO-PASS LAYOUT').fontFamily(AppTheme.fontSemibold).fontSize(16).width('100%')
      Text('Acceptance only: fixed local data; no production account.').fontFamily(AppTheme.fontFamily)
        .fontSize(12).width('100%')
      Row({ space: 4 }) {
        Button('Loading').fontFamily(AppTheme.fontMedium).fontSize(12).height(36).layoutWeight(1).padding({ left: 2, right: 2 })
          .id('probe-toggle-loading').onClick(() => this.probeLoading = !this.probeLoading)
        Button('Available').fontFamily(AppTheme.fontMedium).fontSize(12).height(36).layoutWeight(1).padding({ left: 2, right: 2 })
          .id('probe-toggle-available').onClick(() => this.probeAvailable = !this.probeAvailable)
        Button('Label').fontFamily(AppTheme.fontMedium).fontSize(12).height(36).layoutWeight(1).padding({ left: 2, right: 2 })
          .id('probe-toggle-label').onClick(() => this.probeLongLabel = !this.probeLongLabel)
        Button('Visibility').fontFamily(AppTheme.fontMedium).fontSize(12).height(36).layoutWeight(1)
          .padding({ left: 2, right: 2 }).id('probe-toggle-visibility')
          .onClick(() => this.probeRefreshVisible = !this.probeRefreshVisible)
      }.width('100%').justifyContent(FlexAlign.Center)
      AppEqualActionRow() {
        AppGroupedButton({ label: this.probeLongLabel ? $r('app.string.ui_probe_long_action') :
          $r('app.string.tool_ui_upload'), variant: 'primary', loading: this.probeLoading,
          available: this.probeAvailable, action: () => {
            this.uploadClicks++; this.event = 'UPLOAD_CLICK';
          } })
        AppGroupedButton({ label: $r('app.string.ui_probe_long_action'), variant: 'secondary',
          action: () => this.event = 'LONG_CLICK' })
        if (this.probeRefreshVisible) {
          AppGroupedButton({ label: $r('app.string.tool_ui_refresh'), variant: 'secondary',
            action: () => { this.refreshClicks++; this.event = 'REFRESH_CLICK'; } })
        }
      }
      Text(`loading=${this.probeLoading}; available=${this.probeAvailable}; long=${this.probeLongLabel}; visible=${this.probeRefreshVisible}`)
        .fontFamily(AppTheme.fontFamily).fontSize(12).width('100%').id('probe-state')
      Text(`upload=${this.uploadClicks}; refresh=${this.refreshClicks}; event=${this.event}`)
        .fontFamily(AppTheme.fontFamily).fontSize(12).width('100%').id('probe-events')
    }.width('100%').padding(8).backgroundColor(AppTheme.surface)
  }

  /** 顶部菜单为验收控制；下面的页面均为未改写的正式组件。 */
  build() {
    Column({ space: 6 }) {
      Text('UI SUBPAGE ACCEPTANCE').fontFamily(AppTheme.fontMedium).fontSize(12)
      this.Menu()
      Column() {
        if (this.mode === 'Actions') {
          this.Actions()
        } else if (this.mode === 'Download') {
          DownloadToolsPage({ baseUrl: 'http://127.0.0.1:23460', accountKey: 'ui-subpage-acceptance-only',
            authManager: this.auth, onBack: () => this.mode = 'Actions' })
        } else if (this.mode === 'PDF') {
          PdfToolsPage({ baseUrl: 'http://127.0.0.1:23460', authManager: this.auth,
            accountKey: 'ui-subpage-acceptance-only', onBack: () => this.mode = 'Actions' })
        } else {
          RssHomePage({ baseUrl: 'http://127.0.0.1:23460', accountKey: 'ui-subpage-acceptance-only',
            authManager: this.auth, screen: 'manage', onBack: () => this.mode = 'Actions' })
        }
      }.width(this.panelWidth).layoutWeight(1).backgroundColor(AppTheme.background)
    }.width('100%').height('100%').padding({ top: 48, bottom: 24 }).backgroundColor(AppTheme.background)
  }
}
