import qrEngine from 'libqr_engine.so';
import { BusinessError } from '@kit.BasicServicesKit';
import { common } from '@kit.AbilityKit';
import { scanBarcode, scanCore } from '@kit.ScanKit';
import { image } from '@kit.ImageKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import picker from '@ohos.file.picker';
import fs from '@ohos.file.fs';
import util from '@ohos.util';
import pasteboard from '@ohos.pasteboard';
import { ToolPageHeader, ToolEmptyState } from './ToolUi';
import { AppTheme } from '../../theme/AppTheme';
import { AppActionGroup, AppButton, AppGlassTextArea, AppSegmentItem, AppTextAction } from '../../components/AppControls';

/** 许可证仅展示原文与关闭操作，不引入业务确认。 */
@CustomDialog
struct QrLicenseDialog {
  controller?: CustomDialogController;
  @Prop content: string = '';

  /** 使用固定字体展示可滚动、可复制的许可证原文。 */
  build() {
    Column({ space: 16 }) {
      Text('QR Code generator / quirc').fontFamily(AppTheme.fontSemibold).fontSize(20)
        .fontWeight(600).lineHeight(28).fontColor(AppTheme.textPrimary).width('100%')
      Scroll() {
        Text(this.content).fontFamily(AppTheme.fontFamily).fontSize(15).lineHeight(24)
          .fontColor(AppTheme.textSecondary).width('100%').copyOption(CopyOptions.LocalDevice)
      }.width('100%').layoutWeight(1).align(Alignment.TopStart)
      AppButton({ label: $r('app.string.tool_ui_close'), variant: 'secondary', fullWidth: true,
        action: (): void => { this.controller?.close(); } })
    }.padding(20).width('90%').height('76%').constraintSize({ maxWidth: 400 })
      .alignItems(HorizontalAlign.Start).backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
  }
}

/** 二维码生成与识别使用统一输入、动作和结果规范。 */
@Component
export struct QrToolsPage {
  onBack: () => void = () => {};
  @State private tab: string = 'create';
  @State private content: string = '';
  @State private preview: image.PixelMap | undefined = undefined;
  @State private results: string[] = [];
  @State private busy: boolean = false;
  @State private status: string = '';
  @State private licenseContent: string = '';
  private active: boolean = true;
  private licenseDialog: CustomDialogController = new CustomDialogController({
    builder: QrLicenseDialog({ content: this.licenseContent }),
    autoCancel: true, alignment: DialogAlignment.Center, customStyle: true
  });

  /** 页面退出后停止更新并释放图片。 */
  aboutToDisappear(): void {
    this.active = false;
    this.licenseDialog.close();
    if (!this.busy) this.clearPreview();
  }

  private label(name: string): string {
    try { return this.getUIContext().getHostContext()!.resourceManager.getStringByNameSync(name); }
    catch (_) { return name; }
  }

  private clearPreview(): void {
    this.preview?.release();
    this.preview = undefined;
  }

  private finish(): void {
    this.busy = false;
    if (!this.active) this.clearPreview();
  }

  private async generate(): Promise<void> {
    if (this.busy) return;
    // 按 UTF-8 字节校验，保留输入中的空格和换行。
    if (this.content.trim().length === 0 || new util.TextEncoder().encodeInto(this.content).byteLength > 2950) {
      this.status = this.label('qr_input_invalid'); return;
    }
    this.busy = true;
    this.status = '';
    try {
      this.getUIContext().getFocusController().clearFocus();
      const pixels = await qrEngine.encode(this.content);
      const size = Math.sqrt(pixels.byteLength / 4);
      const bitmap = await image.createPixelMap(pixels, { size: { width: size, height: size }, pixelFormat: image.PixelMapFormat.RGBA_8888 });
      if (!this.active) { bitmap.release(); return; }
      this.clearPreview(); this.preview = bitmap;
    } catch (_) { if (this.active) this.status = this.label('qr_generate_failed'); }
    finally { this.finish(); }
  }

  private async recognize(camera: boolean): Promise<void> {
    if (this.busy) return;
    this.busy = true; this.status = ''; this.results = [];
    try {
      let scans: scanBarcode.ScanResult[] = [];
      if (camera) {
        const result = await scanBarcode.startScanForResult(this.getUIContext().getHostContext() as common.UIAbilityContext,
          { scanTypes: [scanCore.ScanType.QR_CODE], enableMultiMode: false, enableAlbum: true });
        scans = [result];
      } else {
        const options = new photoAccessHelper.PhotoSelectOptions();
        options.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE;
        options.maxSelectNumber = 1;
        const selected = await new photoAccessHelper.PhotoViewPicker().select(options);
        if (selected.photoUris.length === 0 || !this.active) return;
        const values = await this.decodeUri(selected.photoUris[0]);
        if (!this.active) return;
        this.results = values;
        if (values.length === 0) this.status = this.label('qr_no_result');
        return;
      }
      if (!this.active) return;
      // 只展示识别结果，不自动访问其中的链接。
      this.results = scans.map((item: scanBarcode.ScanResult) => item.originalValue).filter((value: string) => value.length > 0);
      if (this.results.length === 0) this.status = this.label('qr_no_result');
    } catch (error) {
      // 用户主动取消扫码时不显示失败提示。
      if (this.active && (error as BusinessError).code !== 1000500002) this.status = this.label('qr_scan_failed');
    }
    finally { this.finish(); }
  }

