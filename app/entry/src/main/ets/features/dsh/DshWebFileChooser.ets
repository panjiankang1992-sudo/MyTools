import common from '@ohos.app.ability.common';
import { PromptAction } from '@ohos.arkui.UIContext';
import photoAccessHelper from '@ohos.file.photoAccessHelper';
import picker from '@ohos.file.picker';
import { AppTheme } from '../../theme/AppTheme';
import { DshImageUploadPreparation } from './DshImageUploadPreparation';

interface DshFileSelection {
  result: FileSelectorResult;
  isCurrent: () => boolean;
}

/** 将系统明确授权的文件交回原网页控件，保留 DSH 的附件预览和上传流程。 */
export class DshWebFileChooser {
  private static readonly MULTIPLE_LIMIT: number = 500;
  private readonly context: common.UIAbilityContext;
  private readonly prompt: PromptAction;
  private pending?: DshFileSelection;
  private preparedUris: Set<string> = new Set();

  /** 使用当前窗口的系统菜单和选择器。 */
  constructor(context: common.UIAbilityContext, prompt: PromptAction) {
    this.context = context;
    this.prompt = prompt;
  }

  /** 接管常规单选和多选，复杂 HTML 文件筛选仍由 ArkWeb 原生处理。 */
  show(event: OnShowFileSelectorEvent, isCurrent: () => boolean): boolean {
    if (!this.current(isCurrent) || this.pending !== undefined) {
      this.deliver(event.result, []);
      return true;
    }
    try {
      const mode = event.fileSelector.getMode();
      if (event.fileSelector.isCapture() ||
        (mode !== FileSelectorMode.FileOpenMode && mode !== FileSelectorMode.FileOpenMultipleMode)) return false;

      // SDK 可能返回文件扩展名，保留原选择器对精确文件类型的筛选。
      const types = event.fileSelector.getAcceptType().map((value: string) => value.trim().toLowerCase())
        .filter((value: string) => value.length > 0);
      const unrestricted = types.length === 0 || types.includes('*/*');
      if (!unrestricted && !types.every((value: string) => value === 'image/*' || value === 'video/*')) return false;

      const selection: DshFileSelection = { result: event.result, isCurrent: isCurrent };
      const limit = mode === FileSelectorMode.FileOpenMultipleMode ? DshWebFileChooser.MULTIPLE_LIMIT : 1;
      this.pending = selection;
      this.pick(selection, limit, unrestricted, types);
      return true;
    } catch (_) {
      // 读取选择器参数失败时不能把未验证的请求交给默认弹窗。
      this.deliver(event.result, []);
      return true;
    }
  }

  /** 离开当前文档后结束原请求，迟到的系统回调不会带回任何文件。 */
  close(): void {
    if (this.pending !== undefined) this.complete(this.pending, []);
  }

  private async pick(selection: DshFileSelection, limit: number, unrestricted: boolean, types: string[]): Promise<void> {
    let files: string[] = [];
    try {
      if (unrestricted) {
        const choice = await this.prompt.showActionMenu({ title: $r('app.string.dsh_web_upload_title'), buttons: [
          { text: $r('app.string.dsh_web_upload_photos'), color: AppTheme.textPrimary },
          { text: $r('app.string.dsh_web_upload_files'), color: AppTheme.textPrimary }
        ] });
        // 页面或账号可能在选择来源的过程中切换，不能再打开第二层选择器。
        if (this.pending !== selection || !this.current(selection.isCurrent)) return;
        if (choice.index === 0) files = await this.pickMedia(limit, true, true);
        else if (choice.index === 1) {
          const options = new picker.DocumentSelectOptions();
          options.maxSelectNumber = limit;
          files = await new picker.DocumentViewPicker(this.context).select(options);
        }
      } else {
        files = await this.pickMedia(limit, types.includes('image/*'), types.includes('video/*'));
      }
    } catch (_) {
      // 用户取消、权限状态变化及系统选择器失败都以空结果结束原页面请求。
    } finally {
      const prepared = await this.prepareImages(files);
      const allowed = this.current(selection.isCurrent) ? this.selectedUris(prepared, limit) : [];
      this.complete(selection, allowed);
    }
  }

  /** 相册大图先在本机缩放到长边上限，明显缩短网页读取和上传时间。 */
  private async prepareImages(files: string[]): Promise<string[]> {
    const prepared: string[] = [];
    let announced = false;
    for (let index = 0; index < files.length; index++) {
      const value = files[index];
      // 一次选择最多处理四张，避免多选时长时间占用界面。
      if (index >= 4) { prepared.push(value); continue; }
      const next = await DshImageUploadPreparation.prepare(this.context, value, index, () => {
        if (announced) return;
        announced = true;
        this.prompt.showToast({ message: $r('app.string.dsh_web_upload_preparing'), duration: 2000 });
      });
      // 只有真正生成压缩副本时才放宽后续的 URI 校验。
      if (next !== value) this.preparedUris.add(next);
      prepared.push(next);
    }
    return prepared;
  }

  private async pickMedia(limit: number, images: boolean, videos: boolean): Promise<string[]> {
    const options = new photoAccessHelper.PhotoSelectOptions();
    options.MIMEType = images && videos ? photoAccessHelper.PhotoViewMIMETypes.IMAGE_VIDEO_TYPE :
      images ? photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE : photoAccessHelper.PhotoViewMIMETypes.VIDEO_TYPE;
    options.maxSelectNumber = limit;
    options.isPhotoTakingSupported = images;
    try {
      return (await new photoAccessHelper.PhotoViewPicker().select(options)).photoUris;
    } catch (_) {
      return [];
    }
  }

  private selectedUris(files: string[], limit: number): string[] {
    const accepted = new Set<string>();
    for (const value of files) {
      // ArkWeb 只需操作系统选择器授予的公开 URI，不传入沙箱路径或网络链接。
      if (/^file:\/\/(?:docs|media)\/[^\u0000-\u0020\u007f\\]+$/.test(value) ||
        this.preparedUris.has(value)) accepted.add(value);
      if (accepted.size >= limit) break;
    }
    return Array.from(accepted);
  }

  private current(isCurrent: () => boolean): boolean {
    try { return isCurrent(); } catch (_) { return false; }
  }

  private complete(selection: DshFileSelection, files: string[]): void {
    if (this.pending !== selection) return;
    this.pending = undefined;
    this.deliver(selection.result, files);
    this.preparedUris.clear();
  }

  private deliver(result: FileSelectorResult, files: string[]): void {
    try { result.handleFileList(files); } catch (_) { /* 浏览器控件可能已经销毁。 */ }
  }
}
