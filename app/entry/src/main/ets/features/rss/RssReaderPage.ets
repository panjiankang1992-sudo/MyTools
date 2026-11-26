import { webview } from '@kit.ArkWeb';
import { common } from '@kit.AbilityKit';
import util from '@ohos.util';
import { AppTheme } from '../../theme/AppTheme';
import { AppButton, AppGroupedButton, AppEqualActionRow, AppTextAction } from '../../components/AppControls';
import { AuthSessionManager } from '../auth/AuthSessionManager';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import { RssApi } from './RssApi';
import { RssEntry, RssStateChange, emptyRssEntry } from './RssModels';
import { RssHtmlPolicy } from './RssHtmlPolicy';

/** 只读正文与显式状态写入分离，阅读视图不持有业务 Cookie。 */
@Component
export struct RssReaderPage {
  entryId: string = '';
  baseUrl: string = '';
  authManager: AuthSessionManager = new AuthSessionManager();
  onBack: () => void = () => {};
  onChanged: (row: RssEntry) => void = () => {};
  @StorageLink('appForeground') @Watch('foregroundChanged') appForeground: boolean = true;
  @State row: RssEntry = emptyRssEntry();
  @State loading: boolean = true;
  @State busy: boolean = false;
  @State notice: ResourceStr = '';
  @State fontSize: number = 18;
  private controller: webview.WebviewController = new webview.WebviewController();
  private cancel: DownloadCancellationToken = new DownloadCancellationToken();
  private active: boolean = false;
  private attached: boolean = false;
  @State rendered: boolean = false;
  private documentBase64: string = '';
  private ownedNavigation: boolean = false;
  private documentPainted: boolean = false;

