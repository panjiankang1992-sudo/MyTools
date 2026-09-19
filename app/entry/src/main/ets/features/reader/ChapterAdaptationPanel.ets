import util from '@ohos.util';
import { KeyboardAvoidMode } from '@ohos.arkui.UIContext';
import { AppTheme } from '../../theme/AppTheme';
import { AuthSessionManager } from '../auth/AuthSessionManager';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import { ChapterAdaptationApi } from './ChapterAdaptationApi';
import { ChapterAdaptationPolicy } from './ChapterAdaptationPolicy';
import { AppSecondaryPillButton } from '../../components/AppControls';
import { AdaptationPendingJournal, AdaptationPendingRecord } from './AdaptationPendingJournal';
import { AdaptationPendingAssetStorage } from './AdaptationPendingAssetStorage';
import { AdaptationCatalog, AdaptationChapter, ChapterAdaptationCapability, ChapterAdaptationInput,
  ChapterAdaptationKind, ChapterAdaptationHistoryItem, ChapterAdaptationDetail, ChapterAdaptationOutput,
  ChapterAdaptationProgress, ChapterAdaptationCandidate, ChapterAdaptationSourceCheck, AdaptationFeatures,
  AdaptationComparison, AdaptationComparisonHunk, AdaptationStyleTemplate } from './ChapterAdaptationModels';

interface AdaptationVersionRowData {
  item: ChapterAdaptationHistoryItem;
}

/** 独立改编页面不写入阅读正文或进度，关闭只停止观察，不隐式取消后台任务。 */
@Component
export struct ChapterAdaptationPanel {
  bookId: string = '';
  bookTitle: string = '';
  targetChapterIndex: number = -1;
  targetChapterTitle: string = '';
  onBackReady: (handler: () => void) => void = (): void => {};
  baseUrl: string = '';
  accountScope: string = '';
  authManager: AuthSessionManager = new AuthSessionManager();
  lifetime: DownloadCancellationToken = new DownloadCancellationToken();
  isCurrent: () => boolean = (): boolean => false;
  onClose: () => void = (): void => {};
  syncShelf: () => Promise<void> = async (): Promise<void> => {};
  @State private busy: boolean = false;
  @State private status: string = '';
  @State private capability?: ChapterAdaptationCapability = undefined;
  @State private catalog?: AdaptationCatalog = undefined;
  @State private chapter?: AdaptationChapter = undefined;
  @State private history: ChapterAdaptationHistoryItem[] = [];
  @State private historyCursor: string = '';
  @State private detail?: ChapterAdaptationDetail = undefined;
  @State private candidate?: ChapterAdaptationOutput = undefined;
  @State private comparison?: AdaptationComparison = undefined;
  @State private progress?: ChapterAdaptationProgress = undefined;
  @State private intent: string = '';
  @State private styles: AdaptationStyleTemplate[] = [];
  @State private styleIndex: number = 0;
  @State private composing: boolean = false;
  @State private kind: ChapterAdaptationKind = 'INITIAL';
  @State private uncertain: boolean = false;
  @State private sourceProof?: ChapterAdaptationSourceCheck = undefined;
  @State private features?: AdaptationFeatures = undefined;
  @State private page: string = 'LIST';
  private requestedKind?: ChapterAdaptationKind = undefined;
  private catalogCursors: string[] = [];
  private readonly stopLifetime: () => void = (): void => this.endView();
  private proofTimer: number = -1;
  private shelfId: string = '';
  private triggerId: string = '';
  private pending?: ChapterAdaptationInput = undefined;
  private journal?: AdaptationPendingJournal = undefined;
  private ensureKey: string = `prepare-${util.generateRandomUUID(true)}`;
  private readonly policy: ChapterAdaptationPolicy = new ChapterAdaptationPolicy();
  private api?: ChapterAdaptationApi = undefined;
  private cancellation: DownloadCancellationToken = new DownloadCancellationToken();
  private timer: number = -1;
  private alive: boolean = false;
  private previousKeyboardMode?: KeyboardAvoidMode = undefined;

  /** 页面出现后才读取当前账户的已同步书架身份。 */
  aboutToAppear(): void {
    this.alive = true; this.lifetime.bind(this.stopLifetime);
    this.onBackReady((): void => this.goBack());
    if (!this.alive || this.lifetime.isCancelled()) return;
    // 仅在改编页缩放可用区域，让输入键盘上方保留提交按钮。
    this.previousKeyboardMode = this.getUIContext().getKeyboardAvoidMode();
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    try { this.journal = new AdaptationPendingJournal(this.accountScope, new AdaptationPendingAssetStorage(this.accountScope)); }
    catch (_) { this.journal = undefined; }
    this.api = new ChapterAdaptationApi(this.baseUrl, this.authManager); this.open();
  }
  /** 隐藏即终止本地网络、定时器和旧回复，不取消服务端任务。 */
  aboutToDisappear(): void { this.endView(); this.lifetime.unbind(this.stopLifetime); }

