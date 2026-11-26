import { AppTheme } from '../../../theme/AppTheme';
import { AppTextAction } from '../../../components/AppControls';
import { DownloadMessage } from './DownloadToolsApi';
import { DownloadPresentationPolicy, DownloadFilePresentation } from './DownloadPresentationPolicy';

/** 下载回复使用单层文字列表，保持原始任务状态。 */
@Component
export struct DownloadReplyCard {
  @Prop row: DownloadMessage = { id: '', requestId: '', kind: '', name: '', status: '', createdAt: '' };
  onCancel: () => void = () => {};
  private files(): DownloadFilePresentation[] { return DownloadPresentationPolicy.files(this.row.result?.items); }
  private state(): string {
    if (this.row.status === 'SUCCEEDED' && this.row.result?.postProcessingStatus === 'FAILED') return 'attention';
    return DownloadPresentationPolicy.state(this.row.status, this.files());
  }
  private color(): string {
    if (this.state() === 'failed' || this.state() === 'expired') return AppTheme.danger;
    if (this.state() === 'partial' || this.state() === 'attention') return AppTheme.warning;
    return this.state() === 'done' || this.state() === 'active' ? AppTheme.primary : AppTheme.textSecondary;
  }
  private title(): Resource {
    if (this.row.status === 'CANCELLING') return $r('app.string.download_ui_cancelling');
    if (this.row.status === 'UPLOADING') return $r('app.string.download_ui_uploading');
    if (this.state() === 'active') return this.row.kind === 'MEDIA' ? $r('app.string.download_ui_organizing') : $r('app.string.download_ui_processing');
    if (this.state() === 'partial') return $r('app.string.download_ui_partial');
    if (this.state() === 'attention') return $r('app.string.download_ui_attention');
    if (this.state() === 'done') return $r('app.string.download_ui_done');
    if (this.state() === 'failed') return $r('app.string.download_ui_failed');
    if (this.state() === 'expired') return $r('app.string.download_ui_expired');
    if (this.state() === 'cancelled') return $r('app.string.download_ui_cancelled');
    if (this.state() === 'no_match') return $r('app.string.download_ui_no_match');
    return $r('app.string.download_ui_unknown');
  }
  private description(): Resource {
    if (this.state() === 'partial') return $r('app.string.download_ui_partial_hint');
    if (this.state() === 'attention') return $r('app.string.download_ui_attention_hint');
    if (this.state() === 'failed') return $r('app.string.download_chat_job_failed');
    if (this.state() === 'expired') return $r('app.string.download_chat_expired');
    if (this.state() === 'cancelled') return $r('app.string.download_chat_cancelled');
    if (this.state() === 'no_match') return $r('app.string.download_chat_no_match');
    if (this.row.status === 'CANCELLING') return $r('app.string.download_chat_cancelling');
    if (this.row.status === 'UPLOADING') return $r('app.string.download_chat_incomplete');
    if (this.state() === 'active') return this.row.kind === 'MEDIA' ? $r('app.string.download_chat_media_received') : $r('app.string.download_chat_received');
    if (this.state() === 'done' && this.row.result?.postProcessingStatus === 'PENDING') return $r('app.string.download_ui_tag_pending');
    if (this.state() === 'done') return this.files().length ? $r('app.string.download_ui_files_hint') : $r('app.string.download_chat_dedup');
    return $r('app.string.download_ui_unknown_hint');
  }
  private label(value: Resource): string { try { return this.getUIContext().getHostContext()?.resourceManager.getStringSync(value.id) ?? ''; } catch (_) { return ''; } }
  private metadata(file: DownloadFilePresentation): string {
    const kind = file.kind === 'video' ? $r('app.string.download_ui_video') :
      file.kind === 'image' ? $r('app.string.download_ui_image') :
      file.kind === 'audio' ? $r('app.string.download_ui_audio') : $r('app.string.download_ui_file');
    return `${this.label(kind)} · ${file.size || this.label($r('app.string.download_ui_unknown_size'))}`;
  }
  /** 用普通文字列表展示回复，长按即可复制，不增加展开层级。 */
  build() {
    Column({ space: 12 }) {
      Row({ space: 8 }) {
        if (this.state() === 'active') {
          LoadingProgress().width(18).height(18).color(AppTheme.primary)
        }
        Text(this.title()).fontSize(16).lineHeight(24).fontFamily(AppTheme.fontMedium)
          .fontWeight(500).fontColor(this.color()).layoutWeight(1)
        if (this.state() === 'active' && this.row.kind !== 'MEDIA' && this.row.status !== 'CANCELLING' &&
          DownloadPresentationPolicy.progress(this.row.result?.progressPercent) > 0) {
          Text(`${DownloadPresentationPolicy.progress(this.row.result?.progressPercent)}%`).fontSize(12).lineHeight(18)
            .fontFamily(AppTheme.fontFamily).fontColor(AppTheme.textSecondary)
        }
      }.width('100%')
      // 仅在服务端提供有效进度时显示确定式进度，其他任务保留加载状态。
      if (this.state() === 'active' && this.row.kind !== 'MEDIA' && this.row.status !== 'CANCELLING' &&
        DownloadPresentationPolicy.progress(this.row.result?.progressPercent) > 0) {
        Progress({ value: DownloadPresentationPolicy.progress(this.row.result?.progressPercent), total: 100,
          type: ProgressType.Linear }).width('100%').color(AppTheme.primary).backgroundColor(AppTheme.progressTrack)
      }
      if (!this.files().length) {
        Text(this.description()).fontSize(13).lineHeight(20).fontFamily(AppTheme.fontFamily)
          .fontColor(AppTheme.textSecondary).width('100%')
      }
      ForEach(this.files(), (file: DownloadFilePresentation, index: number) => {
        if (index > 0) Divider().color(AppTheme.divider)
        Row({ space: 8 }) {
          Text(`${index + 1}.`).fontSize(13).lineHeight(20).fontFamily(AppTheme.fontFamily)
            .fontColor(AppTheme.textSecondary).width(20)
          Column({ space: 4 }) {
            Text(file.name || $r('app.string.download_ui_unnamed')).fontSize(16).lineHeight(24)
              .fontFamily(AppTheme.fontMedium).fontWeight(500).fontColor(AppTheme.textPrimary)
              .width('100%').wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
            Text(this.metadata(file)).fontSize(12).lineHeight(18).fontFamily(AppTheme.fontFamily)
              .fontColor(AppTheme.textSecondary).copyOption(CopyOptions.LocalDevice)
            if (file.tags.length) {
              Text(file.tags.join(' · ')).fontSize(12).lineHeight(18).fontFamily(AppTheme.fontFamily)
                .fontColor(AppTheme.textSecondary).width('100%').copyOption(CopyOptions.LocalDevice)
            }
            if (file.tagStatus === 'PENDING') {
              Text($r('app.string.download_ui_tag_pending')).fontSize(12).lineHeight(18)
                .fontFamily(AppTheme.fontFamily).fontColor(AppTheme.textSecondary)
            }
            if (file.tagStatus === 'FAILED') {
              Text($r('app.string.download_ui_tag_failed')).fontSize(12).lineHeight(18)
                .fontFamily(AppTheme.fontFamily).fontColor(AppTheme.warning)
            }
            if (!file.archive) {
              Text($r('app.string.download_ui_archive_unknown')).fontSize(12).lineHeight(18)
                .fontFamily(AppTheme.fontFamily).fontColor(AppTheme.textSecondary)
            }
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        }.width('100%').constraintSize({ minHeight: 64 }).alignItems(VerticalAlign.Top)
      }, (file: DownloadFilePresentation, index: number) => `${index}:${JSON.stringify(file)}`)
      if (this.state() === 'active') {
        AppTextAction({ label: $r('app.string.download_chat_cancel'), available: this.row.status !== 'CANCELLING',
          action: () => this.onCancel() })
      }
    }.width('100%').padding(16).backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
      .alignItems(HorizontalAlign.Start)
  }
}
