import { common } from '@kit.AbilityKit';
import picker from '@ohos.file.picker';
import fs from '@ohos.file.fs';
import util from '@ohos.util';
import { AppTheme } from '../../theme/AppTheme';
import { AppButton, AppGroupedButton, AppTextAction, AppSearchField, AppGlassTextInput, AppSelect, AppSegmentItem } from '../../components/AppControls';
import { AuthSessionManager } from '../auth/AuthSessionManager';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import { RssApi } from './RssApi';
import { RssReaderPage } from './RssReaderPage';
import { ToolConfirmDialog } from '../tools/ToolUi';
import { RssGroup, RssSubscription, RssEntry, RssFilter, RssCounts, RssDiscovery, RssPreviewItem,
  RssCandidate, RssImportItem, RssCreateSubscription, RssUpdateSubscription } from './RssModels';

/** RSS 独立阅读工作区，所有异步结果绑定账户、服务地址和页面生命周期。 */
@Component
export struct RssHomePage {
  @StorageProp('systemFontScale') systemFontScale: number = 1;
  @Prop @Watch('reset') baseUrl: string = '';
  @Prop @Watch('reset') accountKey: string = '';
  authManager: AuthSessionManager = new AuthSessionManager();
  onBack: () => void = () => {};
  onBackReady: (handler: () => void) => void = () => {};
  @StorageLink('appForeground') @Watch('foregroundChanged') appForeground: boolean = true;
  @State screen: string = 'home';
  @State sources: RssSubscription[] = [];
  @State groups: RssGroup[] = [];
  @State entries: RssEntry[] = [];
  @State failedImageUrls: string[] = [];
  @State counts: RssCounts = { subscriptions: 0, unread: 0, starred: 0 };
  @State view: string = 'unread';
  @State subscriptionId: string = '';
  @State filterGroup: string = '';
  @State search: string = '';
  @State searchVisible: boolean = false;
  @State filtersVisible: boolean = false;
  @State loading: boolean = false;
  @State busy: boolean = false;
  @State notice: ResourceStr = '';
  @State noticeColor: string = AppTheme.textSecondary;
  @State cursor: string = '';
  @State readerId: string = '';
  @State polling: boolean = true;
  @State ready: boolean = false;
  @State url: string = '';
  @State title: string = '';
  @State groupId: string = '';
  @State initialReadAll: boolean = false;
  @State discovery: RssDiscovery = { candidates: [] };
  @State editId: string = '';
  @State interval: number = 60;
  @State paused: boolean = false;
  @State groupName: string = '';
  @State opmlItems: RssImportItem[] = [];
  @State opmlImported: boolean = false;
  private opmlXml: string = '';
  private editRevision: number = 0;
  private epoch: number = 0;
  private active: boolean = false;
  private cancel: DownloadCancellationToken = new DownloadCancellationToken();
  private listScroller: Scroller = new Scroller();
  private listOffset: number = 0;
  private confirmationController?: CustomDialogController;

