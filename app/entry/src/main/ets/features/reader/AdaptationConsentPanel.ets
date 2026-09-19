import { AppTheme } from '../../theme/AppTheme';
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

  build() {
    Column({ space: 14 }) {
      Row({ space: 12 }) {
        Text($r('app.string.adaptation_consent_title')).layoutWeight(1).fontSize(20).fontWeight(FontWeight.Bold)
        Button($r('app.string.adaptation_close')).onClick(() => this.close())
      }.width('100%')
      if (this.state.busy) { LoadingProgress().width(28).height(28) }
      if (this.state.errorCode !== '') {
        Text($r('app.string.adaptation_consent_refresh_required')).fontSize(14).fontColor(AppTheme.danger)
        Text(this.state.errorCode).fontSize(12).fontColor(AppTheme.textSecondary)
      }
      Scroll() {
        Column({ space: 16 }) {
          if (this.state.features !== undefined) {
            Text(this.state.features.consentStatus === 'ACCEPTED' ? $r('app.string.adaptation_consent_accepted') :
              this.state.features.consentStatus === 'REQUIRED' ? $r('app.string.adaptation_consent_required') :
              $r('app.string.adaptation_consent_unavailable')).fontSize(15)
            Text($r('app.string.adaptation_consent_revision', this.state.features.consentRevision.toString())).fontSize(12)
            if (this.state.features.hasActiveConsent && this.state.features.consentStatus !== 'ACCEPTED') {
              Text($r('app.string.adaptation_consent_older_active')).fontSize(14)
            }
            if (this.state.features.disclosure !== undefined) {
              Text(`${this.state.features.disclosure.payload.providerName} · ${this.state.features.disclosure.payload.providerOrigin}`).width('100%').fontSize(14)
              Text(this.state.features.disclosure.payload.dataUseNotice).width('100%').fontSize(15)
              Text(this.state.features.disclosure.payload.retentionNotice).width('100%').fontSize(15)
              Text(this.state.features.disclosure.payload.rightsNotice).width('100%').fontSize(15)
              Text(`${this.state.features.disclosure.version} · ${this.state.features.disclosure.rightsAttestationVersion}`).fontSize(12)
              if (this.state.features.consentStatus !== 'ACCEPTED' && this.state.features.readEnabled) {
                Row({ space: 8 }) {
                  Checkbox({ name: 'adaptation-processing' }).select(this.state.processingAccepted).enabled(!this.state.busy)
                    .accessibilityText($r('app.string.adaptation_consent_processing'))
                    .onChange((value: boolean) => this.controller?.processing(value))
                  Text($r('app.string.adaptation_consent_processing')).layoutWeight(1)
                }.width('100%')
                Row({ space: 8 }) {
                  Checkbox({ name: 'adaptation-rights' }).select(this.state.rightsAccepted).enabled(!this.state.busy)
                    .accessibilityText($r('app.string.adaptation_consent_rights'))
                    .onChange((value: boolean) => this.controller?.rights(value))
                  Text($r('app.string.adaptation_consent_rights')).layoutWeight(1)
                }.width('100%')
                Button($r('app.string.adaptation_consent_accept')).enabled(!this.state.busy && this.state.processingAccepted &&
                  this.state.rightsAccepted && this.controller?.canAccept() === true)
                  .onClick(() => this.controller?.accept())
              }
            }
          }
          Text($r('app.string.adaptation_consent_revoke_notice')).fontSize(13)
          Button($r('app.string.adaptation_consent_revoke')).enabled(!this.state.busy && this.state.features !== undefined && this.controller?.canRevoke() === true)
            .onClick(() => this.controller?.revoke())
          Button($r('app.string.adaptation_refresh')).enabled(!this.state.busy).onClick(() => this.controller?.refresh())
        }.width('100%').alignItems(HorizontalAlign.Start)
      }.layoutWeight(1).width('100%')
    }.width('100%').height('100%').padding(18).backgroundColor(AppTheme.background).alignItems(HorizontalAlign.Start)
  }
}
