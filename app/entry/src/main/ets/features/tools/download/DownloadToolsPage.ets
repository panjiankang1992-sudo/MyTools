import { DownloadReplyCard } from './DownloadReplyCard';
import { ToolPageHeader, ToolEmptyState } from '../ToolUi';
import { KeyboardAvoidMode } from '@ohos.arkui.UIContext';
import { common } from '@kit.AbilityKit';
import picker from '@ohos.file.picker';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import util from '@ohos.util';
import pasteboard from '@ohos.pasteboard';
import { LengthMetrics } from '@kit.ArkUI';
import { AppTheme } from '../../../theme/AppTheme';
import { AppActionGroup, AppButton, AppIconButton, AppTextAction } from '../../../components/AppControls';
import { AuthSessionManager } from '../../auth/AuthSessionManager';
import { DownloadToolsApi, DownloadMessage } from './DownloadToolsApi';
import { DownloadHistoryDataSource, DownloadHistoryEntry } from './DownloadHistoryDataSource';

/** 下载机器人会话：用户气泡与处理回复保持一一对应。 */
@Component
export struct DownloadToolsPage {
  @Prop @Watch('reset') baseUrl: string = '';
  @Prop @Watch('reset') accountKey: string = '';
  authManager: AuthSessionManager = new AuthSessionManager();
  onBack: () => void = () => {};
  @StorageLink('appForeground') @Watch('onForegroundChanged') appForeground: boolean = true;
  @State private messageCount: number = 0;
  @State draft: string = '';
  @State sending: boolean = false;
  @State attachments: boolean = false;
  @State pendingText: string = '';
  @State pendingId: string = '';
  @State notice: string = '';
  @State uploadPercent: number = -1;
  @State private composerWidth: number = 0;
  private clipboardVisible: boolean = false;
  private clipboardReading: boolean = false;
  private draftRevision: number = 0;
  private editorTapRevision: number = 0;
  private revision: number = 0;
  private timer: number = -1;
  private pendingKey: string = '';
  private pendingUri: string = '';
  private pendingName: string = '';
  @State private refreshing: boolean = false;
  @State private loadingOlder: boolean = false;
  @State private olderFailed: boolean = false;
  private scroller: Scroller = new Scroller();
  private historyRows: DownloadHistoryDataSource = new DownloadHistoryDataSource();
  private latest: Map<string, DownloadMessage> = new Map<string, DownloadMessage>();
  private older: Map<string, DownloadMessage> = new Map<string, DownloadMessage>();
  private recent: DownloadMessage[] = [];
  private historyCursor: string = '';
  private historyBoundaryId: string = '';
  private firstVisibleRow: number = -1;
  private loadedOnScroll: boolean = false;
  private historyReady: boolean = false;
  private olderStarted: boolean = false;
  private latestRevision: number = 0;

