import { AppTheme } from '../theme/AppTheme';

/** 玻璃分段栏中的单个选项。 */
@Component
export struct AppSegmentItem {
  @Prop label: string = '';
  @Prop selected: boolean = false;
  action: () => void = () => {};

  build() {
    Button(this.label)
      .height(36)
      .padding({ left: 14, right: 14 })
      .fontSize(13)
      .fontFamily('HarmonyOS Sans')
      .fontWeight(this.selected ? FontWeight.Medium : FontWeight.Normal)
      .fontColor(this.selected ? AppTheme.primary : AppTheme.textSecondary)
      .backgroundColor(this.selected ? AppTheme.accentSoft : Color.Transparent)
      .borderRadius(AppTheme.pillRadius)
      .accessibilityDescription(this.selected ? '已选择' : '未选择')
      .onClick(this.action)
  }
}

/** 青绿渐变的自适应胶囊主按钮。 */
@Component
export struct AppPrimaryPillButton {
  @Prop label: string = '';
  @Prop available: boolean = true;
  @Prop description: string = '';
  action: () => void = () => {};

  build() {
    Button(this.label)
      .height(AppTheme.controlHeight)
      .padding({ left: 22, right: 22 })
      .fontSize(14)
      .fontFamily('HarmonyOS Sans')
      .fontWeight(FontWeight.Medium)
      .fontColor(Color.White)
      .backgroundColor(AppTheme.primary)
      .linearGradient({ angle: 135, colors: [[AppTheme.primary, 0], [AppTheme.primaryEnd, 1]] })
      .borderRadius(AppTheme.pillRadius)
      .shadow({ radius: 3, color: AppTheme.shadowSmall, offsetY: 1 })
      .enabled(this.available)
      .accessibilityDescription(this.description)
      .onClick(this.action)
  }
}

/** 统一浅色胶囊次按钮。 */
@Component
export struct AppSecondaryPillButton {
  @Prop label: string = '';
  @Prop available: boolean = true;
  @Prop description: string = '';
  action: () => void = () => {};

  build() {
    Button(this.label)
      .height(AppTheme.controlHeight)
      .padding({ left: 18, right: 18 })
      .fontSize(14)
      .fontFamily('HarmonyOS Sans')
      .fontWeight(FontWeight.Medium)
      .fontColor(AppTheme.textPrimary)
      .backgroundColor(AppTheme.surfaceTint)
      .border({ width: 1, color: AppTheme.divider, radius: AppTheme.pillRadius })
      .borderRadius(AppTheme.pillRadius)
      .enabled(this.available)
      .accessibilityDescription(this.description)
      .onClick(this.action)
  }
}

/** 统一柔和危险操作胶囊按钮。 */
@Component
export struct AppDangerPillButton {
  @Prop label: string = '';
  @Prop available: boolean = true;
  @Prop description: string = '';
  action: () => void = () => {};

  build() {
    Button(this.label)
      .height(AppTheme.controlHeight)
      .padding({ left: 18, right: 18 })
      .fontSize(14)
      .fontFamily('HarmonyOS Sans')
      .fontWeight(FontWeight.Medium)
      .fontColor(AppTheme.danger)
      .backgroundColor(AppTheme.dangerSoft)
      .border({ width: 1, color: AppTheme.danger, radius: AppTheme.pillRadius })
      .borderRadius(AppTheme.pillRadius)
      .enabled(this.available)
      .accessibilityDescription(this.description)
      .onClick(this.action)
  }
}

/** 可复用的描边筛选标签。 */
@Component
export struct AppFilterChip {
  @Prop label: string = '';
  @Prop selected: boolean = false;
  action: () => void = () => {};

  build() {
    Button(this.label)
      .height(32)
      .padding({ left: 13, right: 13 })
      .fontSize(12)
      .fontFamily('HarmonyOS Sans')
      .fontWeight(this.selected ? FontWeight.Medium : FontWeight.Normal)
      .fontColor(this.selected ? AppTheme.primary : AppTheme.textSecondary)
      .backgroundColor(this.selected ? AppTheme.accentSoft : AppTheme.surface)
      .border({ width: 1, color: this.selected ? AppTheme.primary : AppTheme.divider,
        radius: AppTheme.pillRadius })
      .borderRadius(AppTheme.pillRadius)
      .accessibilityDescription(this.selected ? '已选择' : '未选择')
      .onClick(this.action)
  }
}

/** 统一玻璃质感的单行输入框。 */
@Component
export struct AppGlassTextInput {
  @Prop value: string = '';
  @Prop placeholder: string = '';
  @Prop inputType: InputType = InputType.Normal;
  @Prop limit: number = 1000;
  @Prop available: boolean = true;
  valueChanged: (value: string) => void = () => {};
  submitted: () => void = () => {};

  build() {
    TextInput({ text: this.value, placeholder: this.placeholder })
      .width('100%')
      .height(AppTheme.inputHeight)
      .padding({ left: 16, right: 16 })
      .maxLength(this.limit)
      .type(this.inputType)
      .fontSize(14)
      .fontFamily('HarmonyOS Sans')
      .fontColor(AppTheme.textPrimary)
      .placeholderColor(AppTheme.textTertiary)
      .backgroundColor(AppTheme.glass)
      .border({ width: 1, color: AppTheme.divider, radius: AppTheme.inputRadius })
      .borderRadius(AppTheme.inputRadius)
      .shadow({ radius: 3, color: AppTheme.shadowSmall, offsetY: 1 })
      .enabled(this.available)
      .onChange(this.valueChanged)
      .onSubmit(this.submitted)
  }
}

