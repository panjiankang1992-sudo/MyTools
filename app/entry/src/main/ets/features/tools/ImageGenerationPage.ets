import { AppTheme } from '../../theme/AppTheme';
import { ToolConfirmDialog, ToolEmptyState } from './ToolUi';
import { AppButton, AppGroupedButton, AppEqualActionRow, AppTextAction, AppIconButton, AppFilterChip, AppSegmentItem, AppSearchField, AppGlassTextInput, AppGlassTextArea } from '../../components/AppControls';
import { common } from '@kit.AbilityKit';
import picker from '@ohos.file.picker';
import fs from '@ohos.file.fs';
import util from '@ohos.util';
import window from '@ohos.window';
import { ImageReferencePreparation } from './ImageReferencePreparation';
import { AuthSessionManager } from '../auth/AuthSessionManager';
import { ImageGenerationApi, ImageModel, ImageJob, ImageRequest, GeneratedImage, ImageStyle, ImageStyleWrite } from './ImageGenerationApi';

interface ImagePreview { key: string; uri: string; }

/** 图片工作台：输入、能力选择、异步任务与作品复用。 */
@Component
export struct ImageGenerationPage {
  @Prop @Watch('resetAccount') baseUrl: string = '';
  @Prop @Watch('resetAccount') accountKey: string = '';
  authManager: AuthSessionManager = new AuthSessionManager();
  onBack: () => void = () => {};
  @State styles: ImageStyle[] = [];
  @State chosenStyle: ImageStyle | undefined = undefined;
  @State styleGroup: string = 'BUILTIN';
  @State stylePanel: boolean = false;
  @State styleSearch: string = '';
  @State styleCategory: string = 'ALL';
  @State styleDraft: ImageStyle | undefined = undefined;
  @State styleDetail: boolean = false;
  @State styleLoading: boolean = false;
  @State stylePromptVisible: boolean = false;
  @State styleEditor: boolean = false;
  @State styleName: string = '';
  @State styleTemplate: string = '{prompt}. ';
  @State styleEditingId: string = '';
  @State styleEditingVersion: number = 0;
  @State styleMessage: string = '';
  @State styleBusy: boolean = false;
  private styleSourceJobId: string | undefined = undefined;
  private styleLoadSequence: number = 0;
  private styleCreateId: string = '';
  @State prompt: string = '';
  @State seedText: string = '';
  @State models: ImageModel[] = [];
  @State catalogLoading: boolean = true;
  @State catalogFailed: boolean = false;
  private catalogRead: boolean = false;
  @State modelId: string = 'krea2-local';
  @State mode: string = 'TEXT_TO_IMAGE';
  @State imageSize: string = '1024x1024';
  @State count: number = 1;
  @State references: string[] = [];
  @State jobs: ImageJob[] = [];
  @State previews: ImagePreview[] = [];
  @State message: string = '';
  @State busy: boolean = false;
  @State tab: string = 'create';
  @State page: number = 0;
  @State showAdvanced: boolean = false;
  @State showModels: boolean = false;
  @State viewerVisible: boolean = false;
  @State viewerUri: string = '';
  @State viewerScale: number = 1;
  @State viewerX: number = 0;
  @State viewerY: number = 0;
  @State viewerWidth: number = 0;
  @State viewerHeight: number = 0;
  @State viewerAspect: number = 1;
  @State overlayTopInset: number = 0;
  @State overlayBottomInset: number = 0;
  private overlayWindow: window.Window | undefined = undefined;
  private overlayWatchSequence: number = 0;
  private overlayAvoidAreaChanged: (data: window.AvoidAreaOptions) => void = () => this.readOverlayInsets();
  private pinchStart: number = 1;
  private panStartX: number = 0;
  private panStartY: number = 0;
  private revision: number = 0;
  private timer: number = -1;
  private refreshSequence: number = 0;
  private refreshMessage: string = '';
  private pending: ImageRequest | undefined = undefined;
  @State confirmationTitle: string = '';
  @State confirmationMessage: string = '';
  @State confirmationLabel: string = '';
  @State confirmationDangerous: boolean = false;
  private confirmationAction: () => void = () => {};
  private confirmationController: CustomDialogController = new CustomDialogController({
    builder: ToolConfirmDialog({ title: this.confirmationTitle, message: this.confirmationMessage,
      confirmLabel: this.confirmationLabel, cancelLabel: $r('app.string.image_style_cancel'),
      dangerous: this.confirmationDangerous, confirmed: () => this.finishConfirmation() }),
    autoCancel: true, alignment: DialogAlignment.Center, customStyle: true
  });