  /** 挂载后注册系统返回与加载真实接口。 */
  aboutToAppear(): void { this.active = true; this.onBackReady(() => this.back()); this.reload(); }
  /** 离开模块后立即使全部旧结果失效。 */
  aboutToDisappear(): void { this.active = false; this.invalidate(); }
  private invalidate(): void {
    // 页面、账户或加载周期变化时关闭旧确认层，避免执行失效操作。
    this.confirmationController?.close(); this.confirmationController = undefined;
    this.epoch++; this.cancel.cancel(); this.cancel = new DownloadCancellationToken(); this.busy = false; this.loading = false;
  }
  private current(epoch: number): boolean { return this.active && this.epoch === epoch && !this.cancel.isCancelled(); }
  private reset(): void {
    this.listOffset = 0;
    this.invalidate(); this.screen = 'home'; this.readerId = ''; this.entries = []; this.sources = []; this.groups = []; this.failedImageUrls = [];
    this.counts = { subscriptions: 0, unread: 0, starred: 0 }; this.ready = false; this.url = ''; this.title = '';
    this.subscriptionId = ''; this.filterGroup = ''; this.search = ''; this.cursor = ''; this.notice = '';
    this.opmlXml = ''; this.opmlItems = []; this.discovery = { candidates: [] }; this.editId = ''; this.groupId = '';
    if (this.active && this.accountKey) this.reload();
  }
  private foregroundChanged(): void { if (this.appForeground && this.screen === 'home' && !this.busy && !this.loading) this.reload(); }
  private api(): RssApi { return new RssApi(this.baseUrl, this.authManager); }
  private label(value: Resource): string {
    try { return this.getUIContext().getHostContext()?.resourceManager.getStringSync(value.id) ?? ''; } catch (_) { return ''; }
  }
  private filter(): RssFilter { return { subscriptionId: this.subscriptionId, groupId: this.filterGroup, view: this.view, q: this.search.trim() }; }
  private fail(error: Error): void {
    this.noticeColor = AppTheme.danger;
    switch (error.message) {
      case 'RSS_001': this.notice = $r('app.string.rss_invalid'); break;
      case 'RSS_002': this.notice = $r('app.string.rss_public_https'); break;
      case 'RSS_004': this.notice = $r('app.string.rss_invalid_feed'); break;
      case 'RSS_005': this.notice = $r('app.string.rss_missing'); break;
      case 'RSS_006': this.notice = $r('app.string.rss_limit'); break;
      case 'RSS_007': this.notice = $r('app.string.rss_rate'); break;
      case 'RSS_008': this.notice = $r('app.string.rss_invalid'); break;
      case 'RSS_012': this.notice = $r('app.string.rss_quota'); break;
      case 'RSS_009': this.notice = $r('app.string.rss_conflict'); break;
      default: this.notice = $r('app.string.rss_unavailable');
    }
  }
  private back(): void {
    const returningFromReader = this.screen === 'reader';
    this.invalidate(); this.notice = ''; this.readerId = ''; this.opmlXml = '';
    if (this.screen === 'home') this.onBack();
    else {
      this.screen = 'home';
      if (returningFromReader) {
        this.entries = this.entries.filter((entry: RssEntry) => (this.view !== 'unread' || !entry.read) && (this.view !== 'starred' || entry.starred));
        // 保留已加载页和原游标，不能返回时只剩首屏或将新文章插入滚动位置。
        const epoch = this.epoch;
        setTimeout(() => { if (this.current(epoch) && this.screen === 'home') this.listScroller.scrollTo({ xOffset: 0, yOffset: this.listOffset, animation: false }); }, 0);
        this.refreshCounts();
      } else this.reload();
    }
  }
  private async refreshCounts(): Promise<void> {
    const epoch = this.epoch;
    try {
      const counts = await this.api().counts(this.cancel);
      const sources = await this.api().subscriptions(this.cancel);
      if (this.current(epoch)) { this.counts = counts; this.sources = sources; }
    }
    catch (error) { if (this.current(epoch)) this.fail(error as Error); }
  }
  private entryChanged(updated: RssEntry): void {
    this.entries = this.entries.map((entry: RssEntry) => {
      if (entry.id === updated.id) { entry.read = updated.read; entry.starred = updated.starred; entry.revision = updated.revision; }
      return entry;
    });
  }
  private async reload(): Promise<void> {
    if (!this.active || !this.accountKey || this.screen === 'reader' || this.busy) return;
    this.invalidate(); const epoch = this.epoch; const cancel = this.cancel; this.loading = true; this.notice = '';
    try {
      const api = this.api();
      const capability = await api.capabilities(cancel);
      const sources = await api.subscriptions(cancel);
      const groups = await api.groups(cancel);
      const counts = await api.counts(cancel);
      const page = await api.entries(this.filter(), '', cancel);
      if (!this.current(epoch)) return;
      this.polling = capability.pollingEnabled; this.ready = true; this.sources = sources; this.groups = groups;
      this.counts = counts; this.entries = page.items; this.cursor = page.nextCursor; this.failedImageUrls = [];
    } catch (error) { if (this.current(epoch)) this.fail(error as Error); }
    finally { if (this.current(epoch)) this.loading = false; }
  }
  private async more(): Promise<void> {
    if (this.loading || this.busy || !this.cursor) return;
    const epoch = this.epoch; this.loading = true;
    try {
      const page = await this.api().entries(this.filter(), this.cursor, this.cancel);
      if (this.current(epoch)) { this.entries = this.entries.concat(page.items.filter((item: RssEntry) => !this.entries.some((existing: RssEntry) => existing.id === item.id))); this.cursor = page.nextCursor; }
    } catch (error) { if (this.current(epoch)) this.fail(error as Error); }
    finally { if (this.current(epoch)) this.loading = false; }
  }
  private openReader(row: RssEntry): void {
    this.listOffset = this.listScroller.currentOffset().yOffset;
    this.invalidate(); this.readerId = row.id; this.screen = 'reader'; this.notice = '';
  }
  private openAdd(): void {
    this.invalidate(); this.screen = 'add'; this.url = ''; this.title = ''; this.groupId = ''; this.initialReadAll = false;
    this.discovery = { candidates: [] }; this.notice = '';
  }
  private async detect(candidate: string = ''): Promise<void> {
    if (this.busy) return;
    const epoch = this.epoch; this.busy = true; this.notice = ''; this.discovery = { candidates: [] };
    if (candidate) this.url = candidate;
    try {
      const result = await this.api().discover(this.url.trim(), this.cancel);
      if (this.current(epoch)) { this.discovery = result; this.title = result.preview?.title.slice(0, 256) ?? ''; }
    } catch (error) { if (this.current(epoch)) this.fail(error as Error); }
    finally { if (this.current(epoch)) this.busy = false; }
  }
  private async add(): Promise<void> {
    if (this.busy || !this.discovery.preview) return;
    const epoch = this.epoch; this.busy = true; this.notice = '';
    const body: RssCreateSubscription = { url: this.discovery.preview.url, title: this.title.trim(), groupId: this.groupId, initialReadAll: this.initialReadAll };
    try {
      await this.api().add(body, this.cancel);
      if (this.current(epoch)) { this.busy = false; this.screen = 'home'; this.reload(); }
    } catch (error) { if (this.current(epoch)) this.fail(error as Error); }
    finally { if (this.current(epoch)) this.busy = false; }
  }
  private edit(source: RssSubscription): void {
    this.invalidate(); this.screen = 'edit'; this.editId = source.id; this.editRevision = source.revision;
    this.title = source.customTitle || source.title; this.groupId = source.groupId; this.interval = source.intervalMinutes;
    this.paused = source.status === 'PAUSED'; this.url = source.feedUrl; this.notice = '';
  }
  private async save(): Promise<void> {
    if (this.busy) return;
    const epoch = this.epoch; this.busy = true; this.notice = '';
    const body: RssUpdateSubscription = { expectedRevision: this.editRevision, title: this.title.trim(), groupId: this.groupId,
      status: this.paused ? 'PAUSED' : 'ACTIVE', intervalMinutes: this.interval };
    try {
      await this.api().update(this.editId, body, this.cancel);
      if (this.current(epoch)) { this.busy = false; this.screen = 'manage'; this.reload(); }
    } catch (error) { if (this.current(epoch)) this.fail(error as Error); }
    finally { if (this.current(epoch)) this.busy = false; }
  }
  private async refreshSource(source: RssSubscription): Promise<void> {
    if (this.busy) return;
    const epoch = this.epoch; this.busy = true;
    try {
      const updated = await this.api().refresh(source.id, this.cancel);
      if (this.current(epoch)) { this.sources = this.sources.map((row: RssSubscription) => row.id === updated.id ? updated : row); this.noticeColor = AppTheme.primary; this.notice = $r('app.string.rss_refresh_queued'); }
    } catch (error) { if (this.current(epoch)) this.fail(error as Error); }
    finally { if (this.current(epoch)) this.busy = false; }
  }
  private confirm(action: string, id: string = ''): void {
    if (this.busy) return;
    const epoch = this.epoch;
    // 同时只保留一层确认，回调捕获原操作和生命周期版本。
    this.confirmationController?.close();
    this.confirmationController = new CustomDialogController({
      builder: ToolConfirmDialog({
        title: action === 'mark' ? $r('app.string.rss_mark_all') : $r('app.string.rss_remove'),
        message: action === 'mark' ? $r('app.string.rss_mark_confirm') : action === 'group' ? $r('app.string.rss_group_remove_confirm') : $r('app.string.rss_remove_confirm'),
        cancelLabel: $r('app.string.rss_cancel'), confirmLabel: $r('app.string.rss_confirm'), dangerous: action !== 'mark',
        confirmed: () => this.executeConfirmedAction(action, id, epoch)
      }),
      autoCancel: true, alignment: DialogAlignment.Center, customStyle: true
    });
    this.confirmationController.open();
  }
  private async executeConfirmedAction(action: string, id: string, epoch: number): Promise<void> {
    // 确认后再次校验，拒绝已离开的页面和正在进行的写入。
    if (!this.current(epoch) || this.busy) return;
    this.busy = true; this.notice = '';
    try {
      if (action === 'mark') await this.api().markRead(this.filter(), this.cancel);
      else if (action === 'group') await this.api().removeGroup(id, this.cancel);
      else await this.api().remove(id, this.cancel);
      if (this.current(epoch)) {
        if (this.subscriptionId === id) this.subscriptionId = '';
        if (this.filterGroup === id) this.filterGroup = '';
        this.busy = false; this.reload();
      }
    } catch (error) { if (this.current(epoch)) this.fail(error as Error); }
    finally { if (this.current(epoch)) this.busy = false; }
  }
  private async addGroup(): Promise<void> {
    if (this.busy || !this.groupName.trim()) return;
    const epoch = this.epoch; this.busy = true;
    try {
      await this.api().addGroup(this.groupName.trim(), this.cancel);
      const groups = await this.api().groups(this.cancel);
      if (this.current(epoch)) { this.groups = groups; this.groupName = ''; }
    } catch (error) { if (this.current(epoch)) this.fail(error as Error); }
    finally { if (this.current(epoch)) this.busy = false; }
  }
  private async pickOpml(): Promise<void> {
    if (this.busy) return;
    const epoch = this.epoch; this.busy = true; this.notice = '';
    try {
      const options = new picker.DocumentSelectOptions(); options.maxSelectNumber = 1; options.fileSuffixFilters = ['OPML|opml,xml'];
      const uris = await new picker.DocumentViewPicker(this.getUIContext().getHostContext() as common.UIAbilityContext).select(options);
      if (!this.current(epoch) || !uris.length) return;
      const file = fs.openSync(uris[0], fs.OpenMode.READ_ONLY);
      let xml = '';
      try {
        const size = fs.statSync(file.fd).size;
        if (size < 1 || size > 262144) throw new Error('RSS_006');
        const bytes = new ArrayBuffer(size);
        if (fs.readSync(file.fd, bytes) !== size) throw new Error('RSS_001');
        xml = new util.TextDecoder('utf-8', { fatal: true }).decodeToString(new Uint8Array(bytes));
      } finally { fs.closeSync(file); }
      const rows = await this.api().previewOpml(xml, this.cancel);
      if (this.current(epoch)) { this.opmlXml = xml; this.opmlItems = rows; this.opmlImported = false; this.screen = 'opml'; }
    } catch (error) { if (this.current(epoch)) this.fail(error as Error); }
    finally { if (this.current(epoch)) this.busy = false; }
  }
  private async importOpml(): Promise<void> {
    if (this.busy || !this.opmlXml || this.opmlImported) return;
    const epoch = this.epoch; this.busy = true;
    try {
      const rows = await this.api().importOpml(this.opmlXml, this.cancel);
      if (this.current(epoch)) { this.opmlItems = rows; this.opmlImported = true; this.opmlXml = ''; this.noticeColor = AppTheme.primary; this.notice = $r('app.string.rss_import_finished'); }
    } catch (error) { if (this.current(epoch)) this.fail(error as Error); }
    finally { if (this.current(epoch)) this.busy = false; }
  }
  private async exportOpml(): Promise<void> {
    if (this.busy) return;
    const epoch = this.epoch; this.busy = true;
    try {
      const xml = await this.api().exportOpml(this.cancel);
      if (!this.current(epoch)) return;
      const options = new picker.DocumentSaveOptions(); options.newFileNames = ['MyTools-subscriptions.opml'];
      const uris = await new picker.DocumentViewPicker(this.getUIContext().getHostContext() as common.UIAbilityContext).save(options);
      if (!this.current(epoch) || !uris.length) return;
      const file = fs.openSync(uris[0], fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      try { fs.writeSync(file.fd, xml, { encoding: 'utf-8' }); } finally { fs.closeSync(file); }
      this.noticeColor = AppTheme.primary; this.notice = $r('app.string.rss_exported');
    } catch (error) { if (this.current(epoch)) this.fail(error as Error); }
    finally { if (this.current(epoch)) this.busy = false; }
  }
  private filterTitle(): string {
    if (this.subscriptionId) {
      const source = this.sources.find((row: RssSubscription) => row.id === this.subscriptionId);
      if (source) return source.customTitle || source.title;
    }
    if (this.filterGroup) return this.groups.find((row: RssGroup) => row.id === this.filterGroup)?.name ?? this.label($r('app.string.rss_all_sources'));
    return this.label($r('app.string.rss_all_sources'));
  }
  private sourceStatus(source: RssSubscription): Resource {
    if (source.status === 'PAUSED') return $r('app.string.rss_paused');
    if (source.status === 'GONE') return $r('app.string.rss_gone');
    if (source.syncing) return $r('app.string.rss_syncing');
    if (source.lastErrorCode) return $r('app.string.rss_sync_failed');
    return source.initialized ? $r('app.string.rss_active') : $r('app.string.rss_waiting');
  }
  private importStatus(item: RssImportItem): Resource {
    if (item.status === 'NEW') return $r('app.string.rss_import_ready');
    if (item.status === 'IMPORTED') return $r('app.string.rss_imported');
    if (item.status === 'EXISTS' || item.status === 'DUPLICATE') return $r('app.string.rss_import_exists');
    return $r('app.string.rss_import_failed');
  }
  private groupControlHeight(): number {
    return Math.max(AppTheme.inputHeight, Math.ceil(24 * Math.max(1, this.systemFontScale) + 24));
  }
  @Builder private Action(label: ResourceStr, action: () => void, primary: boolean = false) {
    AppButton({ label: label, variant: primary ? 'primary' : 'secondary',
      available: !this.busy && !this.loading, action: action })
  }
  @Builder private Header() {
    Row({ space: 4 }) {
      Button() { SymbolGlyph($r('sys.symbol.chevron_left')).fontSize(23).fontColor([AppTheme.textPrimary]) }
        .width(44).height(44).padding(0).backgroundColor(Color.Transparent).accessibilityText($r('app.string.rss_back')).onClick(() => this.back())
      Column({ space: 4 }) {
        Text(this.screen === 'home' ? $r('app.string.rss_title') : this.screen === 'add' ? $r('app.string.rss_add') : this.screen === 'edit' ? $r('app.string.rss_edit') : this.screen === 'opml' ? $r('app.string.rss_opml_preview') : $r('app.string.rss_manage')).fontFamily(AppTheme.fontSemibold)
          .fontSize(26).lineHeight(34).fontWeight(600).fontColor(AppTheme.textPrimary)
        if (this.screen === 'home' && this.ready) { Text(`${this.counts.subscriptions} ${this.label($r('app.string.rss_sources_count'))}`).lineHeight(18).fontFamily(AppTheme.fontFamily)
          .fontSize(12).fontColor(AppTheme.textSecondary) }
      }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      if (this.screen === 'home') {
        Button() { SymbolGlyph($r('sys.symbol.magnifyingglass')).fontSize(24).fontColor([AppTheme.textPrimary]) }
          .width(44).height(44).backgroundColor(Color.Transparent).accessibilityText($r('app.string.rss_search')).onClick(() => this.searchVisible = !this.searchVisible)
        Button() { SymbolGlyph($r('sys.symbol.plus')).fontSize(22).fontColor([AppTheme.primary]) }
          .width(44).height(44).padding(0).backgroundColor(Color.Transparent).borderRadius(10)
          .accessibilityText($r('app.string.rss_add')).onClick(() => this.openAdd())
      }
    }.width('100%').padding({ top: 16, bottom: 22, right: 20, left: 8 })
  }
  @Builder private Tab(label: Resource, value: string) {
    AppSegmentItem({ label: `${this.label(label)}${value === 'unread' && this.counts.unread > 0 ? ' ' + this.counts.unread : ''}`,
      selected: this.view === value, action: () => { if (this.view !== value) { this.view = value; this.entries = []; this.reload(); } } })
      .layoutWeight(1)
  }
  @Builder private Filters() {
    Scroll() {
      Column({ space: 4 }) {
        AppTextAction({ label: $r('app.string.rss_all_sources'), action: () => { this.subscriptionId = ''; this.filterGroup = ''; this.filtersVisible = false; this.reload(); } }).width('100%')
        ForEach(this.groups, (group: RssGroup) => {
          AppTextAction({ label: group.name, action: () => { this.filterGroup = group.id; this.subscriptionId = ''; this.filtersVisible = false; this.reload(); } }).width('100%')
        }, (group: RssGroup) => group.id)
        ForEach(this.sources, (source: RssSubscription) => {
          AppTextAction({ label: `${source.customTitle || source.title} · ${source.unreadCount}`,
            action: () => { this.subscriptionId = source.id; this.filterGroup = ''; this.filtersVisible = false; this.reload(); } }).width('100%')
        }, (source: RssSubscription) => `${source.id}:${source.revision}:${source.lastAttemptAt}:${source.syncing}`)
      }.width('100%')
    }.height(240).width('100%').backgroundColor(AppTheme.surface).borderRadius(16)
  }
  @Builder private EntryCard(row: RssEntry) {
    Column({ space: 12 }) {
      Row({ space: 8 }) {
        Text((row.sourceTitle || 'R').slice(0, 1)).lineHeight(18).fontFamily(AppTheme.fontSemibold).width(22).height(22).borderRadius(11).textAlign(TextAlign.Center)
          .backgroundColor(AppTheme.accentSoft).fontColor(AppTheme.primary).fontSize(12).fontWeight(600)
        Text(row.sourceTitle).lineHeight(18).fontFamily(AppTheme.fontMedium).fontSize(12).fontWeight(FontWeight.Medium).fontColor(AppTheme.textSecondary).maxLines(1).layoutWeight(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        if (row.starred) {
          SymbolGlyph($r('sys.symbol.star_fill')).fontSize(12).fontColor([AppTheme.primary])
            .accessibilityText($r('app.string.rss_starred'))
        }
        Text(new Date(row.publishedAt || row.discoveredAt).toLocaleDateString()).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textTertiary)
        if (!row.read) { Circle().width(7).height(7).fill(AppTheme.primary) }
      }.width('100%')
      Row({ space: 14 }) {
        if (row.imageUrl.startsWith('https://') && !this.failedImageUrls.includes(row.imageUrl)) {
          Image(row.imageUrl).width(92).height(80).objectFit(ImageFit.Cover).borderRadius(12)
            .backgroundColor(AppTheme.surface).draggable(false).accessibilityLevel('no')
            .onError(() => {
              // 图片不可用时收回缩略图空间，正文仍可正常打开；刷新后允许重试。
              if (!this.failedImageUrls.includes(row.imageUrl)) this.failedImageUrls = this.failedImageUrls.concat(row.imageUrl);
            })
        }
        Text(row.title).fontFamily(row.read ? AppTheme.fontMedium : AppTheme.fontSemibold).fontSize(18).fontWeight(row.read ? 500 : 600).fontColor(AppTheme.textPrimary)
          .lineHeight(26).maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis }).layoutWeight(1)
      }.width('100%').alignItems(VerticalAlign.Center)
      if (row.summary) {
        Text(row.summary).fontFamily(AppTheme.fontFamily).fontSize(15).lineHeight(24).fontColor(AppTheme.textSecondary)
          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
      }
    }.width('100%').alignItems(HorizontalAlign.Start).padding({ top: 18, bottom: 20 })
      .border({ width: { bottom: 1 }, color: AppTheme.divider }).onClick(() => this.openReader(row))
      .accessibilityGroup(true).accessibilityText(`${row.sourceTitle} ${row.title}`)
  }
  @Builder private Home() {
    Column({ space: 14 }) {
      Row() { this.Tab($r('app.string.rss_unread'), 'unread'); this.Tab($r('app.string.rss_all'), 'all'); this.Tab($r('app.string.rss_star'), 'starred') }
        .width('100%').borderRadius(12).backgroundColor(AppTheme.surface)
      if (this.searchVisible) {
        AppSearchField({ value: this.search, placeholder: this.label($r('app.string.rss_search_placeholder')),
          available: !this.busy, valueChanged: (value: string) => this.search = value,
          submitted: () => this.reload(), cleared: () => this.reload() })
      }
      Row() {
        Button() { Row({ space: 6 }) { Text(this.filterTitle()).lineHeight(24).fontFamily(AppTheme.fontMedium).fontSize(16).fontWeight(FontWeight.Medium).fontColor(AppTheme.textPrimary).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }); SymbolGlyph($r('sys.symbol.chevron_down')).fontSize(12).fontColor([AppTheme.textPrimary]) }.width('100%') }
          .layoutWeight(1).height(44).padding(0).backgroundColor(Color.Transparent)
          .onClick(() => this.filtersVisible = !this.filtersVisible)
        AppTextAction({ label: $r('app.string.rss_mark_all'),
          available: this.ready && !this.loading && !this.busy && this.view !== 'starred', action: () => this.confirm('mark') })
      }.width('100%')
      if (this.filtersVisible) { this.Filters() }
      if (this.ready && !this.polling) { Text($r('app.string.rss_polling_disabled')).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).width('100%') }
    }.padding({ left: 20, right: 20 }).width('100%')
    List({ scroller: this.listScroller }) {
      if (this.entries.length === 0 && !this.loading) {
        ListItem() {
          Column({ space: 14 }) {
            SymbolGlyph($r('sys.symbol.doc_text')).fontSize(42).fontColor([AppTheme.primary])
            Text(!this.ready ? $r('app.string.rss_connect_title') : this.sources.length === 0 ? $r('app.string.rss_empty_sources') : $r('app.string.rss_empty_entries')).lineHeight(26).fontFamily(AppTheme.fontMedium).fontSize(18).fontWeight(FontWeight.Medium)
            Text(!this.ready ? $r('app.string.rss_connect_hint') : this.sources.length === 0 ? $r('app.string.rss_empty_hint') : $r('app.string.rss_empty_filter')).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).textAlign(TextAlign.Center)
            this.Action(!this.ready ? $r('app.string.rss_retry') : this.sources.length === 0 ? $r('app.string.rss_add') : $r('app.string.rss_reload'), () => { if (this.ready && !this.sources.length) this.openAdd(); else this.reload(); }, true)
          }.width('100%').padding({ top: 64, bottom: 50, left: 20, right: 20 })
        }
      }
      ForEach(this.entries, (row: RssEntry) => { ListItem() { this.EntryCard(row) } }, (row: RssEntry) => `${row.id}:${row.revision}`)
      if (this.cursor) { ListItem() { Row() { this.Action($r('app.string.rss_more'), () => this.more()) }.width('100%').justifyContent(FlexAlign.Center).padding(20) } }
    }.layoutWeight(1).width('100%').padding({ left: 20, right: 20 })
    Row() {
      AppTextAction({ label: $r('app.string.rss_reload'), available: !this.loading && !this.busy, action: () => this.reload() })
      Blank()
      AppTextAction({ label: $r('app.string.rss_manage'), action: () => { this.screen = 'manage'; this.notice = ''; } })
    }.width('100%').padding({ left: 8, right: 12, bottom: 10 })
  }
  private groupOptions(): SelectOption[] {
    const options: SelectOption[] = [{ value: this.label($r('app.string.rss_ungrouped')) }];
    return options.concat(this.groups.map((group: RssGroup): SelectOption => { return { value: group.name }; }));
  }
  @Builder private GroupPicker() {
    Text($r('app.string.rss_group')).lineHeight(22).fontFamily(AppTheme.fontMedium).fontWeight(500).fontSize(14).fontColor(AppTheme.textSecondary)
    AppSelect({ options: this.groupOptions(), selected: this.groups.findIndex((group: RssGroup) => group.id === this.groupId) + 1,
      value: this.groups.find((group: RssGroup) => group.id === this.groupId)?.name ?? this.label($r('app.string.rss_ungrouped')),
      available: !this.busy, description: this.label($r('app.string.rss_group')),
      onSelected: (index: number) => this.groupId = index > 0 ? this.groups[index - 1].id : '' })
  }
  @Builder private Add() {
    Scroll() {
      Column({ space: 18 }) {
        Text($r('app.string.rss_add_hint')).lineHeight(22).fontFamily(AppTheme.fontFamily).fontSize(14).fontColor(AppTheme.textSecondary).width('100%')
        AppGlassTextInput({ value: this.url, placeholder: 'https://example.com/feed.xml', limit: 2048, available: !this.busy,
          valueChanged: (value: string) => { this.url = value; this.discovery = { candidates: [] }; } })
        this.Action($r('app.string.rss_detect'), () => this.detect(), true)
        ForEach(this.discovery.candidates, (candidate: RssCandidate) => {
          Column({ space: 5 }) { Text(candidate.title || candidate.url).lineHeight(24).fontFamily(AppTheme.fontFamily).fontSize(15); Text(candidate.url).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary) }
            .padding(16).width('100%').backgroundColor(AppTheme.surface).borderRadius(16).onClick(() => this.detect(candidate.url))
        }, (candidate: RssCandidate) => candidate.url)
        if (this.discovery.preview !== undefined) {
          Column({ space: 10 }) {
            Text($r('app.string.rss_detected')).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.primary).width('100%')
            Text(this.discovery.preview.title).lineHeight(28).fontFamily(AppTheme.fontSemibold).fontSize(21).fontWeight(600).width('100%')
            Text(this.discovery.preview.description).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).maxLines(4).width('100%')
            Text($r('app.string.rss_recent')).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textTertiary).width('100%').margin({ top: 10 })
            ForEach(this.discovery.preview.entries, (entry: RssPreviewItem) => { Text(entry.title).lineHeight(22).fontFamily(AppTheme.fontFamily).fontSize(14).maxLines(2).width('100%').padding({ bottom: 6 }) })
          }.padding(20).width('100%').borderRadius(20).backgroundColor(AppTheme.surface)
          Text($r('app.string.rss_name')).lineHeight(22).fontFamily(AppTheme.fontMedium).fontWeight(500).fontSize(14).fontColor(AppTheme.textSecondary).width('100%')
          AppGlassTextInput({ value: this.title, limit: 256, available: !this.busy, valueChanged: (value: string) => this.title = value })
          this.GroupPicker()
          Row({ space: 12 }) {
            Column({ space: 4 }) { Text($r('app.string.rss_initial_read')).lineHeight(24).fontFamily(AppTheme.fontFamily).fontSize(15); Text($r('app.string.rss_initial_hint')).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary) }.layoutWeight(1).alignItems(HorizontalAlign.Start)
            Toggle({ type: ToggleType.Switch, isOn: this.initialReadAll }).width(AppTheme.switchWidth).height(AppTheme.switchHeight)
              .margin({ top: 10, bottom: 10, left: 2, right: 2 }).responseRegion({ x: -2, y: -10, width: 44, height: 44 })
              .selectedColor(AppTheme.primary).enabled(!this.busy).accessibilityText($r('app.string.rss_initial_read')).onChange((value: boolean) => this.initialReadAll = value)
          }.width('100%')
          this.Action($r('app.string.rss_add_confirm'), () => this.add(), true)
        }
      }.width('100%').padding({ left: 20, right: 20, bottom: 32 }).alignItems(HorizontalAlign.Start)
    }.layoutWeight(1).width('100%').align(Alignment.TopStart)
  }
  @Builder private Manage() {
    Scroll() {
      Column({ space: 14 }) {
        Flex({ wrap: FlexWrap.Wrap, alignItems: ItemAlign.Center }) {
          this.Action($r('app.string.rss_add'), () => this.openAdd(), true)
          AppTextAction({ label: $r('app.string.rss_import'), available: !this.busy && !this.loading, action: () => this.pickOpml() })
          AppTextAction({ label: $r('app.string.rss_export'), available: !this.busy && !this.loading, action: () => this.exportOpml() })
        }.width('100%')
        ForEach(this.sources, (source: RssSubscription) => {
          Column({ space: 10 }) {
            Row() { Text(source.customTitle || source.title).lineHeight(28).fontFamily(AppTheme.fontMedium).layoutWeight(1).fontSize(17).fontWeight(FontWeight.Medium).maxLines(2); Text(this.sourceStatus(source)).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(source.lastErrorCode ? AppTheme.danger : AppTheme.primary) }.width('100%')
            Text(source.feedUrl).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textTertiary).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
            if (source.lastSuccessAt) { Text(`${this.label($r('app.string.rss_last_success'))} ${new Date(source.lastSuccessAt).toLocaleString()}`).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).width('100%') }
            Row() {
              AppTextAction({ label: $r('app.string.rss_edit'), available: !this.busy, action: () => this.edit(source) })
              AppTextAction({ label: $r('app.string.rss_refresh'), available: !this.busy && source.status === 'ACTIVE' && this.polling, action: () => this.refreshSource(source) })
              Blank()
              AppTextAction({ label: $r('app.string.rss_remove'), danger: true, available: !this.busy, action: () => this.confirm('source', source.id) })
            }.width('100%')
          }.width('100%').padding(16).backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
        }, (source: RssSubscription) => `${source.id}:${source.revision}:${source.lastAttemptAt}:${source.syncing}`)
        Text($r('app.string.rss_groups')).lineHeight(26).fontFamily(AppTheme.fontMedium).fontSize(18).fontWeight(FontWeight.Medium).width('100%').margin({ top: 12 })
        Row({ space: 8 }) {
          TextInput({ text: this.groupName, placeholder: $r('app.string.rss_group_name') })
            .layoutWeight(1).height('100%').maxLength(80).padding({ left: 14, right: 14 })
            .fontFamily(AppTheme.fontFamily).fontSize(AppTheme.fieldFontSize).fontWeight(400)
            .fontColor(AppTheme.textPrimary)
            .placeholderFont({ size: AppTheme.fieldFontSize, weight: 400, family: AppTheme.fontFamily })
            .placeholderColor(AppTheme.textTertiary).backgroundColor(AppTheme.surface)
            .border({ width: 1, color: AppTheme.divider, radius: AppTheme.inputRadius })
            .borderRadius(AppTheme.inputRadius).enabled(!this.busy)
            .accessibilityText($r('app.string.rss_group_name'))
            .onChange((value: string) => this.groupName = value)
          AppGroupedButton({ label: $r('app.string.rss_create'), variant: 'secondary', fieldAligned: true, fullWidth: false,
            available: !this.busy && !this.loading, action: () => this.addGroup() }).height('100%')
        }.width('100%').height(this.groupControlHeight())
        ForEach(this.groups, (group: RssGroup) => {
          Row() { Text(group.name).lineHeight(24).fontFamily(AppTheme.fontFamily).layoutWeight(1).fontSize(15); AppTextAction({ label: $r('app.string.rss_remove'), danger: true, available: !this.busy, action: () => this.confirm('group', group.id) }) }.width('100%').padding(8)
        }, (group: RssGroup) => group.id)
      }.width('100%').padding({ left: 20, right: 20, bottom: 30 })
    }.layoutWeight(1).width('100%').align(Alignment.TopStart)
  }
  @Builder private Edit() {
    Scroll() {
      Column({ space: 18 }) {
        Text(this.url).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).width('100%')
        Text($r('app.string.rss_name')).lineHeight(22).fontFamily(AppTheme.fontMedium).fontWeight(500).fontSize(14).fontColor(AppTheme.textSecondary)
        AppGlassTextInput({ value: this.title, limit: 256, available: !this.busy, valueChanged: (value: string) => this.title = value })
        this.GroupPicker()
        Text($r('app.string.rss_interval')).lineHeight(22).fontFamily(AppTheme.fontMedium).fontWeight(500).fontSize(14).fontColor(AppTheme.textSecondary)
        AppSelect({ options: [{ value: '15 min' }, { value: '30 min' }, { value: '60 min' }, { value: '180 min' }],
          value: `${this.interval} min`, selected: [15, 30, 60, 180].indexOf(this.interval), available: !this.busy,
          description: this.label($r('app.string.rss_interval')), onSelected: (index: number) => this.interval = [15, 30, 60, 180][index] })
        Row() { Text($r('app.string.rss_pause')).lineHeight(24).fontFamily(AppTheme.fontFamily).layoutWeight(1).fontSize(15); Toggle({ type: ToggleType.Switch, isOn: this.paused }).width(AppTheme.switchWidth).height(AppTheme.switchHeight)
          .margin({ top: 10, bottom: 10, left: 2, right: 2 }).responseRegion({ x: -2, y: -10, width: 44, height: 44 })
          .selectedColor(AppTheme.primary).enabled(!this.busy).accessibilityText($r('app.string.rss_pause')).onChange((value: boolean) => this.paused = value) }.width('100%')
        this.Action($r('app.string.rss_save'), () => this.save(), true)
      }.width('100%').padding(20).alignItems(HorizontalAlign.Start)
    }.layoutWeight(1).width('100%').align(Alignment.TopStart)
  }
  @Builder private Opml() {
    Column({ space: 14 }) {
      Text($r('app.string.rss_opml_hint')).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).width('100%')
      List({ space: 8 }) {
        ForEach(this.opmlItems, (item: RssImportItem, index: number) => {
          ListItem() { Column({ space: 6 }) { Row() { Text(item.title || item.url).lineHeight(24).fontFamily(AppTheme.fontFamily).fontSize(15).layoutWeight(1).maxLines(2); Text(this.importStatus(item)).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(item.errorCode ? AppTheme.danger : AppTheme.primary) }.width('100%'); Text(item.group || item.url).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).maxLines(1); if (item.errorCode) { Text(item.errorCode).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.danger) } }.width('100%').padding(14).backgroundColor(AppTheme.surface).borderRadius(14) }
        }, (item: RssImportItem, index: number) => `${index}:${item.url}`)
      }.layoutWeight(1).width('100%')
      if (this.opmlImported) { this.Action($r('app.string.rss_done'), () => this.back(), true) }
      else { this.Action($r('app.string.rss_import_confirm'), () => this.importOpml(), true) }
    }.layoutWeight(1).width('100%').padding({ left: 20, right: 20, bottom: 20 })
  }
  /** 按当前页面状态构建订阅工作区。 */
  build() {
    if (this.screen === 'reader') {
      RssReaderPage({ entryId: this.readerId, baseUrl: this.baseUrl, authManager: this.authManager,
        onChanged: (row: RssEntry): void => this.entryChanged(row), onBack: () => this.back() })
    } else {
      Column() {
        this.Header()
        if (this.notice) { Text(this.notice).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(this.noticeColor).padding({ left: 22, right: 22, bottom: 12 }).width('100%') }
        if (this.loading || this.busy) { LoadingProgress().width(26).height(26).color(AppTheme.primary).margin({ bottom: 8 }) }
        if (this.screen === 'home') this.Home()
        else if (this.screen === 'add') this.Add()
        else if (this.screen === 'edit') this.Edit()
        else if (this.screen === 'opml') this.Opml()
        else this.Manage()
      }.width('100%').height('100%').backgroundColor(AppTheme.background)
    }
  }
}