/** 统一玻璃质感的多行文本输入框。 */
@Component
export struct AppGlassTextArea {
  @Prop value: string = '';
  @Prop placeholder: string = '';
  @Prop limit: number = 1000;
  @Prop areaHeight: number = 140;
  @Prop available: boolean = true;
  valueChanged: (value: string) => void = () => {};

  build() {
    TextArea({ text: this.value, placeholder: this.placeholder })
      .width('100%')
      .height(this.areaHeight)
      .padding(16)
      .maxLength(this.limit)
      .fontSize(14)
      .fontFamily('HarmonyOS Sans')
      .fontColor(AppTheme.textPrimary)
      .placeholderColor(AppTheme.textTertiary)
      .backgroundColor(AppTheme.glass)
      .border({ width: 1, color: AppTheme.divider, radius: AppTheme.inputRadius })
      .borderRadius(AppTheme.inputRadius)
      .shadow({ radius: 3, color: AppTheme.shadowSmall, offsetY: 1 })
      .enabled(this.available)
      .onChange(this.valueChanged)
  }
}

/** 统一玻璃质感的只读多行结果面板。 */
@Component
export struct AppGlassTextOutput {
  @Prop value: string = '';
  @Prop placeholder: string = '';
  @Prop areaHeight: number = 140;

  build() {
    Scroll() {
      Text(this.value.length > 0 ? this.value : this.placeholder)
        .width('100%')
        .fontSize(14)
        .fontFamily('HarmonyOS Sans')
        .fontColor(this.value.length > 0 ? AppTheme.textPrimary : AppTheme.textTertiary)
        .copyOption(CopyOptions.LocalDevice)
    }
    .width('100%')
    .height(this.areaHeight)
    .padding(16)
    .backgroundColor(AppTheme.surfaceTint)
    .border({ width: 1, color: AppTheme.divider, radius: AppTheme.inputRadius })
    .borderRadius(AppTheme.inputRadius)
  }
}

/** 带搜索图标和清除操作的统一玻璃搜索框。 */
@Component
export struct AppSearchField {
  @Prop value: string = '';
  @Prop placeholder: string = '';
  @Prop limit: number = 100;
  @Prop available: boolean = true;
  valueChanged: (value: string) => void = () => {};
  submitted: () => void = () => {};
  cleared: () => void = () => {};

  build() {
    Row({ space: 8 }) {
      SymbolGlyph($r('sys.symbol.magnifyingglass'))
        .fontSize(18)
        .fontColor([this.value.length > 0 ? AppTheme.primary : AppTheme.textSecondary])
      TextInput({ text: this.value, placeholder: this.placeholder })
        .layoutWeight(1)
        .height(46)
        .padding(0)
        .maxLength(this.limit)
        .fontSize(14)
        .fontFamily('HarmonyOS Sans')
        .fontColor(AppTheme.textPrimary)
        .placeholderColor(AppTheme.textTertiary)
        .backgroundColor(Color.Transparent)
        .enabled(this.available)
        .onChange(this.valueChanged)
        .onSubmit(this.submitted)
      if (this.value.length > 0) {
        Button() {
          SymbolGlyph($r('sys.symbol.xmark')).fontSize(14).fontColor([AppTheme.textSecondary])
        }
        .width(40)
        .height(40)
        .padding(0)
        .borderRadius(20)
        .backgroundColor(Color.Transparent)
        .accessibilityText('清除搜索内容')
        .onClick(() => {
          this.valueChanged('');
          this.cleared();
        })
      }
    }
    .width('100%')
    .height(AppTheme.inputHeight)
    .padding({ left: 14, right: 6 })
    .backgroundColor(this.value.length > 0 ? AppTheme.accentSoft : AppTheme.glass)
    .border({ width: 1, color: this.value.length > 0 ? AppTheme.primary : AppTheme.divider,
      radius: AppTheme.inputRadius })
    .borderRadius(AppTheme.inputRadius)
    .shadow({ radius: 3, color: AppTheme.shadowSmall, offsetY: 1 })
  }
}

/** 统一设置与入口列表行。 */
@Component
export struct AppSettingsRow {
  @Prop title: string = '';
  @Prop subtitle: string = '';
  action: () => void = () => {};

  build() {
    Button({ type: ButtonType.Normal }) {
      Row({ space: 12 }) {
        Column({ space: 5 }) {
          Text(this.title)
            .width('100%')
            .fontSize(15)
            .fontFamily('HarmonyOS Sans')
            .fontColor(AppTheme.textPrimary)
          Text(this.subtitle)
            .width('100%')
            .maxLines(2)
            .fontSize(12)
            .fontFamily('HarmonyOS Sans')
            .fontColor(AppTheme.textSecondary)
        }
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Start)
        SymbolGlyph($r('sys.symbol.chevron_right'))
          .fontSize(18)
          .fontColor([AppTheme.textSecondary])
      }
      .width('100%')
    }
    .width('100%')
    .constraintSize({ minHeight: 68 })
    .padding({ left: 0, right: 0, top: 10, bottom: 10 })
    .backgroundColor(Color.Transparent)
    .borderRadius(0)
    .border({ width: { bottom: 1 }, color: AppTheme.divider, radius: 0 })
    .accessibilityText(this.title)
    .accessibilityDescription(this.subtitle)
    .onClick(this.action)
  }
}