  private endView(): void {
    if (this.previousKeyboardMode !== undefined) {
      this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardMode);
      this.previousKeyboardMode = undefined;
    }
    this.alive = false; this.stop(); this.clearProof();
    this.pending = undefined; this.detail = undefined; this.candidate = undefined; this.comparison = undefined;
    this.intent = ''; this.features = undefined; this.journal = undefined;
  }

  private stop(): void {
    this.policy.advance(); this.cancellation.cancel();
    if (this.timer >= 0) clearTimeout(this.timer); this.timer = -1;
  }
  private begin(): number {
    this.stop(); this.cancellation = new DownloadCancellationToken(); this.busy = true; this.status = '';
    return this.policy.advance();
  }
  private current(revision: number): boolean { return this.alive && this.isCurrent() && this.policy.current(revision) && !this.cancellation.isCancelled(); }
  private fail(revision: number, error: Error): void { if (this.current(revision)) { this.status = this.policy.error(error); this.busy = false; } }
  private async open(): Promise<void> {
    const revision = this.begin(); if (!this.current(revision) || this.api === undefined) return;
    try {
      let shelf: string;
      try { shelf = await this.api.shelf(this.bookId, this.cancellation); }
      catch (error) {
        if (!this.current(revision)) return;
        if (this.policy.error(error as Error) !== 'ADAPTATION_SHELF_NOT_SYNCED') throw new Error(this.policy.error(error as Error));
        // 仅补同步当前书架项一次，再从服务端精确解析身份，不按书名猜测。
        await this.syncShelf(); if (!this.current(revision)) return;
        shelf = await this.api.shelf(this.bookId, this.cancellation);
      }
      if (!this.current(revision)) return; this.shelfId = shelf;
      try {
        const features = await this.api.features(this.cancellation); if (!this.current(revision)) return; this.features = features;
      } catch (_) { if (!this.current(revision)) return; this.features = undefined; }
      const capability = await this.api.capability(shelf, this.cancellation); if (!this.current(revision)) return;
      this.capability = capability; this.busy = false;
      if (capability.status === 'READY') this.loadCatalog('');
      else if (capability.status === 'PREPARING') this.schedulePreparation(capability.pollAfterMs, revision);
    } catch (error) { this.fail(revision, error as Error); }
  }
  private schedulePreparation(delay: number, revision: number): void {
    this.timer = setTimeout((): void => { if (this.current(revision)) this.open(); }, delay);
  }
  private async prepare(): Promise<void> {
    const revision = this.begin(); if (!this.current(revision) || this.api === undefined || this.shelfId === '') return;
    try {
      const capability = await this.api.ensure(this.shelfId, this.ensureKey, this.cancellation); if (!this.current(revision)) return;
      this.capability = capability; this.busy = false;
      if (capability.status === 'READY') this.loadCatalog('');
      else if (capability.status === 'PREPARING') this.schedulePreparation(capability.pollAfterMs, revision);
    } catch (error) { this.fail(revision, error as Error); }
  }
  private async loadCatalog(cursor: string): Promise<void> {
    const revision = this.begin(); if (!this.current(revision) || this.api === undefined) return;
    try {
      const catalog = await this.api.catalog(this.shelfId, cursor, this.cancellation); if (!this.current(revision)) return;
      if (cursor !== '' && this.catalog !== undefined && (catalog.catalogRevision !== this.catalog.catalogRevision ||
        catalog.bindingRevision !== this.catalog.bindingRevision || catalog.catalogSha256 !== this.catalog.catalogSha256)) throw new Error('ADAPTATION_CATALOG_CHANGED');
      if (cursor === '') this.catalogCursors = [];
      if (this.catalogCursors.includes(cursor)) throw new Error('ADAPTATION_CATALOG_CHANGED');
      this.catalogCursors.push(cursor);
      this.clearProof(); this.catalog = catalog; this.chapter = undefined; this.detail = undefined;
      this.candidate = undefined; this.comparison = undefined; this.progress = undefined;
      this.history = []; this.composing = false; this.busy = false;
      // 使用权威目录定位所选章节，不按相似标题或本地 ID 猜测服务端身份。
      const target = this.policy.targetChapter(catalog.items, this.targetChapterIndex, this.targetChapterTitle);
      if (target !== undefined) this.select(target);
      else if (catalog.nextCursor !== '' && !this.catalogCursors.includes(catalog.nextCursor) &&
        (catalog.items.length === 0 || catalog.items[catalog.items.length - 1].index < this.targetChapterIndex)) {
        this.loadCatalog(catalog.nextCursor);
      } else throw new Error('ADAPTATION_CHAPTER_NOT_FOUND');
    } catch (error) { this.fail(revision, error as Error); }
  }
  private select(chapter: AdaptationChapter): void {
    this.clearProof();
    this.page = 'LIST'; this.chapter = chapter; this.intent = ''; this.pending = undefined; this.uncertain = false; this.history = [];
    this.detail = undefined; this.candidate = undefined; this.comparison = undefined;
    this.progress = undefined; this.composing = false; this.loadHistory('');
  }
  private async loadHistory(cursor: string): Promise<void> {
    const chapter = this.chapter; if (chapter === undefined) return;
    const revision = this.begin(); if (!this.current(revision) || this.api === undefined) return;
    try {
      const page = await this.api.history(this.shelfId, chapter.chapterId, cursor, this.cancellation); if (!this.current(revision)) return;
      this.history = cursor === '' ? page.items : this.history.concat(page.items.filter((item: ChapterAdaptationHistoryItem) =>
        !this.history.some((existing: ChapterAdaptationHistoryItem) => existing.adaptationId === item.adaptationId)));
      this.historyCursor = page.nextCursor;
      // 恢复仅还原表单，不主动调用创建接口；缺失安全存储不能推断没有旧请求。
      if (cursor === '' && this.journal !== undefined && !this.uncertain) {
        const record = await this.journal.read(this.shelfId, chapter.chapterId); if (!this.current(revision)) return;
        if (record !== undefined) this.restorePending(record);
      }
      this.busy = false;

    } catch (error) { this.fail(revision, error as Error); }
  }
  private async view(item: ChapterAdaptationHistoryItem): Promise<void> {
    const chapter = this.chapter; if (chapter === undefined) return;
    const revision = this.begin(); if (!this.current(revision) || this.api === undefined) return;
    this.page = 'DETAIL'; this.composing = false; this.candidate = undefined; this.comparison = undefined; this.progress = undefined; this.clearProof();
    try {
      const detail = await this.api.detail(item.adaptationId, this.shelfId, chapter.chapterId, this.cancellation);
      if (!this.current(revision)) return; this.detail = detail; this.busy = false;
      if (!this.policy.terminal(detail.version.status)) this.watch(detail.version.adaptationId, revision, 1500);
      else if (detail.result !== undefined) this.checkSource(false);
    } catch (error) { this.fail(revision, error as Error); }
  }
  private async compose(kind: ChapterAdaptationKind): Promise<void> {
    if (this.uncertain || (kind !== 'INITIAL' && !this.canDerive())) return;
    this.stop(); this.busy = false; this.kind = kind; this.pending = undefined;
    this.triggerId = kind === 'INITIAL' ? '' : this.detail?.version.adaptationId ?? '';
    this.intent = kind === 'INITIAL' ? '' : this.detail?.version.intent ?? ''; this.composing = true; this.page = 'FORM';
    const previousCode = kind === 'INITIAL' ? 'free-rewrite' : this.detail?.version.styleTemplate?.code ?? 'free-rewrite';
    await this.loadStyles(previousCode);
  }
  private async loadStyles(previousCode: string = 'free-rewrite'): Promise<void> {
    const revision = this.begin(); if (!this.current(revision) || this.api === undefined) return;
    this.styles = []; this.styleIndex = 0;
    try {
      const styles = await this.api.styles(this.cancellation); if (!this.current(revision)) return;
      this.styles = styles; this.styleIndex = Math.max(0, styles.findIndex((item: AdaptationStyleTemplate) => item.code === previousCode));
      this.busy = false;
    } catch (error) { this.fail(revision, error as Error); }
  }
  private restorePending(record: AdaptationPendingRecord): void {
    this.kind = record.kind; this.triggerId = record.trigger; this.pending = record.input;
    this.intent = record.input.intent; this.uncertain = true; this.composing = true; this.page = 'FORM';
  }
  private async submit(): Promise<void> {
    const chapter = this.chapter; const catalog = this.catalog;
    if (chapter === undefined || catalog === undefined || (!chapter.eligible && !this.uncertain) || this.busy) return;
    const wasUncertain = this.uncertain; const journal = this.journal;
    const revision = this.begin(); if (!this.current(revision) || this.api === undefined) return;
    try {
      if (journal === undefined) throw new Error('ADAPTATION_PENDING_STORAGE_UNAVAILABLE');
      if (this.pending === undefined) {
        if (!this.policy.canCreate(this.features)) throw new Error('READER_052');
        if (this.kind !== 'INITIAL' && !this.canDerive()) throw new Error('ADAPTATION_SOURCE_CHECK_REQUIRED');
        const style = this.styles[this.styleIndex];
        if (style === undefined) throw new Error('ADAPTATION_TEMPLATE_INVALID');
        const input: ChapterAdaptationInput = { idempotencyKey: `adapt-${util.generateRandomUUID(true)}`,
          intent: this.intent, expectedBindingRevision: catalog.bindingRevision, expectedCatalogRevision: catalog.catalogRevision,
          templateCode: style.code, templateVersion: style.version };
        if (this.kind !== 'INITIAL' && this.sourceProof !== undefined) {
          input.expectedBindingRevision = this.sourceProof.bindingRevision;
          input.expectedCatalogRevision = this.sourceProof.catalogRevision;
          input.expectedSourceSha256 = this.sourceProof.sourceSha256;
        } else if (chapter.sourceSha256 !== '') input.expectedSourceSha256 = chapter.sourceSha256;
        this.pending = this.policy.input(input);
      }
      const pending = this.pending;
      await journal.put({ scope: this.accountScope, shelf: this.shelfId, chapter: chapter.chapterId,
        kind: this.kind, trigger: this.triggerId, input: pending });
      if (!this.current(revision)) return;
      // 发出后保留完全相同的输入和 key；超时只表示收据未知，不能把后台版本标为失败。
      this.uncertain = true;
      const accepted = await this.api.create(this.shelfId, chapter.chapterId, this.kind, this.triggerId, pending, this.cancellation);
      if (!this.current(revision)) return;
      // 确认清理失败仍保留原请求；后续重放只能取同一收据，不能静默覆盖未知记录。
      let acknowledged = false;
      try { await journal.remove(chapter.chapterId, pending.idempotencyKey); acknowledged = true; } catch (_) {}
      if (!this.current(revision)) return;
      this.uncertain = !acknowledged; if (acknowledged) this.pending = undefined;
      this.page = 'DETAIL'; this.composing = false; this.detail = undefined; this.comparison = undefined; this.busy = false;
      this.status = acknowledged ? accepted.status : 'ADAPTATION_PENDING_STORAGE_UNAVAILABLE';
      this.watch(accepted.adaptationId, revision, accepted.pollAfterMs);
    } catch (error) {
      // 重试时的鉴权或告知拒绝不能证明上一次请求未生效，禁止因此丢弃旧 key。
      if (this.current(revision) && !wasUncertain && this.policy.rejected(error as Error) && journal !== undefined && this.pending !== undefined) {
        try {
          await journal.remove(chapter.chapterId, this.pending.idempotencyKey);
          if (this.current(revision)) { this.uncertain = false; this.pending = undefined; }
        } catch (_) { if (this.current(revision)) this.uncertain = true; }
      }
      if (this.current(revision) && !this.uncertain) this.pending = undefined;
      this.fail(revision, error as Error);

    }
  }
  private watch(id: string, revision: number, delay: number): void {
    this.timer = setTimeout((): void => { if (this.current(revision)) this.poll(id, revision); }, delay);
  }
  private async poll(id: string, revision: number): Promise<void> {
    if (!this.current(revision) || this.api === undefined) return;
    try {
      const progress = await this.api.progress(id, this.cancellation); if (!this.current(revision)) return;
      this.progress = progress; this.status = '';
      if (this.policy.terminal(progress.status)) { this.detail = undefined; this.page = 'LIST'; this.loadHistory(''); } else this.watch(id, revision, progress.pollAfterMs);
    } catch (error) { this.fail(revision, error as Error); }
  }
  private async cancel(): Promise<void> {
    const id = this.progress?.adaptationId ?? this.detail?.version.adaptationId ?? '';
    if (id === '' || this.busy) return; const revision = this.begin(); if (!this.current(revision) || this.api === undefined) return;
    try {
      const progress = await this.api.cancel(id, this.cancellation); if (!this.current(revision)) return;
      this.progress = progress; this.busy = false;
      if (this.policy.terminal(progress.status)) { this.detail = undefined; this.page = 'LIST'; this.loadHistory(''); } else this.watch(id, revision, progress.pollAfterMs);
    } catch (error) { this.fail(revision, error as Error); }
  }
  private async showCandidate(attempt: string): Promise<void> {
    const detail = this.detail; if (detail === undefined) return; const revision = this.begin();
    if (!this.current(revision) || this.api === undefined) return;
    try {
      const output = await this.api.attempt(detail.version.adaptationId, attempt, this.cancellation);
      if (!this.current(revision)) return; this.comparison = undefined; this.candidate = output; this.busy = false;
    } catch (error) { this.fail(revision, error as Error); }
  }
  private async showComparison(): Promise<void> {
    if (this.comparison !== undefined) { this.comparison = undefined; return; }
    const detail = this.detail; if (detail?.result === undefined || this.busy) return;
    const revision = this.begin(); if (!this.current(revision) || this.api === undefined) return;
    try {
      const comparison = await this.api.comparison(detail.version.adaptationId, detail.result, this.cancellation);
      if (!this.current(revision)) return;
      this.candidate = undefined; this.comparison = comparison; this.busy = false;
    } catch (error) { this.fail(revision, error as Error); }
  }
  private inputReady(): boolean { try { this.policy.intent(this.intent); return this.styles[this.styleIndex] !== undefined; } catch (_) { return false; } }
  private styleOptions(): SelectOption[] {
    return this.styles.map((item: AdaptationStyleTemplate): SelectOption => ({ value: item.name }));
  }

  private text(resource: Resource): string {
    try { return this.getUIContext().getHostContext()?.resourceManager.getStringSync(resource.id) ?? ''; }
    catch (_) { return ''; }
  }
  private label(code: string): string {
    let resource: Resource = $r('app.string.adaptation_state_unknown');
    switch (code) {
      case 'INITIAL': resource = $r('app.string.adaptation_new'); break;
      case 'OPTIMIZE': resource = $r('app.string.adaptation_optimize'); break;
      case 'REGENERATE': resource = $r('app.string.adaptation_regenerate'); break;
      case 'PENDING_DISPATCH': resource = $r('app.string.adaptation_state_dispatch'); break;
      case 'QUEUED': resource = $r('app.string.adaptation_state_queued'); break;
      case 'CONTEXT_PENDING': case 'CONTEXT_FREEZING': case 'CONTEXT_FROZEN':
        resource = $r('app.string.adaptation_state_context'); break;
      case 'ANALYZING': case 'PLAN': resource = $r('app.string.adaptation_state_plan'); break;
      case 'GENERATING': case 'GENERATE': resource = $r('app.string.adaptation_state_generate'); break;
      case 'VALIDATING': case 'CRITIC': resource = $r('app.string.adaptation_state_validate'); break;
      case 'REPAIRING': case 'REPAIR': resource = $r('app.string.adaptation_state_repair'); break;
      case 'PERSISTING': case 'PERSIST': resource = $r('app.string.adaptation_state_persist'); break;
      case 'COMPLETED': resource = $r('app.string.adaptation_state_completed'); break;
      case 'CANCEL_REQUESTED': case 'CANCELLING': case 'DISPATCH_CANCELLING':
        resource = $r('app.string.adaptation_state_cancelling'); break;
      case 'CANCELLED': resource = $r('app.string.adaptation_state_cancelled'); break;
      case 'FAILED': case 'BROKEN': resource = $r('app.string.adaptation_state_failed'); break;
      case 'READY': resource = $r('app.string.adaptation_state_ready'); break;
      case 'PREPARING': resource = $r('app.string.adaptation_state_preparing'); break;
      case 'UNAVAILABLE': resource = $r('app.string.adaptation_state_unavailable'); break;
      case 'CHECKING': resource = $r('app.string.adaptation_state_checking'); break;
      case 'CURRENT': resource = $r('app.string.adaptation_state_current'); break;
      case 'STALE': resource = $r('app.string.adaptation_state_stale'); break;
      case 'SELECTED': resource = $r('app.string.adaptation_state_selected'); break;
      case 'NOT_SELECTED': resource = $r('app.string.adaptation_state_not_selected'); break;
      case 'REJECTED_BY_CONSTRAINTS': resource = $r('app.string.adaptation_state_rejected'); break;
    }
    return this.text(resource);
  }
  private statusText(): string {
    if (this.status === 'ADAPTATION_SHELF_NOT_SYNCED' || this.status === 'ADAPTATION_SHELF_AMBIGUOUS')
      return this.text($r('app.string.adaptation_shelf_sync_required'));
    if (this.status.startsWith('ADAPTATION_PENDING_')) return `${this.text($r('app.string.adaptation_pending_storage_error'))} (${this.status})`;
    if (/^(READER_|GATEWAY_|ADAPTATION_)/.test(this.status)) return `${this.text($r('app.string.adaptation_request_error'))} (${this.status})`;
    return this.label(this.status);
  }

  private progressText(): string {
    if (this.progress === undefined) return '';
    const status = this.label(this.progress.status);
    const stage = this.label(this.progress.currentStage);
    return status === stage || this.progress.currentStage === '' ? status : `${status} · ${stage}`;
  }

  private failureText(): string {
    const code = this.detail?.version.lastErrorCode ?? '';
    const message = code === 'READER_033' ? this.text($r('app.string.adaptation_intent_conflict')) :
      this.text($r('app.string.adaptation_failed_help'));
    // 仅显示已规范化的错误码，不将模型回复或底层异常直接展示给用户。
    return code === '' ? message : `${message} (${code})`;
  }

  private canDerive(): boolean { return this.policy.canDeriveVerified(this.detail, this.sourceProof, Date.now()); }
  private goBack(): void {
    if (!this.alive || !this.isCurrent()) return;
    if (this.page === 'READ') { this.comparison = undefined; this.candidate = undefined; this.page = 'DETAIL'; return; }
    if (this.page === 'LIST' || this.chapter === undefined) { this.onClose(); return; }
    this.requestedKind = undefined; this.stop(); this.busy = false; this.composing = false;
    if (this.page === 'FORM' && this.detail !== undefined) { this.page = 'DETAIL'; this.view(this.detail.version); }
    else { this.page = 'LIST'; this.loadHistory(''); }
  }
  private derive(kind: ChapterAdaptationKind): void {
    if (this.busy || this.uncertain || this.detail?.result === undefined) return;
    if (this.canDerive()) { this.compose(kind); return; }
    this.requestedKind = kind; this.checkSource(true);
  }
  private clearProof(): void {
    if (this.proofTimer >= 0) clearTimeout(this.proofTimer); this.proofTimer = -1; this.sourceProof = undefined;
  }
  private async checkSource(request: boolean): Promise<void> {
    const detail = this.detail; if (detail === undefined || detail.result === undefined || this.uncertain) return;
    const revision = this.begin(); if (!this.current(revision) || this.api === undefined) return;
    try {
      const proof = await this.api.sourceCheck(detail.version.adaptationId, request, this.cancellation);
      if (!this.current(revision) || this.detail?.version.adaptationId !== proof.adaptationId) return;
      this.clearProof(); this.sourceProof = proof; this.busy = false;
      this.detail = { version: detail.version, sourceRelation: proof.status === 'CURRENT' || proof.status === 'STALE' ? proof.status : 'UNKNOWN',
        sourceCheckedAt: proof.sourceCheckedAt, result: detail.result, attempts: detail.attempts };
      if (proof.status === 'QUEUED' || proof.status === 'CHECKING') {
        this.timer = setTimeout((): void => { if (this.current(revision)) this.checkSource(false); }, proof.pollAfterMs);
      } else if (proof.status === 'CURRENT') {
        const requested = this.requestedKind; this.requestedKind = undefined;
        if (requested !== undefined) this.compose(requested);
        // 有效期计时独立于输入操作；过期不丢弃用户已填写的意图，也不改变不确定提交的 key。
        this.proofTimer = setTimeout((): void => {
          if (this.alive && this.isCurrent() && this.sourceProof?.adaptationId === proof.adaptationId &&
            this.sourceProof?.sourceCheckedAt === proof.sourceCheckedAt) this.clearProof();
        }, Math.max(0, Math.min(60000, Date.parse(proof.validUntil) - Date.now())));
      }
    } catch (error) { if (this.current(revision)) this.clearProof(); this.fail(revision, error as Error); }
  }

  private dateLabel(value: string): string {
    const date = new Date(value);
    if (!Number.isFinite(date.getTime())) return '';
    const pad = (part: number): string => part.toString().padStart(2, '0');
    return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
  }
  private originLabel(): string {
    const version = this.detail?.version;
    if (version === undefined || version.requestKind === 'INITIAL') return this.text($r('app.string.adaptation_origin_original'));
    const parent = this.history.find((item: ChapterAdaptationHistoryItem) =>
      item.adaptationId === version.lineage.triggerAdaptationId);
    return parent === undefined ? this.label(version.requestKind) :
      `V${parent.revisionNumber} · ${this.label(version.requestKind)}`;
  }

  @Builder
  private SecondaryAction(label: Resource, available: boolean, action: () => void) {
    AppSecondaryPillButton({ label: this.text(label), available: available, action: action })
  }
  @Builder
  private ContextHeading(title: string) {
    Column({ space: 8 }) {
      Text(title).fontSize(24).lineHeight(32).fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Text(this.bookTitle).fontSize(14).fontColor(AppTheme.textSecondary).maxLines(2)
    }.width('100%').alignItems(HorizontalAlign.Start).padding({ bottom: 8 })
  }
  @Builder
  private VersionRow($$: AdaptationVersionRowData) {
    Row({ space: 8 }) {
      Text(`V${$$.item.revisionNumber}`).fontSize(14).fontWeight(FontWeight.Bold).fontColor(AppTheme.primary)
        .padding({ left: 10, right: 10, top: 6, bottom: 6 }).borderRadius(8).backgroundColor(AppTheme.accentSoft)
      Text(this.label($$.item.status)).fontSize(13).fontColor($$.item.status === 'FAILED' ? AppTheme.danger : AppTheme.primary)
      Text($$.item.styleTemplate?.name ?? '').fontSize(13).fontColor(AppTheme.textSecondary).maxLines(1).layoutWeight(1)
      Text(this.dateLabel($$.item.createdAt)).fontSize(11).fontColor(AppTheme.textTertiary)
    }.width('100%')
  }
  @Builder
  private VersionList() {
    this.ContextHeading(this.chapter?.title ?? this.targetChapterTitle)
    if (this.history.length === 0) {
      Column({ space: 12 }) {
        Text($r('app.string.adaptation_empty_title')).fontSize(18).fontWeight(FontWeight.Medium).fontColor(AppTheme.textPrimary)
        Text($r('app.string.adaptation_empty_description')).fontSize(14).lineHeight(22).fontColor(AppTheme.textSecondary)
      }.width('100%').padding({ top: 40, bottom: 40, left: 20, right: 20 })
        .backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius).alignItems(HorizontalAlign.Start)
    } else {
      Button({ type: ButtonType.Normal }) {
        Column({ space: 14 }) {
          this.VersionRow({ item: this.history[0] })
          Text(this.history[0].intent).fontSize(15).lineHeight(24).maxLines(3).fontColor(AppTheme.textSecondary)
          Row({ space: 6 }) {
            Text($r('app.string.adaptation_view_detail')).fontSize(14).fontWeight(FontWeight.Medium).fontColor(AppTheme.primary)
            SymbolGlyph($r('sys.symbol.chevron_right')).fontSize(14).fontColor([AppTheme.primary])
          }
        }.width('100%').alignItems(HorizontalAlign.Start)
      }.width('100%').padding(20).backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
        .enabled(!this.busy && !this.uncertain).onClick(() => this.view(this.history[0]))
      if (this.history.length > 1) {
        Text($r('app.string.adaptation_history')).fontSize(15).fontColor(AppTheme.textSecondary).padding({ top: 8 })
        Column() {
          ForEach(this.history.slice(1), (item: ChapterAdaptationHistoryItem) => {
            Row({ space: 12 }) {
              Column() {
                SymbolGlyph($r('sys.symbol.circle')).fontSize(12).fontColor([AppTheme.primary])
                Divider().vertical(true).strokeWidth(1).color(AppTheme.outline).height(94)
              }.width(12)
              Button({ type: ButtonType.Normal }) {
                Column({ space: 10 }) {
                  this.VersionRow({ item: item })
                  Text(item.intent).fontSize(14).lineHeight(22).maxLines(2).fontColor(AppTheme.textSecondary)
                }.width('100%').alignItems(HorizontalAlign.Start)
              }.layoutWeight(1).padding({ top: 16, bottom: 16, left: 8, right: 12 })
                .backgroundColor(AppTheme.surface).borderRadius(0).enabled(!this.busy && !this.uncertain)
                .onClick(() => this.view(item))
            }.width('100%').alignItems(VerticalAlign.Top)
          }, (item: ChapterAdaptationHistoryItem) => item.adaptationId)
        }.width('100%').padding({ left: 4 }).alignItems(HorizontalAlign.Start)
      }
      if (this.historyCursor !== '') {
        this.SecondaryAction($r('app.string.adaptation_next_page'), !this.busy, () => this.loadHistory(this.historyCursor))
      }
    }
  }
  @Builder
  private Composer() {
    this.ContextHeading(this.label(this.kind))
    Text(this.chapter?.title ?? this.targetChapterTitle).fontSize(14).fontColor(AppTheme.textSecondary)
    Column({ space: 16 }) {
      Text($r('app.string.adaptation_style_label')).fontSize(15).fontWeight(FontWeight.Medium).fontColor(AppTheme.textPrimary)
      if (this.uncertain) {
        Text(`${this.pending?.templateCode ?? 'legacy'} v${this.pending?.templateVersion ?? 1}`).fontSize(13).fontColor(AppTheme.textSecondary)
      } else {
        Select(this.styleOptions()).selected(this.styleIndex).value(this.styles[this.styleIndex]?.name ?? '')
          .width('100%').height(AppTheme.inputHeight).font({ size: 15 }).fontColor(AppTheme.textPrimary)
          .backgroundColor(AppTheme.surface).border({ width: 1, color: AppTheme.divider, radius: 12 })
          .enabled(!this.busy && this.styles.length > 0).onSelect((index: number) => this.styleIndex = index)
        Text(this.styles[this.styleIndex]?.description ?? '').fontSize(13).lineHeight(21).fontColor(AppTheme.textSecondary)
        if (this.styles.length === 0 && !this.busy) {
          this.SecondaryAction($r('app.string.adaptation_style_reload'), true, () => this.loadStyles())
        }
      }
      Text($r('app.string.adaptation_intent_label')).fontSize(15).fontWeight(FontWeight.Medium)
        .fontColor(AppTheme.textPrimary).padding({ top: 12 })
      TextArea({ text: this.intent, placeholder: $r('app.string.adaptation_intent_placeholder') })
        .height(200).padding(14).fontSize(15).fontColor(AppTheme.textPrimary).backgroundColor(AppTheme.surface)
        .border({ width: 1, color: AppTheme.divider, radius: 12 })
        .maxLength(4000).enabled(!this.busy && !this.uncertain).onChange((value: string) => this.intent = value)
      Text(`${Array.from(this.intent).length} / 2000`).width('100%').textAlign(TextAlign.End)
        .fontSize(12).fontColor(AppTheme.textTertiary)
      if (this.kind !== 'INITIAL' && !this.canDerive() && !this.uncertain) {
        this.SecondaryAction($r('app.string.adaptation_verify_source'), !this.busy, () => this.checkSource(true))
      }
    }.width('100%').padding(20).borderRadius(AppTheme.cardRadius).backgroundColor(AppTheme.surface)
      .alignItems(HorizontalAlign.Start)
  }
  @Builder
  private DetailRow(label: Resource, value: string) {
    Row({ space: 20 }) {
      Text(label).width(66).fontSize(13).fontColor(AppTheme.textTertiary)
      Text(value).layoutWeight(1).fontSize(14).lineHeight(22).fontColor(AppTheme.textSecondary)
    }.width('100%').alignItems(VerticalAlign.Top)
  }
  @Builder
  private VersionDetail() {
    if (this.detail !== undefined) {
      Column({ space: 20 }) {
        this.VersionRow({ item: this.detail.version })
        this.DetailRow($r('app.string.adaptation_chapter_label'), this.detail.version.chapterTitle)
        this.DetailRow($r('app.string.adaptation_style_label'), this.detail.version.styleTemplate?.name ??
          this.text($r('app.string.adaptation_legacy_style')))
        this.DetailRow($r('app.string.adaptation_created_label'), this.dateLabel(this.detail.version.createdAt))
        this.DetailRow($r('app.string.adaptation_origin_label'), this.originLabel())
        Divider().color(AppTheme.divider)
        Text($r('app.string.adaptation_intent_label')).fontSize(15).fontWeight(FontWeight.Medium).fontColor(AppTheme.textPrimary)
        Text(this.detail.version.intent).fontSize(15).lineHeight(24).fontColor(AppTheme.textSecondary)
        Divider().color(AppTheme.divider)
        Text($r('app.string.adaptation_preview_label')).fontSize(15).fontWeight(FontWeight.Medium).fontColor(AppTheme.textPrimary)
        if (this.detail.result !== undefined) {
          Text(this.detail.result.content).fontSize(15).lineHeight(24).maxLines(5)
            .textOverflow({ overflow: TextOverflow.Ellipsis }).fontColor(AppTheme.textSecondary)
        } else {
          Text(this.label(this.detail.version.status)).fontSize(14).fontColor(AppTheme.textSecondary)
          if (this.detail.version.status === 'FAILED') {
            Text(this.failureText()).fontSize(14).lineHeight(22).fontColor(AppTheme.danger)
          }
        }
        ForEach(this.detail.attempts, (attempt: ChapterAdaptationCandidate) => {
          if (attempt.hasViewableOutput && attempt.attemptId !== this.detail?.version.selectedAttemptId) {
            AppSecondaryPillButton({ label: `${attempt.attemptNo} · ${this.label(attempt.disposition)}`,
              available: !this.busy, action: () => { this.page = 'READ'; this.showCandidate(attempt.attemptId); } })
          }
        })
      }.width('100%').padding(20).borderRadius(AppTheme.cardRadius).backgroundColor(AppTheme.surface)
        .alignItems(HorizontalAlign.Start)
    }
  }
  @Builder
  private ReadingContent() {
    if (this.detail !== undefined) {
      Text(this.detail.version.chapterTitle).fontSize(22).lineHeight(32).fontWeight(FontWeight.Bold).fontColor(AppTheme.textPrimary)
      Text(`V${this.detail.version.revisionNumber} · ${this.detail.version.styleTemplate?.name ?? ''}`)
        .fontSize(13).fontColor(AppTheme.primary).padding({ bottom: 14 })
              if (this.comparison !== undefined) {
                Text($r('app.string.adaptation_compare_notice')).fontSize(12).fontColor(AppTheme.textSecondary)
                if (this.comparison.mode === 'SIDE_BY_SIDE') {
                  Text($r('app.string.adaptation_compare_fallback')).fontSize(12)
                  Text($r('app.string.adaptation_compare_original')).fontWeight(FontWeight.Bold)
                  Text(this.comparison.original).width('100%').fontSize(17).lineHeight(28).copyOption(CopyOptions.InApp)
                  Text($r('app.string.adaptation_compare_adapted')).fontWeight(FontWeight.Bold)
                  Text(this.comparison.adapted).width('100%').fontSize(17).lineHeight(28).copyOption(CopyOptions.InApp)
                } else {
                  ForEach(this.comparison.hunks, (hunk: AdaptationComparisonHunk) => {
                    Column({ space: 6 }) {
                      if (hunk.kind === 'EQUAL') {
                        Text($r('app.string.adaptation_compare_unchanged')).fontSize(12).fontColor(AppTheme.textSecondary)
                        Text(hunk.original).width('100%').fontSize(17).lineHeight(28).copyOption(CopyOptions.InApp)
                      } else {
                        if (hunk.original !== '') {
                          Text($r('app.string.adaptation_compare_original')).fontSize(12).fontColor(AppTheme.danger)
                          Text(hunk.original).width('100%').fontSize(17).lineHeight(28).copyOption(CopyOptions.InApp)
                        }
                        if (hunk.adapted !== '') {
                          Text($r('app.string.adaptation_compare_adapted')).fontSize(12).fontColor(AppTheme.primary)
                          Text(hunk.adapted).width('100%').fontSize(17).lineHeight(28).copyOption(CopyOptions.InApp)
                        }
                      }
                    }.width('100%').padding(10).backgroundColor(AppTheme.surfaceTint).alignItems(HorizontalAlign.Start)
                  })
                }
              } else if (this.candidate !== undefined) {
                Text(this.label(this.candidate.viewStatus)).fontColor(AppTheme.danger)
                Text(this.candidate.content).width('100%').fontSize(17).lineHeight(28).copyOption(CopyOptions.InApp)
              } else if (this.detail.result !== undefined) {
                Text(this.detail.result.content).width('100%').fontSize(17).lineHeight(28).copyOption(CopyOptions.InApp)
              }

    }
  }
  @Builder
  private Header() {
    Row({ space: 8 }) {
      Button() { SymbolGlyph($r('sys.symbol.chevron_left')).fontSize(22).fontColor([AppTheme.textPrimary]) }
        .width(44).height(44).backgroundColor(Color.Transparent).accessibilityText($r('app.string.adaptation_close'))
        .onClick(() => this.goBack())
      Text(this.page === 'DETAIL' ? $r('app.string.adaptation_detail_title') :
        this.page === 'READ' ? $r('app.string.adaptation_read_title') : $r('app.string.adaptation_title'))
        .layoutWeight(1).fontSize(18).fontWeight(FontWeight.Medium).fontColor(AppTheme.textPrimary).maxLines(1)
      if ((this.page === 'DETAIL' || this.page === 'READ') && this.detail?.result !== undefined) {
        Button($r('app.string.adaptation_optimize')).height(40).padding({ left: 10, right: 10 }).fontSize(13)
          .fontColor(AppTheme.primary).backgroundColor(Color.Transparent).borderRadius(AppTheme.pillRadius)
          .enabled(!this.busy && !this.uncertain).onClick(() => this.derive('OPTIMIZE'))
        Button($r('app.string.adaptation_regenerate')).height(40).padding({ left: 10, right: 10 }).fontSize(13)
          .fontColor(AppTheme.primary).backgroundColor(Color.Transparent).borderRadius(AppTheme.pillRadius)
          .enabled(!this.busy && !this.uncertain).onClick(() => this.derive('REGENERATE'))
      }
    }.width('100%').padding({ left: 12, right: 18, top: 12, bottom: 12 })
  }
  private footerLabel(): Resource {
    if (this.page === 'FORM') return this.uncertain ? $r('app.string.adaptation_retry_same') : $r('app.string.adaptation_submit');
    return this.page === 'LIST' ? $r('app.string.adaptation_new') : $r('app.string.adaptation_read_content');
  }
  private footerAvailable(): boolean {
    if (this.busy) return false;
    if (this.page === 'LIST') return !this.uncertain && this.chapter?.eligible === true;
    if (this.page === 'FORM') return this.uncertain || (this.policy.canCreate(this.features) && this.inputReady() &&
      (this.kind === 'INITIAL' || this.canDerive()));
    return this.detail?.result !== undefined;
  }
  private footerAction(): void {
    if (!this.footerAvailable()) return;
    if (this.page === 'LIST') this.compose('INITIAL');
    else if (this.page === 'FORM') this.submit();
    else { this.candidate = undefined; this.comparison = undefined; this.page = 'READ'; }
  }
  @Builder
  private Footer() {
    // 直接读取当前页面状态，避免值传递 Builder 将初始 busy 状态冻结在按钮上。
    Column() {
      Button(this.footerLabel()).width('100%').height(AppTheme.inputHeight).fontSize(15).fontWeight(FontWeight.Medium)
        .fontFamily('HarmonyOS Sans').fontColor(Color.White).backgroundColor(AppTheme.primary)
        .borderRadius(AppTheme.pillRadius).enabled(this.footerAvailable()).onClick(() => this.footerAction())
    }.width('100%').padding({ left: 24, right: 24, top: 12, bottom: 24 })
  }
  build() {
    Column() {
      this.Header()
      Scroll() {
        Column({ space: 18 }) {
          if (this.busy) { LoadingProgress().width(28).height(28).color(AppTheme.primary) }
          if (this.status !== '') {
            Text(this.statusText()).fontSize(13).lineHeight(21).fontColor(AppTheme.textSecondary)
            if (!this.busy) {
              this.SecondaryAction($r('app.string.adaptation_refresh'), true, () => {
                if (this.page === 'FORM') this.loadStyles();
                else if (this.page === 'DETAIL' && this.detail !== undefined) this.view(this.detail.version);
                else this.open();
              })
            }
          }
          if (this.uncertain) {
            Text($r('app.string.adaptation_uncertain')).fontSize(13).lineHeight(21).fontColor(AppTheme.danger)
            if (this.page !== 'FORM') {
              this.SecondaryAction($r('app.string.adaptation_retry_same'), !this.busy,
                () => { this.page = 'FORM'; this.composing = true; })
            }
          }
          if (this.chapter === undefined) {
            this.ContextHeading(this.targetChapterTitle)
            if (this.capability !== undefined && this.capability.status !== 'READY') {
              Text(this.label(this.capability.status)).fontSize(14).fontColor(AppTheme.textSecondary)
              this.SecondaryAction($r('app.string.adaptation_prepare'),
                !this.busy && this.capability.status !== 'PREPARING', () => this.prepare())
            }
          } else {
            if (this.page === 'LIST') this.VersionList()
            else if (this.page === 'FORM') this.Composer()
            else if (this.page === 'READ') this.ReadingContent()
            else {
              if (this.progress !== undefined && !this.policy.terminal(this.progress.status)) {
                Text(this.progressText())
                  .fontSize(15).fontColor(AppTheme.textPrimary)
                this.SecondaryAction($r('app.string.adaptation_cancel'), !this.busy, () => this.cancel())
              }
              this.VersionDetail()
            }
          }
        }.width('100%').padding({ left: 24, right: 24, top: 4, bottom: 24 }).alignItems(HorizontalAlign.Start)
      }.width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off)
      if (this.page !== 'READ' && this.chapter !== undefined) this.Footer()
    }.width('100%').height('100%').backgroundColor(AppTheme.background)
  }
}
