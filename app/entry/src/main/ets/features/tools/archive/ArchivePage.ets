import { ToolPageHeader, ToolEmptyState, ToolConfirmDialog } from '../ToolUi';
import deviceInfo from '@ohos.deviceInfo';
import { BusinessError } from '@kit.BasicServicesKit';
import { util } from '@kit.ArkTS';
import { common } from '@kit.AbilityKit';
import picker from '@ohos.file.picker';
import fs from '@ohos.file.fs';
import userAuth from '@ohos.userIAM.userAuth';
import { ArchiveTree, ArchiveTreeRow } from './ArchiveTree';
import { AppTheme } from '../../../theme/AppTheme';
import { AppButton, AppGroupedButton, AppEqualActionRow, AppTextAction, AppActionGroup, AppGlassTextInput, AppSearchField, AppSegmentItem } from '../../../components/AppControls';
import { ArchiveEntry, ArchiveErrors, ArchivePassword, ArchivePasswordSummary } from './ArchiveModels';
import { ArchiveHistory, ArchiveJobService, ArchiveOutput, ArchiveUpdate } from './ArchiveJobService';

@Component
export struct ArchivePage {
  @Prop @Watch('accountChanged') accountKey: string = '';
  @Prop @Watch('pageVisibilityChanged') pageVisible: boolean = true;
  baseUrl: string = '';
  onBack: () => void = () => {};
  @StorageLink('incomingArchiveUri') @Watch('receiveIncoming') private incomingUri: string = '';
  @StorageProp('appForeground') @Watch('foregroundChanged') private foreground: boolean = true;
  @State private tab: string = 'file';
  @State private busy: boolean = false;
  @State private ready: boolean = false;
  @State private name: string = '';
  @State private inputBytes: number = 0;
  @State private format: string = '';
  @State private entries: ArchiveEntry[] = [];
  @State private selected: number[] = [];
  @State private search: string = '';
  @State private tree: ArchiveTreeRow[] = [];
  @State private expanded: string[] = [];
  @State private exporting: boolean = false;
  @State private exportedCount: number = 0;
  @State private exportTotal: number = 0;
  private folderExport: boolean = deviceInfo.deviceType === '2in1' && canIUse('SystemCapability.FileManagement.UserFileService.FolderSelection');
  @State private visibleCount: number = 100;
  @State private password: string = '';
  @State private automatic: boolean = true;
  @State private status: string = '';
  @State private warning: string = '';
  @State private progress: ArchiveUpdate = { candidate: 0, candidates: 0, file: 0, files: 0, bytes: 0, total: 0, savingFailed: false };
  @State private paused: boolean = false;
  @State private history: ArchiveHistory[] = [];
  @State private result: ArchiveHistory | undefined = undefined;
  @State private passwords: ArchivePasswordSummary[] = [];
  @State private revealedId: string = '';
  @State private revealedValue: string = '';
  @State private confirmClear: boolean = false;
  @State private license: string = '';
  private service: ArchiveJobService = new ArchiveJobService();
  private active: boolean = true;
  private resume: ((value: boolean) => void) | undefined = undefined;
  private revealTimer: number = -1;
  private auth: userAuth.UserAuthInstance | undefined = undefined;
  private confirmationController: CustomDialogController | undefined = undefined;

  /** 初始化当前账户的本机解压工具。 */
  aboutToAppear(): void { this.initialize(); }

  /** 退出时取消任务，待原生回调结束后清理缓存。 */
  aboutToDisappear(): void {
    this.active = false; this.confirmationController?.close(); this.stop(); this.hideSecret();
    if (!this.busy) this.service.cleanup();
  }

  private accountChanged(): void {
    // 账户切换后退出旧实例，禁止复用旧账户的密码和结果。
    this.confirmationController?.close(); this.stop(); this.hideSecret(); this.onBack();
  }