  /** 进入页面时读取当前账号的创作能力与作品。 */
  aboutToAppear(): void { this.watchOverlayInsets(); this.refresh(); }
  /** 离开页面时释放当前会话的轮询和媒体缓存。 */
  aboutToDisappear(): void {
    this.confirmationController.close(); this.confirmationAction = () => {};
    this.overlayWatchSequence++;
    if (this.overlayWindow !== undefined) {
      try { this.overlayWindow.off('avoidAreaChange', this.overlayAvoidAreaChanged); } catch (_) {}
      this.overlayWindow = undefined;
    }
    this.viewerVisible = false; this.viewerUri = ';';
    this.revision++;
    if (this.timer !== -1) clearTimeout(this.timer);
    this.timer = -1;
    this.clearPreviews();
  }
  private resetAccount(): void {
    this.aboutToDisappear();
    this.styleSearch = ''; this.styleCategory = 'ALL'; this.styleDraft = undefined; this.styleDetail = false; this.styleLoading = false;
    this.styles = []; this.chosenStyle = undefined; this.styleSourceJobId = undefined;
    this.styleEditor = false; this.stylePanel = false; this.styleName = ''; this.styleTemplate = '{prompt}. ';
    this.styleEditingId = ''; this.styleEditingVersion = 0; this.styleCreateId = ''; this.styleMessage = ''; this.styleBusy = false;
    this.prompt = ''; this.seedText = ''; this.references = []; this.jobs = []; this.models = [];
    this.catalogLoading = true; this.catalogFailed = false; this.catalogRead = false;
    this.pending = undefined; this.busy = false; this.page = 0; this.message = ''; this.refreshMessage = '';
    this.watchOverlayInsets();
    this.refresh();
  }
  private async watchOverlayInsets(): Promise<void> {
    const revision = this.revision;
    const context = this.getUIContext().getHostContext();
    if (context === undefined || this.overlayWindow !== undefined) return;
    const sequence = ++this.overlayWatchSequence;
    try {
      const current = await window.getLastWindow(context);
      if (revision !== this.revision || sequence !== this.overlayWatchSequence) return;
      this.overlayWindow = current; this.readOverlayInsets();
      current.on('avoidAreaChange', this.overlayAvoidAreaChanged);
    } catch (_) {}
  }
  private readOverlayInsets(): void {
    if (this.overlayWindow === undefined) return;
    try {
      const system = this.overlayWindow.getWindowAvoidArea(window.AvoidAreaType.TYPE_SYSTEM);
      const cutout = this.overlayWindow.getWindowAvoidArea(window.AvoidAreaType.TYPE_CUTOUT);
      const navigation = this.overlayWindow.getWindowAvoidArea(window.AvoidAreaType.TYPE_NAVIGATION_INDICATOR);
      // 全屏弹层按真实安全区留白，旋转屏幕或切换导航方式后同步更新。
      this.overlayTopInset = this.getUIContext().px2vp(Math.max(system.topRect.height, cutout.topRect.height));
      this.overlayBottomInset = this.getUIContext().px2vp(Math.max(system.bottomRect.height, navigation.bottomRect.height));
    } catch (_) {}
  }
  private imageLabel(resource: Resource): string {
    try { return this.getUIContext().getHostContext()?.resourceManager.getStringSync(resource.id) ?? ''; }
    catch (_) { return ''; }
  }
  private api(): ImageGenerationApi { return new ImageGenerationApi(this.baseUrl, this.authManager); }
  private selected(): ImageModel | undefined { return this.models.find((item: ImageModel) => item.id === this.modelId); }
  private ready(): boolean { return this.selected()?.status === 'READY' && (this.selected()?.modes.includes(this.mode) ?? false); }
  private active(job: ImageJob): boolean { return ['QUEUED', 'RUNNING', 'PERSISTING', 'CANCEL_REQUESTED'].includes(job.status); }
  private status(value: string): string {
    if (value === 'QUEUED') return '排队中';
    if (value === 'RUNNING') return '正在生成';
    if (value === 'PERSISTING') return '正在保存';
    if (value === 'CANCEL_REQUESTED') return '正在取消';
    if (value === 'UNCONFIRMED') return '结果待核对';
    if (value === 'CANCELLED') return '已取消';
    if (value === 'SUCCEEDED') return '已完成';
    if (value === 'PARTIAL_SUCCESS') return '部分完成';
    return '生成失败';
  }
  private async refresh(): Promise<void> {
    const revision = this.revision;
    const sequence = ++this.refreshSequence;
    if (this.timer !== -1) clearTimeout(this.timer);
    // 首次读取与后台刷新区分，已有作品不会因刷新闪空。
    this.catalogLoading = !this.catalogRead;
    try {
      const api = this.api();
      const models = await api.models();
      const jobs = await api.works(this.page);
      if (revision !== this.revision || sequence !== this.refreshSequence) return;
      this.models = models; this.jobs = jobs;
      this.catalogFailed = false;
      // 连接恢复时只清除网络错误，不覆盖上传或保存的操作反馈。
      if (this.refreshMessage.length > 0 && this.message === this.refreshMessage) this.message = '';
      this.refreshMessage = '';
      for (const job of jobs) {
        for (const item of job.result.images ?? []) {
          if (revision !== this.revision || sequence !== this.refreshSequence) return;
          await this.preview(job, item, revision);
        }
      }
    } catch (error) {
      if (revision === this.revision && sequence === this.refreshSequence) {
        this.message = '图片服务暂不可用，请稍后刷新；不会自动切换模型。';
        this.refreshMessage = this.message;
        this.catalogFailed = true;
      }
    } finally {
      if (revision === this.revision && sequence === this.refreshSequence) {
        this.catalogLoading = false;
        this.catalogRead = true;
        this.timer = setTimeout(() => this.refresh(), 5000);
      }
    }
  }
  private clearPreviews(): void {
    for (const preview of this.previews) { try { fs.unlinkSync(preview.uri); } catch (_) {} }
    this.previews = [];
  }
  private async preview(job: ImageJob, item: GeneratedImage, revision: number): Promise<void> {
    const key = `${job.id}-${item.index}`;
    if (this.previews.some((value: ImagePreview) => value.key === key)) return;
    const context = this.getUIContext().getHostContext();
    if (context === undefined) return;
    const path = `${context.cacheDir}/image-generation-${util.generateRandomUUID()}.png`;
    try {
      await this.api().download(job.id, item.index, path);
      if (revision !== this.revision) { try { fs.unlinkSync(path); } catch (_) {} return; }
      this.previews = this.previews.concat([{ key: key, uri: path }]);
    } catch (_) { try { fs.unlinkSync(path); } catch (_) {} }
  }
  private previewUri(job: ImageJob, item: GeneratedImage): string {
    const path = this.previews.find((value: ImagePreview) => value.key === `${job.id}-${item.index}`)?.uri ?? '';
    return path.length > 0 ? `file://${path}` : '';
  }
  private async upload(): Promise<void> {
    if (this.busy || this.references.length >= (this.mode === 'IMAGE_TO_IMAGE' || this.mode === 'IMAGE_TO_PROMPT' ? 1 : this.selected()?.maxReferences ?? 0)) return;
    const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
    const revision = this.revision;
    this.busy = true; this.message = '';
    try {
      const options = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 1; options.fileSuffixFilters = ['PNG / JPEG|png,jpg,jpeg'];
      const selected = await new picker.DocumentViewPicker(context).select(options);
      if (revision !== this.revision || selected.length === 0) return;
      const file = fs.openSync(selected[0], fs.OpenMode.READ_ONLY);
      let encoded: string = '';
      let resized: boolean = false;
      try {
        const prepared = await ImageReferencePreparation.prepare(file.fd);
        if (revision !== this.revision) return;
        resized = prepared.resized;
        encoded = new util.Base64Helper().encodeToStringSync(new Uint8Array(prepared.bytes));
      } finally { fs.closeSync(file); }
      const id = await this.api().upload(encoded);
      if (revision !== this.revision) return;
      this.references = this.references.concat([id]).slice(0, this.selected()?.maxReferences ?? 1); this.pending = undefined; this.message = resized ? '已添加底稿，已自动适配生成尺寸，原图不变' : '已添加底稿';
    } catch (error) {
      if (revision === this.revision) this.message = error instanceof Error ? error.message : '底稿上传失败';
    } finally { if (revision === this.revision) this.busy = false; }
  }
  private styleEnabled(): boolean {
    return this.modelId === 'krea2-local' && (this.mode === 'TEXT_TO_IMAGE' || this.mode === 'IMAGE_TO_IMAGE');
  }
  private styleLabel(style: ImageStyle): string {
    if (style.origin !== 'BUILTIN') return style.name;
    try {
      return this.getUIContext().getHostContext()?.resourceManager.getStringByNameSync(`image_style_name_${style.id.replace(/-/g, '_')}`) ?? style.name;
    } catch (_) { return style.name; }
  }
  private combinedPrompt(): string {
    return this.chosenStyle === undefined || !this.styleEnabled() ? this.prompt.trim() :
      this.chosenStyle.promptTemplate.split('{prompt}').join(this.prompt.trim());
  }
  private async loadStyles(): Promise<void> {
    const revision = this.revision;
    const sequence = ++this.styleLoadSequence; this.styleLoading = true;
    try {
      const styles = await this.api().styles();
      if (revision !== this.revision || sequence !== this.styleLoadSequence) return;
      this.styles = styles; this.styleMessage = '';
    } catch (_) {
      if (revision === this.revision && sequence === this.styleLoadSequence) this.styleMessage = this.imageLabel($r('app.string.image_style_load_failed'));
    } finally { if (revision === this.revision && sequence === this.styleLoadSequence) this.styleLoading = false; }
  }
  private chooseStyle(style: ImageStyle | undefined): void {
    if (this.busy || this.styleBusy) return;
    this.chosenStyle = style; this.styleSourceJobId = undefined; this.pending = undefined;
  }
  private editStyle(style?: ImageStyle, copy: boolean = false): void {
    if (this.busy || this.styleBusy) return;
    this.styleEditingId = !copy && style?.origin === 'USER' ? style.id : '';
    this.styleEditingVersion = this.styleEditingId.length > 0 ? style?.version ?? 0 : 0;
    this.styleCreateId = util.generateRandomUUID();
    this.styleName = style === undefined ? '' : this.styleLabel(style);
    this.styleTemplate = style?.promptTemplate ?? '{prompt}. ';
    this.styleMessage = ''; this.styleEditor = true; this.stylePanel = true; this.styleDetail = false;
  }
  private validStyleTemplate(): boolean {
    const parts = this.styleTemplate.split('{prompt}');
    return parts.length === 2 && !parts.join('').includes('{') && !parts.join('').includes('}') &&
      this.styleTemplate.trim().length > 0 && this.styleTemplate.length <= 2000 &&
      this.styleName.trim().length > 0 && this.styleName.length <= 80;
  }
  private async saveStyle(): Promise<void> {
    if (this.styleBusy || !this.validStyleTemplate()) return;
    const revision = this.revision;
    const body: ImageStyleWrite = { name: this.styleName.trim(), promptTemplate: this.styleTemplate.trim() };
    if (this.styleEditingVersion > 0) body.expectedVersion = this.styleEditingVersion;
    const id = this.styleEditingId.length > 0 ? this.styleEditingId : this.styleCreateId;
    this.styleBusy = true; this.styleLoading = false; this.styleLoadSequence++;
    try {
      const style = await this.api().saveStyle(id, body);
      if (revision !== this.revision) return;
      this.styles = this.styles.filter((item: ImageStyle) => item.id !== style.id).concat([style]);
      this.chosenStyle = style; this.styleSourceJobId = undefined; this.pending = undefined;
      this.styleEditor = false; this.stylePanel = false; this.styleDraft = style; this.styleCategory = 'USER'; this.styleGroup = 'USER'; this.styleMessage = this.imageLabel($r('app.string.image_style_saved'));
    } catch (_) {
      if (revision === this.revision) this.styleMessage = this.imageLabel($r('app.string.image_style_save_failed'));
    } finally { if (revision === this.revision) this.styleBusy = false; }
  }
  private confirmDeleteStyle(style: ImageStyle): void {
    this.confirmChange(this.imageLabel($r('app.string.image_style_delete')),
      this.imageLabel($r('app.string.image_style_delete_notice')),
      this.imageLabel($r('app.string.image_style_delete')), true, () => this.deleteStyle(style));
  }
  private confirmChange(title: string, message: string, label: string, dangerous: boolean,
    action: () => void): void {
    if (this.busy || this.styleBusy) return;
    const revision = this.revision;
    this.confirmationTitle = title; this.confirmationMessage = message;
    this.confirmationLabel = label; this.confirmationDangerous = dangerous;
    // 账号或页面已切换时，旧确认不会改写新的创作输入。
    this.confirmationAction = () => { if (revision === this.revision && !this.busy && !this.styleBusy) action(); };
    this.confirmationController.open();
  }
  private finishConfirmation(): void {
    const action = this.confirmationAction;
    // 先清空待执行动作，连续激活确认也只会执行一次。
    this.confirmationAction = () => {};
    action();
  }
  private async deleteStyle(style: ImageStyle): Promise<void> {
    if (this.styleBusy || this.busy) return;
    const revision = this.revision; this.styleBusy = true; this.styleLoading = false; this.styleLoadSequence++;
    try {
      await this.api().deleteStyle(style.id);
      if (revision !== this.revision) return;
      this.styles = this.styles.filter((item: ImageStyle) => item.id !== style.id);
      if (this.chosenStyle?.id === style.id) { this.chosenStyle = undefined; this.styleSourceJobId = undefined; this.pending = undefined; }
      this.styleDraft = this.chosenStyle; this.styleDetail = false; this.styleMessage = this.imageLabel($r('app.string.image_style_deleted'));
    } catch (_) {
      if (revision === this.revision) this.styleMessage = this.imageLabel($r('app.string.image_style_save_failed'));
    } finally { if (revision === this.revision) this.styleBusy = false; }
  }
  private restoreStyle(job: ImageJob): void {
    this.chosenStyle = job.request.styleSnapshot;
    this.styleSourceJobId = this.chosenStyle === undefined ? undefined : job.id;
  }
  private async generate(): Promise<void> {
    if (this.styleBusy || this.busy || !this.ready() || (this.mode !== 'IMAGE_TO_PROMPT' && this.prompt.trim().length === 0)) return;
    if (this.mode !== 'TEXT_TO_IMAGE' && this.references.length === 0) return;
    if (this.seedText.length > 0 && (!/^\d{1,10}$/.test(this.seedText) || Number(this.seedText) > 2147483647)) {
      this.message = '种子请输入 0 至 2147483647 的整数，或留空随机生成'; return;
    }
    if (this.combinedPrompt().length > 4000) { this.message = this.imageLabel($r('app.string.image_style_too_long')); return; }
    const revision = this.revision;
    this.busy = true; this.message = '';
    if (this.pending === undefined) {
      this.pending = { resourceId: this.modelId, prompt: this.mode === 'IMAGE_TO_PROMPT' ? 'Describe the image as a generation prompt.' : this.prompt.trim(), mode: this.mode,
        references: this.references.slice(), size: this.imageSize, count: this.count,
        seed: this.seedText.length > 0 ? Number(this.seedText) : Math.floor(Math.random() * 2147483648), idempotencyKey: util.generateRandomUUID() };
      if (this.styleEnabled() && this.chosenStyle !== undefined) {
        this.pending.styleId = this.chosenStyle.id; this.pending.styleVersion = this.chosenStyle.version;
        this.pending.styleSourceJobId = this.styleSourceJobId;
      }
    }
    try {
      await this.api().create(this.pending);
      if (revision !== this.revision) return;
      this.pending = undefined; this.page = 0; this.tab = 'works';
      await this.refresh();
    } catch (_) {
      if (revision === this.revision) this.message = '提交未确认，重试将使用相同任务编号，避免重复生成。';
    } finally { if (revision === this.revision) this.busy = false; }
  }
  private async cancel(job: ImageJob): Promise<void> {
    const revision = this.revision;
    try { await this.api().cancel(job.id); if (revision === this.revision) await this.refresh(); }
    catch (_) { if (revision === this.revision) this.message = '取消尚未确认，请刷新后重试'; }
  }
  private async save(job: ImageJob, item: GeneratedImage): Promise<void> {
    const revision = this.revision;
    try {
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const options = new picker.DocumentSaveOptions(); options.newFileNames = [`image-${item.index + 1}.png`];
      const targets = await new picker.DocumentViewPicker(context).save(options);
      if (revision !== this.revision || targets.length === 0) return;
      await this.api().download(job.id, item.index, targets[0]);
      if (revision === this.revision) this.message = '图片已保存';
    } catch (_) { if (revision === this.revision) this.message = '保存失败，请重试'; }
  }
  private selectMode(mode: string): void {
    if (this.mode === mode) return;
    if (mode === 'TEXT_TO_IMAGE' && this.references.length > 0) {
      this.confirmChange('切换创作方式', '文字生成不会使用底稿，切换后将清除当前底稿。', '切换', false, () => {
          this.mode = mode; this.references = []; this.pending = undefined;
        });
    } else {
      this.mode = mode;
      if (mode === 'IMAGE_TO_IMAGE' || mode === 'IMAGE_TO_PROMPT') this.references = this.references.slice(0, 1);
      this.pending = undefined;
    }
  }
  private selectModel(model: ImageModel): void {
    if (this.modelId === model.id) return;
    const changes = (this.references.length > model.maxReferences) || !model.modes.includes(this.mode) ||
      !model.sizes.includes(this.imageSize) || !model.counts.includes(this.count) ||
      (model.location !== 'LOCAL' && this.seedText.length > 0);
    if (changes) {
      this.confirmChange('切换生成模型',
        '目标模型不支持部分当前参数。切换后会调整尺寸和数量，清除不支持的底稿或种子；文字描述会保留。',
        '切换', false, () => this.applyModel(model));
    } else { this.applyModel(model); }
  }
  private applyModel(model: ImageModel): void {
    this.modelId = model.id;
    this.chosenStyle = undefined; this.styleSourceJobId = undefined;
    if (!model.modes.includes(this.mode)) this.mode = model.modes[0] ?? 'TEXT_TO_IMAGE';
    if (this.mode === 'IMAGE_TO_IMAGE' || this.mode === 'IMAGE_TO_PROMPT') this.references = this.references.slice(0, 1);
    if (this.mode === 'TEXT_TO_IMAGE' || this.references.length > model.maxReferences) this.references = [];
    if (!model.sizes.includes(this.imageSize)) this.imageSize = model.sizes[0] ?? '1024x1024';
    if (!model.counts.includes(this.count)) this.count = model.counts[0] ?? 1;
    if (model.location !== 'LOCAL') this.seedText = '';
    this.pending = undefined;
  }
  private async useReference(job: ImageJob, item: GeneratedImage): Promise<void> {
    const revision = this.revision;
    const model = this.models.find((value: ImageModel) => value.status === 'READY' && (value.modes.includes('IMAGE_TO_IMAGE') || value.modes.includes('STYLE_REFERENCE')));
    if (model === undefined) { this.message = '当前没有通过底稿参考验收的模型'; return; }
    try {
      const id = await this.api().reference(job.id, item.index);
      if (revision !== this.revision) return;
      this.modelId = model.id; this.mode = model.modes.includes('IMAGE_TO_IMAGE') ? 'IMAGE_TO_IMAGE' : 'STYLE_REFERENCE'; this.references = [id];
      this.restoreStyle(job);
      this.prompt = job.request.prompt; this.imageSize = model.sizes[0]; this.count = 1;
      this.pending = undefined; this.tab = 'create';
    } catch (_) { if (revision === this.revision) this.message = '底稿创建失败，请重试'; }
  }
  private elapsedLabel(job: ImageJob): string {
    if (job.elapsedMillis === undefined) return '耗时待同步';
    const seconds = Math.floor(Math.max(0, job.elapsedMillis) / 1000);
    const duration = seconds < 60 ? `${seconds}秒` : seconds < 3600 ?
      `${Math.floor(seconds / 60)}分${seconds % 60}秒` : `${Math.floor(seconds / 3600)}小时${Math.floor(seconds % 3600 / 60)}分`;
    return `${this.active(job) ? '已用时' : '总耗时'} ${duration}`;
  }
  private modelName(id: string): string {
    if (id === 'vision-local') return this.imageLabel($r('app.string.image_mode_prompt'));
    return this.models.find((model: ImageModel) => model.id === id)?.name ?? '图片模型';
  }
  private useExtractedPrompt(job: ImageJob): void {
    if (!job.result.prompt || this.busy) return;
    const model = this.models.find((item: ImageModel) => item.status === 'READY' && item.modes.includes('TEXT_TO_IMAGE')) ??
      this.models.find((item: ImageModel) => item.modes.includes('TEXT_TO_IMAGE'));
    if (model !== undefined) this.applyModel(model);
    this.chosenStyle = undefined; this.styleSourceJobId = undefined;
    this.prompt = job.result.prompt; this.mode = 'TEXT_TO_IMAGE'; this.references = [];
    this.pending = undefined; this.tab = 'create';
  }
  private reuseParameters(job: ImageJob): void {
    this.restoreStyle(job);
    this.prompt = job.request.prompt; this.modelId = job.request.resourceId; this.mode = job.request.mode;
    this.references = job.request.references.slice(); this.imageSize = job.request.size; this.count = job.request.count;
    this.seedText = `${job.request.seed}`; this.pending = undefined; this.tab = 'create';
  }
  @Builder
  private QuietButton(label: ResourceStr, action: () => void) {
    AppTextAction({ label: label, action: action })
  }
  @Builder
  private ReferenceUploadButton() {
    AppButton({
      label: `${this.imageLabel($r('app.string.image_reference_upload'))} ${this.references.length} ${this.imageLabel($r('app.string.image_reference_limit'))}`,
      variant: 'secondary', available: !this.busy,
      action: () => this.upload()
    })
  }
  @Builder
  private Choice(label: string, value: string, group: string) {
    // 在构建器内直接读取状态，避免传入布尔快照后选中样式不再刷新。
    AppFilterChip({ label: label, available: !this.busy,
      selected: group === 'size' ? this.imageSize === value : group === 'count' ? this.count === Number(value) : this.mode === value,
      action: () => {
        if (group === 'size') { this.imageSize = value; this.pending = undefined; }
        else if (group === 'count') { this.count = Number(value); this.pending = undefined; }
        else { this.selectMode(value); }
      }
    }).margin({ right: 8 })
  }
  private openStyleLibrary(): void {
    if (this.busy || this.styleBusy) return;
    this.styleDraft = this.chosenStyle; this.styleSearch = ''; this.styleCategory = 'ALL';
    this.styleDetail = false; this.styleEditor = false; this.stylePromptVisible = false;
    this.styleMessage = ''; this.stylePanel = true; this.loadStyles();
  }
  private styleFamily(style: ImageStyle): string {
    if (style.origin !== 'BUILTIN') return 'USER';
    if (['photographic', 'analog-film', 'cinematic', 'food-photo'].includes(style.id)) return 'PHOTO';
    if (['watercolor', 'impressionist', 'pointillism', 'surrealist'].includes(style.id)) return 'PAINT';
    if (['anime', 'comic', 'line-art', 'digital-art', 'fantasy', 'pixel'].includes(style.id)) return 'ILLUSTRATION';
    if (['isometric', 'low-poly', 'clay', 'origami', 'paper-cut'].includes(style.id)) return 'CRAFT';
    return 'DESIGN';
  }
  private familyLabel(value: string): string {
    try { return this.getUIContext().getHostContext()?.resourceManager.getStringByNameSync(`image_style_category_${value.toLowerCase()}`) ?? value; }
    catch (_) { return value; }
  }
  private styleDescription(style: ImageStyle): string {
    if (style.origin !== 'BUILTIN') return this.imageLabel($r('app.string.image_style_custom_description'));
    try { return this.getUIContext().getHostContext()?.resourceManager.getStringByNameSync(`image_style_desc_${style.id.replace(/-/g, '_')}`) ?? this.familyLabel(this.styleFamily(style)); }
    catch (_) { return this.familyLabel(this.styleFamily(style)); }
  }
  private styleIcon(style?: ImageStyle): Resource {
    if (!style) return $r('sys.symbol.circle');
    const family = this.styleFamily(style);
    return family === 'PHOTO' ? $r('sys.symbol.camera') : family === 'ILLUSTRATION' ? $r('sys.symbol.picture') :
      family === 'USER' ? $r('sys.symbol.person') : family === 'CRAFT' ? $r('sys.symbol.doc_text') : $r('sys.symbol.paintbrush');
  }
  private filteredStyles(): ImageStyle[] {
    const query = this.styleSearch.trim().toLowerCase();
    return this.styles.filter((style: ImageStyle) =>
      (this.styleCategory === 'ALL' || this.styleFamily(style) === this.styleCategory) &&
      (query.length === 0 || `${this.styleLabel(style)} ${style.name} ${this.styleDescription(style)}`.toLowerCase().includes(query)));
  }
  private styleCompatible(style: ImageStyle): boolean {
    return style.compatibleModels.includes(this.modelId) && style.modes.includes(this.mode);
  }
  private closeStyleLibrary(): void {
    if (this.styleBusy) return;
    if (this.styleEditor) { this.styleEditor = false; }
    else if (this.styleDetail) { this.styleDetail = false; this.stylePromptVisible = false; }
    else { this.stylePanel = false; }
  }
  @Builder
  private StyleControls() {
    Column({ space: 12 }) {
      Row() {
        Text($r('app.string.image_style_title')).fontFamily(AppTheme.fontMedium).fontSize(14).fontWeight(500).layoutWeight(1)
        Text($r('app.string.image_style_optional')).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary)
      }.width('100%')
      Button({ type: ButtonType.Normal }) {
        Row({ space: 12 }) {
          SymbolGlyph(this.styleIcon(this.chosenStyle)).fontSize(24).fontColor([AppTheme.primary])
          Column({ space: 5 }) {
            Text(this.chosenStyle ? this.styleLabel(this.chosenStyle) : $r('app.string.image_style_natural')).fontFamily(AppTheme.fontMedium).fontSize(16).fontWeight(500).fontColor(AppTheme.ink)
            Text(this.chosenStyle ? this.styleDescription(this.chosenStyle) : $r('app.string.image_style_natural_hint')).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).maxLines(2)
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          Text($r('app.string.image_style_change')).fontFamily(AppTheme.fontFamily).fontSize(16).fontColor(AppTheme.primary)
          SymbolGlyph($r('sys.symbol.chevron_right')).fontSize(18).fontColor([AppTheme.primary])
        }.width('100%')
      }.width('100%').padding(14).backgroundColor(AppTheme.accentSoft).borderRadius(14)
        .enabled(!this.busy && !this.styleBusy).onClick(() => this.openStyleLibrary())
        .accessibilityText(this.imageLabel($r('app.string.image_style_change')))
      if (this.chosenStyle) {
        Row() {
          Text($r('app.string.image_style_applied_hint')).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).layoutWeight(1)
          AppTextAction({ label: $r('app.string.image_style_clear'), available: !this.busy && !this.styleBusy,
            action: () => this.chooseStyle(undefined) })
        }.width('100%')
      }
    }.width('100%').padding(16).backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius).alignItems(HorizontalAlign.Start)
  }
  @Builder
  private StyleTile(style: ImageStyle) {
    Button({ type: ButtonType.Normal }) {
      Column({ space: 8 }) {
        Row() {
          SymbolGlyph(this.styleIcon(style)).fontSize(22).fontColor([AppTheme.primary])
          Blank()
          if (this.styleDraft?.id === style.id) {
            SymbolGlyph($r('sys.symbol.checkmark_circle_fill')).fontSize(18).fontColor([AppTheme.primary])
          }
        }.width('100%')
        Text(this.styleLabel(style)).fontFamily(AppTheme.fontMedium).fontSize(16).fontWeight(500).fontColor(AppTheme.ink).maxLines(2).width('100%')
        Text(this.styleDescription(style)).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).maxLines(2).width('100%')
      }.alignItems(HorizontalAlign.Start).width('100%')
    }.width('100%').constraintSize({ minHeight: 156 }).padding(12).borderRadius(16)
      .backgroundColor(this.styleDraft?.id === style.id ? AppTheme.accentSoft : AppTheme.surface)
      .border({ width: this.styleDraft?.id === style.id ? 2 : 1, radius: 16, color: this.styleDraft?.id === style.id ? AppTheme.primary : AppTheme.divider })
      .enabled(!this.styleBusy)
      .onClick(() => { this.styleDraft = style; this.styleDetail = true; this.stylePromptVisible = false; this.styleMessage = ''; })
      .accessibilityText(`${this.styleLabel(style)} ${this.styleDescription(style)}`)
  }
  @Builder
  private StyleLibrary() {
    Column({ space: 16 }) {
      Row({ space: 12 }) {
        AppIconButton({ icon: $r('sys.symbol.chevron_left'), available: !this.styleBusy,
          description: this.imageLabel($r('app.string.image_style_back')), action: () => this.closeStyleLibrary() })
        Text(this.styleEditor ? $r('app.string.image_style_edit_title') : this.styleDetail ? $r('app.string.image_style_detail_title') : $r('app.string.image_style_library')).fontFamily(AppTheme.fontSemibold)
          .fontSize(20).lineHeight(28).fontWeight(600).fontColor(AppTheme.ink).layoutWeight(1)
        if (!this.styleDetail && !this.styleEditor) {
          AppTextAction({ label: $r('app.string.image_style_new'), action: () => this.editStyle() })
        }
      }.width('100%')
      if (this.styleEditor) {
        Scroll() { Column({ space: 16 }) {
          Text($r('app.string.image_style_editor_help')).fontFamily(AppTheme.fontFamily).fontSize(13).fontColor(AppTheme.textSecondary)
          Text($r('app.string.image_style_name')).fontFamily(AppTheme.fontMedium).fontSize(14).fontWeight(500)
          AppGlassTextInput({ value: this.styleName, placeholder: this.imageLabel($r('app.string.image_style_name')),
            limit: 80, available: !this.styleBusy, valueChanged: (value: string) => this.styleName = value })
          Text($r('app.string.image_style_template_label')).fontFamily(AppTheme.fontMedium).fontSize(14).fontWeight(500)
          AppGlassTextArea({ value: this.styleTemplate, areaHeight: 180, limit: 2000, available: !this.styleBusy,
            valueChanged: (value: string) => this.styleTemplate = value })
          Text($r('app.string.image_style_prompt_preview')).fontFamily(AppTheme.fontMedium).fontSize(14).fontWeight(500)
          Text(this.styleTemplate.split('{prompt}').join(this.prompt.trim() || this.imageLabel($r('app.string.image_style_sample')))).fontFamily(AppTheme.fontFamily)
            .fontSize(13).fontColor(AppTheme.textSecondary).copyOption(CopyOptions.InApp)
        }.alignItems(HorizontalAlign.Start).width('100%').padding(16).backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius).enabled(!this.styleBusy) }.layoutWeight(1).width('100%').align(Alignment.TopStart)
      } else if (this.styleDetail && this.styleDraft) {
        Scroll() { Column({ space: 20 }) {
          Column({ space: 14 }) {
            SymbolGlyph(this.styleIcon(this.styleDraft)).fontSize(40).fontColor([AppTheme.primary])
            Text(this.styleLabel(this.styleDraft)).fontFamily(AppTheme.fontSemibold).fontSize(26).fontWeight(600).fontColor(AppTheme.ink)
            Text(this.styleDescription(this.styleDraft)).fontFamily(AppTheme.fontFamily).fontSize(15).fontColor(AppTheme.textSecondary).textAlign(TextAlign.Center)
            Text(this.familyLabel(this.styleFamily(this.styleDraft))).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.primary).padding(8).backgroundColor(AppTheme.accentSoft).borderRadius(10)
          }.padding(28).width('100%').backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
          Text($r('app.string.image_style_detail_hint')).fontFamily(AppTheme.fontFamily).fontSize(13).fontColor(AppTheme.textSecondary)
          if (!this.styleCompatible(this.styleDraft)) { Text($r('app.string.image_style_incompatible')).fontFamily(AppTheme.fontFamily).fontSize(13).fontColor(AppTheme.danger) }
          AppButton({ label: this.stylePromptVisible ? $r('app.string.image_style_hide_prompt') : $r('app.string.image_style_prompt_preview'),
            variant: 'secondary', action: () => this.stylePromptVisible = !this.stylePromptVisible })
          if (this.stylePromptVisible) {
            Text(this.styleDraft.promptTemplate.split('{prompt}').join(this.prompt.trim() || this.imageLabel($r('app.string.image_style_sample')))).fontFamily(AppTheme.fontFamily)
              .fontSize(13).fontColor(AppTheme.textSecondary).copyOption(CopyOptions.InApp).width('100%').padding(16).backgroundColor(AppTheme.surface).borderRadius(14)
          }
          Flex({ wrap: FlexWrap.Wrap }) {
            AppTextAction({ label: $r('app.string.image_style_copy_mine'), available: !this.styleBusy,
              action: () => this.editStyle(this.styleDraft, true) }).margin({ right: 8 })
            if (this.styleDraft.origin === 'USER') {
              AppTextAction({ label: $r('app.string.image_style_edit'), available: !this.styleBusy,
                action: () => this.editStyle(this.styleDraft) }).margin({ right: 8 })
              AppButton({ label: $r('app.string.image_style_delete'), variant: 'danger', available: !this.styleBusy,
                action: () => { if (this.styleDraft) this.confirmDeleteStyle(this.styleDraft); } })
            }
          }.width('100%').enabled(!this.styleBusy)
          Text(`v${this.styleDraft.version}`).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textTertiary)
        }.alignItems(HorizontalAlign.Start).width('100%') }.layoutWeight(1).width('100%').align(Alignment.TopStart)
      } else {
        AppSearchField({ value: this.styleSearch, placeholder: this.imageLabel($r('app.string.image_style_search')),
          valueChanged: (value: string) => this.styleSearch = value, cleared: () => this.styleSearch = '' })
        Scroll() { Row({ space: 8 }) {
          ForEach(['ALL', 'PHOTO', 'PAINT', 'ILLUSTRATION', 'CRAFT', 'DESIGN', 'USER'], (category: string) => {
            AppFilterChip({ label: this.familyLabel(category), selected: this.styleCategory === category,
              action: () => this.styleCategory = category })
          })
        } }.scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).width('100%')
        Row() {
          Text(`${this.filteredStyles().length} ${this.imageLabel($r('app.string.image_style_count_unit'))}`).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).layoutWeight(1)
          AppTextAction({ label: $r('app.string.image_style_refresh'), available: !this.styleLoading,
            loading: this.styleLoading, action: () => this.loadStyles() })
        }.width('100%')
        Scroll() { Column({ space: 12 }) {
          if (this.styleSearch.length === 0 && this.styleCategory === 'ALL') {
            Button({ type: ButtonType.Normal }) { Row({ space: 12 }) {
              SymbolGlyph($r('sys.symbol.circle')).fontSize(22).fontColor([AppTheme.primary])
              Column({ space: 4 }) { Text($r('app.string.image_style_natural')).fontFamily(AppTheme.fontMedium).fontSize(14).fontWeight(500);Text($r('app.string.image_style_natural_hint')).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary) }.layoutWeight(1).alignItems(HorizontalAlign.Start)
              if (!this.styleDraft) { SymbolGlyph($r('sys.symbol.checkmark_circle_fill')).fontSize(20).fontColor([AppTheme.primary]) }
            }.width('100%') }.padding(16).width('100%').backgroundColor(this.styleDraft ? AppTheme.surface : AppTheme.accentSoft).fontColor(AppTheme.ink).borderRadius(16)
              .enabled(!this.styleBusy).onClick(() => this.styleDraft = undefined)
          }
          if (this.styleLoading && this.styles.length === 0) { LoadingProgress().width(36).height(36) }
          Flex({ wrap: FlexWrap.Wrap }) {
            ForEach(this.filteredStyles(), (style: ImageStyle) => { Column() { this.StyleTile(style) }.width('50%').padding(4) }, (style: ImageStyle) => `${style.id}-${style.version}`)
          }.width('100%')
          if (!this.styleLoading && this.filteredStyles().length === 0) {
            Text(this.styleCategory === 'USER' && !this.styleSearch ? $r('app.string.image_style_empty_mine') : $r('app.string.image_style_empty_search')).fontFamily(AppTheme.fontFamily).fontSize(14).fontColor(AppTheme.textSecondary).padding({ top: 24, bottom: 24 })
          }
        }.width('100%') }.layoutWeight(1).width('100%').scrollBar(BarState.Off).align(Alignment.TopStart)
      }
      if (this.styleMessage) { Text(this.styleMessage).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).width('100%') }
      Column({ space: 10 }) {
        if (!this.styleEditor) {
          Row({ space: 8 }) { Text($r('app.string.image_style_candidate')).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).flexShrink(0);Text(this.styleDraft ? this.styleLabel(this.styleDraft) : $r('app.string.image_style_natural')).fontFamily(AppTheme.fontMedium).fontSize(14).fontWeight(500).fontColor(AppTheme.primary).layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).textAlign(TextAlign.End) }.width('100%')
        }
        AppButton({ label: this.styleBusy ? $r('app.string.image_style_saving') : this.styleEditor ? $r('app.string.image_style_save') : $r('app.string.image_style_apply'),
          loading: this.styleBusy, fullWidth: true,
          available: !this.styleBusy && (this.styleEditor ? this.validStyleTemplate() : !this.styleDraft || this.styleCompatible(this.styleDraft)),
          action: () => { if (this.styleEditor) { this.saveStyle(); } else {
            // 未改变候选时保留历史作品的风格版本来源。
            if (this.styleDraft?.id !== this.chosenStyle?.id || this.styleDraft?.version !== this.chosenStyle?.version) this.chooseStyle(this.styleDraft);
            this.stylePanel = false;
          } } })
      }.padding({ top: 12 }).width('100%')
    }.width('100%').height('100%')
      .padding({ top: this.overlayTopInset + 12, left: 20, right: 20, bottom: this.overlayBottomInset + 16 })
      .backgroundColor(AppTheme.background)
  }
  @Builder
  private Composer() {
    Column({ space: 10 }) {
      Column({ space: 4 }) {
        Text('让想象，成为画面').fontFamily(AppTheme.fontSemibold).fontSize(26).lineHeight(34).fontWeight(600).fontColor(AppTheme.ink)
        Text('从一句描述开始，创作属于你的影像。').fontFamily(AppTheme.fontFamily).fontSize(15).lineHeight(24).fontColor(AppTheme.textSecondary)
      }.width('100%').alignItems(HorizontalAlign.Start).padding({ top: 8, bottom: 4 })
      if (this.mode !== 'IMAGE_TO_PROMPT') {
      Column({ space: 12 }) {
        Row() {
          Text('画面描述').fontFamily(AppTheme.fontMedium).fontSize(14).fontWeight(500).layoutWeight(1)
          Text(`${this.prompt.length} / 4000`).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textTertiary)
        }.width('100%')
        AppGlassTextArea({ value: this.prompt, placeholder: '想画什么？\n描述人物形象、动作、场景与光线…',
          areaHeight: 156, limit: 4000, available: !this.busy,
          valueChanged: (value: string) => { this.prompt = value; this.pending = undefined; } })
        Row() {
          Text('主体 · 风格 · 构图 · 光线').fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textTertiary).layoutWeight(1)
          if (this.prompt.length > 0) {
            AppTextAction({ label: '清空', available: !this.busy,
              action: () => { this.prompt = ''; this.pending = undefined; } })
          }
        }.width('100%')
      }.padding(16).width('100%').backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
        .border({ width: 1, color: AppTheme.divider })
      }
      if (this.styleEnabled()) { this.StyleControls() }
      Column({ space: 10 }) {
        Row() {
          Text('生成模型').fontFamily(AppTheme.fontMedium).fontSize(14).fontWeight(500).layoutWeight(1)
          AppTextAction({ label: this.showModels ? '收起' : '切换模型', available: !this.busy,
            action: () => this.showModels = !this.showModels })
        }.width('100%')
        ForEach(this.showModels ? this.models : this.models.filter((model: ImageModel) => model.id === this.modelId), (model: ImageModel) => {
          Button({ type: ButtonType.Normal }) {
          Row({ space: 12 }) {
            SymbolGlyph($r('sys.symbol.picture')).fontSize(21)
              .fontColor([this.modelId === model.id ? AppTheme.primary : AppTheme.textTertiary])
            Column({ space: 3 }) {
              Text(model.id === 'vision-local' ? this.imageLabel($r('app.string.image_mode_prompt')) : model.name).fontFamily(AppTheme.fontMedium).fontSize(16).fontWeight(500).fontColor(AppTheme.ink)
              Text(`${model.location === 'LOCAL' ? '本地运行' : '远端服务'} · ${model.status === 'READY' ? '已就绪' : '暂不可用'}`).fontFamily(AppTheme.fontFamily)
                .fontSize(12).fontColor(AppTheme.textSecondary)
            }.layoutWeight(1).alignItems(HorizontalAlign.Start)
            if (this.modelId === model.id) {
              SymbolGlyph($r('sys.symbol.checkmark_circle_fill')).fontSize(18).fontColor([AppTheme.primary])
            }
          }.width('100%')
          }.width('100%').constraintSize({ minHeight: 64 }).padding(14).borderRadius(12).backgroundColor(AppTheme.surface)
            .border({ width: 1, color: this.modelId === model.id ? AppTheme.primary : AppTheme.divider })
            .enabled(!this.busy).onClick(() => { this.selectModel(model); this.showModels = false; })
            .accessibilityText(`选择图片模型：${model.name}`)
            .accessibilityDescription(this.modelId === model.id ? '已选择' : '未选择')
        }, (model: ImageModel) => `${model.id}:${model.status}`)
      }.width('100%')
      if (this.ready()) {
        Column({ space: 10 }) {
          if ((this.selected()?.modes.length ?? 0) > 1) {
            Flex({ wrap: FlexWrap.Wrap }) {
              ForEach(this.selected()?.modes ?? [], (mode: string) => {
                this.Choice(mode === 'TEXT_TO_IMAGE' ? '文字生成' : mode === 'IMAGE_TO_IMAGE' ? this.imageLabel($r('app.string.image_mode_edit')) : mode === 'IMAGE_TO_PROMPT' ? this.imageLabel($r('app.string.image_mode_prompt')) : '风格参考', mode, 'mode')
              })
            }.width('100%')
          }
          if (this.mode !== 'TEXT_TO_IMAGE') {
            this.ReferenceUploadButton()
            if (this.references.length > 0) {
              this.QuietButton('清除底稿', () => { this.references = []; this.pending = undefined; })
            }
          }
          if (this.mode !== 'IMAGE_TO_PROMPT') {
          Row() {
            Text('画面尺寸').fontFamily(AppTheme.fontMedium).fontSize(14).lineHeight(20).fontWeight(500).layoutWeight(1)
            Text(this.imageSize.replace('x', ' × ')).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textTertiary)
          }.width('100%')
          Flex({ wrap: FlexWrap.Wrap }) {
            ForEach(this.selected()?.sizes ?? [], (size: string) => {
              this.Choice(size === '1024x1024' ? '方形' : size === '832x1216' ? '竖版' : size === '1216x832' ? '横版' : size,
                size, 'size')
            })
          }.width('100%')
          Column({ space: 4 }) {
            Text('生成数量').fontFamily(AppTheme.fontMedium).fontSize(14).lineHeight(20).fontWeight(500)
            Flex({ wrap: FlexWrap.Wrap }) {
              ForEach(this.selected()?.counts ?? [], (count: number) => {
                this.Choice(`${count} 张`, `${count}`, 'count')
              })
            }.width('100%')
          }.width('100%').alignItems(HorizontalAlign.Start)
          if (this.selected()?.location === 'LOCAL') {
            Button({ type: ButtonType.Normal }) {
            Row() {
              Text('高级设置').fontFamily(AppTheme.fontMedium).fontSize(16).fontWeight(500)
                .lineHeight(24).fontColor(AppTheme.textSecondary).layoutWeight(1)
              Text(this.showAdvanced ? '收起' : this.seedText.length > 0 ? '固定种子' : '随机种子').fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textTertiary)
              SymbolGlyph($r('sys.symbol.chevron_right')).fontSize(18).fontColor([AppTheme.textTertiary]).margin({ left: 6 })
            }.width('100%')
            }.width('100%').constraintSize({ minHeight: 48 }).padding(0).backgroundColor(Color.Transparent)
              .borderRadius(10).onClick(() => this.showAdvanced = !this.showAdvanced)
              .accessibilityText('高级设置').accessibilityDescription(this.showAdvanced ? '已展开' : '已折叠')
            if (this.showAdvanced) {
              AppGlassTextInput({ value: this.seedText, label: '随机种子（可选）', placeholder: '留空，每次探索新画面',
                inputType: InputType.Number, limit: 10, available: !this.busy,
                valueChanged: (value: string) => { this.seedText = value; this.pending = undefined; } })
            }
          }
          }
        }.width('100%').padding(12).borderRadius(AppTheme.cardRadius).backgroundColor(AppTheme.surface).enabled(!this.busy)
      }
      AppButton({ label: this.busy ? '正在提交…' : this.ready() ? (this.mode === 'IMAGE_TO_PROMPT' ? this.imageLabel($r('app.string.image_extract_prompt')) : '生成图片') : this.models.length === 0 ? (this.catalogLoading ? '正在连接图片服务…' : this.catalogFailed ? '连接失败，请刷新' : '暂无可用图片模型') : '当前模型暂不可用',
        fullWidth: true, loading: this.busy,
        available: this.ready() && !this.busy && (this.mode === 'IMAGE_TO_PROMPT' || this.prompt.trim().length > 0) && (this.mode === 'TEXT_TO_IMAGE' || this.references.length > 0),
        action: () => this.generate() })
      Text('生成后可在「我的作品」查看进度和保存图片').fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textTertiary)
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  private openViewer(job: ImageJob, item: GeneratedImage): void {
    this.viewerUri = this.previewUri(job, item);
    const dimensions = job.request.size.split('x');
    this.viewerAspect = Number(dimensions[0]) / Number(dimensions[1]) || 1;
    this.resetViewer();
    this.viewerVisible = this.viewerUri.length > 0;
  }
  private resetViewer(): void {
    this.viewerScale = 1; this.viewerX = 0; this.viewerY = 0;
  }
  private constrainViewer(): void {
    // 根据缩放后的画布限制位移，避免图片被拖出可视区域。
    const fitWidth = Math.min(this.viewerWidth, this.viewerHeight * this.viewerAspect);
    const fitHeight = Math.min(this.viewerHeight, this.viewerWidth / this.viewerAspect);
    const limitX = Math.max(0, (fitWidth * this.viewerScale - this.viewerWidth) / 2);
    const limitY = Math.max(0, (fitHeight * this.viewerScale - this.viewerHeight) / 2);
    this.viewerX = Math.max(-limitX, Math.min(limitX, this.viewerX));
    this.viewerY = Math.max(-limitY, Math.min(limitY, this.viewerY));
  }
  @Builder
  private FullscreenViewer() {
    Column() {
      Row() {
        AppTextAction({ label: '关闭', darkMode: true, action: () => this.viewerVisible = false })
        Blank()
        Text(`${Math.round(this.viewerScale * 100)}%`).fontFamily(AppTheme.fontFamily).fontSize(13).fontColor('#C4C4C4')
        AppTextAction({ label: '复位', darkMode: true, action: () => this.resetViewer() }).margin({ left: 14 })
      }.width('100%').padding({ left: 20, right: 20, top: this.overlayTopInset + 12, bottom: 12 })
      Stack() {
        Image(this.viewerUri).width('100%').height('100%').objectFit(ImageFit.Contain)
          .interpolation(ImageInterpolation.High)
          .scale({ x: this.viewerScale, y: this.viewerScale })
          .translate({ x: this.viewerX, y: this.viewerY })
          .accessibilityText('全屏作品图片，双击放大，双指缩放')
      }.width('100%').layoutWeight(1).clip(true)
        .onAreaChange((oldArea: Area, area: Area) => {
          this.viewerWidth = Number(area.width); this.viewerHeight = Number(area.height); this.constrainViewer();
        })
        .gesture(GestureGroup(GestureMode.Exclusive,
          TapGesture({ count: 2 }).onAction(() => {
            if (this.viewerScale > 1) { this.resetViewer(); } else { this.viewerScale = 2; }
          }),
          PinchGesture({ fingers: 2 })
            .onActionStart(() => this.pinchStart = this.viewerScale)
            .onActionUpdate((event: GestureEvent) => {
              this.viewerScale = Math.max(1, Math.min(6, this.pinchStart * event.scale)); this.constrainViewer();
            }),
          PanGesture({ fingers: 1, direction: PanDirection.All, distance: 4 })
            .onActionStart(() => { this.panStartX = this.viewerX; this.panStartY = this.viewerY; })
            .onActionUpdate((event: GestureEvent) => {
              this.viewerX = this.panStartX + event.offsetX; this.viewerY = this.panStartY + event.offsetY; this.constrainViewer();
            })
        ))
      Text('双指缩放 · 双击放大 · 拖动查看').fontFamily(AppTheme.fontFamily).fontSize(12).fontColor('#A0A0A0')
        .padding({ left: 20, right: 20, top: 16, bottom: this.overlayBottomInset + 16 })
    }.width('100%').height('100%').backgroundColor('#101010')
  }
  @Builder
  private Works() {
    Column({ space: 18 }) {
      Row() {
        Text('每一次想象，都值得收藏').fontFamily(AppTheme.fontFamily).fontSize(13).fontColor(AppTheme.textSecondary).layoutWeight(1)
        Text(`第 ${this.page + 1} 页`).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textTertiary)
      }.width('100%')
      if (this.jobs.length === 0 && this.catalogLoading) {
        Column({ space: 12 }) {
          LoadingProgress().width(28).height(28).color(AppTheme.primary)
          Text('正在加载图片作品…').fontFamily(AppTheme.fontFamily).fontSize(15).fontColor(AppTheme.textSecondary)
        }.width('100%').padding(32)
      } else if (this.jobs.length === 0 && this.catalogFailed) {
        ToolEmptyState({ icon: $r('sys.symbol.picture'), title: '作品暂时无法加载',
          description: '已保留当前页码，请稍后重试。' })
        AppTextAction({ label: '重新加载', action: () => this.refresh() })
      } else if (this.jobs.length === 0) {
        Column({ space: 12 }) {
          SymbolGlyph($r('sys.symbol.picture')).fontSize(36).fontColor([AppTheme.primary])
          Text('你的第一幅作品，从这里开始').fontFamily(AppTheme.fontMedium).fontSize(16).fontWeight(500)
          Text('写下想法，让画面慢慢浮现。').fontFamily(AppTheme.fontFamily).fontSize(13).fontColor(AppTheme.textSecondary)
          this.QuietButton('开始创作', () => this.tab = 'create')
        }.width('100%').padding({ top: 60, bottom: 60 }).backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
      }
      ForEach(this.jobs, (job: ImageJob) => {
        Column({ space: 0 }) {
          ForEach(job.result.images ?? [], (item: GeneratedImage) => {
            if (this.previewUri(job, item).length > 0) {
              Image(this.previewUri(job, item)).width('100%').aspectRatio(1).objectFit(ImageFit.Contain)
                .backgroundColor('#E8ECE9').accessibilityText('点击全屏查看作品')
                .onClick(() => this.openViewer(job, item))
            }
            Flex({ wrap: FlexWrap.Wrap, alignItems: ItemAlign.Center }) {
              Text(`${item.index + 1} / ${job.result.images?.length ?? 0}`).fontFamily(AppTheme.fontFamily).fontSize(12)
                .fontColor(AppTheme.textTertiary).margin({ right: 8 })
              AppTextAction({ label: '保存图片', action: () => this.save(job, item) }).margin({ right: 8 })
              if (this.models.some((model: ImageModel) => model.status === 'READY' && (model.modes.includes('IMAGE_TO_IMAGE') || model.modes.includes('STYLE_REFERENCE')))) {
                AppTextAction({ label: '用作底稿', action: () => this.useReference(job, item) }).margin({ right: 8 })
              }
            }.width('100%').padding({ left: 14, right: 14, top: 10 })
          }, (item: GeneratedImage) => `${job.id}-${item.index}`)
          Column({ space: 10 }) {
            Flex({ wrap: FlexWrap.Wrap, alignItems: ItemAlign.Center }) {
              Text(this.status(job.status)).fontFamily(AppTheme.fontMedium).fontSize(12).fontWeight(500)
                .fontColor(this.active(job) || job.status === 'SUCCEEDED' ? AppTheme.primary : AppTheme.textSecondary)
                .backgroundColor(AppTheme.accentSoft).borderRadius(6).padding({ left: 8, right: 8, top: 4, bottom: 4 })
                .margin({ right: 8 })
              if (job.status === 'QUEUED' || job.status === 'RUNNING') {
                AppTextAction({ label: '取消', action: () => this.cancel(job) }).margin({ right: 8 })
              }
              AppTextAction({ label: '再次创作', action: () => this.reuseParameters(job) }).margin({ right: 8 })
            }.width('100%')
            if (job.result.prompt) {
              Text(job.result.prompt).fontFamily(AppTheme.fontFamily).fontSize(15).lineHeight(24).fontColor(AppTheme.ink).width('100%').copyOption(CopyOptions.InApp)
              this.QuietButton(this.imageLabel($r('app.string.image_use_prompt')), () => this.useExtractedPrompt(job))
              Text(this.imageLabel($r('app.string.image_prompt_notice'))).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary)
            }
            if (job.request.styleSnapshot !== undefined) {
              Text(`${this.styleLabel(job.request.styleSnapshot)} · v${job.request.styleSnapshot.version}`).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.primary)
            }
            Text(job.request.prompt).fontFamily(AppTheme.fontFamily).fontSize(15).lineHeight(24).fontColor(AppTheme.ink).maxLines(3)
              .textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
            Text(`${this.modelName(job.request.resourceId)} · ${job.request.size.replace('x', ' × ')} · ${job.request.count} 张`).fontFamily(AppTheme.fontFamily)
              .fontSize(12).fontColor(AppTheme.textTertiary).width('100%')
            if (job.result.unconfirmed === true) {
              Text('结果尚未确认，请先核对，避免重复生成。远端请求可能已经计费。').fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary)
            }
            Text(this.elapsedLabel(job)).fontFamily(AppTheme.fontFamily).width('100%').textAlign(TextAlign.End).fontSize(12)
              .fontColor(AppTheme.textSecondary).accessibilityText(`${this.elapsedLabel(job)}，包含排队、生成和保存`)
          }.padding(14).width('100%').alignItems(HorizontalAlign.Start)
        }.width('100%').borderRadius(AppTheme.cardRadius).clip(true).backgroundColor(AppTheme.surface)
          .border({ width: 1, color: AppTheme.divider })
      }, (job: ImageJob) => `${job.id}:${job.status}:${job.errorCode}:${job.result.images?.length ?? 0}:${job.result.unconfirmed ?? false}:${job.elapsedMillis ?? -1}`)
      AppEqualActionRow() {
        AppGroupedButton({ label: '上一页', variant: 'text', fullWidth: true, available: this.page > 0,
          action: () => { this.page--; this.clearPreviews(); this.refresh(); } })
        Column() {
          Text(`${this.page + 1}`).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textTertiary)
            .constraintSize({ maxWidth: 92 }).maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis }).textAlign(TextAlign.Center)
        }.justifyContent(FlexAlign.Center).alignItems(HorizontalAlign.Center)
        AppGroupedButton({ label: '下一页', variant: 'text', fullWidth: true, available: this.jobs.length === 20,
          action: () => { this.page++; this.clearPreviews(); this.refresh(); } })
      }
    }.width('100%')
  }
  @Builder
  private ActiveOverlay() {
    if (this.stylePanel) { this.StyleLibrary() } else { this.FullscreenViewer() }
  }
  /** 构建图片创作、作品与全屏查看页面。 */
  build() {
    Column({ space: 14 }) {
      Row({ space: 12 }) {
        AppIconButton({ icon: $r('sys.symbol.chevron_left'), description: '返回', action: () => this.onBack() })
        Text('图片工作台').fontFamily(AppTheme.fontSemibold).fontSize(20).fontWeight(600).fontColor(AppTheme.ink).layoutWeight(1)
        this.QuietButton('刷新', () => this.refresh())
      }.width('100%')
      Row({ space: 4 }) {
        AppSegmentItem({ label: '创作', selected: this.tab === 'create', action: () => this.tab = 'create' }).layoutWeight(1)
        AppSegmentItem({ label: '我的作品', selected: this.tab === 'works', action: () => this.tab = 'works' }).layoutWeight(1)
      }.width('100%').backgroundColor(AppTheme.surfaceTint).borderRadius(12)
      if (this.tab === 'create') { this.Composer() } else { this.Works() }
      if (this.message.length > 0) {
        Text(this.message).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary).width('100%').padding(12)
          .backgroundColor(AppTheme.accentSoft).borderRadius(12)
      }
    }.width('100%').padding({ top: 4, bottom: 16 }).alignItems(HorizontalAlign.Start)
      .bindContentCover(this.stylePanel || this.viewerVisible, this.ActiveOverlay(), { modalTransition: ModalTransition.NONE,
        onDisappear: () => { this.stylePanel = false; this.viewerVisible = false; } })
  }
}
