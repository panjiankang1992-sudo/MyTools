import { AppTheme } from '../../theme/AppTheme';
import { AppActionGroup, AppIconButton } from '../../components/AppControls';

/** 工具页统一导航，保证返回操作具有完整的触控区域。 */
@Component
export struct ToolPageHeader {
  @Prop darkMode: boolean = false;
  @Prop title: ResourceStr = '';
  @Prop available: boolean = true;
  onBack: () => void = () => {};
  /** 构建紧凑的工具导航栏。 */
  build() {
    Row({ space: 12 }) {
      AppIconButton({ icon: $r('sys.symbol.chevron_left'), available: this.available, darkMode: this.darkMode,
        description: '返回', action: () => this.onBack() })
      Text(this.title).fontFamily(AppTheme.fontSemibold).fontSize(20).lineHeight(28)
        .fontWeight(600).fontColor(this.darkMode ? AppTheme.darkTextPrimary : AppTheme.ink).layoutWeight(1)
    }.width('100%')
  }
}

/** 空状态使用统一图标、标题和说明，引导用户理解下一步。 */
@Component
export struct ToolEmptyState {
  @Prop darkMode: boolean = false;
  icon: Resource = $r('sys.symbol.doc_text');
  @Prop title: ResourceStr = '';
  @Prop description: ResourceStr = '';
  /** 构建无数据时的说明卡片。 */
  build() {
    Column({ space: 12 }) {
      SymbolGlyph(this.icon).fontSize(30).fontColor([this.darkMode ? AppTheme.darkPrimary : AppTheme.primary]).margin({ bottom: 4 })
      Text(this.title).fontFamily(AppTheme.fontSemibold).fontSize(18).lineHeight(26)
        .fontWeight(600).fontColor(this.darkMode ? AppTheme.darkTextPrimary : AppTheme.ink).textAlign(TextAlign.Center)
      Text(this.description).fontFamily(AppTheme.fontFamily).fontSize(15).fontColor(this.darkMode ? AppTheme.darkTextSecondary : AppTheme.textSecondary)
        .textAlign(TextAlign.Center).lineHeight(24)
    }.width('100%').padding({ top: 32, bottom: 32, left: 20, right: 20 })
      .backgroundColor(this.darkMode ? AppTheme.darkSurface : AppTheme.surface).borderRadius(AppTheme.cardRadius)
  }
}

/** 创作参数变更与删除操作共用确认弹窗，固定字体和等宽操作组。 */
@CustomDialog
export struct ToolConfirmDialog {
  controller?: CustomDialogController;
  @Prop title: ResourceStr = '';
  @Prop message: ResourceStr = '';
  @Prop confirmLabel: ResourceStr = '';
  @Prop cancelLabel: ResourceStr = '';
  @Prop dangerous: boolean = false;
  confirmed: () => void = () => {};

  /** 构建确认内容，取消和确认均保持完整的触控区域。 */
  build() {
    Column({ space: 16 }) {
      Text(this.title).fontFamily(AppTheme.fontSemibold).fontSize(20).fontWeight(600)
        .lineHeight(28).fontColor(AppTheme.textPrimary).width('100%')
      Text(this.message).fontFamily(AppTheme.fontFamily).fontSize(15).lineHeight(24)
        .fontColor(AppTheme.textSecondary).width('100%')
      AppActionGroup({ primaryLabel: this.confirmLabel, secondaryLabel: this.cancelLabel,
        dangerous: this.dangerous, primaryAction: (): void => {
          // 先关闭弹窗再执行原业务，防止再次点击重复确认。
          this.controller?.close(); this.confirmed();
        }, secondaryAction: (): void => { this.controller?.close(); } })
    }.padding(20).width('90%').constraintSize({ maxWidth: 400 })
      .alignItems(HorizontalAlign.Start).backgroundColor(AppTheme.surface)
      .borderRadius(AppTheme.cardRadius)
  }
}
