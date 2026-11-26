import { AppTheme } from '../../theme/AppTheme';
import { AppButton, AppTextAction } from '../../components/AppControls';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import { AuthSessionManager } from '../auth/AuthSessionManager';
import { ChapterAdaptationApi } from './ChapterAdaptationApi';
import { AdaptationFeatures } from './ChapterAdaptationModels';
import { AdaptationConsentController, AdaptationConsentState, emptyAdaptationConsentState } from './AdaptationConsentController';

/** 账户设置和章节改编共享同一个纯文本告知界面，不要求书架存在任何小说。 */
@Component
export struct AdaptationConsentPanel {
  baseUrl: string = '';
  authManager: AuthSessionManager = new AuthSessionManager();
  lifetime: DownloadCancellationToken = new DownloadCancellationToken();
  isCurrent: () => boolean = (): boolean => false;
  onFeatures: (features: AdaptationFeatures | undefined) => void = (_: AdaptationFeatures | undefined): void => {};
  onClose: () => void = (): void => {};
  @State private state: AdaptationConsentState = emptyAdaptationConsentState();
  private controller?: AdaptationConsentController = undefined;

  /** 数据仅从认证账户接口读取，组件出现不自动生成同意。 */
  aboutToAppear(): void {
    this.controller = new AdaptationConsentController(new ChapterAdaptationApi(this.baseUrl, this.authManager), this.lifetime,
      this.isCurrent, (state: AdaptationConsentState): void => { this.state = state; this.onFeatures(state.features); });
    this.controller.open();
  }

  /** 页面销毁清空全部文案和确认状态，外层生命周期令牌可更早中断请求。 */
  aboutToDisappear(): void { this.controller?.close(); this.controller = undefined; this.state = emptyAdaptationConsentState(); }

  private close(): void { this.controller?.close(); this.onClose(); }

  /** 构建独立告知内容，勾选后才允许显式确认。 */
  build() {
    Column({ space: 14 }) {
      Row({ space: 12 }) {
        Text($r('app.string.adaptation_consent_title')).lineHeight(28).fontFamily(AppTheme.fontSemibold).layoutWeight(1).fontSize(20).fontWeight(600)
        AppTextAction({ label: $r('app.string.adaptation_close'), action: () => this.close() })
      }.width('100%')
      if (this.state.busy) { LoadingProgress().width(28).height(28) }
      if (this.state.errorCode !== '') {
        Text($r('app.string.adaptation_consent_refresh_required')).lineHeight(22).fontFamily(AppTheme.fontFamily).fontSize(14).fontColor(AppTheme.danger)
        Text(this.state.errorCode).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12).fontColor(AppTheme.textSecondary)
      }
      Scroll() {
        Column({ space: 16 }) {
          if (this.state.features !== undefined) {
            Text(this.state.features.consentStatus === 'ACCEPTED' ? $r('app.string.adaptation_consent_accepted') :
              this.state.features.consentStatus === 'REQUIRED' ? $r('app.string.adaptation_consent_required') :
              $r('app.string.adaptation_consent_unavailable')).lineHeight(24).fontFamily(AppTheme.fontFamily).fontSize(15)
            Text($r('app.string.adaptation_consent_revision', this.state.features.consentRevision.toString())).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12)
            if (this.state.features.hasActiveConsent && this.state.features.consentStatus !== 'ACCEPTED') {
              Text($r('app.string.adaptation_consent_older_active')).lineHeight(22).fontFamily(AppTheme.fontFamily).fontSize(14)
            }
            if (this.state.features.disclosure !== undefined) {
              Text(`${this.state.features.disclosure.payload.providerName} · ${this.state.features.disclosure.payload.providerOrigin}`).lineHeight(22).fontFamily(AppTheme.fontFamily).width('100%').fontSize(14)
              Text(this.state.features.disclosure.payload.dataUseNotice).lineHeight(24).fontFamily(AppTheme.fontFamily).width('100%').fontSize(15)
              Text(this.state.features.disclosure.payload.retentionNotice).lineHeight(24).fontFamily(AppTheme.fontFamily).width('100%').fontSize(15)
              Text(this.state.features.disclosure.payload.rightsNotice).lineHeight(24).fontFamily(AppTheme.fontFamily).width('100%').fontSize(15)
              Text(`${this.state.features.disclosure.version} · ${this.state.features.disclosure.rightsAttestationVersion}`).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12)
              if (this.state.features.consentStatus !== 'ACCEPTED' && this.state.features.readEnabled) {
                Row({ space: 8 }) {
                  Checkbox({ name: 'adaptation-processing' }).width(AppTheme.checkboxSize).height(AppTheme.checkboxSize).margin(12)
                    .responseRegion({ x: -12, y: -12, width: 44, height: 44 }).selectedColor(AppTheme.primary)
                    .select(this.state.processingAccepted).enabled(!this.state.busy)
                    .accessibilityText($r('app.string.adaptation_consent_processing'))
                    .onChange((value: boolean) => this.controller?.processing(value))
                  Text($r('app.string.adaptation_consent_processing')).fontFamily(AppTheme.fontFamily).fontSize(16).lineHeight(24).layoutWeight(1)
                }.width('100%')
                Row({ space: 8 }) {
                  Checkbox({ name: 'adaptation-rights' }).width(AppTheme.checkboxSize).height(AppTheme.checkboxSize).margin(12)
                    .responseRegion({ x: -12, y: -12, width: 44, height: 44 }).selectedColor(AppTheme.primary)
                    .select(this.state.rightsAccepted).enabled(!this.state.busy)
                    .accessibilityText($r('app.string.adaptation_consent_rights'))
                    .onChange((value: boolean) => this.controller?.rights(value))
                  Text($r('app.string.adaptation_consent_rights')).fontFamily(AppTheme.fontFamily).fontSize(16).lineHeight(24).layoutWeight(1)
                }.width('100%')
                AppButton({ label: $r('app.string.adaptation_consent_accept'), fullWidth: true,
                  available: !this.state.busy && this.state.processingAccepted && this.state.rightsAccepted && this.controller?.canAccept() === true,
                  action: () => this.controller?.accept() })
              }
            }
          }
          Text($r('app.string.adaptation_consent_revoke_notice')).lineHeight(18).fontFamily(AppTheme.fontFamily).fontSize(12)
          AppTextAction({ label: $r('app.string.adaptation_consent_revoke'), danger: true,
            available: !this.state.busy && this.state.features !== undefined && this.controller?.canRevoke() === true,
            action: () => this.controller?.revoke() })
          AppTextAction({ label: $r('app.string.adaptation_refresh'), available: !this.state.busy, action: () => this.controller?.refresh() })
        }.width('100%').alignItems(HorizontalAlign.Start)
      }.layoutWeight(1).width('100%')
    }.width('100%').height('100%').padding(20).backgroundColor(AppTheme.background).alignItems(HorizontalAlign.Start)
  }
}