  private async decodeUri(uri: string): Promise<string[]> {
    let file: fs.File | undefined = undefined;
    let source: image.ImageSource | undefined = undefined;
    let pixels: image.PixelMap | undefined = undefined;
    try {
      file = fs.openSync(uri, fs.OpenMode.READ_ONLY);
      source = image.createImageSource(file.fd);
      const info = await source.getImageInfo();
      const ratio = Math.min(1, 2048 / Math.max(info.size.width, info.size.height));
      pixels = await source.createPixelMap({ desiredPixelFormat: image.PixelMapFormat.RGBA_8888,
        desiredSize: { width: Math.max(1, Math.round(info.size.width * ratio)), height: Math.max(1, Math.round(info.size.height * ratio)) } });
      const actual = await pixels.getImageInfo();
      const buffer = new ArrayBuffer(pixels.getPixelBytesNumber());
      await pixels.readPixelsToBuffer(buffer);
      return await qrEngine.decode(buffer, actual.size.width, actual.size.height);
    } catch (_) {
      throw new Error('QR_IMAGE_INVALID');
    } finally {
      try { await pixels?.release(); } catch (_) { /* 图片可能已释放。 */ }
      try { await source?.release(); } catch (_) { /* 图片源可能已释放。 */ }
      try { if (file) fs.closeSync(file); } catch (_) { /* 文件可能已关闭。 */ }
    }
  }

  private async recognizeFile(): Promise<void> {
    if (this.busy) return;
    this.busy = true; this.status = ''; this.results = [];
    try {
      const options = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 1; options.fileSuffixFilters = ['.png,.jpg,.jpeg,.webp,.bmp'];
      const values = await new picker.DocumentViewPicker(this.getUIContext().getHostContext() as common.UIAbilityContext).select(options);
      if (values.length === 0 || !this.active) return;
      const results = await this.decodeUri(values[0]);
      if (!this.active) return;
      this.results = results;
      if (results.length === 0) this.status = this.label('qr_no_result');
    } catch (_) { if (this.active) this.status = this.label('qr_scan_failed'); }
    finally { this.finish(); }
  }

  private async save(): Promise<void> {
    if (this.busy || !this.preview) return;
    this.busy = true; this.status = '';
    let output: fs.File | undefined = undefined;
    const packer = image.createImagePacker();
    try {
      const options = new picker.DocumentSaveOptions();
      options.newFileNames = ['MyTools-QR.png'];
      const paths = await new picker.DocumentViewPicker(this.getUIContext().getHostContext() as common.UIAbilityContext).save(options);
      if (paths.length === 0 || !this.active || !this.preview) return;
      output = fs.openSync(paths[0], fs.OpenMode.READ_WRITE);
      await packer.packToFile(this.preview, output.fd, { format: 'image/png', quality: 100 });
      if (this.active) this.status = this.label('qr_saved');
    } catch (_) { if (this.active) this.status = this.label('qr_save_failed'); }
    finally {
      try { if (output) fs.closeSync(output); } catch (_) { /* 文件可能已经关闭。 */ }
      await packer.release(); this.finish();
    }
  }

