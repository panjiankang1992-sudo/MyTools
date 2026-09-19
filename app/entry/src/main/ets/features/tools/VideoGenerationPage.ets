import { AppTheme } from '../../theme/AppTheme';
import { common } from '@kit.AbilityKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import picker from '@ohos.file.picker';
import fs from '@ohos.file.fs';
import util from '@ohos.util';
import { AuthSessionManager } from '../auth/AuthSessionManager';
import { VideoGenerationApi, VideoInput, VideoJob, VideoModeSpec, VideoModel, VideoOutput,
  VideoRequest } from './VideoGenerationApi';

/** 一个素材槽位：角色、需要的素材类型，以及是否必须填写。 */
interface VideoInputSlot { index: number; role: string; kind: string; optional: boolean; }
/** 已缓存到本地的封面。 */
interface VideoCover { id: string; path: string; }
/** 已缓存到本地的成片，避免重复下载。 */
interface VideoPlayback { id: string; path: string; }

/** 进行中集合：只有这些状态才需要轮询，其余终态必须停止轮询。 */
const ACTIVE_STATUS: string[] = ['QUEUED', 'RUNNING', 'PERSISTING', 'CANCEL_REQUESTED'];
/** 每页作品数量，与服务端 LIMIT 20 保持一致。 */
const PAGE_SIZE: number = 20;
/** 轮询间隔：终态即停止，避免页面在后台空转。 */
const POLL_INTERVAL_MS: number = 5000;

/** 视频工作台：能力选择、素材上传、异步任务与作品回看。 */
@Component
export struct VideoGenerationPage {
  @Prop @Watch('resetAccount') baseUrl: string = '';
  @Prop @Watch('resetAccount') accountKey: string = '';
  authManager: AuthSessionManager = new AuthSessionManager();
  onBack: () => void = () => {};
  @State models: VideoModel[] = [];
  @State modelId: string = '';
  @State mode: string = '';
  @State entry: string = 'image';
  @State prompt: string = '';
  @State uploadIds: string[] = [];
  @State jobs: VideoJob[] = [];
  @State covers: VideoCover[] = [];
  @State message: string = '';
  @State busy: boolean = false;
  @State tab: string = 'create';
  @State page: number = 0;
  @State progressText: string = '';
  @State playerUri: string = '';
  @State playerTitle: string = '';
  private players: VideoPlayback[] = [];
  private files: string[] = [];
  private revision: number = 0;
  private timer: number = -1;
  private refreshSequence: number = 0;
  private videoController: VideoController = new VideoController();
  private pending: VideoRequest | undefined = undefined;

