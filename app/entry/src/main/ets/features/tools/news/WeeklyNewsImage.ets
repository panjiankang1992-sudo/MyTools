import image from '@ohos.multimedia.image';
import util from '@ohos.util';
import { AuthSessionManager } from '../../auth/AuthSessionManager';
import { DownloadCancellationToken } from '../../../shared/network/DownloadCancellationToken';
import { AppTheme } from '../../../theme/AppTheme';
import { WeeklyNewsApi } from './WeeklyNewsApi';

/** 原图按可见区域加载，账号和页面变化立即丢弃旧图片。 */
@Component
export struct WeeklyNewsImage {
  @Prop darkMode: boolean = false;
  @Prop @Watch('reset') baseUrl: string = '';
  @Prop @Watch('reset') accountKey: string = '';
  @Prop @Watch('reset') reportId: string = '';
  @Prop @Watch('reset') assetId: string = '';
  @Prop compact: boolean = false;
  authManager: AuthSessionManager = new AuthSessionManager();
  @State pixels?: image.PixelMap = undefined;
  @State failed: boolean = false;
  private cancel: DownloadCancellationToken = new DownloadCancellationToken();
  private epoch: number = 0;
  private active: boolean = false;
  private loading: boolean = false;
  private visible: boolean = false;
  /** 组件出现后等待进入可见区。 */
  aboutToAppear(): void { this.active = true; }
  /** 不保留退出页面的私有图片字节。 */
  aboutToDisappear(): void { this.active = false; this.reset(); }
  private reset(): void {
    this.epoch++; this.cancel.cancel(); this.cancel = new DownloadCancellationToken(); this.loading = false; this.failed = false;
    const old = this.pixels; this.pixels = undefined; if (old) old.release();
    if (this.active && this.visible) this.load();
  }
  private async load(): Promise<void> {
    if (!this.active || !this.visible || this.loading || this.pixels || this.failed || !this.accountKey) return;
    const epoch = this.epoch; this.loading = true;
    try {
      const asset = await new WeeklyNewsApi(this.baseUrl, this.authManager).asset(this.reportId, this.assetId, this.cancel);
      if (!this.active || epoch !== this.epoch || asset.assetId !== this.assetId) return;
      const bytes = new util.Base64Helper().decodeSync(asset.base64);
      const source = image.createImageSource(bytes.buffer as ArrayBuffer);
      try {
        const info = await source.getImageInfo();
        const max = this.compact ? 240 : 1280;
        const scale = Math.min(1, max / Math.max(info.size.width, info.size.height));
        const pixels = await source.createPixelMap({ desiredSize: { width: Math.max(1, Math.round(info.size.width * scale)),
          height: Math.max(1, Math.round(info.size.height * scale)) } });
        if (this.active && epoch === this.epoch) this.pixels = pixels;
        else await pixels.release();
      } finally { await source.release(); }
    } catch (_) { if (this.active && epoch === this.epoch) this.failed = true; }
    finally { if (this.active && epoch === this.epoch) this.loading = false; }
  }
  /** 无原图或加载失败不生成替代封面。 */
  build() {
    Stack() {
      if (this.pixels) {
        Image(this.pixels).width('100%').height('100%').objectFit(this.compact ? ImageFit.Cover : ImageFit.Contain)
          .accessibilityText($r('app.string.news_original_image'))
      } else {
        Text(this.failed ? $r('app.string.news_image_failed') : $r('app.string.news_image_loading'))
          .fontSize(12).fontColor(this.darkMode ? AppTheme.darkTextSecondary : AppTheme.textSecondary).textAlign(TextAlign.Center).padding(8)
      }
    }.width(this.compact ? 88 : '100%').height(this.compact ? 88 : 240).borderRadius(12).clip(true)
      .backgroundColor(this.darkMode ? AppTheme.darkSurfaceTint : AppTheme.surfaceTint).onVisibleAreaChange([0, 0.1], (visible: boolean) => {
        this.visible = visible; if (visible) this.load();
      })
  }
}
