import { common } from '@kit.AbilityKit';
import ConfigurationConstant from '@ohos.app.ability.ConfigurationConstant';
import util from '@ohos.util';
import { AppTheme } from '../../../theme/AppTheme';
import { AppButton, AppTextAction } from '../../../components/AppControls';
import { AuthSessionManager } from '../../auth/AuthSessionManager';
import { DownloadCancellationToken } from '../../../shared/network/DownloadCancellationToken';
import { ToolPageHeader, ToolEmptyState } from '../ToolUi';
import { WeeklyNewsApi } from './WeeklyNewsApi';
import { WeeklyNewsImage } from './WeeklyNewsImage';
import { WeeklyJob, WeeklyReport, WeeklyArticle, WeeklySection, WeeklyFailure, WEEKLY_CATEGORIES } from './WeeklyNewsModels';

/** 手动新闻工作台，页面离开只取消读取，不取消服务端任务。 */
@Component
export struct WeeklyNewsPage {
  @StorageProp('systemColorMode') systemColorMode: number = ConfigurationConstant.ColorMode.COLOR_MODE_LIGHT;
  @Prop @Watch('reset') baseUrl: string = '';
  @Prop @Watch('reset') accountKey: string = '';
  @StorageLink('appForeground') @Watch('foregroundChanged') appForeground: boolean = true;
  authManager: AuthSessionManager = new AuthSessionManager();
  apiFactory?: () => WeeklyNewsApi;
  onBack: () => void = () => {};
  onBackReady: (handler: () => void) => void = () => {};
  @State screen: string = 'home';
  @State ready: boolean = false;
  @State serviceEnabled: boolean = false;
  @State busy: boolean = false;
  @State loading: boolean = false;
  @State notice: ResourceStr = '';
  @State job?: WeeklyJob = undefined;
  @State selectedJob?: WeeklyJob = undefined;
  @State report?: WeeklyReport = undefined;
  @State article?: WeeklyArticle = undefined;
  @State rows: WeeklyArticle[] = [];
  @State history: WeeklyJob[] = [];
  @State cursor: string = '';
  @State category: string = 'TECH';
  @State previewEnd: string = '';
  private alive: boolean = false;
  private epoch: number = 0;
  private navigation: number = 0;
  private timer: number = -1;
  private pendingKey: string = '';
  private pendingRetry: string = '';
  private cancel: DownloadCancellationToken = new DownloadCancellationToken();
  private scroller: Scroller = new Scroller();