  aboutToAppear(): void { this.refresh(); }
  aboutToDisappear(): void { this.teardown(); }
  /** 切换账号时必须停止轮询、释放播放器并清空上一账号的全部状态。 */
  private resetAccount(): void {
    this.teardown();
    this.models = []; this.modelId = ''; this.mode = ''; this.prompt = ''; this.uploadIds = [];
    this.jobs = []; this.message = ''; this.busy = false; this.tab = 'create'; this.page = 0;
    this.pending = undefined;
    this.refresh();
  }
  /** 退出页面：停止轮询、释放播放器、删除本次会话写入缓存的封面与成片。 */
  private teardown(): void {
    this.revision++;
    this.stopPolling();
    this.releasePlayer();
    for (const path of this.files) this.removeFile(path);
    this.files = [];
    this.players = [];
    this.covers = [];
  }
  private api(): VideoGenerationApi { return new VideoGenerationApi(this.baseUrl, this.authManager); }
  private selectedModel(): VideoModel | undefined {
    return this.models.find((item: VideoModel) => item.id === this.modelId);
  }
  private catalogModes(): VideoModeSpec[] { return this.selectedModel()?.modes ?? []; }
  /** 三入口与模式的固定映射：文字创作、图片动起来、视频改编。 */
  private static readonly ENTRY_MODES: Record<string, string[]> = {
    'text': ['TEXT_TO_VIDEO'],
    'image': ['FIRST_FRAME', 'SUBJECT_REFERENCES', 'FIRST_LAST_FRAMES'],
    'video': ['STRUCTURE_RESTYLE', 'MASKED_EDIT']
  };
  /** 当前入口下的模式；入口只做筛选，不改变服务端契约。 */
  private entryModes(): VideoModeSpec[] {
    const allowed = VideoGenerationPage.ENTRY_MODES[this.entry] ?? [];
    return this.catalogModes().filter((item: VideoModeSpec) => allowed.includes(item.mode));
  }
  /** 该入口是否至少有一个已验收模式，用于提示用户哪些入口现在能用。 */
  private entryUsable(entry: string): boolean {
    const allowed = VideoGenerationPage.ENTRY_MODES[entry] ?? [];
    return this.catalogModes().some((item: VideoModeSpec) =>
      allowed.includes(item.mode) && item.validated);
  }
  private entryLabel(entry: string): string {
    if (entry === 'text') return '文字创作';
    if (entry === 'image') return '图片动起来';
    return '视频改编';
  }
  private entryHint(entry: string): string {
    if (entry === 'text') return '只给描述，从零生成';
    if (entry === 'image') return '拿一张（或两张）图当首帧/主体';
    return '拿一段原片做重绘或局部修改';
  }
  /** 切换入口时自动落到该入口下第一个已验收模式，没有可用模式则清空选择。 */
  private selectEntry(entry: string): void {
    if (this.busy || this.entry === entry) return;
    this.entry = entry;
    const usable = this.entryModes().filter((item: VideoModeSpec) => item.validated);
    this.mode = usable.length > 0 ? usable[0].mode : '';
    this.uploadIds = [];
    this.syncInputs();
    this.pending = undefined;
    this.message = usable.length > 0 ? '' :
      `「${this.entryLabel(entry)}」下还没有通过验收的模式。`;
  }
  private selectedMode(): VideoModeSpec | undefined {
    return this.catalogModes().find((item: VideoModeSpec) => item.mode === this.mode);
  }
  private validatedModes(): VideoModeSpec[] {
    return this.catalogModes().filter((item: VideoModeSpec) => item.validated);
  }
  private active(job: VideoJob): boolean { return ACTIVE_STATUS.includes(job.status); }
  private showable(job: VideoJob): boolean {
    return job.status === 'SUCCEEDED' || job.status === 'PARTIAL_SUCCESS';
  }
  private slots(): VideoInputSlot[] {
    const spec = this.selectedMode();
    const list: VideoInputSlot[] = [];
    if (spec === undefined) return list;
    for (let index = 0; index < spec.maxInputs; index++) {
      const roleIndex = spec.roles.length === 0 ? -1 : Math.min(index, spec.roles.length - 1);
      const slot: VideoInputSlot = {
        index: index,
        role: roleIndex < 0 ? '' : spec.roles[roleIndex].name,
        kind: roleIndex < 0 ? 'IMAGE' : spec.roles[roleIndex].kind,
        // 前 minInputs 个槽位是必填的，其余为可选；顺序即服务端语义顺序。
        optional: index >= spec.minInputs
      };
      list.push(slot);
    }
    return list;
  }
  /** 槽位数量必须与已选素材一一对应，模式变化后补齐或截断。 */
  private syncInputs(): void {
    const total = this.slots().length;
    if (this.uploadIds.length === total) return;
    const values: string[] = [];
    for (let index = 0; index < total; index++) {
      values.push(index < this.uploadIds.length ? this.uploadIds[index] : '');
    }
    this.uploadIds = values;
  }
  private chosenCount(): number {
    return this.uploadIds.filter((value: string) => value.length > 0).length;
  }
  private requiredFilled(): boolean {
    const spec = this.selectedMode();
    if (spec === undefined) return false;
    if (this.chosenCount() > spec.maxInputs) return false;
    for (let index = 0; index < spec.minInputs; index++) {
      if (index >= this.uploadIds.length || this.uploadIds[index].length === 0) return false;
    }
    return true;
  }
  /** 只有目录里通过验收、素材齐备且描述合法的模式才允许提交。 */
  private canSubmit(): boolean {
    const model = this.selectedModel();
    const spec = this.selectedMode();
    if (this.busy || model === undefined || spec === undefined) return false;
    if (model.status !== 'READY' || !spec.validated) return false;
    if (spec.requiresPrompt && this.prompt.trim().length === 0) return false;
    if (this.prompt.length > 4000) return false;
    return this.requiredFilled();
  }
  private submitLabel(): string {
    if (this.busy) return '处理中…';
    if (this.models.length === 0) return '正在连接视频服务…';
    if (this.validatedModes().length === 0) return '当前没有通过验收的模式';
    const model = this.selectedModel();
    if (model !== undefined && model.status !== 'READY') return '视频模型尚未就绪';
    if (this.selectedMode() === undefined) return '请选择生成模式';
    return '生成视频';
  }
  private hintText(): string {
    if (this.models.length === 0) return '正在读取视频能力目录…';
    if (this.validatedModes().length === 0) return '当前没有通过验收的模式，暂时不能生成。';
    const spec = this.selectedMode();
    if (spec === undefined) return '请选择一个已验收的模式。';
    return `需要 ${spec.minInputs} - ${spec.maxInputs} 个素材 · 固定 ${this.outputSummary()}`;
  }
  /** 固定输出：尺寸、帧数与帧率都不对用户开放其他取值。 */
  private outputSummary(): string {
    const model = this.selectedModel();
    const spec = this.selectedMode();
    const size = model === undefined || model.sizes.length === 0 ? '832x480' : model.sizes[0];
    const frames = spec === undefined || spec.frames.length === 0 ? 49 : spec.frames[0];
    const fps = model === undefined ? 16 : model.fps;
    return `${size.replace('x', ' × ')} · ${frames} 帧 · ${fps} fps`;
  }
  private reasonText(spec: VideoModeSpec): string {
    return spec.reasonCode.length > 0 ? spec.reasonCode : 'REASON_UNKNOWN';
  }
  private modeLabel(mode: string): string {
    if (mode === 'TEXT_TO_VIDEO') return '文生视频';
    if (mode === 'FIRST_FRAME') return '单图首帧';
    if (mode === 'SUBJECT_REFERENCES') return '双图主体参考';
    if (mode === 'FIRST_LAST_FRAMES') return '首尾帧过渡';
    if (mode === 'STRUCTURE_RESTYLE') return '视频整体重绘';
    if (mode === 'MASKED_EDIT') return '视频局部修改';
    return mode;
  }
  private roleLabel(role: string): string {
    if (role === 'FIRST_FRAME') return '首帧图片';
    if (role === 'LAST_FRAME') return '尾帧图片';
    if (role === 'SUBJECT') return '参考主体图片';
    if (role === 'SOURCE_VIDEO') return '源视频';
    if (role === 'MASK_VIDEO') return '遮罩视频';
    return role.length > 0 ? role : '素材';
  }
  private statusLabel(value: string): string {
    if (value === 'QUEUED') return '排队中';
    if (value === 'RUNNING') return '正在生成';
    if (value === 'PERSISTING') return '正在保存';
    if (value === 'CANCEL_REQUESTED') return '正在取消';
    if (value === 'SUCCEEDED') return '已完成';
    if (value === 'PARTIAL_SUCCESS') return '部分完成';
    if (value === 'FAILED') return '生成失败';
    if (value === 'CANCELLED') return '已取消';
    if (value === 'TIMED_OUT') return '已超时';
    return value;
  }
  /** 服务端错误消息就是稳定错误码；只要文本里出现错误码就按错误码解释。 */
  private rawCode(value: string): string {
    if (typeof value !== 'string') return '';
    const matched = value.match(/VIDEO_[0-9]{3}/);
    return matched === null ? '' : matched[0];
  }
  private rawMessage(error: Object): string {
    return error instanceof Error ? error.message : '';
  }
  private failureText(error: Object): string {
    const raw = this.rawMessage(error);
    // 稳定错误码走映射表；客户端已经中文化的提示直接展示，避免套上误导性的兜底前缀。
    if (this.rawCode(raw).length > 0) return this.errorText(raw);
    return raw.length > 0 ? raw : '操作失败，请稍后重试。';
  }
  /** 网关错误体与任务详情 errorCode 共用同一张中文映射表。 */
  private errorText(raw: string): string {
    const code = this.rawCode(raw);
    if (code === 'VIDEO_001') return '输入无效：请检查素材、描述与参数是否符合要求。';
    if (code === 'VIDEO_002') return '该模式暂无可用能力，请换一个已验收的模式。';
    if (code === 'VIDEO_003') return '服务端资源不足，暂时无法受理，请稍后再试。';
    if (code === 'VIDEO_004') return '推理失败，可以调整描述或素材后重试。';
    if (code === 'VIDEO_005') return '生成超时，请稍后重试。';
    if (code === 'VIDEO_006') return '成片保存失败，服务端可重试登记。';
    if (code === 'VIDEO_007') return '登录状态已失效，请重新登录后再试。';
    if (code === 'VIDEO_008') return '重复提交：同一任务编号对应了不同内容。';
    if (code === 'VIDEO_009') return '任务、素材或成片不存在，或当前账户不可见。';
    if (code === 'VIDEO_010') return '视频服务暂不可用，请稍后重试。';
    // 表外的码不允许显示成成功或空白，统一兜底并带上原始错误码。
    if (code.length > 0) return `生成失败，请稍后重试（${code}）`;
    if (raw.length === 0) return '生成失败，请稍后重试。';
    return `生成失败，请稍后重试（${raw.substring(0, 80)}）`;
  }
  private elapsedLabel(job: VideoJob): string {
    const seconds = Math.floor(Math.max(0, job.elapsedMillis) / 1000);
    const duration = seconds < 60 ? `${seconds}秒` : seconds < 3600 ?
      `${Math.floor(seconds / 60)}分${seconds % 60}秒` :
      `${Math.floor(seconds / 3600)}小时${Math.floor(seconds % 3600 / 60)}分`;
    return `${this.active(job) ? '已用时' : '总耗时'} ${duration}`;
  }
  private cachePath(name: string, suffix: string): string {
    const context = this.getUIContext().getHostContext();
    return context === undefined ? '' :
      `${context.cacheDir}/video-generation-${name}-${util.generateRandomUUID()}${suffix}`;
  }
  private removeFile(path: string): void {
    if (path.length === 0) return;
    try { fs.unlinkSync(path); } catch (_) {}
  }
  private coverUri(job: VideoJob): string {
    const found = this.covers.find((item: VideoCover) => item.id === job.id);
    return found === undefined ? '' : `file://${found.path}`;
  }
  private stopPolling(): void {
    if (this.timer !== -1) clearTimeout(this.timer);
    this.timer = -1;
  }
  /** 只有存在进行中的任务才续期轮询；全部进入终态后自动停止。 */
  private schedule(): void {
    this.stopPolling();
    if (!this.jobs.some((job: VideoJob) => this.active(job))) return;
    this.timer = setTimeout(() => this.refresh(), POLL_INTERVAL_MS);
  }
  private async refresh(): Promise<void> {
    const revision = this.revision;
    const sequence = ++this.refreshSequence;
    this.stopPolling();
    try {
      const api = this.api();
      const models = await api.models();
      const jobs = await api.works(this.page);
      if (revision !== this.revision || sequence !== this.refreshSequence) return;
      this.models = models;
      this.jobs = jobs;
      this.ensureSelection();
      await this.loadCovers(jobs, revision, sequence);
    } catch (_) {
      if (revision === this.revision && sequence === this.refreshSequence) {
        this.message = '视频服务暂不可用，请稍后刷新；不会自动切换模式。';
      }
    } finally {
      if (revision === this.revision && sequence === this.refreshSequence) this.schedule();
    }
  }
  /** 默认选中第一个已就绪模型与第一个已验收模式；不存在时保持不可提交。 */
  private ensureSelection(): void {
    if (this.models.length === 0) {
      this.modelId = '';
      this.mode = '';
      this.syncInputs();
      return;
    }
    if (this.selectedModel() === undefined) {
      const ready = this.models.find((item: VideoModel) => item.status === 'READY');
      this.modelId = ready === undefined ? this.models[0].id : ready.id;
      this.mode = '';
      this.uploadIds = [];
      this.pending = undefined;
    }
    if (!this.entryUsable(this.entry)) {
      // 当前入口下没有已验收模式时，自动切到第一个可用入口，用户不必自己找。
      const usable = ['image', 'text', 'video'].find((item: string) => this.entryUsable(item));
      if (usable !== undefined) {
        this.entry = usable;
        this.mode = '';
        this.uploadIds = [];
        this.pending = undefined;
      }
    }
    if (this.selectedMode() === undefined) {
      const validated = this.entryModes().filter((item: VideoModeSpec) => item.validated);
      this.mode = validated.length === 0 ? '' : validated[0].mode;
      this.uploadIds = [];
      this.pending = undefined;
    }
    this.syncInputs();
  }
  private async loadCovers(jobs: VideoJob[], revision: number, sequence: number): Promise<void> {
    for (const job of jobs) {
      if (revision !== this.revision || sequence !== this.refreshSequence) return;
      if (!this.showable(job)) continue;
      if (this.covers.some((item: VideoCover) => item.id === job.id)) continue;
      await this.loadCover(job, revision);
    }
  }
  /** 封面体积小，成功后立即缓存，供历史列表展示。 */
  private async loadCover(job: VideoJob, revision: number): Promise<void> {
    const path = this.cachePath('cover', '.png');
    if (path.length === 0) return;
    try {
      await this.api().downloadCoverToUri(job.id, path);
      if (revision !== this.revision) { this.removeFile(path); return; }
      this.files = this.files.concat([path]);
      this.covers = this.covers.concat([{ id: job.id, path: path }]);
    } catch (_) {
      this.removeFile(path);
    }
  }
  private selectMode(spec: VideoModeSpec): void {
    if (this.busy) return;
    if (!spec.validated) {
      this.message = `「${this.modeLabel(spec.mode)}」未验收，暂不能使用（原因码：${this.reasonText(spec)}）`;
      return;
    }
    if (this.mode === spec.mode) return;
    this.mode = spec.mode;
    // 不同模式的角色顺序与数量都不同，切换必须清空素材，避免语义错位。
    this.uploadIds = [];
    this.syncInputs();
    this.pending = undefined;
    this.message = '';
  }
  private async pick(slot: VideoInputSlot): Promise<void> {
    if (this.busy) return;
    const revision = this.revision;
    this.busy = true;
    this.message = '';
    this.progressText = slot.kind === 'VIDEO' ? '正在读取视频素材…' : '正在读取图片素材…';
    try {
      const path = slot.kind === 'VIDEO' ? await this.pickVideo() : await this.pickImage();
      if (revision !== this.revision || path.length === 0) return;
      this.progressText = '正在上传素材，服务端会重新实测…';
      const uploadId = slot.kind === 'VIDEO' ?
        await this.api().uploadVideo(path) : await this.api().uploadImage(path);
      if (revision !== this.revision) return;
      this.setUpload(slot.index, uploadId);
      this.pending = undefined;
      this.message = `已添加${this.roleLabel(slot.role)}`;
    } catch (error) {
      if (revision === this.revision) this.message = this.failureText(error);
    } finally {
      if (revision === this.revision) {
        this.busy = false;
        this.progressText = '';
        this.schedule();
      }
    }
  }
  /** 图片使用系统图片选择器，返回的 uri 可直接读取原始字节。 */
  private async pickImage(): Promise<string> {
    const context = this.getUIContext().getHostContext();
    if (context === undefined) throw new Error('无法打开系统图片选择器');
    try {
      const options = new photoAccessHelper.PhotoSelectOptions();
      options.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE;
      options.maxSelectNumber = 1;
      const selected = await new photoAccessHelper.PhotoViewPicker().select(options);
      return selected.photoUris.length === 0 ? '' : selected.photoUris[0];
    } catch (_) {
      // 选择器取消与不可用都按“未选择素材”处理，不打断当前输入。
      return '';
    }
  }
  /** 视频使用系统文件选择器，只保留 MP4。 */
  private async pickVideo(): Promise<string> {
    const context = this.getUIContext().getHostContext();
    if (context === undefined) throw new Error('无法打开系统文件选择器');
    try {
      const options = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 1;
      options.fileSuffixFilters = ['MP4 视频|mp4'];
      const selected = await new picker.DocumentViewPicker(context as common.UIAbilityContext).select(options);
      return selected.length === 0 ? '' : selected[0];
    } catch (_) {
      return '';
    }
  }
  private setUpload(index: number, uploadId: string): void {
    if (index < 0 || index >= this.uploadIds.length) return;
    const values = this.uploadIds.slice();
    values[index] = uploadId;
    this.uploadIds = values;
  }
  private clearSlot(index: number): void {
    if (this.busy) return;
    this.setUpload(index, '');
    this.pending = undefined;
    this.message = '';
  }
  /** 提交前冻结请求体，失败重试复用同一幂等键，避免重复生成。 */
  private buildRequest(): VideoRequest {
    const model = this.selectedModel();
    const spec = this.selectedMode();
    const inputs: VideoInput[] = [];
    for (const slot of this.slots()) {
      if (slot.index >= this.uploadIds.length || this.uploadIds[slot.index].length === 0) continue;
      const input: VideoInput = { uploadId: this.uploadIds[slot.index], role: slot.role };
      inputs.push(input);
    }
    const output: VideoOutput = {
      size: model === undefined || model.sizes.length === 0 ? '832x480' : model.sizes[0],
      frames: spec === undefined || spec.frames.length === 0 ? 49 : spec.frames[0],
      fps: model === undefined ? 16 : model.fps
    };
    return {
      resourceId: this.modelId,
      mode: this.mode,
      prompt: this.prompt.trim(),
      inputs: inputs,
      output: output,
      seed: Math.floor(Math.random() * 2147483648),
      idempotencyKey: util.generateRandomUUID()
    };
  }
  private async generate(): Promise<void> {
    if (!this.canSubmit()) return;
    const revision = this.revision;
    this.busy = true;
    this.message = '';
    if (this.pending === undefined) this.pending = this.buildRequest();
    try {
      await this.api().create(this.pending);
      if (revision !== this.revision) return;
      this.pending = undefined;
      this.page = 0;
      this.tab = 'works';
      await this.refresh();
    } catch (error) {
      if (revision !== this.revision) return;
      const code = this.rawCode(this.rawMessage(error));
      if (code.length > 0) {
        // 服务端已明确拒绝，纠正后应按新意图提交，因此丢弃旧幂等键。
        this.pending = undefined;
        this.message = this.errorText(code);
      } else {
        this.message = '提交未确认，重试将复用同一任务编号，避免重复生成。';
      }
    } finally {
      if (revision === this.revision) this.busy = false;
    }
  }
  private async cancelJob(job: VideoJob): Promise<void> {
    const revision = this.revision;
    try {
      await this.api().cancel(job.id);
      if (revision === this.revision) await this.refresh();
    } catch (error) {
      if (revision === this.revision) this.message = this.failureText(error);
    }
  }
  /** 复用历史任务的参数；模式已不可用时明确拒绝而不是静默降级。 */
  private reuseParameters(job: VideoJob): void {
    if (this.busy) return;
    const request = job.request;
    const model = this.models.find((item: VideoModel) => item.id === request.resourceId);
    const spec = model === undefined ? undefined :
      model.modes.find((item: VideoModeSpec) => item.mode === request.mode);
    if (model === undefined || spec === undefined || !spec.validated || model.status !== 'READY') {
      this.message = '该任务使用的模式当前不可用，无法复用参数。';
      return;
    }
    this.modelId = model.id;
    this.mode = request.mode;
    this.prompt = request.prompt;
    this.uploadIds = [];
    this.syncInputs();
    const restored = request.inputs ?? [];
    const slots = this.slots();
    const values: string[] = [];
    for (let index = 0; index < slots.length; index++) {
      // 角色必须与当前目录一致，顺序错位会改变首尾帧语义。
      values.push(index < restored.length && restored[index].role === slots[index].role ?
        restored[index].uploadId : '');
    }
    this.uploadIds = values;
    this.pending = undefined;
    this.tab = 'create';
    this.message = '已恢复历史参数，确认后可以重新提交。';
  }
  /** 回看成片：复用本地缓存，缺失时下载到 cacheDir 后再交给播放器。 */
  private async play(job: VideoJob): Promise<void> {
    if (this.busy || !this.showable(job)) return;
    const revision = this.revision;
    const cached = this.players.find((item: VideoPlayback) => item.id === job.id);
    if (cached !== undefined) {
      this.attach(cached.path, job.id);
      return;
    }
    const path = this.cachePath('video', '.mp4');
    if (path.length === 0) { this.message = '无法获取应用缓存目录'; return; }
    this.busy = true;
    this.message = '';
    this.progressText = '正在下载成片…';
    try {
      await this.api().downloadVideoToUri(job.id, path, (received: number, total: number) => {
        if (revision !== this.revision) return;
        this.progressText = total > 0 ? `正在下载成片 ${Math.floor(received * 100 / total)}%` :
          `正在下载成片 ${Math.floor(received / (1024 * 1024))} MB`;
      });
      if (revision !== this.revision) { this.removeFile(path); return; }
      this.files = this.files.concat([path]);
      this.players = this.players.concat([{ id: job.id, path: path }]);
      this.attach(path, job.id);
    } catch (error) {
      this.removeFile(path);
      if (revision === this.revision) this.message = this.failureText(error);
    } finally {
      if (revision === this.revision) {
        this.busy = false;
        this.progressText = '';
        this.schedule();
      }
    }
  }
  private attach(path: string, jobId: string): void {
    this.attachUri(`file://${path}`, `成片 ${jobId.substring(0, 8)}`);
  }
  /** 交给播放器的既可能是本地缓存，也可能是带票据的在线地址。 */
  private attachUri(uri: string, title: string): void {
    this.releasePlayer();
    this.playerTitle = title;
    this.playerUri = uri;
  }
  /**
   * 在线播放：先用票据换取带随机令牌的地址，播放器按 Range 边下边播，不必等整段下完。
   * 票据失效或网关不可用时给出可读提示，不静默失败。
   */
  private async playOnline(job: VideoJob): Promise<void> {
    if (this.busy || !this.showable(job)) return;
    const revision = this.revision;
    this.busy = true;
    this.message = '';
    this.progressText = '正在获取播放地址…';
    try {
      const ticket = await this.api().videoTicket(job.id);
      if (revision !== this.revision) return;
      this.attachUri(ticket.url, `成片 ${job.id.substring(0, 8)}（在线）`);
    } catch (error) {
      if (revision === this.revision) this.message = this.failureText(error);
    } finally {
      if (revision === this.revision) {
        this.busy = false;
        this.progressText = '';
        this.schedule();
      }
    }
  }
  /** 原片对比：源视频模式播放上传的原片，方便与成片逐段对照。 */
  private async compareSource(job: VideoJob): Promise<void> {
    const upload = this.sourceUpload(job);
    if (this.busy || upload.length === 0) return;
    const revision = this.revision;
    this.busy = true;
    this.message = '';
    this.progressText = '正在获取原片播放地址…';
    try {
      const ticket = await this.api().sourceTicket(upload);
      if (revision !== this.revision) return;
      this.attachUri(ticket.url, `原片（对比 ${job.id.substring(0, 8)}）`);
    } catch (error) {
      if (revision === this.revision) this.message = this.failureText(error);
    } finally {
      if (revision === this.revision) {
        this.busy = false;
        this.progressText = '';
        this.schedule();
      }
    }
  }
  /** 取该任务提交的源视频素材；没有则说明这个模式没有原片可对比。 */
  private sourceUpload(job: VideoJob): string {
    const inputs = job.request.inputs === undefined ? [] : job.request.inputs;
    const source = inputs.find((item: VideoInput) => item.role === 'SOURCE_VIDEO');
    return source === undefined ? '' : source.uploadId;
  }
  private releasePlayer(): void {
    try { this.videoController.stop(); } catch (_) {}
    this.playerUri = '';
    this.playerTitle = '';
  }
  private leave(): void {
    this.teardown();
    this.onBack();
  }
  @Builder
  private QuietButton(label: string, action: () => void) {
    Button(label).type(ButtonType.Normal).height(34).fontSize(12).fontWeight(FontWeight.Medium)
      .fontColor(AppTheme.textSecondary).backgroundColor(AppTheme.surfaceTint).borderRadius(10)
      .padding({ left: 12, right: 12 }).enabled(!this.busy).onClick(action)
  }
  @Builder
  private ModeChips() {
    Column({ space: 8 }) {
      Row() {
        Text('生成模式').fontSize(14).fontWeight(FontWeight.Medium).layoutWeight(1)
        Text(`${this.entryModes().filter((item: VideoModeSpec) => item.validated).length} / ` +
          `${this.entryModes().length} 个模式已验收`)
          .fontSize(11).fontColor(AppTheme.textTertiary)
      }.width('100%')
      Row({ space: 8 }) {
        ForEach(['text', 'image', 'video'], (entry: string) => {
          Column({ space: 2 }) {
            Text(this.entryLabel(entry)).fontSize(12).fontWeight(FontWeight.Medium)
              .fontColor(this.entry === entry ? AppTheme.primary : AppTheme.ink)
            Text(this.entryUsable(entry) ? '可用' : '未验收').fontSize(9)
              .fontColor(this.entryUsable(entry) ? AppTheme.primary : AppTheme.textTertiary)
          }.layoutWeight(1).padding({ top: 8, bottom: 8 }).borderRadius(10)
            .backgroundColor(this.entry === entry ? AppTheme.accentSoft : AppTheme.background)
            .border({ width: 1, color: this.entry === entry ? AppTheme.primary : AppTheme.divider })
            .opacity(this.entryUsable(entry) ? 1 : 0.55)
            .onClick(() => this.selectEntry(entry))
            .accessibilityText(`${this.entryLabel(entry)}：${this.entryHint(entry)}`)
        }, (entry: string) => entry)
      }.width('100%')
      Text(this.entryHint(this.entry)).fontSize(11).fontColor(AppTheme.textTertiary).width('100%')
      ForEach(this.entryModes(), (spec: VideoModeSpec) => {
        Column({ space: 4 }) {
          Row({ space: 8 }) {
            Text(this.modeLabel(spec.mode)).fontSize(13).fontWeight(FontWeight.Medium).layoutWeight(1)
              .fontColor(spec.validated ? (this.mode === spec.mode ? AppTheme.primary : AppTheme.ink) :
                AppTheme.textTertiary)
            Text(spec.validated ? '已验收' : '未验收').fontSize(10)
              .fontColor(spec.validated ? AppTheme.primary : AppTheme.textTertiary)
              .backgroundColor(spec.validated ? AppTheme.accentSoft : AppTheme.surfaceTint)
              .borderRadius(6).padding({ left: 6, right: 6, top: 2, bottom: 2 })
          }.width('100%')
          Text(spec.validated ? this.modeContract(spec) : `未验收，禁止提交 · 原因码 ${this.reasonText(spec)}`)
            .fontSize(11).fontColor(AppTheme.textTertiary).width('100%')
        }.width('100%').padding(10).borderRadius(12)
          .backgroundColor(spec.validated && this.mode === spec.mode ? AppTheme.accentSoft : AppTheme.background)
          .border({ width: 1,
            color: spec.validated && this.mode === spec.mode ? AppTheme.primary : AppTheme.divider })
          .opacity(spec.validated ? 1 : 0.55)
          .enabled(spec.validated && !this.busy)
          .onClick(() => this.selectMode(spec))
      }, (spec: VideoModeSpec) => `${spec.mode}:${spec.validated}:${spec.reasonCode}`)
    }.width('100%').padding(14).backgroundColor(AppTheme.surface).borderRadius(20)
      .alignItems(HorizontalAlign.Start)
  }
  private modeContract(spec: VideoModeSpec): string {
    const frames = spec.frames.length === 0 ? 49 : spec.frames[0];
    return `${spec.minInputs} - ${spec.maxInputs} 个素材 · ${spec.requiresPrompt ? '描述必填' : '描述可选'} · ${frames} 帧`;
  }
  @Builder
  private InputSlots() {
    Column({ space: 8 }) {
      ForEach(this.slots(), (slot: VideoInputSlot) => {
        Row({ space: 10 }) {
          Column({ space: 3 }) {
            Text(`${this.roleLabel(slot.role)}${slot.optional ? '（可选）' : ''}`)
              .fontSize(13).fontWeight(FontWeight.Medium).fontColor(AppTheme.ink)
            Text(slot.kind === 'VIDEO' ? `${slot.optional ? '可选' : '必填'} · MP4，服务端实测不超过 200 MB` :
              `${slot.optional ? '可选' : '必填'} · PNG/JPEG，服务端实测不超过 20 MB`)
              .fontSize(11).fontColor(AppTheme.textTertiary)
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          Button(this.slotActionLabel(slot)).type(ButtonType.Normal).height(34).fontSize(12)
            .fontColor(AppTheme.primary).backgroundColor(AppTheme.accentSoft).borderRadius(10)
            .enabled(!this.busy).onClick(() => this.pick(slot))
          if (slot.index < this.uploadIds.length && this.uploadIds[slot.index].length > 0) {
            Button('清除').type(ButtonType.Normal).height(34).fontSize(12)
              .fontColor(AppTheme.textSecondary).backgroundColor(AppTheme.surfaceTint).borderRadius(10)
              .enabled(!this.busy).onClick(() => this.clearSlot(slot.index))
          }
        }.width('100%')
      }, (slot: VideoInputSlot) => `${slot.index}:${slot.role}:${slot.kind}`)
    }.width('100%')
  }
  private slotActionLabel(slot: VideoInputSlot): string {
    if (slot.index < this.uploadIds.length && this.uploadIds[slot.index].length > 0) return '重新选择';
    return slot.kind === 'VIDEO' ? '选择视频' : '选择图片';
  }
  private modePromptRequired(): boolean {
    const spec = this.selectedMode();
    return spec === undefined ? false : spec.requiresPrompt;
  }
  private modeSelected(): boolean { return this.selectedMode() !== undefined; }
  private promptLabel(): string {
    return this.modePromptRequired() ? '必填 · 不会自动扩写或上传外部服务' : '可选 · 留空则完全依赖素材';
  }
  private modelSummary(): string {
    const model = this.selectedModel();
    if (model === undefined) return '正在读取视频模型…';
    const location = model.location === 'LOCAL' ? '本机运行' : '远端服务';
    const status = model.status === 'READY' ? '已就绪' : `暂不可用（${model.reasonCode.length > 0 ?
      model.reasonCode : 'REASON_UNKNOWN'}）`;
    return `${model.name} · ${location} · ${status}`;
  }
  @Builder
  private Composer() {
    Column({ space: 12 }) {
      Column({ space: 4 }) {
        Text('让文字或图片，动起来').fontSize(24).fontWeight(FontWeight.Bold).fontColor(AppTheme.ink)
        Text('只展示通过验收的模式，成片参数固定不开放其他取值。')
          .fontSize(13).fontColor(AppTheme.textSecondary)
      }.width('100%').alignItems(HorizontalAlign.Start).padding({ top: 8, bottom: 4 })
      Column({ space: 6 }) {
        Text('视频模型').fontSize(13).fontWeight(FontWeight.Medium)
        Text(this.modelSummary()).fontSize(12).fontColor(AppTheme.textSecondary)
        Text(`固定输出 ${this.outputSummary()}`).fontSize(11).fontColor(AppTheme.textTertiary)
      }.width('100%').padding(14).backgroundColor(AppTheme.surface).borderRadius(20)
        .alignItems(HorizontalAlign.Start)
      this.ModeChips()
      if (this.modeSelected()) {
        Column({ space: 10 }) {
          Row() {
            Text('画面描述').fontSize(14).fontWeight(FontWeight.Medium).layoutWeight(1)
            Text(`${this.prompt.length} / 4000`).fontSize(10).fontColor(AppTheme.textTertiary)
          }.width('100%')
          TextArea({ text: this.prompt, placeholder: '描述主体、动作、运镜与光线…' })
            .height(88).fontSize(15).fontColor(AppTheme.ink).backgroundColor(Color.Transparent)
            .padding(0).maxLength(4000).enabled(!this.busy)
            .onChange((value: string) => { this.prompt = value; this.pending = undefined; })
          Row() {
            Text(this.promptLabel()).fontSize(11).fontColor(AppTheme.textTertiary).layoutWeight(1)
            if (this.prompt.length > 0) {
              Text('清空').fontSize(12).fontColor(AppTheme.textSecondary).padding(8).enabled(!this.busy)
                .onClick(() => { this.prompt = ''; this.pending = undefined; })
            }
          }.width('100%')
        }.padding(16).width('100%').backgroundColor(AppTheme.surface).borderRadius(20)
          .border({ width: 1, color: AppTheme.divider }).alignItems(HorizontalAlign.Start)
      }
      if (this.slots().length > 0) {
        Column({ space: 10 }) {
          Text('素材').fontSize(14).fontWeight(FontWeight.Medium).width('100%')
          this.InputSlots()
        }.padding(14).width('100%').backgroundColor(AppTheme.surface).borderRadius(20)
          .alignItems(HorizontalAlign.Start)
      }
      Button(this.submitLabel()).type(ButtonType.Normal).width('100%').height(50).fontSize(16)
        .fontWeight(FontWeight.Medium).fontColor(Color.White)
        .backgroundColor(this.canSubmit() ? AppTheme.primary : AppTheme.outline).borderRadius(14)
        .enabled(this.canSubmit()).onClick(() => this.generate())
      Text(this.hintText()).fontSize(11).fontColor(AppTheme.textTertiary)
        .accessibilityText(`提交提示：${this.hintText()}`)
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder
  private Player() {
    Column({ space: 8 }) {
      Row() {
        Text(this.playerTitle.length > 0 ? `正在播放 ${this.playerTitle}` : '正在播放')
          .fontSize(13).fontWeight(FontWeight.Medium).layoutWeight(1).fontColor(AppTheme.ink)
        this.QuietButton('停止播放', () => this.releasePlayer())
      }.width('100%')
      Video({ src: this.playerUri, controller: this.videoController })
        .width('100%').height(220).autoPlay(true).controls(true).objectFit(ImageFit.Contain)
        .onPrepared(() => { this.progressText = ''; })
        .onError(() => { this.message = '播放失败：本地成片可重试，在线播放请重新获取播放地址。'; })
    }.width('100%').padding(12).backgroundColor(AppTheme.surface).borderRadius(20)
  }
  @Builder
  private WorksCard(job: VideoJob) {
    Column({ space: 10 }) {
      if (this.coverUri(job).length > 0) {
        Stack({ alignContent: Alignment.Center }) {
          Image(this.coverUri(job)).width('100%').height(160).objectFit(ImageFit.Cover)
            .backgroundColor(AppTheme.surfaceTint).accessibilityText('成片封面，点击播放')
          Text('播放成片').fontSize(12).fontColor(Color.White).padding({ left: 12, right: 12, top: 6, bottom: 6 })
            .backgroundColor('#66000000').borderRadius(10)
        }.width('100%').height(160).clip(true).borderRadius(12)
          .accessibilityText('点击播放成片')
          .onClick(() => this.play(job))
      }
      Row({ space: 8 }) {
        Text(this.statusLabel(job.status)).fontSize(11).fontWeight(FontWeight.Medium)
          .fontColor(this.showable(job) || this.active(job) ? AppTheme.primary : AppTheme.textSecondary)
          .backgroundColor(AppTheme.accentSoft).borderRadius(6).padding({ left: 8, right: 8, top: 4, bottom: 4 })
        Text(this.elapsedLabel(job)).fontSize(11).fontColor(AppTheme.textTertiary).layoutWeight(1)
      }.width('100%')
      Text(job.request.prompt === undefined || job.request.prompt.length === 0 ? '（无描述）' : job.request.prompt)
        .fontSize(14).lineHeight(21).fontColor(AppTheme.ink).maxLines(3)
        .textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
      Text(`${this.modeLabel(job.request.mode)} · ${this.jobOutputLabel(job)}`)
        .fontSize(10).fontColor(AppTheme.textTertiary).width('100%')
      if (job.errorCode.length > 0) {
        Text(this.errorText(job.errorCode)).fontSize(12).fontColor(AppTheme.danger).width('100%')
      }
      Row({ space: 8 }) {
        if (this.showable(job)) {
          this.QuietButton('播放成片', () => this.play(job))
          this.QuietButton('在线播放', () => this.playOnline(job))
        }
        if (this.showable(job) && this.sourceUpload(job).length > 0) {
          this.QuietButton('对比原片', () => this.compareSource(job))
        }
        if (job.status === 'QUEUED' || job.status === 'RUNNING') {
          this.QuietButton('取消', () => this.cancelJob(job))
        }
        this.QuietButton('再次创作', () => this.reuseParameters(job))
      }.width('100%')
    }.width('100%').padding(12).backgroundColor(AppTheme.surface).borderRadius(20)
      .border({ width: 1, color: AppTheme.divider }).alignItems(HorizontalAlign.Start)
  }
  /** 优先展示服务端实测的成片参数，未产出时退回提交时的固定规格。 */
  private jobOutputLabel(job: VideoJob): string {
    const model = this.models.find((item: VideoModel) => item.id === job.request.resourceId);
    const requested = job.request.output;
    const result = job.result;
    const hasActual = result !== undefined && result.width !== undefined && result.height !== undefined &&
      result.width > 0 && result.height > 0;
    const size = hasActual ? `${result.width}x${result.height}` :
      (requested === undefined ? '832x480' : requested.size);
    const frames = result !== undefined && result.frames !== undefined && result.frames > 0 ? result.frames :
      (requested === undefined ? 49 : requested.frames);
    const fps = result !== undefined && result.fps !== undefined && result.fps > 0 ? result.fps :
      (model === undefined ? 16 : model.fps);
    const duration = result === undefined || result.durationMs === undefined || result.durationMs <= 0 ? '' :
      ` · ${Math.round(result.durationMs / 100) / 10} 秒`;
    return `${size.replace('x', ' × ')} · ${frames} 帧 · ${fps} fps${duration}`;
  }
  @Builder
  private Works() {
    Column({ space: 16 }) {
      Row() {
        Text('生成需要时间，可以离开页面稍后回看').fontSize(13).fontColor(AppTheme.textSecondary).layoutWeight(1)
        Text(`${this.jobs.length} 条`).fontSize(11).fontColor(AppTheme.textTertiary)
      }.width('100%')
      if (this.playerUri.length > 0) { this.Player() }
      if (this.jobs.length === 0) {
        Column({ space: 12 }) {
          SymbolGlyph($r('sys.symbol.play_round_rectangle_fill')).fontSize(36).fontColor([AppTheme.primary])
          Text('还没有视频作品').fontSize(16).fontWeight(FontWeight.Medium)
          Text('选择模式与素材，生成第一支短片。').fontSize(13).fontColor(AppTheme.textSecondary)
          this.QuietButton('开始创作', () => { this.tab = 'create'; this.releasePlayer(); })
        }.width('100%').padding({ top: 50, bottom: 50 }).backgroundColor(AppTheme.surface).borderRadius(20)
      }
      ForEach(this.jobs, (job: VideoJob) => {
        this.WorksCard(job)
      }, (job: VideoJob) => `${job.id}:${job.status}:${job.errorCode}:${job.elapsedMillis}`)
      Row({ space: 16 }) {
        Button('上一页').height(36).fontSize(12).backgroundColor(AppTheme.surface)
          .fontColor(AppTheme.textSecondary).enabled(this.page > 0 && !this.busy)
          .onClick(() => { this.page--; this.covers = []; this.refresh(); })
        Text(`第 ${this.page + 1} 页`).fontSize(12).fontColor(AppTheme.textTertiary)
        Button('下一页').height(36).fontSize(12).backgroundColor(AppTheme.surface)
          .fontColor(AppTheme.textSecondary).enabled(this.jobs.length === PAGE_SIZE && !this.busy)
          .onClick(() => { this.page++; this.covers = []; this.refresh(); })
      }.width('100%').justifyContent(FlexAlign.Center)
    }.width('100%')
  }
  build() {
    Column({ space: 14 }) {
      Row({ space: 12 }) {
        Button() {
          SymbolGlyph($r('sys.symbol.chevron_left')).fontSize(18).fontColor([AppTheme.ink])
        }.width(36).height(36).backgroundColor(AppTheme.surface).borderRadius(12)
          .accessibilityText('返回').onClick(() => this.leave())
        Text('视频工作台').fontSize(20).fontWeight(FontWeight.Bold).fontColor(AppTheme.ink).layoutWeight(1)
        this.QuietButton('刷新', () => this.refresh())
      }.width('100%')
      Row({ space: 4 }) {
        Button('创作').type(ButtonType.Normal).layoutWeight(1).height(40).fontSize(14).fontWeight(FontWeight.Medium)
          .fontColor(this.tab === 'create' ? AppTheme.ink : AppTheme.textSecondary)
          .backgroundColor(this.tab === 'create' ? AppTheme.surface : Color.Transparent).borderRadius(11)
          .onClick(() => { this.tab = 'create'; this.releasePlayer(); })
        Button('我的作品').type(ButtonType.Normal).layoutWeight(1).height(40).fontSize(14)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.tab === 'works' ? AppTheme.ink : AppTheme.textSecondary)
          .backgroundColor(this.tab === 'works' ? AppTheme.surface : Color.Transparent).borderRadius(11)
          .onClick(() => this.tab = 'works')
      }.width('100%').padding(4).backgroundColor(AppTheme.surfaceTint).borderRadius(14)
      if (this.progressText.length > 0) {
        Text(this.progressText).fontSize(12).fontColor(AppTheme.primary).width('100%').padding(10)
          .backgroundColor(AppTheme.accentSoft).borderRadius(12)
      }
      if (this.tab === 'create') { this.Composer() } else { this.Works() }
      if (this.message.length > 0) {
        Text(this.message).fontSize(12).fontColor(AppTheme.textSecondary).width('100%').padding(12)
          .backgroundColor(AppTheme.accentSoft).borderRadius(12)
      }
    }.width('100%').padding({ top: 4, bottom: 16 }).alignItems(HorizontalAlign.Start)
  }
}
