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
      .onClick(this.action)
  }
}

/** 统一浅色胶囊次按钮。 */
@Component
export struct AppSecondaryPillButton {
  @Prop label: string = '';
  @Prop available: boolean = true;
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
      .onClick(this.action)
  }
}

/** 统一柔和危险操作胶囊按钮。 */
@Component
export struct AppDangerPillButton {
  @Prop label: string = '';
  @Prop available: boolean = true;
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
