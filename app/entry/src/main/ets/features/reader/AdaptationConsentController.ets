import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import { AdaptationFeatures } from './ChapterAdaptationModels';
import { ChapterAdaptationPolicy } from './ChapterAdaptationPolicy';

/** 授权管理只需要账户接口，不能通过此端口读取书架、章节或调用模型。 */
export interface AdaptationConsentPort {
  features(cancellation: DownloadCancellationToken): Promise<AdaptationFeatures>;
  acceptConsent(features: AdaptationFeatures, accepted: boolean, rights: boolean,
    cancellation: DownloadCancellationToken): Promise<AdaptationFeatures>;
  revokeConsent(revision: number, cancellation: DownloadCancellationToken): Promise<AdaptationFeatures>;
}

/** 一次页面观察的状态，不持久保存告知、勾选或账户授权结果。 */
export interface AdaptationConsentState {
  busy: boolean;
  features?: AdaptationFeatures;
  processingAccepted: boolean;
  rightsAccepted: boolean;
  errorCode: string;
}

/** 未读取状态不能推断授权，两个确认项始终默认未勾选。 */
export function emptyAdaptationConsentState(): AdaptationConsentState {
  return { busy: false, features: undefined, processingAccepted: false, rightsAccepted: false, errorCode: '' };
}

/** 与页面框架无关的授权生命周期；外层账户令牌可在组件销毁前立即取消网络。 */
export class AdaptationConsentController {
  private readonly api: AdaptationConsentPort;
  private readonly lifetime: DownloadCancellationToken;
  private readonly isCurrent: () => boolean;
  private readonly onChange: (state: AdaptationConsentState) => void;
  private readonly policy: ChapterAdaptationPolicy = new ChapterAdaptationPolicy();
  private cancellation: DownloadCancellationToken = new DownloadCancellationToken();
  private revision: number = 0;
  private alive: boolean = false;
  private disposed: boolean = false;
  private mutation: boolean = false;
  private value: AdaptationConsentState = emptyAdaptationConsentState();
  private readonly stopHandler: () => void = (): void => this.close();

  constructor(api: AdaptationConsentPort, lifetime: DownloadCancellationToken,
    isCurrent: () => boolean, onChange: (state: AdaptationConsentState) => void) {
    this.api = api; this.lifetime = lifetime; this.isCurrent = isCurrent; this.onChange = onChange;
    this.lifetime.bind(this.stopHandler);
  }

  /** 仅显式打开页面后进行只读查询，出现时不自动接受任何告知。 */
  async open(): Promise<void> {
    if (this.disposed || this.lifetime.isCancelled() || !this.isCurrent()) return;
    this.alive = true;
    await this.refresh();
  }

  /** 返回当前快照，调用者通过确认方法更改勾选，不直接改写快照。 */
  snapshot(): AdaptationConsentState { return this.value; }

  /** 只读查询可替换更早查询；写入尚未确认时不另发请求猜测其结果。 */
  async refresh(): Promise<void> {
    if (!this.active() || this.mutation) return;
    const revision = this.begin(false);
    if (!this.current(revision)) return;
    try {
      const features = await this.api.features(this.cancellation);
      if (this.current(revision)) this.loaded(features);
    } catch (error) { this.failed(revision, error as Error); }
  }

  /** 处理确认只能来自当前页面一次明确选择，忙碌状态不接受迟到的勾选事件。 */
  processing(value: boolean): void {
    if (!this.active() || this.value.busy || this.value.features === undefined) return;
    this.value = { busy: this.value.busy, features: this.value.features, processingAccepted: value,
      rightsAccepted: this.value.rightsAccepted, errorCode: this.value.errorCode }; this.emit();
  }

  /** 权利确认独立于处理确认，刷新和任何写操作都会清空。 */
  rights(value: boolean): void {
    if (!this.active() || this.value.busy || this.value.features === undefined) return;
    this.value = { busy: this.value.busy, features: this.value.features, processingAccepted: this.value.processingAccepted,
      rightsAccepted: value, errorCode: this.value.errorCode }; this.emit();
  }

  /** 创建能力关闭不妨碍当前告知的管理，但读取灰度关闭时不能新授权。 */
  canAccept(): boolean {
    return this.active() && !this.value.busy && this.value.features?.consentStatus !== 'ACCEPTED' &&
      this.value.features?.readEnabled === true && this.value.features.disclosure !== undefined &&
      this.value.processingAccepted && this.value.rightsAccepted;
  }

  /** 撤销不依赖书籍、已发布告知或功能开关，必须有刚读取的授权修订。 */
  canRevoke(): boolean { return this.active() && !this.value.busy && this.value.features !== undefined; }

  /** 使用当前显示的版本、摘要和修订，仅发送一次明确同意。 */
  async accept(): Promise<void> {
    if (!this.canAccept()) return;
    await this.change(true);
  }

  /** 使用当前修订撤销全部告知；失败后必须刷新并再次明确操作。 */
  async revoke(): Promise<void> {
    if (!this.canRevoke()) return;
    await this.change(false);
  }

  /** 关闭仅使本地观察失效，不能声称撤回已送达服务器的写操作。 */
  close(): void {
    this.disposed = true; this.alive = false; this.revision++; this.cancellation.cancel();
    this.lifetime.unbind(this.stopHandler); this.mutation = false; this.value = emptyAdaptationConsentState();
  }

  private async change(accept: boolean): Promise<void> {
    const features = this.value.features;
    if (features === undefined) return;
    const processing = this.value.processingAccepted; const rights = this.value.rightsAccepted;
    const revision = this.begin(true);
    if (!this.current(revision)) return;
    try {
      const result = accept ? await this.api.acceptConsent(features, processing, rights, this.cancellation) :
        await this.api.revokeConsent(features.consentRevision, this.cancellation);
      if (this.current(revision)) this.loaded(result);
    } catch (error) { this.failed(revision, error as Error); }
  }

  private active(): boolean { return this.alive && !this.lifetime.isCancelled() && this.isCurrent(); }
  private current(revision: number): boolean { return this.active() && revision === this.revision && !this.cancellation.isCancelled(); }
  private begin(mutation: boolean): number {
    this.cancellation.cancel(); this.cancellation = new DownloadCancellationToken(); this.revision++;
    this.mutation = mutation; this.value = emptyAdaptationConsentState(); this.value.busy = true; this.emit();
    return this.revision;
  }
  private loaded(features: AdaptationFeatures): void {
    this.mutation = false; this.value = emptyAdaptationConsentState(); this.value.features = features; this.emit();
  }
  private failed(revision: number, error: Error): void {
    if (!this.current(revision)) return;
    // 409、超时和其他异常均不沿用旧修订重试，也不在页面显示服务端原始异常文本。
    this.mutation = false; this.value = emptyAdaptationConsentState(); this.value.errorCode = this.policy.error(error); this.emit();
  }
  private emit(): void { if (this.active()) this.onChange(this.value); }
}