  /** 每次进入详情重新取得最新状态版本。 */
  aboutToAppear(): void { this.active = true; this.load(); }
  /** 离页取消请求，迟到结果不得写回界面。 */
  aboutToDisappear(): void { this.active = false; this.cancel.cancel(); }
  private api(): RssApi { return new RssApi(this.baseUrl, this.authManager); }
  private foregroundChanged(): void { if (this.appForeground && this.documentPainted) this.onRendered(); }
  private async load(): Promise<void> {
    this.loading = true; this.notice = '';
    try {
      const row = await this.api().entry(this.entryId, this.cancel);
      if (!this.active) return;
      this.row = row; this.loading = false;
    } catch (_) { if (this.active) { this.notice = $r('app.string.rss_load_failed'); this.loading = false; } }
  }
  private showDocument(): void {
    if (!this.attached || !this.active || !this.row.id) return;
    try {
      const html = RssHtmlPolicy.document(this.row.contentHtml, this.row.summary, this.fontSize);
      this.documentBase64 = new util.Base64Helper().encodeToStringSync(new util.TextEncoder().encodeInto(html));
      this.controller.loadData(html,
        'text/html', 'UTF-8', 'https://rss-reader.invalid/', 'https://rss-reader.invalid/');
    } catch (_) { this.notice = $r('app.string.rss_load_failed'); }
  }
  /** 只在正文成功呈现后尝试一次已读，不覆盖用户后续标记未读。 */
  private onRendered(): void {
    if (this.rendered || !this.row.id || !this.active || !this.appForeground || !this.documentPainted) return;
    this.rendered = true;
    if (!this.row.read) this.change(true, this.row.starred);
  }
  private async change(read: boolean, starred: boolean): Promise<void> {
    if (this.busy || !this.row.id || !this.rendered) return;
    this.busy = true; this.notice = '';
    const body: RssStateChange = { expectedRevision: this.row.revision, read: read, starred: starred };
    try {
      const updated = await this.api().state(this.row.id, body, this.cancel);
      if (this.active) { this.row = updated; this.onChanged(updated); }
    } catch (error) {
      if (!this.active) return;
      this.notice = (error as Error).message === 'RSS_009' ? $r('app.string.rss_conflict') : $r('app.string.rss_write_failed');
      // 冲突只刷新状态，不重放用户操作。
      try { const latest = await this.api().entry(this.row.id, this.cancel); if (this.active) { this.row = latest; this.onChanged(latest); } } catch (_) {}
    } finally { if (this.active) this.busy = false; }
  }
  private async external(url: string): Promise<void> {
    if (!this.active || !RssHtmlPolicy.external(url)) return;
    try { await (this.getUIContext().getHostContext() as common.UIAbilityContext).startAbility({ action: 'ohos.want.action.viewData', uri: url }); }
    catch (_) { if (this.active) this.notice = $r('app.string.rss_open_failed'); }
  }
  /** 构建隔离正文与固定字体的原生阅读操作。 */
  build() {
    Column() {
      Row() {
        Button() { SymbolGlyph($r('sys.symbol.chevron_left')).fontSize(23).fontColor([AppTheme.textPrimary]) }
          .width(44).height(44).backgroundColor(Color.Transparent).accessibilityText($r('app.string.rss_back')).onClick(this.onBack)
        Text(this.row.sourceTitle || this.row.title).lineHeight(24).fontFamily(AppTheme.fontFamily).layoutWeight(1).fontSize(15).fontColor(AppTheme.textSecondary).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        AppTextAction({ label: 'Aa',
          action: () => { this.fontSize = this.fontSize >= 24 ? 16 : this.fontSize + 2; this.showDocument(); } })
          .accessibilityText($r('app.string.rss_font'))
      }.width('100%').padding({ left: 8, right: 12, top: 8 })
      if (this.notice) { Text(this.notice).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.danger).padding(12).width('100%') }
      if (this.loading) { LoadingProgress().width(36).height(36).color(AppTheme.primary).margin(40) }
      else if (!this.row.id) { AppButton({ label: $r('app.string.rss_retry'), variant: 'secondary', action: () => this.load() }) }
      else {
        Column({ space: 10 }) {
          Text(this.row.title).fontFamily(AppTheme.fontSemibold).fontSize(26).lineHeight(34).fontWeight(600).fontColor(AppTheme.textPrimary).width('100%').maxLines(4).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(`${this.row.author ? this.row.author + ' · ' : ''}${new Date(this.row.publishedAt || this.row.discoveredAt).toLocaleDateString()}`).lineHeight(18).fontFamily(AppTheme.fontFamily)
            .fontSize(12).fontColor(AppTheme.textSecondary).width('100%')
          if (!this.row.publishedAt) { Text($r('app.string.rss_collected_time')).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textTertiary).width('100%') }
        }.width('100%').padding({ left: 20, right: 20, top: 16, bottom: 12 }).alignItems(HorizontalAlign.Start)
        Web({ src: '', controller: this.controller, incognitoMode: true }).layoutWeight(1).width('100%')
          .javaScriptAccess(false).domStorageAccess(false).fileAccess(false).databaseAccess(false)
          .onlineImageAccess(true).imageAccess(true)
          .onControllerAttached(() => { this.attached = true; this.showDocument(); })
          .onFirstContentfulPaint(() => { if (this.ownedNavigation) { this.documentPainted = true; this.onRendered(); } })
          .onPageEnd((event) => {
            // 部分缓存/短文没有绘制回调，以明确归属的文档完成事件补齐状态。
            if (event && (event.url === 'https://rss-reader.invalid/' || RssHtmlPolicy.ownedDataUrl(event.url, this.documentBase64))) {
              this.documentPainted = true; this.onRendered();
            }
          })
          .onLoadIntercept((event) => {
            if (!event) return true;
            const url = event.data.getRequestUrl();
            if (RssHtmlPolicy.ownedDataUrl(url, this.documentBase64)) { this.ownedNavigation = true; return false; }
            if (url === 'https://rss-reader.invalid/') { this.ownedNavigation = true; return false; }
            if (url === 'about:blank') return false;
            if (event.data.isMainFrame() && event.data.isRequestGesture()) this.external(url);
            return true;
          })
        Column() {
          AppEqualActionRow() {
            AppGroupedButton({ variant: 'text', label: this.row.starred ? $r('app.string.rss_starred') : $r('app.string.rss_star'),
              available: !this.busy && this.rendered, action: () => this.change(this.row.read, !this.row.starred) })
            AppGroupedButton({ variant: 'text', label: this.row.read ? $r('app.string.rss_mark_unread') : $r('app.string.rss_mark_read'),
              available: !this.busy && this.rendered, action: () => this.change(!this.row.read, this.row.starred) })
            if (this.row.url) { AppGroupedButton({ variant: 'text', label: $r('app.string.rss_original'), action: () => this.external(this.row.url) }) }
          }
        }.width('100%').padding({ left: 12, right: 12, top: 9, bottom: 12 }).backgroundColor(AppTheme.surface)
      }
    }.width('100%').height('100%').backgroundColor(AppTheme.background)
  }
}