  private pageVisibilityChanged(): void {
    if (!this.pageVisible) { this.confirmationController?.close(); this.password = ''; this.hideSecret(); this.stop(); }
  }

  private async showLicense(): Promise<void> {
    this.hideSecret(); this.tab = 'license';
    try {
      const manager = this.getUIContext().getHostContext()!.resourceManager;
      const license = await manager.getRawFileContent('licenses/7zip-license.txt');
      const lgpl = await manager.getRawFileContent('licenses/7zip-lgpl.txt');
      if (this.active) this.license = new util.TextDecoder().decodeToString(license) + '\n\n' + new util.TextDecoder().decodeToString(lgpl);
    } catch (_) { this.status = this.label('archive_008'); }
  }

  private foregroundChanged(): void {
    if (!this.foreground) { this.confirmationController?.close(); this.password = ''; this.hideSecret(); this.stop(); }
  }

  private async initialize(): Promise<void> {
    try {
      await this.service.initialize(this.getUIContext().getHostContext() as common.UIAbilityContext,
        `${this.baseUrl}|${this.accountKey || 'local'}`);
      if (!this.active) return;
      this.ready = true; this.history = this.service.history();
      this.service.onUpdate = (value: ArchiveUpdate) => {
        if (!this.active) return;
        this.progress = { candidate: value.candidate, candidates: value.candidates, file: value.file, files: value.files,
          bytes: value.bytes, total: value.total, savingFailed: value.savingFailed };
        if (value.savingFailed) this.warning = this.label('archive_012');
      };
      this.service.onPause = async (): Promise<boolean> => {
        if (!this.active || !this.foreground) return false;
        this.paused = true;
        return new Promise<boolean>((resolve) => { this.resume = resolve; });
      };
      this.receiveIncoming();
    } catch (error) { this.fail(error); }
  }

  private label(key: string): string {
    try { return this.getUIContext().getHostContext()!.resourceManager.getStringByNameSync(key); }
    catch (_) { return key; }
  }

  private fail(error: Error): void {
    console.info(`Archive failure code: ${(error as BusinessError).code ?? -1}`);
    if (this.active) this.status = this.label(ArchiveErrors.resource(error.message));
  }

  private finish(): void {
    this.busy = false; this.exporting = false; this.password = ''; this.paused = false;
    if (!this.active) this.service.cleanup();
  }

  private receiveIncoming(): void {
    if (!this.ready || this.busy || this.incomingUri.length === 0) return;
    const uri = this.incomingUri;
    this.incomingUri = ''; this.tab = 'file';
    this.importUri(uri);
  }

  private async importUri(uri: string): Promise<void> {
    this.busy = true; this.status = ''; this.warning = '';
    try {
      this.entries = []; this.tree = []; this.expanded = []; this.search = ''; this.visibleCount = 100; this.selected = []; this.result = undefined;
      this.name = decodeURIComponent(uri.split('/').pop() || 'archive');
      this.inputBytes = fs.statSync(uri).size;
      await this.service.prepare(uri);
      if (!this.active || !this.pageVisible || !this.foreground) return;
      await this.inspect();
    } catch (error) { this.fail(error); }
    finally { this.finish(); }
  }

  private async choose(): Promise<void> {
    if (this.busy) return;
    this.busy = true; this.status = ''; this.warning = '';
    try {
      const options = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 1;
      options.fileSuffixFilters = ['Archives|.zip,.7z,.rar,.tar,.gz,.tgz'];
      const uris = await new picker.DocumentViewPicker(this.getUIContext().getHostContext() as common.UIAbilityContext).select(options);
      if (uris.length === 0 || !this.active) return;
      this.entries = []; this.tree = []; this.expanded = []; this.search = ''; this.visibleCount = 100; this.selected = []; this.result = undefined;
      this.name = decodeURIComponent(uris[0].split('/').pop() || 'archive');
      this.inputBytes = fs.statSync(uris[0]).size;
      await this.service.prepare(uris[0]);
      if (!this.active || !this.pageVisible || !this.foreground) return;
      await this.inspect();
    } catch (error) { this.fail(error); }
    finally { this.finish(); }
  }