  private previousKeyboardMode?: KeyboardAvoidMode = undefined;
  /** 进入页面后恢复键盘避让并读取已有会话。 */
  aboutToAppear(): void {
    this.previousKeyboardMode = this.getUIContext().getKeyboardAvoidMode();
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.clipboardVisible = true;
    this.refresh();
  }
  /** 离开页面后停止轮询并恢复原键盘模式。 */
  aboutToDisappear(): void {
    this.clipboardVisible = false;
    if (this.previousKeyboardMode !== undefined) {
      this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardMode);
      this.previousKeyboardMode = undefined;
    }
    this.revision++;
    if (this.timer !== -1) clearTimeout(this.timer);
    this.timer = -1;
    this.refreshing = false;
    this.loadingOlder = false;
  }
  private reset(): void {
    this.aboutToDisappear(); this.latest.clear(); this.older.clear(); this.recent = []; this.historyCursor = '';
    this.historyBoundaryId = ''; this.firstVisibleRow = -1; this.loadedOnScroll = false;
    this.historyReady = false; this.olderStarted = false; this.loadingOlder = false; this.olderFailed = false;
    this.draft = ''; this.pendingText = '';
    this.pendingKey = ''; this.pendingId = ''; this.pendingUri = ''; this.pendingName = ''; this.sending = false;
    this.notice = ''; this.uploadPercent = -1; this.refreshing = false; this.syncHistory(); this.aboutToAppear();
  }
  /** 前后台切换使正在进行的手动粘贴失效，不自动读取剪贴板。 */
  private onForegroundChanged(): void {
    this.draftRevision++;
    if (this.appForeground && this.clipboardVisible) this.refresh();
  }
  /** 仅由系统粘贴按钮授权后调用，不申请受限剪贴板权限。 */
  private async pasteFromButton(): Promise<void> {
    if (!this.clipboardVisible || !this.appForeground || this.clipboardReading ||
      this.draft.length > 0 || this.sending || this.pendingKey || this.attachments) return;
    const revision = this.revision;
    this.clipboardReading = true;
    try {
      const board = pasteboard.getSystemPasteboard();
      const draftRevision = this.draftRevision;
      const editorTapRevision = this.editorTapRevision;
      const data = await board.getData();
      const text = data.getPrimaryText().trim().slice(0, 20000);
      // 读取期间输入、发送、切页或选文件后，不再采用旧结果。
      if (!text || revision !== this.revision || draftRevision !== this.draftRevision ||
        !this.clipboardVisible || !this.appForeground || this.draft.length > 0 ||
        this.sending || this.pendingKey || this.attachments) return;
      // 粘贴只改草稿。清除旧编辑焦点，输入框再次由用户点击时才开启键盘。
      if (editorTapRevision === this.editorTapRevision) this.getUIContext().getFocusController().clearFocus();
      this.draft = text;
    } catch (_) {
      // 剪贴板无权限或暂时不可用时，不影响聊天和手动粘贴。
    } finally {
      this.clipboardReading = false;
    }
  }
  private api(): DownloadToolsApi { return new DownloadToolsApi(this.baseUrl, this.authManager); }
  private label(value: Resource): string {
    try { return this.getUIContext().getHostContext()?.resourceManager.getStringSync(value.id) ?? ''; }
    catch (_) { return ''; }
  }
  private syncHistory(): void {
    const merged = new Map<string, DownloadMessage>(this.older);
    this.latest.forEach((row: DownloadMessage, id: string) => merged.set(id, row));
    const rows = Array.from(merged.values()).filter((row: DownloadMessage) => row.id !== this.pendingId);
    rows.sort((left: DownloadMessage, right: DownloadMessage) => {
      if (left.createdAt !== right.createdAt) return left.createdAt < right.createdAt ? -1 : 1;
      return left.id === right.id ? 0 : left.id < right.id ? -1 : 1;
    });
    this.historyRows.sync(rows);
    this.messageCount = rows.length;
  }
  private scrollToLatest(revision: number): void {
    setTimeout(() => {
      if (revision === this.revision) this.scroller.scrollEdge(Edge.Bottom);
    }, 0);
  }
  private preserveRecent(): void {
    // 读过历史后保留已浏览的连续页，避免新记录挤出首屏时留下空档。
    this.recent.forEach((row: DownloadMessage) => this.older.set(row.id, this.latest.get(row.id) ?? row));
  }
  private async refresh(): Promise<void> {
    if (this.refreshing || !this.appForeground) return;
    const revision = this.revision;
    const latestRevision = this.latestRevision;
    this.refreshing = true;
    try {
      const api = this.api();
      const page = await api.history();
      if (revision !== this.revision || latestRevision !== this.latestRevision) return;
      if (this.notice === this.label($r('app.string.download_chat_unavailable'))) this.notice = '';
      const followLatest = !this.historyReady || this.scroller.isAtEnd();
      const newLatest = page.items.length > 0 && !this.latest.has(page.items[0].id);
      if (this.olderStarted) this.preserveRecent();
      this.recent = page.items.slice(0, 10);
      if (!this.olderStarted) {
        this.historyCursor = page.nextCursor ?? '';
        this.historyBoundaryId = this.recent[this.recent.length - 1]?.id ?? '';
      }
      this.latest.clear();
      page.items.forEach((row: DownloadMessage) => {
        this.latest.set(row.id, row);
        if (this.older.has(row.id)) this.older.set(row.id, row);
      });
      this.syncHistory();
      if (this.messageCount > 0 && (!this.historyReady || (followLatest && newLatest))) this.scrollToLatest(revision);
      this.historyReady = true;
      // 已展开的旧记录结束后可能移出首屏集合，按需补查它们而不刷新整个历史。
      const settled = Array.from(this.older.values())
        .filter((row: DownloadMessage) => !this.latest.has(row.id) && api.isUnfinished(row)).slice(0, 4);
      await Promise.all(settled.map(async (row: DownloadMessage): Promise<void> => {
        try {
          const updated = await api.detail(row.id);
          if (revision === this.revision && !this.latest.has(row.id)) this.older.set(row.id, updated);
        } catch (_) {
          // 网络恢复后再随首屏轮询补查。
        }
      }));
      if (settled.length && revision === this.revision) this.syncHistory();
    } catch (_) { if (revision === this.revision) this.notice = this.label($r('app.string.download_chat_unavailable')); }
    finally {
      if (revision === this.revision) {
        this.refreshing = false;
        if (this.timer !== -1) clearTimeout(this.timer);
        this.timer = setTimeout(() => this.refresh(), latestRevision === this.latestRevision ? 5000 : 0);
      }
    }
  }
  private async loadOlder(): Promise<void> {
    if (!this.historyCursor || this.loadingOlder || !this.appForeground) return;
    const revision = this.revision;
    const cursor = this.historyCursor;
    this.olderStarted = true;
    this.preserveRecent();
    this.loadingOlder = true;
    this.olderFailed = false;
    try {
      const page = await this.api().history(cursor);
      if (revision !== this.revision) return;
      const anchorId = this.historyBoundaryId;
      const anchorIndex = this.historyRows.indexOf(anchorId);
      // 旧的未完成任务可能比锚点更早，保留连续历史的边界以免跳过新加载的一页。
      const keepAnchor = anchorIndex >= 0 && this.firstVisibleRow <= anchorIndex;
      const anchorRect = keepAnchor ? this.scroller.getItemRect(anchorIndex) : undefined;
      page.items.forEach((row: DownloadMessage) => this.older.set(row.id, this.latest.get(row.id) ?? row));
      this.historyCursor = page.nextCursor === cursor ? '' : page.nextCursor ?? '';
      this.historyBoundaryId = page.items[page.items.length - 1]?.id ?? '';
      this.syncHistory();
      if (keepAnchor) {
        const newIndex = this.historyRows.indexOf(anchorId);
        if (newIndex !== anchorIndex && newIndex >= 0) {
          const offset = anchorRect && anchorRect.height > 0 ? -Math.min(anchorRect.y, 64) : 0;
          this.scroller.scrollToIndex(newIndex, false, ScrollAlign.START, { extraOffset: LengthMetrics.vp(offset) });
        }
      }
    } catch (_) {
      if (revision === this.revision) this.olderFailed = true;
    } finally {
      if (revision === this.revision) this.loadingOlder = false;
    }
  }
  private async send(): Promise<void> {
    if (this.sending || (!this.draft.trim() && !this.pendingText)) return;
    this.draftRevision++;
    if (!this.pendingKey) {
      this.pendingText = this.draft.trim(); this.draft = '';
      this.pendingKey = util.generateRandomUUID();
    }
    await this.submit();
  }
  private async submit(): Promise<void> {
    const revision = this.revision; this.sending = true; this.notice = ''; this.attachments = false;
    try {
      const row = this.pendingUri ? await this.api().upload(this.pendingUri, this.pendingName, this.pendingKey,
        (value: number, id: string) => {
          if (revision === this.revision) {
            this.uploadPercent = value;
            if (this.pendingId !== id) {
              this.pendingId = id;
              this.syncHistory();
            }
          }
        },
        () => revision === this.revision) : await this.api().send(this.pendingText, this.pendingKey);
      if (revision !== this.revision) return;
      this.latestRevision++;
      this.latest.set(row.id, row);
      this.pendingKey = ''; this.pendingText = ''; this.pendingId = ''; this.pendingUri = ''; this.pendingName = '';
      this.uploadPercent = -1;
      this.syncHistory();
      this.scrollToLatest(revision);
      this.refresh();
    } catch (error) {
      if (revision === this.revision) this.notice = this.label((error as Error).message === 'DOWNLOAD_001' ?
        $r('app.string.download_chat_invalid') : $r('app.string.download_chat_failed'));
    } finally { if (revision === this.revision) this.sending = false; }
  }
  private async pick(media: boolean): Promise<void> {
    if (this.sending || this.pendingKey) return;
    this.draftRevision++;
    const revision = this.revision;
    try {
      let uris: string[] = [];
      if (media) {
        const options = new photoAccessHelper.PhotoSelectOptions(); options.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_VIDEO_TYPE;
        options.maxSelectNumber = 1;
        uris = (await new photoAccessHelper.PhotoViewPicker().select(options)).photoUris;
      } else {
        const options = new picker.DocumentSelectOptions(); options.maxSelectNumber = 1;
        uris = await new picker.DocumentViewPicker(this.getUIContext().getHostContext() as common.UIAbilityContext).select(options);
      }
      if (revision !== this.revision || !uris.length) return;
      this.pendingUri = uris[0];
      this.pendingName = decodeURIComponent(uris[0].split('/').pop() ?? 'upload.bin').split('?')[0];
      this.pendingText = this.pendingName; this.pendingKey = util.generateRandomUUID(); this.uploadPercent = 0;
      await this.submit();
    } catch (_) { if (revision === this.revision) this.notice = this.label($r('app.string.download_chat_failed')); }
  }
  private async cancel(row: DownloadMessage): Promise<void> {
    try { await this.api().cancel(row.id); await this.refresh(); }
    catch (_) { this.notice = this.label($r('app.string.download_chat_unavailable')); }
  }
  @Builder
  private Bubble(text: string, mine: boolean) {
    Row({ space: 8 }) {
      if (mine) Blank()
      if (!mine) {
        SymbolGlyph($r('sys.symbol.arrow_down')).fontSize(18).fontColor([AppTheme.primary]).width(36).height(36)
          .backgroundColor(AppTheme.accentSoft).borderRadius(10)
      }
      Text(text).fontSize(15).lineHeight(24).fontFamily(AppTheme.fontFamily).fontColor(AppTheme.textPrimary)
        .padding(12).borderRadius(16).backgroundColor(mine ? AppTheme.accentSoft : AppTheme.surface)
        .constraintSize({ maxWidth: '82%' }).copyOption(CopyOptions.LocalDevice)
      if (!mine) Blank()
    }.width('100%').alignItems(VerticalAlign.Top)
  }

  @Builder
  private AttachmentButton(title: Resource, icon: Resource, photos: boolean) {
    // 系统文件和相册选择保留图文入口，命中区域覆盖整张选择卡。
    Button({ type: ButtonType.Normal }) {
      Column({ space: 10 }) {
        SymbolGlyph(icon).fontSize(24).fontColor([AppTheme.primary])
        Text(title).fontSize(14).lineHeight(20).fontFamily(AppTheme.fontMedium).fontWeight(500)
          .fontColor(AppTheme.textPrimary)
      }.width('100%')
    }.constraintSize({ minHeight: 88 }).layoutWeight(1).padding(14).borderRadius(AppTheme.cardRadius)
      .backgroundColor(AppTheme.surfaceTint).enabled(!this.sending && !this.pendingKey)
      .accessibilityText(title).onClick(() => this.pick(photos))
  }

  private narrowComposer(): boolean { return this.composerWidth < 360; }

  @Builder
  private ComposerInput() {
    // 编辑器始终留在同一输入行，分行操作不会重建草稿或改变修改时序。
    TextArea({ text: this.draft, placeholder: $r('app.string.download_chat_placeholder') }).layoutWeight(1)
      .constraintSize({ minHeight: AppTheme.inputHeight, maxHeight: 128 }).fontSize(AppTheme.fieldFontSize).lineHeight(24)
      .fontFamily(AppTheme.fontFamily).maxLength(20000).padding(12).fontColor(AppTheme.textPrimary)
      .placeholderFont({ size: AppTheme.fieldFontSize, weight: 400, family: AppTheme.fontFamily })
      .placeholderColor(AppTheme.textTertiary).backgroundColor(AppTheme.surfaceTint).borderRadius(AppTheme.inputRadius)
      .enableKeyboardOnFocus(false).enabled(!this.pendingKey)
      .onClick(() => { this.editorTapRevision++; })
      .onChange((value: string) => { this.draftRevision++; this.draft = value; })
  }

  @Builder
  private ComposerActions() {
    if (!this.draft && !this.pendingKey && !this.sending) {
      // 安全粘贴按钮继续由系统授予本次读取权限，不替换成普通按钮。
      PasteButton({ text: PasteDescription.PASTE, buttonType: ButtonType.Normal })
        .width(64).height(AppTheme.inputHeight).fontSize(AppTheme.buttonFontSize)
        .fontFamily(AppTheme.fontMedium).borderRadius(AppTheme.inputRadius)
        .fontColor(AppTheme.textPrimary).backgroundColor(AppTheme.surfaceTint)
        .onClick((_event: ClickEvent, result: PasteButtonOnClickResult) => {
          if (result === PasteButtonOnClickResult.SUCCESS) this.pasteFromButton();
        })
    }
    AppButton({ label: $r('app.string.download_chat_send'), loading: this.sending, fieldAligned: true,
      available: !this.sending && !this.pendingKey && this.draft.trim().length > 0, action: () => this.send() })
  }

  /** 构建会话、处理回复和附件输入区域。 */
  build() {
    Column() {
      Row({ space: 8 }) {
        ToolPageHeader({ title: $r('app.string.download_chat_title'), onBack: () => this.onBack() }).layoutWeight(1)
        AppTextAction({ label: $r('app.string.download_chat_refresh'), loading: this.refreshing,
          available: !this.refreshing, action: () => this.refresh() })
      }.width('100%').padding({ left: 20, right: 20, top: 8, bottom: 8 })
      Stack({ alignContent: Alignment.Top }) {
        List({ scroller: this.scroller, space: 16 }) {
          if (this.messageCount === 0 && !this.pendingText) {
            ListItem() {
              if (this.refreshing) {
                Column() {
                  LoadingProgress().width(32).height(32).color(AppTheme.primary)
                    .accessibilityText($r('app.string.download_chat_refresh'))
                }.width('100%').padding({ top: 40, bottom: 40 })
              } else {
                ToolEmptyState({ icon: $r('sys.symbol.arrow_down'), title: $r('app.string.tool_ui_download_intro'),
                  description: $r('app.string.tool_ui_download_hint') })
              }
            }
          }
          LazyForEach(this.historyRows, (entry: DownloadHistoryEntry) => {
            ListItem() {
              Column({ space: 12 }) {
                Text(new Date(entry.row.createdAt).toLocaleString()).fontSize(12).lineHeight(18)
                  .fontFamily(AppTheme.fontFamily).fontColor(AppTheme.textTertiary)
                this.Bubble(entry.row.name, true)
                DownloadReplyCard({ row: entry.row, onCancel: () => this.cancel(entry.row) })
              }.width('100%')
            }
          }, (entry: DownloadHistoryEntry) => entry.key)
          if (this.pendingText) {
            ListItem() {
              Column({ space: 16 }) {
                this.Bubble(this.pendingText, true)
                if (this.sending) {
                  this.Bubble(this.label($r('app.string.download_chat_sending')), false)
                  if (this.uploadPercent >= 0) {
                    Column({ space: 6 }) {
                      Progress({ value: this.uploadPercent, total: 100, type: ProgressType.Linear })
                        .width('100%').color(AppTheme.primary).backgroundColor(AppTheme.progressTrack)
                      Text(`${this.uploadPercent}%`).fontSize(12).lineHeight(18)
                        .fontFamily(AppTheme.fontFamily).fontColor(AppTheme.textSecondary)
                    }.width('100%')
                  }
                } else {
                  this.Bubble(this.label($r('app.string.download_chat_retry_hint')), false)
                  AppActionGroup({ primaryLabel: $r('app.string.download_chat_retry'),
                    secondaryLabel: $r('app.string.download_chat_discard'), primaryAction: () => this.submit(),
                    secondaryAction: () => {
                      this.pendingKey = ''; this.pendingText = ''; this.pendingId = ''; this.pendingUri = '';
                      this.pendingName = ''; this.uploadPercent = -1; this.syncHistory();
                    } })
                }
              }
            }
          }
          if (this.notice) {
            ListItem() {
              Text(this.notice).width('100%').fontSize(14).lineHeight(22).fontFamily(AppTheme.fontFamily)
                .fontColor(AppTheme.danger).padding(14).backgroundColor(AppTheme.dangerSoft).borderRadius(12)
            }
          }
        }.width('100%').height('100%').padding(20).cachedCount(2).maintainVisibleContentPosition(true)
          .edgeEffect(EdgeEffect.Spring, { alwaysEnabled: true })
          .onScrollIndex((first: number) => this.firstVisibleRow = first)
          .onScrollStart(() => this.loadedOnScroll = false)
          .onScrollFrameBegin((offset: number, state: ScrollState) => {
            // 只有用户向旧记录方向滑动时才读取下一页，首屏定位和刷新不会触发翻页。
            if ((state === ScrollState.Scroll || state === ScrollState.Fling) && offset < -0.5 &&
              this.historyCursor && !this.loadedOnScroll && !this.loadingOlder && !this.olderFailed &&
              this.firstVisibleRow >= 0 && this.firstVisibleRow <= this.historyRows.indexOf(this.historyBoundaryId)) {
              this.loadedOnScroll = true;
              this.loadOlder();
            }
            return { offsetRemain: offset };
          })
        if (this.loadingOlder) {
          LoadingProgress().width(24).height(24).color(AppTheme.primary).margin({ top: 8 })
            .hitTestBehavior(HitTestMode.None).accessibilityText($r('app.string.download_chat_refresh'))
        } else if (this.olderFailed) {
          AppTextAction({ label: $r('app.string.download_chat_retry'), action: () => this.loadOlder() })
            .backgroundColor(AppTheme.surface).borderRadius(AppTheme.inputRadius).margin({ top: 8 })
        }
      }.width('100%').layoutWeight(1)
      Column({ space: 8 }) {
        Row({ space: 8 }) {
          AppIconButton({ icon: $r('sys.symbol.plus'), description: this.label($r('app.string.download_chat_attach')),
            available: !this.sending && !this.pendingKey, selected: this.attachments, fieldAligned: true,
            action: () => this.attachments = !this.attachments })
          this.ComposerInput()
          if (!this.narrowComposer()) {
            this.ComposerActions()
          }
        }.width('100%').alignItems(VerticalAlign.Bottom)
        if (this.narrowComposer()) {
          // 窄窗口把操作放到下一行，给输入框保留可读宽度，避免占位文字被挤成竖排。
          Row({ space: 8 }) {
            this.ComposerActions()
          }.width('100%').justifyContent(FlexAlign.End).alignItems(VerticalAlign.Bottom)
        }
      }.width('100%')
        .padding({ left: 16, right: 16, top: 8, bottom: 12 }).backgroundColor(AppTheme.surface)
        .onAreaChange((_oldArea: Area, area: Area) => {
          // 仅按实际可用宽度切换布局，不读取设备屏幕宽度，也不在测量阶段回写状态。
          const width = Math.round(Number(area.width));
          if (Number.isFinite(width) && width > 0 && width !== this.composerWidth) this.composerWidth = width;
        })
      if (this.attachments) {
        Row({ space: 12 }) {
          this.AttachmentButton($r('app.string.download_chat_photos'), $r('sys.symbol.picture'), true)
          this.AttachmentButton($r('app.string.download_chat_files'), $r('sys.symbol.folder_fill'), false)
        }.width('100%').padding(16).backgroundColor(AppTheme.surface)
      }
    }.width('100%').height('100%').backgroundColor(AppTheme.background)
  }
}