  private async copy(value: string): Promise<void> {
    try {
      await pasteboard.getSystemPasteboard().setData(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, value));
      if (this.active) this.status = this.label('qr_copied');
    } catch (_) { if (this.active) this.status = this.label('qr_copy_failed'); }
  }

  private async showLicense(): Promise<void> {
    try {
      const bytes = await this.getUIContext().getHostContext()!.resourceManager.getRawFileContent('qr-license.txt');
      if (!this.active) return;
      // 读取完成后再次核对生命周期，离页后不打开新的弹层。
      this.licenseContent = util.TextDecoder.create('utf-8').decodeToString(bytes);
      this.licenseDialog.open();
    } catch (_) { /* 许可证读取异常不影响二维码功能。 */ }
  }

  /** 状态文案保留原语义，以颜色辅助识别成功和失败。 */
  private statusColor(): string {
    // 成功反馈与输入或处理失败使用不同的语义颜色。
    if (this.status === this.label('qr_saved') || this.status === this.label('qr_copied')) return AppTheme.primary;
    if (['qr_input_invalid', 'qr_generate_failed', 'qr_scan_failed', 'qr_save_failed', 'qr_copy_failed']
      .some((name: string) => this.status === this.label(name))) return AppTheme.danger;
    return AppTheme.textSecondary;
  }

  @Builder
  private ModeButton(title: Resource, value: string) {
    AppSegmentItem({ label: title, selected: this.tab === value, available: !this.busy,
      action: () => { this.tab = value; this.status = ''; } }).layoutWeight(1)
  }

  /** 构建生成、识别和导出界面。 */
  build() {
    Column({ space: 20 }) {
      Row({ space: 8 }) {
        ToolPageHeader({ title: $r('app.string.qr_title'), onBack: () => this.onBack() }).layoutWeight(1)
        AppTextAction({ label: $r('app.string.archive_license'), action: () => this.showLicense() })
      }.width('100%')
      Text($r('app.string.tool_ui_qr_hint')).fontSize(13).lineHeight(20).fontFamily(AppTheme.fontFamily)
        .fontColor(AppTheme.textSecondary).width('100%')
      Row() {
        this.ModeButton($r('app.string.qr_generate'), 'create')
        this.ModeButton($r('app.string.qr_recognize'), 'scan')
      }.width('100%').backgroundColor(AppTheme.surfaceTint).borderRadius(10)
      if (this.tab === 'create') {
        if (!this.preview) {
          Column({ space: 14 }) {
            Text($r('app.string.tool_ui_qr_create_title')).fontSize(20).lineHeight(28)
              .fontFamily(AppTheme.fontSemibold).fontWeight(600).fontColor(AppTheme.textPrimary).width('100%')
            Text($r('app.string.tool_ui_qr_create_hint')).fontSize(13).lineHeight(20)
              .fontFamily(AppTheme.fontFamily).fontColor(AppTheme.textSecondary).width('100%')
            AppGlassTextArea({ value: this.content, placeholder: this.label('qr_placeholder'), areaHeight: 140,
              limit: 2950, available: !this.busy,
              valueChanged: (value: string) => { this.content = value; this.clearPreview(); this.status = ''; } })
            AppButton({ label: $r('app.string.qr_generate_action'), fullWidth: true, loading: this.busy,
              available: !this.busy && this.content.trim().length > 0, action: () => this.generate() })
          }.padding(16).width('100%').backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
        }
        if (this.preview) {
          Column({ space: 14 }) {
            Text($r('app.string.tool_ui_qr_ready')).fontSize(18).lineHeight(26)
              .fontFamily(AppTheme.fontSemibold).fontWeight(600).fontColor(AppTheme.primary)
            Image(this.preview).width('100%').height(240).objectFit(ImageFit.Contain).backgroundColor(Color.White)
            Text(this.content).fontSize(13).lineHeight(20).fontFamily(AppTheme.fontFamily)
              .fontColor(AppTheme.textSecondary).maxLines(3).width('100%').copyOption(CopyOptions.LocalDevice)
            AppTextAction({ label: $r('app.string.qr_edit'), available: !this.busy, action: () => this.clearPreview() })
            Text($r('app.string.tool_ui_qr_ready_hint')).fontSize(12).lineHeight(18)
              .fontFamily(AppTheme.fontFamily).fontColor(AppTheme.textSecondary)
            AppButton({ label: $r('app.string.qr_save'), fullWidth: true, loading: this.busy,
              available: !this.busy, action: () => this.save() })
          }.padding(16).width('100%').backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
        }
      } else {
        ToolEmptyState({ icon: $r('sys.symbol.camera'), title: $r('app.string.tool_ui_qr_scan_title'), description: $r('app.string.tool_ui_qr_scan_hint') })
        AppActionGroup({ primaryLabel: $r('app.string.qr_camera'), secondaryLabel: $r('app.string.qr_photo'),
          available: !this.busy, secondaryAvailable: !this.busy, primaryAction: () => this.recognize(true), secondaryAction: () => this.recognize(false) })
        AppButton({ label: $r('app.string.qr_image_file'), variant: 'secondary', fullWidth: true,
          available: !this.busy, action: () => this.recognizeFile() })
        if (this.results.length > 0) {
          Text($r('app.string.tool_ui_qr_result')).fontSize(18).lineHeight(26)
            .fontFamily(AppTheme.fontSemibold).fontWeight(600).fontColor(AppTheme.textPrimary).width('100%')
        }
        ForEach(this.results, (value: string) => {
          Column({ space: 12 }) {
            Text(value).copyOption(CopyOptions.LocalDevice).width('100%').fontSize(15).lineHeight(24)
              .fontFamily(AppTheme.fontFamily).fontColor(AppTheme.textPrimary)
            AppTextAction({ label: $r('app.string.qr_copy'), action: () => this.copy(value) })
          }.padding(16).backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius).width('100%')
        })
      }
      if (this.busy) {
        LoadingProgress().width(28).height(28).color(AppTheme.primary)
          .accessibilityText($r('app.string.qr_title'))
      }
      if (this.status.length > 0) {
        Text(this.status).fontSize(14).lineHeight(22).fontFamily(AppTheme.fontFamily).fontColor(this.statusColor())
          .padding(14).backgroundColor(AppTheme.surfaceTint).borderRadius(12).width('100%')
      }
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
}