  /** 首次进入只恢复任务和历史，不自动采集。 */
  aboutToAppear(): void { this.alive = true; this.onBackReady(() => this.back()); this.reload(); }
  /** 停止页面读取，后台任务由服务端继续执行。 */
  aboutToDisappear(): void { this.alive = false; this.invalidate(); }
  private api(): WeeklyNewsApi { return this.apiFactory ? this.apiFactory() : new WeeklyNewsApi(this.baseUrl, this.authManager); }
  private isDark(): boolean { return this.systemColorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK; }
  private color(light: string, dark: string): string { return this.isDark() ? dark : light; }
  private current(epoch: number): boolean { return this.alive && this.appForeground && this.epoch === epoch && !this.cancel.isCancelled(); }
  private invalidate(): void {
    this.epoch++; this.navigation++; this.cancel.cancel(); this.cancel = new DownloadCancellationToken();
    if (this.timer >= 0) clearTimeout(this.timer); this.timer = -1; this.loading = false; this.busy = false;
  }
  private reset(): void {
    this.invalidate(); this.job = undefined; this.selectedJob = undefined; this.report = undefined; this.article = undefined;
    this.rows = []; this.history = []; this.cursor = ''; this.pendingKey = ''; this.pendingRetry = ''; this.notice = '';
    this.screen = 'home'; this.ready = false; this.serviceEnabled = false; if (this.alive && this.appForeground) this.reload();
  }
  private foregroundChanged(): void {
    // 后台不持续轮询；回到前台重新读取实际进度，不能把页面状态当任务状态。
    if (!this.appForeground) this.invalidate(); else if (this.alive) this.reload();
  }
  private running(): boolean { return this.job !== undefined && ['QUEUED', 'RUNNING'].includes(this.job.status); }
  private async reload(): Promise<void> {
    if (!this.alive || !this.appForeground || !this.accountKey || this.busy) return;
    this.invalidate(); const epoch = this.epoch; this.loading = true; this.notice = '';
    try {
      const api = this.api(), capability = await api.capabilities(this.cancel);
      const active = await api.active(this.cancel), history = await api.history('', this.cancel);
      if (!this.current(epoch)) return;
      // 活动任务恢复后保持其冻结窗口，不能把顶部范围滑到本次打开时间。
      this.serviceEnabled = capability.manualWeeklyEnabled; this.ready = true; this.previewEnd = active ? active.windowEnd : capability.serverTime;
      this.history = history.items; this.cursor = history.nextCursor; this.job = active || history.items[0];
      if (this.screen === 'home') {
        const selected = this.selectedJob ? await api.job(this.selectedJob.id, this.cancel) : active || history.items[0];
        if (!this.current(epoch)) return;
        if (selected) await this.showReport(selected, epoch);
      }
    } catch (_) { if (this.current(epoch)) this.notice = $r('app.string.news_read_failed'); }
    finally { if (this.current(epoch)) { this.loading = false; this.schedule(); } }
  }
  private schedule(delay: number = 5000): void {
    if (this.timer >= 0) clearTimeout(this.timer);
    if (this.alive && this.appForeground && this.running()) this.timer = setTimeout(() => { this.poll(); }, delay);
  }
  private async poll(): Promise<void> {
    this.timer = -1; if (!this.job || !this.running() || !this.appForeground) return;
    const epoch = this.epoch, id = this.job.id; let delay = 5000;
    try {
      const updated = await this.api().job(id, this.cancel);
      if (!this.current(epoch) || this.job?.id !== id) return;
      this.job = updated;
      if (!this.running()) {
        this.notice = ''; this.history = [updated].concat(this.history.filter((row: WeeklyJob) => row.id !== id));
        if (this.screen === 'home' && (!this.selectedJob || this.selectedJob.id === id)) await this.showReport(updated, epoch);
      }
    } catch (_) { if (this.current(epoch)) { this.notice = $r('app.string.news_poll_failed'); delay = 15000; } }
    finally { if (this.current(epoch)) this.schedule(delay); }
  }
  private async create(): Promise<void> {
    if (!this.serviceEnabled || this.busy || this.loading || this.running()) return;
    this.pendingKey = this.pendingKey || util.generateRandomUUID(true); const epoch = this.epoch;
    this.busy = true; this.notice = '';
    try {
      const job = await this.api().create(this.pendingKey, this.cancel);
      if (!this.current(epoch)) return;
      this.pendingKey = ''; this.job = job; this.selectedJob = job; this.report = undefined; this.rows = []; this.screen = 'home'; this.previewEnd = job.windowEnd;
      this.notice = job.reused ? $r('app.string.news_reused') : '';
      if (job.reportRevision > 0) await this.showReport(job, epoch);
      this.schedule();
    } catch (_) { if (this.current(epoch)) this.notice = $r('app.string.news_submit_uncertain'); }
    finally { if (this.current(epoch)) this.busy = false; }
  }
  private async retry(): Promise<void> {
    const target = this.selectedJob || this.job;
    if (!target || this.busy || this.loading || this.running() || !['PARTIAL', 'FAILED'].includes(target.status)) return;
    const operation = `${target.id}_${target.revision}`;
    if (!this.pendingRetry.startsWith(operation)) this.pendingRetry = `${operation}_${util.generateRandomUUID(true)}`;
    const epoch = this.epoch; this.busy = true; this.notice = '';
    try {
      const job = await this.api().retry(target.id, this.pendingRetry, target.revision, this.cancel);
      if (!this.current(epoch)) return;
      this.pendingRetry = ''; this.job = job; this.selectedJob = job; this.previewEnd = job.windowEnd; this.schedule();
    } catch (_) { if (this.current(epoch)) this.notice = $r('app.string.news_retry_failed'); }
    finally { if (this.current(epoch)) this.busy = false; }
  }
  private async showReport(job: WeeklyJob, epoch: number): Promise<void> {
    const navigation = ++this.navigation; this.selectedJob = job;
    if (!job.reportRevision) { this.report = undefined; this.rows = []; return; }
    const report = await this.api().report(job.id, this.cancel);
    if (!this.current(epoch) || navigation !== this.navigation) return;
    this.report = report;
    if (!report.sections.some((section: WeeklySection) => section.category === this.category)) this.category = report.sections[0]?.category || 'TECH';
    const section = report.sections.find((row: WeeklySection) => row.category === this.category);
    this.rows = [];
    if (section) {
      const data = await this.api().section(job.id, this.category, this.cancel);
      if (this.current(epoch) && navigation === this.navigation) this.rows = data.items;
    }
  }
  private async selectCategory(category: string): Promise<void> {
    if (!this.report || this.loading) return;
    this.category = category; this.rows = []; const navigation = ++this.navigation, epoch = this.epoch; this.loading = true;
    try {
      if (this.report.sections.some((row: WeeklySection) => row.category === category)) {
        const section = await this.api().section(this.report.id, category, this.cancel);
        if (this.current(epoch) && navigation === this.navigation) this.rows = section.items;
      }
    } catch (_) { if (this.current(epoch) && navigation === this.navigation) this.notice = $r('app.string.news_read_failed'); }
    finally { if (this.current(epoch) && navigation === this.navigation) this.loading = false; }
  }
  private async openArticle(item: WeeklyArticle): Promise<void> {
    if (!this.report || this.loading) return;
    const navigation = ++this.navigation, epoch = this.epoch; this.loading = true; this.notice = '';
    try {
      const article = await this.api().article(this.report.id, item.eventId, this.cancel);
      if (!this.current(epoch) || navigation !== this.navigation) return;
      this.article = article; this.screen = 'detail';
    } catch (_) { if (this.current(epoch)) this.notice = $r('app.string.news_read_failed'); }
    finally { if (this.current(epoch)) this.loading = false; }
  }
  private async openHistory(): Promise<void> {
    this.navigation++; this.screen = 'history'; this.article = undefined; this.notice = '';
    const epoch = this.epoch; this.loading = true;
    try {
      const page = await this.api().history('', this.cancel);
      if (this.current(epoch) && this.screen === 'history') { this.history = page.items; this.cursor = page.nextCursor; }
    } catch (_) { if (this.current(epoch)) this.notice = $r('app.string.news_read_failed'); }
    finally { if (this.current(epoch)) this.loading = false; }
  }
  private async more(): Promise<void> {
    if (!this.cursor || this.loading) return;
    const epoch = this.epoch; this.loading = true;
    try {
      const page = await this.api().history(this.cursor, this.cancel);
      if (this.current(epoch) && this.screen === 'history') {
        this.history = this.history.concat(page.items.filter((item: WeeklyJob) => !this.history.some((row: WeeklyJob) => row.id === item.id)));
        this.cursor = page.nextCursor;
      }
    } catch (_) { if (this.current(epoch)) this.notice = $r('app.string.news_read_failed'); }
    finally { if (this.current(epoch)) this.loading = false; }
  }
  private async selectHistory(job: WeeklyJob): Promise<void> {
    const epoch = this.epoch; this.screen = 'home'; this.loading = true; this.notice = ''; this.top();
    try { await this.showReport(job, epoch); }
    catch (_) { if (this.current(epoch)) this.notice = $r('app.string.news_read_failed'); }
    finally { if (this.current(epoch)) this.loading = false; }
  }
  private back(): void {
    if (this.screen === 'home') this.onBack();
    else { this.navigation++; this.screen = 'home'; this.article = undefined; this.notice = ''; this.loading = false; }
  }
  private top(): void { this.scroller.scrollTo({ xOffset: 0, yOffset: 0, animation: false }); }
  private async source(url: string): Promise<void> {
    if (!/^https:\/\/[^\s/@?#]+(?:[/?#][^\s]*)?$/i.test(url)) return;
    try { await (this.getUIContext().getHostContext() as common.UIAbilityContext).startAbility({ action: 'ohos.want.action.viewData', uri: url }); }
    catch (_) { this.notice = $r('app.string.news_source_failed'); }
  }
  private date(value: string, time: boolean = false): string {
    if (!value) return '';
    const date = new Date(Date.parse(value) + 8 * 3600000);
    const day = `${date.getUTCFullYear()}/${String(date.getUTCMonth() + 1).padStart(2, '0')}/${String(date.getUTCDate()).padStart(2, '0')}`;
    return time ? `${day} ${String(date.getUTCHours()).padStart(2, '0')}:${String(date.getUTCMinutes()).padStart(2, '0')}` : day;
  }
  private range(start: string, end: string): string { return `${this.date(start, true)} – ${this.date(end, true)}`; }
  private categoryLabel(category: string): Resource {
    switch (category) {
      case 'WORLD': return $r('app.string.news_world'); case 'DOMESTIC': return $r('app.string.news_domestic');
      case 'LOCAL': return $r('app.string.news_local'); case 'TRENDING': return $r('app.string.news_trending');
      case 'MILITARY': return $r('app.string.news_military'); case 'FINANCE': return $r('app.string.news_finance');
      case 'SOCIETY': return $r('app.string.news_society'); case 'SPORTS': return $r('app.string.news_sports');
      case 'ENTERTAINMENT': return $r('app.string.news_entertainment'); default: return $r('app.string.news_tech');
    }
  }
  private statusLabel(status: string): Resource {
    switch (status) {
      case 'SUCCEEDED': return $r('app.string.news_succeeded'); case 'PARTIAL': return $r('app.string.news_partial');
      case 'FAILED': return $r('app.string.news_failed'); case 'RUNNING': return $r('app.string.news_running');
      default: return $r('app.string.news_queued');
    }
  }
  private stageLabel(stage: string): Resource {
    switch (stage) {
      case 'COLLECTING': return $r('app.string.news_collecting'); case 'ENRICHING': return $r('app.string.news_enriching');
      case 'MERGING': return $r('app.string.news_merging'); case 'ANALYZING': return $r('app.string.news_analyzing');
      case 'SAVING': return $r('app.string.news_saving'); default: return $r('app.string.news_queued');
    }
  }
  private count(category: string): number { return this.report?.sections.find((row: WeeklySection) => row.category === category)?.count || 0; }
  private failed(category: string): boolean { return this.report?.failedSections.some((row: WeeklyFailure) => row.category === category) === true; }

  @Builder private Home() {
    Column({ space: 12 }) {
      if (!this.report) {
        Text($r('app.string.news_heading')).fontSize(26).fontWeight(600).fontColor(this.color(AppTheme.ink, AppTheme.darkTextPrimary)).width('100%')
        Text($r('app.string.news_description')).fontSize(15).lineHeight(24).fontColor(this.color(AppTheme.textSecondary, AppTheme.darkTextSecondary)).width('100%')
      }
      Text($r('app.string.news_manual')).fontSize(12).fontColor(this.color(AppTheme.primary, AppTheme.darkPrimary)).width('100%')
      if (this.previewEnd) {
        Text(this.range(new Date(Date.parse(this.previewEnd) - 7 * 86400000).toISOString(), this.previewEnd))
          .fontSize(12).fontColor(this.color(AppTheme.textSecondary, AppTheme.darkTextSecondary)).width('100%')
      }
      AppButton({ darkMode: this.isDark(), label: this.running() ? $r('app.string.news_running') : $r('app.string.news_get'), fullWidth: true,
        available: this.ready && this.serviceEnabled && !this.running() && !this.busy && !this.loading, loading: this.busy, action: () => this.create() })
      if (this.ready && !this.serviceEnabled) Text($r('app.string.news_disabled')).fontSize(14).fontColor(this.color(AppTheme.textSecondary, AppTheme.darkTextSecondary))
    }.width('100%').padding(20).backgroundColor(this.color(AppTheme.surface, AppTheme.darkSurface)).borderRadius(AppTheme.cardRadius).alignItems(HorizontalAlign.Start)
    if (this.job && this.running()) {
      Column({ space: 10 }) {
        Row({ space: 10 }) {
          LoadingProgress().width(20).height(20).color(this.color(AppTheme.primary, AppTheme.darkPrimary))
          Text(this.stageLabel(this.job.progress.stage)).fontSize(16).fontWeight(600).fontColor(this.color(AppTheme.ink, AppTheme.darkTextPrimary)).layoutWeight(1)
          Text(`${this.job.progress.completedCategories} / 10`).fontSize(14).fontColor(this.color(AppTheme.primary, AppTheme.darkPrimary))
        }.width('100%')
        Text(this.range(this.job.windowStart, this.job.windowEnd)).fontSize(12).fontColor(this.color(AppTheme.textSecondary, AppTheme.darkTextSecondary))
        if (this.job.progress.category) Text(this.categoryLabel(this.job.progress.category)).fontSize(14).fontColor(this.color(AppTheme.primary, AppTheme.darkPrimary))
        Text($r('app.string.news_background')).fontSize(14).lineHeight(22).fontColor(this.color(AppTheme.textSecondary, AppTheme.darkTextSecondary))
      }.width('100%').padding(16).backgroundColor(this.color(AppTheme.accentSoft, AppTheme.darkAccentSoft)).borderRadius(16).alignItems(HorizontalAlign.Start)
    }
    if ((this.selectedJob || this.job) && ['PARTIAL', 'FAILED'].includes((this.selectedJob || this.job)!.status)) {
      Column({ space: 8 }) {
        Text(this.statusLabel((this.selectedJob || this.job)!.status)).fontSize(16).fontWeight(600).fontColor(this.color(AppTheme.ink, AppTheme.darkTextPrimary))
        Text(this.range((this.selectedJob || this.job)!.windowStart, (this.selectedJob || this.job)!.windowEnd)).fontSize(12).fontColor(this.color(AppTheme.textSecondary, AppTheme.darkTextSecondary))
        Text($r('app.string.news_retry_hint')).fontSize(14).lineHeight(22).fontColor(this.color(AppTheme.textSecondary, AppTheme.darkTextSecondary))
        AppButton({ darkMode: this.isDark(), label: $r('app.string.news_retry'), variant: 'secondary', available: this.serviceEnabled && !this.busy && !this.running(), action: () => this.retry() })
      }.width('100%').padding(16).backgroundColor(this.color(AppTheme.surface, AppTheme.darkSurface)).borderRadius(16).alignItems(HorizontalAlign.Start)
    }
    if (this.report) {
      Column({ space: 6 }) {
        Text($r('app.string.news_result')).fontSize(20).fontWeight(600).fontColor(this.color(AppTheme.ink, AppTheme.darkTextPrimary))
        Text(this.range(this.report.windowStart, this.report.windowEnd)).fontSize(12).fontColor(this.color(AppTheme.textSecondary, AppTheme.darkTextSecondary))
        Text($r('app.string.news_result_count', this.report.articleCount, this.report.sections.length)).fontSize(13).fontColor(this.color(AppTheme.textSecondary, AppTheme.darkTextSecondary))
        if (this.report.regionCode) Text($r('app.string.news_region', this.report.regionCode)).fontSize(12).fontColor(this.color(AppTheme.textSecondary, AppTheme.darkTextSecondary))
      }.width('100%').alignItems(HorizontalAlign.Start)
      Flex({ wrap: FlexWrap.Wrap }) {
        ForEach(WEEKLY_CATEGORIES, (category: string) => {
          Button({ type: ButtonType.Normal }) {
            Row({ space: 4 }) {
              Text(this.categoryLabel(category)).fontSize(14).fontColor(this.category === category ? (this.isDark() ? AppTheme.darkPrimaryText : Color.White) : this.color(AppTheme.ink, AppTheme.darkTextPrimary))
              Text(this.failed(category) ? '!' : String(this.count(category))).fontSize(12).fontColor(this.category === category ? (this.isDark() ? AppTheme.darkPrimaryText : Color.White) : this.color(AppTheme.ink, AppTheme.darkTextPrimary))
            }
          }.padding({ left: 12, right: 12 }).height(42).margin({ right: 6, bottom: 8 }).borderRadius(21)
            .backgroundColor(this.category === category ? this.color(AppTheme.primary, AppTheme.darkPrimary) : this.color(AppTheme.surface, AppTheme.darkSurface)).enabled(!this.loading)
            .onClick(() => this.selectCategory(category))
        }, (category: string) => category)
      }.width('100%')
      if (!this.loading && this.rows.length === 0) {
        ToolEmptyState({ darkMode: this.isDark(), title: this.failed(this.category) ? $r('app.string.news_category_failed') : $r('app.string.news_category_empty'),
          description: this.failed(this.category) ? $r('app.string.news_retry_hint') : $r('app.string.news_shortfall') })
      } else {
        ForEach(this.rows, (item: WeeklyArticle) => { this.ArticleCard(item) }, (item: WeeklyArticle) => `${this.report?.id}_${this.report?.revision}_${item.eventId}`)
        if (this.rows.length > 0 && this.rows.length < 20) Text($r('app.string.news_shortfall')).fontSize(13).lineHeight(22).fontColor(this.color(AppTheme.textSecondary, AppTheme.darkTextSecondary))
      }
    } else if (!this.loading && !this.running() && !this.job) {
      ToolEmptyState({ darkMode: this.isDark(), title: $r('app.string.news_empty_title'), description: $r('app.string.news_empty_hint') })
    }
  }
  @Builder private ArticleCard(item: WeeklyArticle) {
    Column({ space: 10 }) {
      Row({ space: 12 }) {
        Column({ space: 7 }) {
          Text(`${String(item.rank).padStart(2, '0')}  ${item.title}`).fontSize(17).fontWeight(600).lineHeight(25).fontColor(this.color(AppTheme.ink, AppTheme.darkTextPrimary)).width('100%')
          Text(`${this.date(item.publishedAt)} · ${item.sources.join(' / ')}`).fontSize(12).fontColor(this.color(AppTheme.textSecondary, AppTheme.darkTextSecondary)).maxLines(2)
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        if (item.image && this.report) WeeklyNewsImage({ darkMode: this.isDark(), baseUrl: this.baseUrl, accountKey: this.accountKey, authManager: this.authManager,
          reportId: this.report.id, assetId: item.image.assetId, compact: true })
      }.width('100%').alignItems(VerticalAlign.Top)
      Text(item.summary).fontSize(14).lineHeight(23).fontColor(this.color(AppTheme.textSecondary, AppTheme.darkTextSecondary)).maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
      AppTextAction({ darkMode: this.isDark(), label: $r('app.string.news_read_detail'), action: () => this.openArticle(item) })
    }.width('100%').padding(16).backgroundColor(this.color(AppTheme.surface, AppTheme.darkSurface)).borderRadius(16).alignItems(HorizontalAlign.Start)
  }
  @Builder private Detail() {
    if (this.article && this.report) {
      Text(this.categoryLabel(this.article.category)).fontSize(13).fontColor(this.color(AppTheme.primary, AppTheme.darkPrimary)).width('100%')
      Text(this.article.title).fontSize(24).fontWeight(600).lineHeight(34).fontColor(this.color(AppTheme.ink, AppTheme.darkTextPrimary)).width('100%')
      Text(`${this.date(this.article.publishedAt, true)} · ${this.article.sources.join(' / ')}`).fontSize(13).lineHeight(22).fontColor(this.color(AppTheme.textSecondary, AppTheme.darkTextSecondary)).width('100%')
      if (this.article.image) {
        WeeklyNewsImage({ darkMode: this.isDark(), baseUrl: this.baseUrl, accountKey: this.accountKey, authManager: this.authManager, reportId: this.report.id, assetId: this.article.image.assetId })
        if (this.article.image.caption) Text(this.article.image.caption).fontSize(12).lineHeight(20).fontColor(this.color(AppTheme.textSecondary, AppTheme.darkTextSecondary)).width('100%')
      }
      Text(this.article.summary).fontSize(17).lineHeight(29).fontColor(this.color(AppTheme.ink, AppTheme.darkTextPrimary)).width('100%').copyOption(CopyOptions.InApp)
      Text($r('app.string.news_key_points')).fontSize(18).fontWeight(600).fontColor(this.color(AppTheme.ink, AppTheme.darkTextPrimary)).width('100%')
      ForEach(this.article.points, (point: string, index: number) => {
        Text(`${index + 1}. ${point}`).fontSize(16).lineHeight(27).fontColor(this.color(AppTheme.ink, AppTheme.darkTextPrimary)).width('100%').copyOption(CopyOptions.InApp)
      }, (point: string) => point)
      if (this.article.rankingReason) {
        Text($r('app.string.news_why_selected')).fontSize(18).fontWeight(600).fontColor(this.color(AppTheme.ink, AppTheme.darkTextPrimary)).width('100%')
        Text(this.article.rankingReason).fontSize(15).lineHeight(25).fontColor(this.color(AppTheme.textSecondary, AppTheme.darkTextSecondary)).width('100%')
      }
      if (this.article.limitation) Text(this.article.limitation).fontSize(14).lineHeight(24).fontColor(this.color(AppTheme.textSecondary, AppTheme.darkTextSecondary)).width('100%')
      Text($r('app.string.news_sources')).fontSize(18).fontWeight(600).fontColor(this.color(AppTheme.ink, AppTheme.darkTextPrimary)).width('100%')
      ForEach(this.article.sourceLinks, (url: string, index: number) => {
        AppButton({ darkMode: this.isDark(), label: `${index + 1}. ${url}`, variant: 'secondary', fullWidth: true, action: () => this.source(url) })
      }, (url: string) => url)
    }
  }
  @Builder private History() {
    if (this.history.length === 0 && !this.loading) ToolEmptyState({ darkMode: this.isDark(), title: $r('app.string.news_no_history'), description: $r('app.string.news_empty_hint') })
    ForEach(this.history, (job: WeeklyJob) => {
      Column({ space: 8 }) {
        Row() {
          Text(this.date(job.createdAt, true)).fontSize(17).fontWeight(600).fontColor(this.color(AppTheme.ink, AppTheme.darkTextPrimary)).layoutWeight(1)
          Text(this.statusLabel(job.status)).fontSize(13).fontColor(this.color(AppTheme.primary, AppTheme.darkPrimary))
        }.width('100%')
        Text(this.range(job.windowStart, job.windowEnd)).fontSize(12).fontColor(this.color(AppTheme.textSecondary, AppTheme.darkTextSecondary)).width('100%')
        AppTextAction({ darkMode: this.isDark(), label: $r('app.string.news_view_report'), action: () => this.selectHistory(job) })
      }.width('100%').padding(16).backgroundColor(this.color(AppTheme.surface, AppTheme.darkSurface)).borderRadius(16).alignItems(HorizontalAlign.Start)
    }, (job: WeeklyJob) => `${job.id}_${job.revision}`)
    if (this.cursor) AppButton({ darkMode: this.isDark(), label: $r('app.string.news_more'), variant: 'secondary', loading: this.loading, available: !this.loading, action: () => this.more() })
  }
  @Builder private Notice() {
    if (this.notice) {
      Text(this.notice).fontSize(14).lineHeight(23).fontColor(this.color(AppTheme.textSecondary, AppTheme.darkTextSecondary)).width('100%')
      AppTextAction({ darkMode: this.isDark(), label: $r('app.string.news_refresh'), action: () => this.reload() })
    }
  }
  /** 列表保留挂载和滚动位置，详情与历史使用独立滚动区域。 */
  build() {
    Column({ space: 14 }) {
      Row({ space: 8 }) {
        ToolPageHeader({ darkMode: this.isDark(), title: this.screen === 'history' ? $r('app.string.news_history') : $r('app.string.news_title'), onBack: () => this.back() }).layoutWeight(1)
        if (this.screen === 'home') AppTextAction({ darkMode: this.isDark(), label: $r('app.string.news_history'), action: () => this.openHistory() })
      }.width('100%')
      Stack({ alignContent: Alignment.TopStart }) {
        Scroll(this.scroller) {
          Column({ space: 14 }) {
            this.Notice()
            this.Home()
            if (this.loading) LoadingProgress().width(26).height(26).color(this.color(AppTheme.primary, AppTheme.darkPrimary))
          }.width('100%').alignItems(HorizontalAlign.Start).padding({ bottom: 28 })
        }.width('100%').height('100%').scrollBar(BarState.Off).align(Alignment.TopStart)
          // 隐藏时仍保留布局，避免详情高度改变列表的滚动范围或重建已加载卡片。
          .visibility(this.screen === 'home' ? Visibility.Visible : Visibility.Hidden)
          .enabled(this.screen === 'home')
          .accessibilityLevel(this.screen === 'home' ? 'auto' : 'no-hide-descendants')
          .hitTestBehavior(this.screen === 'home' ? HitTestMode.Default : HitTestMode.None)
        if (this.screen !== 'home') {
          Scroll() {
            Column({ space: 14 }) {
              this.Notice()
              if (this.screen === 'detail') this.Detail()
              else this.History()
              if (this.loading) LoadingProgress().width(26).height(26).color(this.color(AppTheme.primary, AppTheme.darkPrimary))
            }.width('100%').alignItems(HorizontalAlign.Start).padding({ bottom: 28 })
          }.width('100%').height('100%').scrollBar(BarState.Off).align(Alignment.TopStart)
        }
      }.width('100%').layoutWeight(1)
    }.width('100%').height('100%').padding({ left: 20, right: 20, top: 16 }).backgroundColor(this.color(AppTheme.background, AppTheme.darkBackground))
  }
}
