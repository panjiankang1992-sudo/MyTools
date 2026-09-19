import { AppTheme } from '../../theme/AppTheme';
import { common } from '@kit.AbilityKit';
import picker from '@ohos.file.picker';
import fs from '@ohos.file.fs';
import util from '@ohos.util';
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
  private pinchStart: number = 1;
  private panStartX: number = 0;
  private panStartY: number = 0;
  private revision: number = 0;
  private timer: number = -1;
  private refreshSequence: number = 0;
  private refreshMessage: string = '';
  private pending: ImageRequest | undefined = undefined;

  aboutToAppear(): void { this.refresh(); }
  aboutToDisappear(): void {
    this.viewerVisible = false; this.viewerUri = ';';
    this.revision++;
    if (this.timer !== -1) clearTimeout(this.timer);
    this.timer = -1;
    this.clearPreviews();
  }
  private resetAccount(): void {
    this.aboutToDisappear();
    this.styles = []; this.chosenStyle = undefined; this.styleSourceJobId = undefined;
    this.styleEditor = false; this.stylePanel = false; this.styleName = ''; this.styleTemplate = '{prompt}. ';
    this.styleEditingId = ''; this.styleEditingVersion = 0; this.styleCreateId = ''; this.styleMessage = ''; this.styleBusy = false;
    this.prompt = ''; this.seedText = ''; this.references = []; this.jobs = []; this.models = [];
    this.pending = undefined; this.busy = false; this.page = 0; this.message = ''; this.refreshMessage = '';
    this.refresh();
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
    try {
      const api = this.api();
      const models = await api.models();
      const jobs = await api.works(this.page);
      if (revision !== this.revision || sequence !== this.refreshSequence) return;
      this.models = models; this.jobs = jobs;
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
      }
    } finally {
      if (revision === this.revision && sequence === this.refreshSequence) this.timer = setTimeout(() => this.refresh(), 5000);
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
      try {
        const length = fs.statSync(file.fd).size;
        if (length <= 0 || length > 1024 * 1024) throw new Error('底稿请使用不超过 1 MB 的 PNG 或 JPEG');
        const buffer = new ArrayBuffer(length);
        const read = fs.readSync(file.fd, buffer);
        if (read !== length) throw new Error('底稿读取不完整');
        encoded = new util.Base64Helper().encodeToStringSync(new Uint8Array(buffer));
      } finally { fs.closeSync(file); }
      const id = await this.api().upload(encoded);
      if (revision !== this.revision) return;
      this.references = this.references.concat([id]).slice(0, this.selected()?.maxReferences ?? 1); this.pending = undefined; this.message = '已添加底稿';
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
    const sequence = ++this.styleLoadSequence;
    try {
      const styles = await this.api().styles();
      if (revision !== this.revision || sequence !== this.styleLoadSequence) return;
      this.styles = styles; this.styleMessage = '';
    } catch (_) {
      if (revision === this.revision && sequence === this.styleLoadSequence) this.styleMessage = this.imageLabel($r('app.string.image_style_load_failed'));
    }
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
    this.styleMessage = ''; this.styleEditor = true; this.stylePanel = true;
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
    this.styleBusy = true; this.styleLoadSequence++;
    try {
      const style = await this.api().saveStyle(id, body);
      if (revision !== this.revision) return;
      this.styles = this.styles.filter((item: ImageStyle) => item.id !== style.id).concat([style]);
      this.chosenStyle = style; this.styleSourceJobId = undefined; this.pending = undefined;
      this.styleEditor = false; this.styleGroup = 'USER'; this.styleMessage = this.imageLabel($r('app.string.image_style_saved'));
    } catch (_) {
      if (revision === this.revision) this.styleMessage = this.imageLabel($r('app.string.image_style_save_failed'));
    } finally { if (revision === this.revision) this.styleBusy = false; }
  }
  private confirmDeleteStyle(style: ImageStyle): void {
    const revision = this.revision;
    this.getUIContext().showAlertDialog({ title: this.imageLabel($r('app.string.image_style_delete')),
      message: this.imageLabel($r('app.string.image_style_delete_notice')),
      primaryButton: { value: this.imageLabel($r('app.string.image_style_cancel')), action: () => {} },
      secondaryButton: { value: this.imageLabel($r('app.string.image_style_delete')), action: () => {
        if (revision === this.revision) this.deleteStyle(style);
      } } });
  }
  private async deleteStyle(style: ImageStyle): Promise<void> {
    if (this.styleBusy || this.busy) return;
    const revision = this.revision; this.styleBusy = true; this.styleLoadSequence++;
    try {
      await this.api().deleteStyle(style.id);
      if (revision !== this.revision) return;
      this.styles = this.styles.filter((item: ImageStyle) => item.id !== style.id);
      if (this.chosenStyle?.id === style.id) { this.chosenStyle = undefined; this.styleSourceJobId = undefined; this.pending = undefined; }
      this.styleMessage = this.imageLabel($r('app.string.image_style_deleted'));
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
      this.getUIContext().showAlertDialog({ title: '切换创作方式', message: '文字生成不会使用底稿，切换后将清除当前底稿。',
        primaryButton: { value: '取消', action: () => {} }, secondaryButton: { value: '切换', action: () => {
          this.mode = mode; this.references = []; this.pending = undefined;
        } } });
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
      this.getUIContext().showAlertDialog({ title: '切换生成模型',
        message: '目标模型不支持部分当前参数。切换后会调整尺寸和数量，清除不支持的底稿或种子；文字描述会保留。',
        primaryButton: { value: '取消', action: () => {} },
        secondaryButton: { value: '切换', action: () => this.applyModel(model) } });
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
  private QuietButton(label: string, action: () => void) {
    Button(label).type(ButtonType.Normal).height(36).fontSize(12).fontWeight(FontWeight.Medium)
      .fontColor(AppTheme.textSecondary).backgroundColor(AppTheme.surfaceTint).borderRadius(10)
      .padding({ left: 14, right: 14 }).onClick(action)
  }
  @Builder
  private ReferenceUploadButton() {
    Button() {
      Row({ space: 4 }) {
        Text($r('app.string.image_reference_upload')).fontSize(12)
        Text(`${this.references.length}`).fontSize(12)
        Text($r('app.string.image_reference_limit')).fontSize(12)
      }
    }.type(ButtonType.Normal).height(36).fontColor(AppTheme.textSecondary)
      .backgroundColor(AppTheme.surfaceTint).borderRadius(10).padding({ left: 14, right: 14 })
      .onClick(() => this.upload())
  }
  @Builder
  private Choice(label: string, value: string, group: string) {
    // 在构建器内直接读取状态，避免传入布尔快照后选中样式不再刷新。
    Button(label).type(ButtonType.Normal).height(36).layoutWeight(1).fontSize(13)
      .fontWeight(FontWeight.Medium)
      .fontColor((group === 'size' ? this.imageSize === value : group === 'count' ? this.count === Number(value) : this.mode === value) ? AppTheme.primary : AppTheme.textSecondary)
      .backgroundColor((group === 'size' ? this.imageSize === value : group === 'count' ? this.count === Number(value) : this.mode === value) ? AppTheme.accentSoft : AppTheme.background)
      .borderRadius(10)
      .border({ width: 1, radius: 10,
        color: (group === 'size' ? this.imageSize === value : group === 'count' ? this.count === Number(value) : this.mode === value) ? AppTheme.primary : Color.Transparent })
      .onClick(() => {
        if (group === 'size') { this.imageSize = value; this.pending = undefined; }
        else if (group === 'count') { this.count = Number(value); this.pending = undefined; }
        else { this.selectMode(value); }
      })
  }
  @Builder
  private StyleControls() {
    Column({ space: 10 }) {
      Row() {
        Text($r('app.string.image_style_title')).fontSize(14).fontWeight(FontWeight.Medium).layoutWeight(1)
        Button(this.chosenStyle === undefined ? this.imageLabel($r('app.string.image_style_none')) : this.styleLabel(this.chosenStyle))
          .fontSize(13).fontColor(AppTheme.primary).backgroundColor(AppTheme.accentSoft)
          .onClick(() => { this.stylePanel = !this.stylePanel; if (this.stylePanel) this.loadStyles(); })
      }.width('100%')
      if (this.chosenStyle !== undefined) {
        Text(`${this.styleLabel(this.chosenStyle)} · v${this.chosenStyle.version}`).fontSize(12).fontColor(AppTheme.textSecondary)
        Text(this.combinedPrompt()).fontSize(12).fontColor(AppTheme.textSecondary).maxLines(5).copyOption(CopyOptions.InApp)
        Text(`${this.combinedPrompt().length} / 4000`).fontSize(11).fontColor(AppTheme.textTertiary)
      }
      if (this.stylePanel) {
        Row({ space: 6 }) {
          this.QuietButton(this.imageLabel($r('app.string.image_style_none')), () => this.chooseStyle(undefined))
          this.QuietButton(this.imageLabel($r('app.string.image_style_builtin')), () => { this.styleGroup = 'BUILTIN'; })
          this.QuietButton(this.imageLabel($r('app.string.image_style_mine')), () => { this.styleGroup = 'USER'; })
        }
        Row({ space: 6 }) {
          this.QuietButton(this.imageLabel($r('app.string.image_style_new')), () => this.editStyle())
          this.QuietButton(this.imageLabel($r('app.string.image_style_refresh')), () => this.loadStyles())
        }
        Scroll() {
          Column({ space: 8 }) {
            ForEach(this.styles.filter((item: ImageStyle) => item.origin === this.styleGroup), (style: ImageStyle) => {
              Column({ space: 6 }) {
                Button(`${this.styleLabel(style)} · v${style.version}`).fontSize(13).width('100%')
                  .fontColor(this.chosenStyle?.id === style.id && this.chosenStyle?.version === style.version ? AppTheme.primary : AppTheme.ink)
                  .backgroundColor(AppTheme.surfaceTint).onClick(() => this.chooseStyle(style))
                Row({ space: 6 }) {
                  this.QuietButton(this.imageLabel($r('app.string.image_style_copy')), () => this.editStyle(style, true))
                  if (style.origin === 'USER') {
                    this.QuietButton(this.imageLabel($r('app.string.image_style_edit')), () => this.editStyle(style))
                    this.QuietButton(this.imageLabel($r('app.string.image_style_delete')), () => this.confirmDeleteStyle(style))
                  }
                }
              }.width('100%')
            }, (style: ImageStyle) => `${style.id}-${style.version}`)
          }.width('100%')
        }.height(220).width('100%')
        if (this.styleEditor) {
          Text($r('app.string.image_style_editor_help')).fontSize(12).fontColor(AppTheme.textSecondary)
          TextInput({ text: this.styleName, placeholder: this.imageLabel($r('app.string.image_style_name')) })
            .maxLength(80).onChange((value: string) => { this.styleName = value; })
          TextArea({ text: this.styleTemplate }).height(110).maxLength(2000)
            .onChange((value: string) => { this.styleTemplate = value; })
          Text(this.styleTemplate.split('{prompt}').join(this.prompt.trim() || this.imageLabel($r('app.string.image_style_sample'))))
            .fontSize(12).fontColor(AppTheme.textSecondary).copyOption(CopyOptions.InApp)
          Row({ space: 8 }) {
            Button($r('app.string.image_style_save')).enabled(this.validStyleTemplate()).onClick(() => this.saveStyle())
            this.QuietButton(this.imageLabel($r('app.string.image_style_cancel')), () => { this.styleEditor = false; })
          }
        }
        if (this.styleMessage.length > 0) {
          Text(this.styleMessage).fontSize(12).fontColor(AppTheme.textSecondary)
        }
      }
    }.width('100%').padding(16).backgroundColor(AppTheme.surface).borderRadius(20)
      .alignItems(HorizontalAlign.Start).enabled(!this.busy && !this.styleBusy)
  }
  @Builder
  private Composer() {
    Column({ space: 10 }) {
      Column({ space: 4 }) {
        Text('让想象，成为画面').fontSize(24).fontWeight(FontWeight.Bold).fontColor(AppTheme.ink)
        Text('从一句描述开始，创作属于你的影像。').fontSize(13).fontColor(AppTheme.textSecondary)
      }.width('100%').alignItems(HorizontalAlign.Start).padding({ top: 8, bottom: 4 })
      if (this.mode !== 'IMAGE_TO_PROMPT') {
      Column({ space: 12 }) {
        Row() {
          Text('画面描述').fontSize(14).fontWeight(FontWeight.Medium).layoutWeight(1)
          Text(`${this.prompt.length} / 4000`).fontSize(10).fontColor(AppTheme.textTertiary)
        }.width('100%')
        TextArea({ text: this.prompt, placeholder: '想画什么？\n描述人物形象、动作、场景与光线…' })
          .height(76).fontSize(15).fontColor(AppTheme.ink).backgroundColor(Color.Transparent)
          .padding(0).maxLength(4000).enabled(!this.busy)
          .onChange((value: string) => { this.prompt = value; this.pending = undefined; })
        Row() {
          Text('主体 · 风格 · 构图 · 光线').fontSize(11).fontColor(AppTheme.textTertiary).layoutWeight(1)
          if (this.prompt.length > 0) {
            Text('清空').fontSize(12).fontColor(AppTheme.textSecondary).padding(8).enabled(!this.busy)
              .onClick(() => { this.prompt = ''; this.pending = undefined; })
          }
        }.width('100%')
      }.padding(16).width('100%').backgroundColor(AppTheme.surface).borderRadius(20)
        .border({ width: 1, color: AppTheme.divider })
      }
      if (this.styleEnabled()) { this.StyleControls() }
      Column({ space: 10 }) {
        Row() {
          Text('生成模型').fontSize(14).fontWeight(FontWeight.Medium).layoutWeight(1)
          Text(this.showModels ? '收起' : '切换模型').fontSize(12).fontColor(AppTheme.primary)
            .padding({ left: 12, top: 5, bottom: 5 }).onClick(() => this.showModels = !this.showModels)
        }.width('100%')
        ForEach(this.showModels ? this.models : this.models.filter((model: ImageModel) => model.id === this.modelId), (model: ImageModel) => {
          Row({ space: 12 }) {
            SymbolGlyph($r('sys.symbol.picture')).fontSize(21)
              .fontColor([this.modelId === model.id ? AppTheme.primary : AppTheme.textTertiary])
            Column({ space: 3 }) {
              Text(model.id === 'vision-local' ? this.imageLabel($r('app.string.image_mode_prompt')) : model.name).fontSize(14).fontWeight(FontWeight.Medium).fontColor(AppTheme.ink)
              Text(`${model.location === 'LOCAL' ? '本地运行' : '远端服务'} · ${model.status === 'READY' ? '已就绪' : '暂不可用'}`)
                .fontSize(11).fontColor(AppTheme.textSecondary)
            }.layoutWeight(1).alignItems(HorizontalAlign.Start)
            if (this.modelId === model.id) {
              SymbolGlyph($r('sys.symbol.checkmark_circle_fill')).fontSize(18).fontColor([AppTheme.primary])
            }
          }.width('100%').padding(14).borderRadius(14).backgroundColor(AppTheme.surface)
            .border({ width: 1, color: this.modelId === model.id ? AppTheme.primary : AppTheme.divider })
            .enabled(!this.busy).onClick(() => { this.selectModel(model); this.showModels = false; })
        }, (model: ImageModel) => `${model.id}:${model.status}`)
      }.width('100%')
      if (this.ready()) {
        Column({ space: 10 }) {
          if ((this.selected()?.modes.length ?? 0) > 1) {
            Row({ space: 8 }) {
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
            Text('画面尺寸').fontSize(13).fontWeight(FontWeight.Medium).layoutWeight(1)
            Text(this.imageSize.replace('x', ' × ')).fontSize(10).fontColor(AppTheme.textTertiary)
          }.width('100%')
          Row({ space: 8 }) {
            ForEach(this.selected()?.sizes ?? [], (size: string) => {
              this.Choice(size === '1024x1024' ? '方形' : size === '832x1216' ? '竖版' : size === '1216x832' ? '横版' : size,
                size, 'size')
            })
          }.width('100%')
          Row({ space: 10 }) {
            Text('生成数量').fontSize(13).fontWeight(FontWeight.Medium).layoutWeight(1)
            Row({ space: 6 }) {
              ForEach(this.selected()?.counts ?? [], (count: number) => {
                this.Choice(`${count} 张`, `${count}`, 'count')
              })
            }.width('68%')
          }.width('100%')
          if (this.selected()?.location === 'LOCAL') {
            Row() {
              Text('高级设置').fontSize(12).fontColor(AppTheme.textSecondary).layoutWeight(1)
              Text(this.showAdvanced ? '收起' : this.seedText.length > 0 ? '固定种子' : '随机种子').fontSize(11).fontColor(AppTheme.textTertiary)
              SymbolGlyph($r('sys.symbol.chevron_right')).fontSize(10).fontColor([AppTheme.textTertiary]).margin({ left: 6 })
            }.width('100%').height(30).onClick(() => this.showAdvanced = !this.showAdvanced)
            if (this.showAdvanced) {
              TextInput({ text: this.seedText, placeholder: '种子留空，每次探索新画面' }).height(40).fontSize(12)
                .backgroundColor(AppTheme.background).type(InputType.Number).maxLength(10)
                .onChange((value: string) => { this.seedText = value; this.pending = undefined; })
            }
          }
          }
        }.width('100%').padding(12).borderRadius(18).backgroundColor(AppTheme.surface).enabled(!this.busy)
      }
      Button(this.busy ? '正在提交…' : this.ready() ? (this.mode === 'IMAGE_TO_PROMPT' ? this.imageLabel($r('app.string.image_extract_prompt')) : '生成图片') : this.models.length === 0 ? '正在连接图片服务…' : '当前模型暂不可用').type(ButtonType.Normal)
        .width('100%').height(50).fontSize(16).fontWeight(FontWeight.Medium).fontColor(Color.White)
        .backgroundColor(AppTheme.primary).borderRadius(14)
        .enabled(this.ready() && !this.busy && (this.mode === 'IMAGE_TO_PROMPT' || this.prompt.trim().length > 0) && (this.mode === 'TEXT_TO_IMAGE' || this.references.length > 0))
        .onClick(() => this.generate())
      Text('生成后可在「我的作品」查看进度和保存图片').fontSize(11).fontColor(AppTheme.textTertiary)
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
        Button('关闭').height(40).fontSize(14).fontColor(Color.White).backgroundColor('#292929')
          .onClick(() => this.viewerVisible = false)
        Blank()
        Text(`${Math.round(this.viewerScale * 100)}%`).fontSize(13).fontColor('#C4C4C4')
        Button('复位').height(40).fontSize(14).fontColor(Color.White).backgroundColor('#292929')
          .margin({ left: 14 }).onClick(() => this.resetViewer())
      }.width('100%').padding({ left: 16, right: 16, top: 48, bottom: 12 })
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
      Text('双指缩放 · 双击放大 · 拖动查看').fontSize(12).fontColor('#A0A0A0').padding(18)
    }.width('100%').height('100%').backgroundColor('#101010')
  }
  @Builder
  private Works() {
    Column({ space: 18 }) {
      Row() {
        Text('每一次想象，都值得收藏').fontSize(13).fontColor(AppTheme.textSecondary).layoutWeight(1)
        Text(`第 ${this.page + 1} 页`).fontSize(11).fontColor(AppTheme.textTertiary)
      }.width('100%')
      if (this.jobs.length === 0) {
        Column({ space: 12 }) {
          SymbolGlyph($r('sys.symbol.picture')).fontSize(36).fontColor([AppTheme.primary])
          Text('你的第一幅作品，从这里开始').fontSize(16).fontWeight(FontWeight.Medium)
          Text('写下想法，让画面慢慢浮现。').fontSize(13).fontColor(AppTheme.textSecondary)
          this.QuietButton('开始创作', () => this.tab = 'create')
        }.width('100%').padding({ top: 60, bottom: 60 }).backgroundColor(AppTheme.surface).borderRadius(20)
      }
      ForEach(this.jobs, (job: ImageJob) => {
        Column({ space: 0 }) {
          ForEach(job.result.images ?? [], (item: GeneratedImage) => {
            if (this.previewUri(job, item).length > 0) {
              Image(this.previewUri(job, item)).width('100%').aspectRatio(1).objectFit(ImageFit.Contain)
                .backgroundColor('#E8ECE9').accessibilityText('点击全屏查看作品')
                .onClick(() => this.openViewer(job, item))
            }
            Row({ space: 8 }) {
              Text(`${item.index + 1} / ${job.result.images?.length ?? 0}`).fontSize(11).fontColor(AppTheme.textTertiary).layoutWeight(1)
              this.QuietButton('保存图片', () => this.save(job, item))
              if (this.models.some((model: ImageModel) => model.status === 'READY' && (model.modes.includes('IMAGE_TO_IMAGE') || model.modes.includes('STYLE_REFERENCE')))) {
                this.QuietButton('用作底稿', () => this.useReference(job, item))
              }
            }.width('100%').padding({ left: 14, right: 14, top: 10 })
          }, (item: GeneratedImage) => `${job.id}-${item.index}`)
          Column({ space: 10 }) {
            Row() {
              Text(this.status(job.status)).fontSize(11).fontWeight(FontWeight.Medium)
                .fontColor(this.active(job) || job.status === 'SUCCEEDED' ? AppTheme.primary : AppTheme.textSecondary)
                .backgroundColor(AppTheme.accentSoft).borderRadius(6).padding({ left: 8, right: 8, top: 4, bottom: 4 })
              Blank()
              if (job.status === 'QUEUED' || job.status === 'RUNNING') { this.QuietButton('取消', () => this.cancel(job)) }
              this.QuietButton('再次创作', () => this.reuseParameters(job))
            }.width('100%')
            if (job.result.prompt) {
              Text(job.result.prompt).fontSize(14).lineHeight(21).fontColor(AppTheme.ink).width('100%').copyOption(CopyOptions.InApp)
              this.QuietButton(this.imageLabel($r('app.string.image_use_prompt')), () => this.useExtractedPrompt(job))
              Text(this.imageLabel($r('app.string.image_prompt_notice'))).fontSize(11).fontColor(AppTheme.textSecondary)
            }
            if (job.request.styleSnapshot !== undefined) {
              Text(`${this.styleLabel(job.request.styleSnapshot)} · v${job.request.styleSnapshot.version}`).fontSize(12).fontColor(AppTheme.primary)
            }
            Text(job.request.prompt).fontSize(14).lineHeight(21).fontColor(AppTheme.ink).maxLines(3)
              .textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
            Text(`${this.modelName(job.request.resourceId)} · ${job.request.size.replace('x', ' × ')} · ${job.request.count} 张`)
              .fontSize(10).fontColor(AppTheme.textTertiary).width('100%')
            if (job.result.unconfirmed === true) {
              Text('结果尚未确认，请先核对，避免重复生成。远端请求可能已经计费。').fontSize(12).fontColor(AppTheme.textSecondary)
            }
            Text(this.elapsedLabel(job)).width('100%').textAlign(TextAlign.End).fontSize(11)
              .fontColor(AppTheme.textSecondary).accessibilityText(`${this.elapsedLabel(job)}，包含排队、生成和保存`)
          }.padding(14).width('100%').alignItems(HorizontalAlign.Start)
        }.width('100%').borderRadius(20).clip(true).backgroundColor(AppTheme.surface)
          .border({ width: 1, color: AppTheme.divider })
      }, (job: ImageJob) => `${job.id}:${job.status}:${job.errorCode}:${job.result.images?.length ?? 0}:${job.result.unconfirmed ?? false}:${job.elapsedMillis ?? -1}`)
      Row({ space: 16 }) {
        Button('上一页').height(36).fontSize(12).backgroundColor(AppTheme.surface).fontColor(AppTheme.textSecondary)
          .enabled(this.page > 0).onClick(() => { this.page--; this.clearPreviews(); this.refresh(); })
        Text(`${this.page + 1}`).fontSize(12).fontColor(AppTheme.textTertiary)
        Button('下一页').height(36).fontSize(12).backgroundColor(AppTheme.surface).fontColor(AppTheme.textSecondary)
          .enabled(this.jobs.length === 20).onClick(() => { this.page++; this.clearPreviews(); this.refresh(); })
      }.width('100%').justifyContent(FlexAlign.Center)
    }.width('100%')
  }
  build() {
    Column({ space: 14 }) {
      Row({ space: 12 }) {
        Button() {
          SymbolGlyph($r('sys.symbol.chevron_left')).fontSize(18).fontColor([AppTheme.ink])
        }.width(36).height(36).backgroundColor(AppTheme.surface).borderRadius(12).accessibilityText('返回').onClick(() => this.onBack())
        Text('图片工作台').fontSize(20).fontWeight(FontWeight.Bold).fontColor(AppTheme.ink).layoutWeight(1)
        this.QuietButton('刷新', () => this.refresh())
      }.width('100%')
      Row({ space: 4 }) {
        Button('创作').type(ButtonType.Normal).layoutWeight(1).height(40).fontSize(14).fontWeight(FontWeight.Medium)
          .fontColor(this.tab === 'create' ? AppTheme.ink : AppTheme.textSecondary)
          .backgroundColor(this.tab === 'create' ? AppTheme.surface : Color.Transparent).borderRadius(11).onClick(() => this.tab = 'create')
        Button('我的作品').type(ButtonType.Normal).layoutWeight(1).height(40).fontSize(14).fontWeight(FontWeight.Medium)
          .fontColor(this.tab === 'works' ? AppTheme.ink : AppTheme.textSecondary)
          .backgroundColor(this.tab === 'works' ? AppTheme.surface : Color.Transparent).borderRadius(11).onClick(() => this.tab = 'works')
      }.width('100%').padding(4).backgroundColor('#E5EBE7').borderRadius(14)
      if (this.tab === 'create') { this.Composer() } else { this.Works() }
      if (this.message.length > 0) {
        Text(this.message).fontSize(12).fontColor(AppTheme.textSecondary).width('100%').padding(12)
          .backgroundColor(AppTheme.accentSoft).borderRadius(12)
      }
    }.width('100%').padding({ top: 4, bottom: 16 }).alignItems(HorizontalAlign.Start)
      .bindContentCover($$this.viewerVisible, this.FullscreenViewer(), { modalTransition: ModalTransition.NONE })
  }
}