  private async inspect(): Promise<void> {
    const result = await this.service.inspect(this.password, this.automatic);
    if (!this.active) return;
    this.format = result.format;
    if (result.code.length > 0) { this.status = this.label(ArchiveErrors.resource(result.code)); return; }
    this.entries = result.entries;
    this.tree = ArchiveTree.build(this.entries);
    this.expanded = [];
    this.selected = this.entries.map((item: ArchiveEntry) => item.id);
    this.status = '';
  }

  private async unlock(): Promise<void> {
    if (this.busy || this.name.length === 0) return;
    this.busy = true; this.status = ''; this.warning = '';
    try { await this.inspect(); }
    catch (error) { this.fail(error); }
    finally { this.finish(); }
  }

  private async extract(): Promise<void> {
    if (this.busy || this.selected.length === 0) return;
    this.busy = true; this.status = ''; this.warning = ''; this.result = undefined;
    try {
      const result = await this.service.extract(this.entries.filter((item: ArchiveEntry) => this.selected.includes(item.id)),
        this.password, this.automatic, this.name);
      if (!this.active) return;
      this.result = result; this.history = this.service.history(); this.status = this.label('archive_done');
    } catch (error) { this.fail(error); }
    finally { this.finish(); }
  }

  private stop(): void {
    this.service.cancel(); this.resume?.(false); this.resume = undefined; this.paused = false;
  }

  private async showPasswords(): Promise<void> {
    this.tab = 'passwords'; this.hideSecret(); this.status = '';
    try { this.passwords = await this.service.passwords.summaries(); }
    catch (error) { this.fail(error); }
  }

  private hideSecret(): void {
    this.revealedId = ''; this.revealedValue = '';
    if (this.revealTimer >= 0) clearTimeout(this.revealTimer);
    this.revealTimer = -1;
    try { this.auth?.cancel(); } catch (_) { /* 认证可能已经结束。 */ }
    this.auth = undefined;
  }

  private async reveal(id: string): Promise<void> {
    this.hideSecret();
    try {
      const auth = userAuth.getUserAuthInstance({ challenge: new Uint8Array(),
        authType: [userAuth.UserAuthType.PIN], authTrustLevel: userAuth.AuthTrustLevel.ATL1 },
        { title: this.label('archive_reveal_title') });
      this.auth = auth;
      auth.on('result', { onResult: (result: userAuth.UserAuthResult) => {
        if (result.result === userAuth.UserAuthResultCode.SUCCESS && this.active && this.foreground && this.tab === 'passwords') {
          this.loadSecret(id);
        }
        this.auth = undefined;
      } });
      auth.start();
    } catch (_) { this.status = this.label('archive_auth_failed'); }
  }

  private async loadSecret(id: string): Promise<void> {
    try {
      const row = (await this.service.passwords.load()).find((item: ArchivePassword) => item.id === id);
      if (!row || !this.active || !this.foreground || this.tab !== 'passwords') return;
      this.revealedId = id; this.revealedValue = row.value;
      this.revealTimer = setTimeout(() => this.hideSecret(), 15000);
    } catch (error) { this.fail(error); }
  }

  private async removePassword(id: string): Promise<void> {
    try { this.hideSecret(); await this.service.passwords.remove(id); await this.showPasswords(); }
    catch (error) { this.fail(error); }
  }

  private async clearPasswords(): Promise<void> {
    try { this.hideSecret(); await this.service.passwords.clear(); this.confirmClear = false; await this.showPasswords(); }
    catch (error) { this.fail(error); }
  }

  private async exportOutput(history: ArchiveHistory, item: ArchiveOutput): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      const options = new picker.DocumentSaveOptions();
      options.newFileNames = [item.path.split('/').pop() || 'file'];
      const uris = await new picker.DocumentViewPicker(this.getUIContext().getHostContext() as common.UIAbilityContext).save(options);
      if (uris.length > 0 && this.active && this.pageVisible) {
        await this.service.exportFile(history, item, uris[0]); this.status = this.label('archive_exported');
      }
    } catch (error) { this.fail(error); }
    finally { this.finish(); }
  }

  /** 确认删除当前结果，返回或取消时不修改历史。 */
  private confirmRemoveResult(id: string): void {
    if (this.busy || !this.active || !this.pageVisible || !this.foreground) return;
    this.confirmationController?.close();
    this.confirmationController = new CustomDialogController({
      builder: ToolConfirmDialog({ title: $r('app.string.archive_delete_result'),
        message: $r('app.string.archive_delete_notice'), cancelLabel: $r('app.string.archive_cancel'),
        confirmLabel: $r('app.string.archive_delete'), dangerous: true, confirmed: () => {
          // 弹窗关闭或账户离开后不执行旧删除，确认只作用于原结果。
          if (this.busy || !this.active || !this.pageVisible || !this.foreground) return;
          this.removeResult(id);
        } }),
      autoCancel: true, alignment: DialogAlignment.Center, customStyle: true
    });
    this.confirmationController.open();
  }

  private removeResult(id: string): void {
    try {
      this.service.removeHistory(id); this.history = this.service.history();
      if (this.result?.id === id) this.result = undefined;
    } catch (error) { this.fail(error); }
  }

  private formatBytes(bytes: number): string {
    return bytes >= 1048576 ? `${(bytes / 1048576).toFixed(1)} MB` : `${(bytes / 1024).toFixed(1)} KB`;
  }

  private filtered(): ArchiveTreeRow[] {
    return ArchiveTree.visible(this.tree, this.expanded, this.search);
  }

  private toggleFolder(path: string): void {
    this.expanded = this.expanded.includes(path) ? this.expanded.filter((value: string) => value !== path) : this.expanded.concat([path]);
  }

  private async exportAll(history: ArchiveHistory, directory: boolean): Promise<void> {
    if (this.busy) return;
    this.busy = true; this.status = ''; this.exportedCount = 0; this.exportTotal = history.outputs.length;
    try {
      const viewPicker = new picker.DocumentViewPicker(this.getUIContext().getHostContext() as common.UIAbilityContext);
      let uris: string[] = [];
      if (directory) {
        const options = new picker.DocumentSelectOptions();
        if (canIUse('SystemCapability.FileManagement.UserFileService.FolderSelection')) {
          options.selectMode = picker.DocumentSelectMode.FOLDER;
        } else { throw new Error('ARCHIVE_008'); }
        options.maxSelectNumber = 1;
        uris = await viewPicker.select(options);
      } else {
        const options = new picker.DocumentSaveOptions();
        options.newFileNames = ArchiveTree.exportNames(history.outputs.map((item: ArchiveOutput) => item.path));
        uris = await viewPicker.save(options);
      }
      if (uris.length === 0 || !this.active || !this.pageVisible) return;
      this.exporting = true;
      const update = (done: number, total: number): void => { this.exportedCount = done; this.exportTotal = total; };
      if (directory) {
        const folder = await this.service.exportDirectory(history, uris[0], update);
        this.status = `${this.label('archive_folder_exported')} ${folder}`;
      } else {
        await this.service.exportFiles(history, uris, update);
        this.status = `${this.label('archive_exported')} ${this.exportedCount}/${this.exportTotal}`;
      }
    } catch (error) {
      this.fail(error);
      if (!directory && this.exporting) this.status += ` ${this.label('archive_partial_export')} ${this.exportedCount}/${this.exportTotal}`;
    } finally { this.finish(); }
  }

  @Builder
  private ResultCard(history: ArchiveHistory) {
    Column({ space: 12 }) {
      Text(history.name).fontSize(16).fontFamily(AppTheme.fontSemibold).width('100%')
      Text(`${history.outputs.length} · ${this.formatBytes(history.outputs.reduce((sum: number, item: ArchiveOutput) => sum + item.size, 0))}`)
        .fontSize(12).fontFamily(AppTheme.fontFamily).fontColor(AppTheme.textSecondary).width('100%')
      Text($r('app.string.archive_result_location')).fontSize(12).fontFamily(AppTheme.fontFamily).fontColor(AppTheme.textSecondary)
      if (history.outputs.length > 0) {
        AppButton({ label: $r('app.string.archive_export_all'), fullWidth: true, available: !this.busy,
          action: () => this.exportAll(history, false) })
        Text($r('app.string.archive_flat_export_notice')).fontSize(12).fontFamily(AppTheme.fontFamily).fontColor(AppTheme.textSecondary)
      }
      if (this.folderExport) {
        AppButton({ label: $r('app.string.archive_export_folder'), variant: 'secondary', fullWidth: true,
          available: !this.busy, action: () => this.exportAll(history, true) })
      }
      if ((history.directories || []).length > 0) {
        Text(`${this.label('archive_preserved_folders')} ${(history.directories || []).length}`)
          .fontSize(12).fontFamily(AppTheme.fontFamily).fontColor(AppTheme.textSecondary)
      }
      ForEach(history.outputs.slice(0, this.visibleCount), (item: ArchiveOutput) => {
        Row({ space: 12 }) {
          Column({ space: 4 }) {
            Text(item.path).fontSize(15).fontFamily(AppTheme.fontFamily).width('100%').maxLines(3)
            Text(this.formatBytes(item.size)).fontSize(12).fontFamily(AppTheme.fontFamily).fontColor(AppTheme.textSecondary)
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          AppTextAction({ label: $r('app.string.archive_export'), available: !this.busy,
            action: () => this.exportOutput(history, item) })
        }.width('100%').constraintSize({ minHeight: 64 })
          .border({ width: { bottom: 1 }, color: AppTheme.divider })
      }, (item: ArchiveOutput) => item.source)
      if (history.outputs.length > this.visibleCount) {
        AppTextAction({ label: $r('app.string.archive_more'), action: () => this.visibleCount += 100 })
      }
      AppButton({ label: $r('app.string.archive_delete_result'), variant: 'danger', available: !this.busy,
        action: () => this.confirmRemoveResult(history.id) })
    }.padding(16).width('100%').backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
  }

  /** 展示文件预览、密码匹配、结果及本机密码库。 */
  build() {
    Column({ space: 16 }) {
      Row({ space: 8 }) {
        ToolPageHeader({ title: $r('app.string.archive_title'), available: !this.busy,
          onBack: () => { this.hideSecret(); this.onBack(); } }).layoutWeight(1)
        AppTextAction({ label: $r('app.string.archive_license'), available: !this.busy, action: () => this.showLicense() })
      }.width('100%')
      Text($r('app.string.tool_ui_archive_hint')).fontSize(13).fontFamily(AppTheme.fontFamily)
        .fontColor(AppTheme.textSecondary).width('100%')
      Row({ space: 0 }) {
        AppSegmentItem({ label: $r('app.string.archive_files'), selected: this.tab === 'file', available: !this.busy,
          action: () => { this.hideSecret(); this.tab = 'file'; } }).layoutWeight(1)
        AppSegmentItem({ label: $r('app.string.archive_history'), selected: this.tab === 'history', available: !this.busy,
          action: () => { this.hideSecret(); this.tab = 'history'; this.history = this.service.history(); } }).layoutWeight(1)
        AppSegmentItem({ label: $r('app.string.archive_vault'), selected: this.tab === 'passwords', available: this.ready && !this.busy,
          action: () => this.showPasswords() }).layoutWeight(1)
      }.width('100%').backgroundColor(AppTheme.surfaceTint).borderRadius(12)
      if (this.warning.length > 0) {
        Text(this.warning).fontColor(AppTheme.danger).fontSize(13).fontFamily(AppTheme.fontFamily)
          .width('100%').padding(12).backgroundColor(AppTheme.dangerSoft).borderRadius(12)
      }
      if (this.status.length > 0) {
        Text(this.status).fontColor(AppTheme.textSecondary).fontSize(13).fontFamily(AppTheme.fontFamily)
          .width('100%').padding(12).backgroundColor(AppTheme.surfaceTint).borderRadius(12)
      }
      if (this.tab === 'file') {
        Column({ space: 12 }) {
          Row({ space: 12 }) {
            SymbolGlyph($r('sys.symbol.folder_fill')).fontSize(28).fontColor([AppTheme.primary])
              .width(56).height(56).backgroundColor(AppTheme.accentSoft).borderRadius(14)
            Column({ space: 6 }) {
              Text(this.name || this.label('tool_ui_archive_title')).fontSize(18).fontFamily(AppTheme.fontSemibold)
                .width('100%').maxLines(2)
              Text(this.name ? `${this.format} · ${this.formatBytes(this.inputBytes)}` : this.label('archive_formats'))
                .fontSize(12).fontFamily(AppTheme.fontFamily).fontColor(AppTheme.textSecondary).width('100%')
            }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          }.width('100%')
          Text($r('app.string.tool_ui_archive_local')).fontSize(12).fontFamily(AppTheme.fontFamily)
            .fontColor(AppTheme.primary).width('100%')
          AppButton({ label: $r('app.string.archive_choose'), fullWidth: true, available: this.ready && !this.busy,
            action: () => this.choose() })
        }.padding(16).width('100%').backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
        if (this.name.length > 0) {
          Column({ space: 12 }) {
            Text($r('app.string.tool_ui_archive_password')).fontSize(18).fontFamily(AppTheme.fontSemibold).width('100%')
            Row({ space: 12 }) {
              Text($r('app.string.archive_auto')).layoutWeight(1).fontSize(16).fontFamily(AppTheme.fontFamily)
              Toggle({ type: ToggleType.Switch, isOn: this.automatic }).selectedColor(AppTheme.primary)
                .enabled(!this.busy).onChange((value: boolean) => this.automatic = value)
            }.width('100%').constraintSize({ minHeight: 64 })
            AppGlassTextInput({ value: this.password, placeholder: this.label('archive_password_hint'),
              inputType: InputType.Password, limit: 1000, available: !this.busy,
              valueChanged: (value: string) => this.password = value })
            Text($r('app.string.archive_password_notice')).fontSize(12).fontFamily(AppTheme.fontFamily)
              .fontColor(AppTheme.textSecondary).width('100%')
            if (this.entries.length === 0) {
              AppButton({ label: $r('app.string.archive_unlock'), available: !this.busy, action: () => this.unlock() })
            }
          }.padding(16).width('100%').backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
        }
        if (this.entries.length > 0 && !this.result && !this.busy) {
          Column({ space: 12 }) {
            Row({ space: 8 }) {
              Text(`${this.label('archive_preview')} · ${this.selected.length}/${this.entries.length}`)
                .layoutWeight(1).fontSize(16).fontFamily(AppTheme.fontSemibold)
              AppTextAction({ label: $r('app.string.archive_select_all'), available: !this.busy, action: () => {
                this.selected = this.selected.length === this.entries.length ? [] : this.entries.map((item: ArchiveEntry) => item.id);
              } })
            }.width('100%')
            AppSearchField({ value: this.search, placeholder: this.label('archive_search'),
              valueChanged: (value: string) => { this.search = value; this.visibleCount = 100; },
              cleared: () => { this.search = ''; this.visibleCount = 100; } })
            ForEach(this.filtered().slice(0, this.visibleCount), (entry: ArchiveTreeRow) => {
              Row({ space: 8 }) {
                Column() {
                  Checkbox({ name: entry.path }).select(entry.ids.every((id: number) => this.selected.includes(id)))
                    .width(20).height(20).selectedColor(AppTheme.primary).enabled(!this.busy)
                    .responseRegion({ x: -12, y: -12, width: 44, height: 44 })
                    .accessibilityText(entry.name)
                    .onChange((value: boolean) => { this.selected = ArchiveTree.select(this.selected, entry, value); })
                }.width(44).height(44).justifyContent(FlexAlign.Center)
                if (entry.directory) {
                  AppButton({ label: this.expanded.includes(entry.path) || this.search.length > 0 ? '−' : '+',
                    variant: 'secondary', fullWidth: true, minWidth: 44, action: () => this.toggleFolder(entry.path) }).width(44)
                }
                Column({ space: 4 }) {
                  Text(`${entry.directory ? this.label('archive_folder_prefix') : ''}${entry.name}`)
                    .fontSize(15).fontFamily(AppTheme.fontFamily).width('100%').maxLines(3)
                  if (entry.directory) {
                    Text(`${this.label('archive_selected')} ${entry.ids.filter((id: number) => this.selected.includes(id)).length}/${entry.ids.length}`)
                      .fontSize(12).fontFamily(AppTheme.fontFamily).fontColor(AppTheme.primary)
                  }
                  Text(`${this.formatBytes(entry.size)}${entry.encrypted ? ' · ' + this.label('archive_encrypted') : ''}`)
                    .fontSize(12).fontFamily(AppTheme.fontFamily).fontColor(AppTheme.textSecondary).width('100%')
                }.layoutWeight(1).alignItems(HorizontalAlign.Start)
              }.width('100%').constraintSize({ minHeight: 64 })
                .padding({ top: 8, bottom: 8, left: Math.min(entry.depth, 3) * 8 })
                .border({ width: { bottom: 1 }, color: AppTheme.divider })
            }, (entry: ArchiveTreeRow) => entry.path)
            if (this.filtered().length > this.visibleCount) {
              AppTextAction({ label: $r('app.string.archive_more'), action: () => this.visibleCount += 100 })
            }
            Text($r('app.string.archive_output_notice')).fontSize(12).fontFamily(AppTheme.fontFamily).fontColor(AppTheme.textSecondary)
            AppButton({ label: $r('app.string.archive_extract'), fullWidth: true,
              available: !this.busy && this.selected.length > 0, action: () => this.extract() })
          }.padding(16).width('100%').backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
        }
        if (this.result && !this.busy) { this.ResultCard(this.result) }
      } else if (this.tab === 'history') {
        if (this.history.length === 0) {
          ToolEmptyState({ icon: $r('sys.symbol.doc_text'), title: $r('app.string.archive_history_empty'),
            description: $r('app.string.tool_ui_archive_history_hint') })
        }
        if (!this.busy) { ForEach(this.history, (item: ArchiveHistory) => { this.ResultCard(item) }, (item: ArchiveHistory) => item.id) }
      } else if (this.tab === 'passwords') {
        Text($r('app.string.archive_vault_notice')).fontSize(13).fontFamily(AppTheme.fontFamily).fontColor(AppTheme.textSecondary)
        if (this.passwords.length === 0) {
          ToolEmptyState({ icon: $r('sys.symbol.lock'), title: $r('app.string.archive_vault_empty'),
            description: $r('app.string.tool_ui_archive_vault_hint') })
        }
        ForEach(this.passwords, (item: ArchivePasswordSummary) => {
          Column({ space: 12 }) {
            Text(this.revealedId === item.id ? this.revealedValue : '••••••••')
              .fontSize(18).fontFamily(AppTheme.fontMedium).width('100%')
            Text(`${new Date(item.lastUsedAt).toLocaleString()} · ${this.label('archive_attempts')} ${item.attempts}`)
              .fontSize(12).fontFamily(AppTheme.fontFamily).fontColor(AppTheme.textSecondary).width('100%')
            Text(item.successes > 0 ? `${this.label('archive_successes')} ${item.successes}` : this.label('archive_unverified'))
              .fontSize(12).fontFamily(AppTheme.fontFamily).fontColor(AppTheme.primary).width('100%')
            AppEqualActionRow() {
              AppGroupedButton({ label: $r('app.string.archive_reveal'), variant: 'secondary',
                fullWidth: true, action: () => this.reveal(item.id) })
              AppGroupedButton({ label: $r('app.string.archive_delete'), variant: 'danger',
                fullWidth: true, action: () => this.removePassword(item.id) })
            }
          }.padding(16).width('100%').backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
        }, (item: ArchivePasswordSummary) => item.id)
        if (this.passwords.length > 0) {
          AppButton({ label: this.confirmClear ? this.label('archive_clear_confirm') : this.label('archive_clear'),
            variant: 'danger', action: () => {
              if (this.confirmClear) this.clearPasswords(); else this.confirmClear = true;
            } })
        }
      }
      if (this.tab === 'license') {
        Text('7-Zip 26.03 · LGPL / BSD / unRAR').fontSize(16).fontFamily(AppTheme.fontSemibold)
        Text('https://www.7-zip.org/a/7z2603-src.tar.xz').copyOption(CopyOptions.LocalDevice)
          .fontSize(12).fontFamily(AppTheme.fontFamily)
        Text(this.license).fontSize(12).fontFamily(AppTheme.fontFamily).width('100%')
      }
      if (this.busy && this.exporting) {
        Column({ space: 12 }) {
          Text(`${this.label('archive_export_progress')} ${this.exportedCount}/${this.exportTotal}`)
            .fontSize(16).fontFamily(AppTheme.fontSemibold)
          Progress({ value: this.exportedCount, total: Math.max(this.exportTotal, 1), type: ProgressType.Linear })
            .color(AppTheme.primary).width('100%')
          AppButton({ label: $r('app.string.archive_cancel'), variant: 'secondary', action: () => this.stop() })
        }.padding(16).width('100%').backgroundColor(AppTheme.accentSoft).borderRadius(AppTheme.cardRadius)
      }
      if (this.busy && !this.exporting) {
        Column({ space: 12 }) {
          Text(this.paused ? this.label('archive_paused') : this.label('archive_working'))
            .fontSize(16).fontFamily(AppTheme.fontSemibold)
          Text(`${this.label('archive_matching')} ${this.progress.candidate}/${this.progress.candidates} · ${this.label('archive_files')} ${this.progress.file}/${this.progress.files}`)
            .fontSize(12).fontFamily(AppTheme.fontFamily)
          // 未获得真实总量时使用不定加载，避免把等待误报为百分比进度。
          if (this.progress.total > 0) {
            Progress({ value: this.progress.bytes, total: this.progress.total, type: ProgressType.Linear })
              .width('100%').color(AppTheme.primary)
          } else {
            LoadingProgress().width(24).height(24).color(AppTheme.primary)
          }
          Text(`${this.formatBytes(this.progress.bytes)} / ${this.formatBytes(this.progress.total)}`)
            .fontSize(12).fontFamily(AppTheme.fontFamily)
          if (this.paused) {
            AppActionGroup({ primaryLabel: $r('app.string.archive_continue'), secondaryLabel: $r('app.string.archive_cancel'),
              primaryAction: () => { this.paused = false; this.resume?.(true); this.resume = undefined; },
              secondaryAction: () => this.stop() })
          } else {
            AppButton({ label: $r('app.string.archive_cancel'), variant: 'secondary', action: () => this.stop() })
          }
        }.padding(16).width('100%').backgroundColor(AppTheme.accentSoft).borderRadius(AppTheme.cardRadius)
      }
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
}
