import { AppTheme } from '../theme/AppTheme';

/** 可复用的语义按钮，普通动作保持紧凑，输入行模式让底板与字段等高。 */
@Component
export struct AppButton {
  @Prop label: ResourceStr = '';
  @Prop variant: string = 'primary';
  @Prop available: boolean = true;
  @Prop loading: boolean = false;
  @Prop description: string = '';
  @Prop fullWidth: boolean = false;
  @Prop darkMode: boolean = false;
  @Prop grouped: boolean = false;
  @Prop fieldAligned: boolean = false;
  @Prop minWidth: number = 0;
  @State private pressed: boolean = false;
  action: () => void = () => {};

  private isText(): boolean { return this.variant === 'text' || this.variant === 'dangerText'; }
  private isFilled(): boolean { return this.variant === 'primary' || this.variant === 'destructive'; }
  private inset(): number { return this.fieldAligned ? 0 : (this.grouped || this.isFilled() ? 2 : (this.isText() ? 6 : 4)); }
  private targetHeight(): number { return this.fieldAligned ? AppTheme.inputHeight : AppTheme.touchTarget; }
  private radius(): number { return this.fieldAligned ? AppTheme.inputRadius : AppTheme.buttonRadius; }
  private foreground(): ResourceColor {
    // 加载中的文字仍需清晰，只把真正不可用的操作使用禁用颜色。
    if (!this.available && !this.loading) {
      return this.darkMode ? AppTheme.darkDisabledText : AppTheme.disabledText;
    }
    if (this.isFilled()) {
      return this.darkMode ? AppTheme.darkPrimaryText : Color.White;
    }
    if (this.variant === 'danger' || this.variant === 'dangerText') {
      return this.darkMode ? AppTheme.darkDanger : AppTheme.danger;
    }
    return this.isText() ? (this.darkMode ? AppTheme.darkPrimary : AppTheme.primary) :
      (this.darkMode ? AppTheme.darkTextPrimary : AppTheme.textPrimary);
  }
  private fill(): ResourceColor {
    if (!this.available && !this.loading) {
      return this.darkMode ? AppTheme.darkDisabledSurface : AppTheme.disabledSurface;
    }
    if (this.variant === 'primary') {
      return this.pressed ? (this.darkMode ? AppTheme.darkPrimaryPressed : AppTheme.primaryPressed) :
        (this.darkMode ? AppTheme.darkPrimary : AppTheme.primary);
    }
    if (this.variant === 'destructive') {
      return this.darkMode ? AppTheme.darkDanger : AppTheme.danger;
    }
    if (this.pressed) {
      return this.darkMode ? AppTheme.darkAccentSoft : AppTheme.accentSoft;
    }
    if (this.variant === 'danger') {
      return this.darkMode ? AppTheme.darkDangerSoft : AppTheme.dangerSoft;
    }
    return this.isText() ? Color.Transparent : (this.darkMode ? AppTheme.darkSurface : AppTheme.surface);
  }

  private dimension(value: Length | undefined, fallback: number): number {
    const size = Number(value);
    return Number.isFinite(size) ? Math.max(0, size) : fallback;
  }

  /** 测量自然内容后填满父级指定的高度，避免分组底板只拉伸点击区域。 */
  onMeasureSize(selfLayoutInfo: GeometryInfo, children: Array<Measurable>, constraint: ConstraintSizeOptions): SizeResult {
    const inset = this.inset() * 2;
    const maxWidth = this.dimension(constraint.maxWidth, Number.POSITIVE_INFINITY);
    const maxHeight = this.dimension(constraint.maxHeight, Number.POSITIVE_INFINITY);
    const minWidth = Math.min(maxWidth, Math.max(this.dimension(constraint.minWidth, 0),
      this.grouped ? Math.max(AppTheme.groupMinWidth, this.minWidth) : this.minWidth));
    const natural = children[0].measure({ minWidth: minWidth, maxWidth: maxWidth,
      minHeight: Math.max(0, Math.min(maxHeight, this.targetHeight()) - inset),
      maxHeight: Math.max(0, maxHeight - inset) });
    const width = Math.min(maxWidth, (this.fullWidth || this.grouped) && Number.isFinite(maxWidth) ?
      maxWidth : Math.max(minWidth, natural.width));
    const height = Math.min(maxHeight, Math.max(this.targetHeight(), this.dimension(constraint.minHeight, 0),
      natural.height + inset));
    // 第二次测量只消费父级约束，不回写状态；共同高度会同时作用于可见底板和命中区域。
    children[0].measure({ minWidth: width, maxWidth: width,
      minHeight: Math.max(0, height - inset), maxHeight: Math.max(0, height - inset) });
    children[1].measure({ minWidth: width, maxWidth: width, minHeight: height, maxHeight: height });
    return { width: width, height: height };
  }

  /** 将底板置于命中区域中央，原生按钮覆盖整个命中区域并保留可访问语义。 */
  onPlaceChildren(selfLayoutInfo: GeometryInfo, children: Array<Layoutable>, constraint: ConstraintSizeOptions): void {
    children[0].layout({ x: 0, y: this.inset() });
    children[1].layout({ x: 0, y: 0 });
  }

  @Builder
  private ButtonChildren() {
      Row({ space: 6 }) {
        if (this.loading) {
          LoadingProgress().width(18).height(18).color(this.foreground())
        }
        Text(this.label)
          .fontFamily(!this.grouped && this.isFilled() ? AppTheme.fontSemibold : AppTheme.fontMedium)
          .fontSize(AppTheme.buttonFontSize)
          .fontWeight(!this.grouped && this.isFilled() ? 600 : 500)
          .lineHeight(22)
          .fontColor(this.foreground())
          .textAlign(TextAlign.Center)
          .flexShrink(1)
      }
      .justifyContent(FlexAlign.Center)
      .constraintSize({ minHeight: this.targetHeight() - this.inset() * 2 })
      .padding({ left: this.isText() ? 8 : 14, right: this.isText() ? 8 : 14, top: 4, bottom: 4 })
      .backgroundColor(this.fill())
      .border({ width: this.variant === 'secondary' && this.available ? 1 : 0,
        color: this.darkMode ? AppTheme.darkDivider : AppTheme.divider, radius: this.radius() })
      .borderRadius(this.radius())
      .accessibilityLevel('no-hide-descendants')
      .hitTestBehavior(HitTestMode.None)
    // 透明原生按钮承载点击、键盘焦点和朗读名称，视觉层由上面的底板负责。
    Button(this.label, { type: ButtonType.Normal })
    .fontColor(Color.Transparent).fontFamily(AppTheme.fontMedium).fontSize(AppTheme.buttonFontSize)
    .padding(0).stateEffect(false)
    .borderRadius(this.radius())
    .backgroundColor(Color.Transparent)
    .enabled(this.available && !this.loading)
    .accessibilityDescription(this.description)
    .onTouch((event: TouchEvent) => { this.pressed = event.type === TouchType.Down; })
    .onClick(() => {
      // 外层可用状态和加载状态共同防止重复提交。
      if (this.available && !this.loading) { this.action(); }
    })
  }

  @BuilderParam private builder: () => void = this.ButtonChildren;

  /** 构建按钮，业务加载时阻止重复激活。 */
  build() {
    this.builder()
  }
}

/** 成组动作由单个原生按钮承载可见底板，交由父级Stretch共同增高。 */
@Component
export struct AppGroupedButton {
  @Prop label: ResourceStr = '';
  @Prop labelText: ResourceStr = '';
  @Prop variant: string = 'primary';
  @Prop available: boolean = true;
  @Prop loading: boolean = false;
  @Prop description: string = '';
  @Prop fullWidth: boolean = true;
  @Prop darkMode: boolean = false;
  @Prop fieldAligned: boolean = false;
  @Prop minWidth: number = 0;
  @State private pressed: boolean = false;
  action: () => void = () => {};

  private isText(): boolean { return this.variant === 'text' || this.variant === 'dangerText'; }
  private isFilled(): boolean { return this.variant === 'primary' || this.variant === 'destructive'; }
  private targetHeight(): number { return this.fieldAligned ? AppTheme.inputHeight : AppTheme.touchTarget; }
  private radius(): number { return this.fieldAligned ? AppTheme.inputRadius : AppTheme.buttonRadius; }
  private contentMinHeight(): number {
    return this.targetHeight() - 8 - (this.variant === 'secondary' && this.available ? 2 : 0);
  }
  private foreground(): ResourceColor {
    // 加载中的文字仍需清晰，只把真正不可用的操作使用禁用颜色。
    if (!this.available && !this.loading) {
      return this.darkMode ? AppTheme.darkDisabledText : AppTheme.disabledText;
    }
    if (this.isFilled()) {
      return this.darkMode ? AppTheme.darkPrimaryText : Color.White;
    }
    if (this.variant === 'danger' || this.variant === 'dangerText') {
      return this.darkMode ? AppTheme.darkDanger : AppTheme.danger;
    }
    return this.isText() ? (this.darkMode ? AppTheme.darkPrimary : AppTheme.primary) :
      (this.darkMode ? AppTheme.darkTextPrimary : AppTheme.textPrimary);
  }
  private fill(): ResourceColor {
    if (!this.available && !this.loading) {
      return this.darkMode ? AppTheme.darkDisabledSurface : AppTheme.disabledSurface;
    }
    if (this.variant === 'primary') {
      return this.pressed ? (this.darkMode ? AppTheme.darkPrimaryPressed : AppTheme.primaryPressed) :
        (this.darkMode ? AppTheme.darkPrimary : AppTheme.primary);
    }
    if (this.variant === 'destructive') {
      return this.darkMode ? AppTheme.darkDanger : AppTheme.danger;
    }
    if (this.pressed) {
      return this.darkMode ? AppTheme.darkAccentSoft : AppTheme.accentSoft;
    }
    if (this.variant === 'danger') {
      return this.darkMode ? AppTheme.darkDangerSoft : AppTheme.dangerSoft;
    }
    return this.isText() ? Color.Transparent : (this.darkMode ? AppTheme.darkSurface : AppTheme.surface);
  }


  private accessibilityLabel(): string {
    const value = this.labelText !== '' ? this.labelText : this.label;
    if (typeof value === 'string') return value;
    try { return this.getUIContext().getHostContext()?.resourceManager.getStringSync(value.id) ?? this.description; }
    catch (_) { return this.description; }
  }

  /** 原生按钮消费等高容器的精确尺寸，不依赖平台的自动交叉轴拉伸。 */
  build() {
    Button({ type: ButtonType.Normal }) {
      Row({ space: 6 }) {
        if (this.loading) {
          LoadingProgress().width(18).height(18).color(this.foreground())
        }
        Text(this.labelText !== '' ? this.labelText : this.label)
          .fontFamily(AppTheme.fontMedium).fontSize(AppTheme.buttonFontSize).fontWeight(500)
          .lineHeight(22).fontColor(this.foreground()).textAlign(TextAlign.Center)
          .wordBreak(WordBreak.BREAK_WORD).flexShrink(1)
      }
      .justifyContent(FlexAlign.Center)
      .constraintSize({ minHeight: this.contentMinHeight() })
      .accessibilityLevel('no-hide-descendants')
    }
    .width(this.fullWidth ? '100%' : undefined)
    .constraintSize({ minWidth: this.minWidth, minHeight: this.targetHeight() })
    .padding({ left: 12, right: 12, top: 4, bottom: 4 })
    .backgroundColor(this.fill()).borderRadius(this.radius())
    .border({ width: this.variant === 'secondary' && this.available ? 1 : 0,
      color: this.darkMode ? AppTheme.darkDivider : AppTheme.divider, radius: this.radius() })
    .stateEffect(false).enabled(this.available && !this.loading)
    .accessibilityText(this.accessibilityLabel())
    .accessibilityDescription(this.description)
    .onTouch((event: TouchEvent) => { this.pressed = event.type === TouchType.Down; })
    .onClick(() => {
      // 原生禁用状态之外仍保留回调守卫，阻止忙碌时重复提交。
      if (this.available && !this.loading) { this.action(); }
    })
  }
}

/** 直接测量任意数量的动作节点，等宽自然测量后再强制共同高度。 */
@Component
export struct AppEqualActionRow {
  @Prop gap: number = 8;
  @Prop widthLimit: number = 0;
  @Prop rowMinHeight: number = AppTheme.touchTarget;
  @BuilderParam content: () => void;

  private finite(value: Length | undefined, fallback: number): number {
    const size = Number(value);
    return Number.isFinite(size) ? Math.max(0, size) : fallback;
  }

  private rowWidth(available: Length | undefined, minimum: Length | undefined, fallback: number): number {
    const safeFallback = this.finite(minimum, this.finite(fallback, 0));
    const limit = this.finite(this.widthLimit, 0);
    const width = this.finite(available, limit > 0 ? limit : safeFallback);
    return limit > 0 ? Math.min(width, limit) : width;
  }

  private spacing(width: number, count: number): number {
    return count > 1 ? Math.min(this.finite(this.gap, 0), width / (count - 1)) : 0;
  }

  /** 只消费本轮约束与子节点结果，测量期间不写入组件状态。 */
  onMeasureSize(selfLayoutInfo: GeometryInfo, children: Array<Measurable>, constraint: ConstraintSizeOptions): SizeResult {
    const width = this.rowWidth(constraint.maxWidth, constraint.minWidth, selfLayoutInfo.width);
    const maxHeight = this.finite(constraint.maxHeight, Number.POSITIVE_INFINITY);
    const minimum = Math.min(maxHeight, Math.max(this.finite(constraint.minHeight, 0),
      this.finite(this.rowMinHeight, AppTheme.touchTarget)));
    if (children.length === 0) return { width: width, height: Math.min(maxHeight, this.finite(constraint.minHeight, 0)) };
    const gap = this.spacing(width, children.length);
    const slotWidth = Math.max(0, (width - gap * (children.length - 1)) / children.length);
    let height = minimum;
    // 第一遍让每个动作在相同实际宽度下自然换行，不借用整屏最大高度填满自身。
    children.forEach((child: Measurable) => {
      const natural = child.measure({ minWidth: slotWidth, maxWidth: slotWidth,
        minHeight: minimum, maxHeight: maxHeight });
      height = Math.max(height, this.finite(natural.height, minimum));
    });
    height = Math.min(maxHeight, Math.ceil(height));
    // 第二遍提供精确共同高度，短标签与长标签都消费相同的可见底板尺寸。
    children.forEach((child: Measurable) => {
      child.measure({ minWidth: slotWidth, maxWidth: slotWidth, minHeight: height, maxHeight: height });
    });
    return { width: width, height: height };
  }

  /** 使用当前实际宽度横向放置全部动作，不依赖固定子节点数量。 */
  onPlaceChildren(selfLayoutInfo: GeometryInfo, children: Array<Layoutable>, constraint: ConstraintSizeOptions): void {
    if (children.length === 0) return;
    const width = this.finite(selfLayoutInfo.width, 0);
    const gap = this.spacing(width, children.length);
    const slotWidth = Math.max(0, (width - gap * (children.length - 1)) / children.length);
    children.forEach((child: Layoutable, index: number) => {
      child.layout({ x: index * (slotWidth + gap), y: 0 });
    });
  }

  /** 稳定语法节点关闭构建器栈，避免末尾自定义动作的布局节点丢失。 */
  build() {
    ForEach([0], () => {
      this.content()
    }, (token: number) => token.toString())
  }
}

/** 文字操作沿用标准点击区域，危险动作仅改变语义色。 */
@Component
export struct AppTextAction {
  @Prop label: ResourceStr = '';
  @Prop available: boolean = true;
  @Prop loading: boolean = false;
  @Prop description: string = '';
  @Prop danger: boolean = false;
  @Prop darkMode: boolean = false;
  action: () => void = () => {};

  /** 构建统一文字操作。 */
  build() {
    AppButton({ label: this.label, variant: this.danger ? 'dangerText' : 'text', available: this.available,
      loading: this.loading, description: this.description, darkMode: this.darkMode, action: this.action })
  }
}

/** 弹层标题使用对称的固定操作区，标题只消费剩余宽度。 */
@Component
export struct AppSheetHeader {
  @Prop title: ResourceStr = '';
  @Prop leftLabel: ResourceStr = '\u5173\u95ed';
  @Prop rightLabel: ResourceStr = '\u6e05\u9664';
  @Prop rightAvailable: boolean = true;
  @Prop darkMode: boolean = false;
  leftAction: () => void = () => {};
  rightAction: () => void = () => {};

  /** 构建可随文字放大增高的弹层标题，左右操作始终保留相同宽度。 */
  build() {
    Row({ space: 8 }) {
      Row() {
        if (this.leftLabel !== '') {
          AppTextAction({ label: this.leftLabel, darkMode: this.darkMode, action: this.leftAction })
            .width('100%')
        }
      }
      .width(64).flexShrink(0).justifyContent(FlexAlign.Center)
      Text(this.title).layoutWeight(1)
        .fontSize(18).fontFamily(AppTheme.fontSemibold).fontWeight(600)
        .fontColor(this.darkMode ? AppTheme.darkTextPrimary : AppTheme.textPrimary)
        .textAlign(TextAlign.Center).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
      Row() {
        if (this.rightLabel !== '') {
          AppTextAction({ label: this.rightLabel, available: this.rightAvailable,
            darkMode: this.darkMode, action: this.rightAction })
            .width('100%')
        }
      }
      .width(64).flexShrink(0).justifyContent(FlexAlign.Center)
    }
    .width('100%').padding({ left: 16, right: 16 })
    .constraintSize({ minHeight: AppTheme.touchTarget }).alignItems(VerticalAlign.Center)
  }
}

/** 提供固定命中区域和名称的图标按钮。 */
@Component
export struct AppIconButton {
  @Prop icon: Resource = $r('sys.symbol.xmark');
  @Prop symbol: boolean = true;
  @Prop description: string = '';
  @Prop available: boolean = true;
  @Prop selected: boolean = false;
  @Prop darkMode: boolean = false;
  @Prop fieldAligned: boolean = false;
  @State private pressed: boolean = false;
  action: () => void = () => {};

  private targetSize(): number { return this.fieldAligned ? AppTheme.inputHeight : AppTheme.touchTarget; }
  private plateSize(): number { return this.fieldAligned ? AppTheme.inputHeight : 32; }
  private plateRadius(): number { return this.fieldAligned ? AppTheme.inputRadius : 8; }
  private targetPadding(): number { return this.fieldAligned ? 0 : 6; }
  private fill(): ResourceColor {
    if (this.selected || this.pressed) return this.darkMode ? AppTheme.darkAccentSoft : AppTheme.accentSoft;
    return this.fieldAligned ? (this.darkMode ? AppTheme.darkSurfaceTint : AppTheme.surfaceTint) : Color.Transparent;
  }

  /** 构建图标与选中反馈。 */
  build() {
    Button({ type: ButtonType.Normal }) {
      Row() {
        if (this.symbol) {
          SymbolGlyph(this.icon).fontSize(20)
            .fontColor([this.selected ? (this.darkMode ? AppTheme.darkPrimary : AppTheme.primary) :
              (this.darkMode ? AppTheme.darkTextSecondary : AppTheme.textSecondary)])
        } else {
          Image(this.icon).width(20).height(20).objectFit(ImageFit.Contain)
        }
      }
      .width(this.plateSize()).height(this.plateSize()).justifyContent(FlexAlign.Center)
      .backgroundColor(this.fill()).borderRadius(this.plateRadius())
    }
    .width(this.targetSize()).height(this.targetSize()).padding(this.targetPadding())
    .backgroundColor(Color.Transparent).borderRadius(this.fieldAligned ? AppTheme.inputRadius : AppTheme.buttonRadius)
    .enabled(this.available).accessibilityText(this.description)
    .onTouch((event: TouchEvent) => { this.pressed = event.type === TouchType.Down; })
    .onClick(this.action)
  }
}

/** 主次或确认取消动作共享宽度、字重和底板高度。 */
@Component
export struct AppActionGroup {
  @Prop primaryLabel: ResourceStr = '';
  @Prop secondaryLabel: ResourceStr = '';
  @Prop primaryDescription: string = '';
  @Prop secondaryDescription: string = '';
  @Prop secondaryAvailable: boolean = true;
  @Prop available: boolean = true;
  @Prop loading: boolean = false;
  @Prop dangerous: boolean = false;
  @Prop darkMode: boolean = false;
  @Prop fullWidth: boolean = true;
  primaryAction: () => void = () => {};
  secondaryAction: () => void = () => {};

  /** 明确测量同宽动作并强制共同高度，长文案与大字不依赖自动Stretch。 */
  build() {
    AppEqualActionRow({ widthLimit: this.fullWidth ? 0 : AppTheme.groupMinWidth * 2 + 8 }) {
      AppGroupedButton({ label: this.secondaryLabel, variant: 'secondary', fullWidth: true,
        available: this.secondaryAvailable, description: this.secondaryDescription,
        darkMode: this.darkMode, action: this.secondaryAction })
      AppGroupedButton({ label: this.primaryLabel, variant: this.dangerous ? 'destructive' : 'primary',
        available: this.available, loading: this.loading, fullWidth: true,
        description: this.primaryDescription,
        darkMode: this.darkMode, action: this.primaryAction })
    }
  }
}

/** 分段导航选项，胶囊底板不复用提交按钮语义。 */
@Component
export struct AppSegmentItem {
  @Prop label: ResourceStr = '';
  @Prop selected: boolean = false;
  @Prop available: boolean = true;
  @Prop darkMode: boolean = false;
  action: () => void = () => {};

  /** 构建44命中高的分段选项。 */
  build() {
    Button({ type: ButtonType.Normal }) {
      Text(this.label).fontSize(14).fontFamily(AppTheme.fontMedium).fontWeight(500).lineHeight(22)
        .fontColor(this.selected ? (this.darkMode ? AppTheme.darkPrimary : AppTheme.primary) :
          (this.darkMode ? AppTheme.darkTextSecondary : AppTheme.textSecondary))
        .padding({ left: 12, right: 12, top: 5, bottom: 5 })
        .constraintSize({ minHeight: 32 })
        .backgroundColor(this.selected ? (this.darkMode ? AppTheme.darkSurface : AppTheme.surface) : Color.Transparent)
        .borderRadius(8)
    }
    .constraintSize({ minHeight: AppTheme.touchTarget }).padding({ left: 0, right: 0, top: 6, bottom: 6 })
    .backgroundColor(Color.Transparent).borderRadius(AppTheme.buttonRadius).enabled(this.available)
    .accessibilityDescription(this.selected ? '已选择' : '未选择')
    .onClick(this.action)
  }
}

/** 兼容既有主按钮调用，统一使用纯色紧凑按钮。 */
@Component
export struct AppPrimaryPillButton {
  @Prop label: ResourceStr = '';
  @Prop available: boolean = true;
  @Prop loading: boolean = false;
  @Prop description: string = '';
  @Prop fullWidth: boolean = false;
  @Prop grouped: boolean = false;
  @Prop fieldAligned: boolean = false;
  action: () => void = () => {};

  /** 构建兼容主按钮。 */
  build() {
    if (this.grouped) {
      AppGroupedButton({ label: this.label, available: this.available, loading: this.loading,
        description: this.description, fullWidth: this.fullWidth,
        fieldAligned: this.fieldAligned, action: this.action })
    } else {
      AppButton({ label: this.label, available: this.available, loading: this.loading,
        description: this.description, fullWidth: this.fullWidth,
        fieldAligned: this.fieldAligned, action: this.action })
    }
  }
}

/** 兼容既有次按钮调用，保持中性描边。 */
@Component
export struct AppSecondaryPillButton {
  @Prop label: ResourceStr = '';
  @Prop available: boolean = true;
  @Prop loading: boolean = false;
  @Prop description: string = '';
  @Prop fullWidth: boolean = false;
  @Prop grouped: boolean = false;
  @Prop fieldAligned: boolean = false;
  action: () => void = () => {};

  /** 构建兼容次按钮。 */
  build() {
    if (this.grouped) {
      AppGroupedButton({ label: this.label, variant: 'secondary', available: this.available, loading: this.loading,
        description: this.description, fullWidth: this.fullWidth,
        fieldAligned: this.fieldAligned, action: this.action })
    } else {
      AppButton({ label: this.label, variant: 'secondary', available: this.available, loading: this.loading,
        description: this.description, fullWidth: this.fullWidth,
        fieldAligned: this.fieldAligned, action: this.action })
    }
  }
}

/** 兼容既有危险动作调用，保留弱底语义。 */
@Component
export struct AppDangerPillButton {
  @Prop label: ResourceStr = '';
  @Prop available: boolean = true;
  @Prop description: string = '';
  @Prop fullWidth: boolean = false;
  @Prop grouped: boolean = false;
  @Prop fieldAligned: boolean = false;
  action: () => void = () => {};

  /** 构建兼容危险按钮。 */
  build() {
    if (this.grouped) {
      AppGroupedButton({ label: this.label, variant: 'danger', available: this.available,
        description: this.description, fullWidth: this.fullWidth,
        fieldAligned: this.fieldAligned, action: this.action })
    } else {
      AppButton({ label: this.label, variant: 'danger', available: this.available,
        description: this.description, fullWidth: this.fullWidth,
        fieldAligned: this.fieldAligned, action: this.action })
    }
  }
}

/** 可复用筛选标签，可见高30与44命中高分离。 */
@Component
export struct AppFilterChip {
  @Prop label: ResourceStr = '';
  @Prop selected: boolean = false;
  @Prop available: boolean = true;
  @Prop darkMode: boolean = false;
  action: () => void = () => {};

  /** 构建紧凑筛选标签。 */
  build() {
    Button({ type: ButtonType.Normal }) {
      Text(this.label).fontSize(14).fontFamily(AppTheme.fontMedium).fontWeight(500).lineHeight(20)
        .fontColor(this.selected ? (this.darkMode ? AppTheme.darkPrimary : AppTheme.primary) :
          (this.darkMode ? AppTheme.darkTextSecondary : AppTheme.textSecondary))
        .padding({ left: 10, right: 10, top: 4, bottom: 4 })
        .constraintSize({ minHeight: 30 })
        .backgroundColor(this.selected ? (this.darkMode ? AppTheme.darkAccentSoft : AppTheme.accentSoft) :
          (this.darkMode ? AppTheme.darkSurface : AppTheme.surface))
        .border({ width: 1, color: this.selected ? (this.darkMode ? AppTheme.darkPrimary : AppTheme.primary) :
          (this.darkMode ? AppTheme.darkDivider : AppTheme.divider), radius: AppTheme.pillRadius })
        .borderRadius(AppTheme.pillRadius)
    }
    .constraintSize({ minHeight: AppTheme.touchTarget }).padding({ left: 0, right: 0, top: 7, bottom: 7 })
    .backgroundColor(Color.Transparent).borderRadius(AppTheme.pillRadius).enabled(this.available)
    .accessibilityDescription(this.selected ? '已选择' : '未选择')
    .onClick(this.action)
  }
}

/** 带标签、错误与只读状态的标准单行输入。 */
@Component
export struct AppGlassTextInput {
  @Prop value: string = '';
  @Prop label: ResourceStr = '';
  @Prop placeholder: ResourceStr = '';
  @Prop description: string = '';
  @Prop error: string = '';
  @Prop darkMode: boolean = false;
  @Prop readOnly: boolean = false;
  @Prop inputType: InputType = InputType.Normal;
  @Prop limit: number = 1000;
  @Prop available: boolean = true;
  valueChanged: (value: string) => void = () => {};
  submitted: () => void = () => {};

  /** 构建标签、字段与就近校验提示。 */
  build() {
    Column({ space: 6 }) {
      if (this.label !== '') {
        Text(this.label).width('100%').fontSize(14).fontFamily(AppTheme.fontMedium).fontWeight(500)
          .fontColor(this.darkMode ? AppTheme.darkTextPrimary : AppTheme.textPrimary)
      }
      if (this.readOnly) {
        Text(this.value.length > 0 ? this.value : this.placeholder).width('100%')
          .constraintSize({ minHeight: AppTheme.inputHeight }).padding({ left: 14, right: 14, top: 10, bottom: 10 })
          .fontSize(AppTheme.fieldFontSize).lineHeight(24).fontFamily(AppTheme.fontFamily).fontWeight(400)
          .fontColor(this.darkMode ? AppTheme.darkTextPrimary : AppTheme.textPrimary)
          .copyOption(this.available ? CopyOptions.LocalDevice : CopyOptions.None)
          .backgroundColor(this.darkMode ? AppTheme.darkSurfaceTint : AppTheme.surfaceTint)
          .border({ width: 1, color: this.darkMode ? AppTheme.darkDivider : AppTheme.divider, radius: AppTheme.inputRadius })
          .borderRadius(AppTheme.inputRadius).accessibilityDescription(this.description)
      } else {
        TextInput({ text: this.value, placeholder: this.placeholder })
          .width('100%').constraintSize({ minHeight: AppTheme.inputHeight })
          .padding({ left: 14, right: 14 }).maxLength(this.limit).type(this.inputType)
          .fontSize(AppTheme.fieldFontSize).fontFamily(AppTheme.fontFamily).fontWeight(400)
          .fontColor(this.darkMode ? AppTheme.darkTextPrimary : AppTheme.textPrimary)
          .placeholderFont({ size: AppTheme.fieldFontSize, weight: 400, family: AppTheme.fontFamily })
          .placeholderColor(this.darkMode ? AppTheme.darkTextSecondary : AppTheme.textTertiary)
          .backgroundColor(!this.available || this.readOnly ?
            (this.darkMode ? AppTheme.darkSurfaceTint : AppTheme.surfaceTint) :
            (this.darkMode ? AppTheme.darkSurface : AppTheme.surface))
          .border({ width: 1, color: this.error.length > 0 ? (this.darkMode ? AppTheme.darkDanger : AppTheme.danger) :
            (this.darkMode ? AppTheme.darkDivider : AppTheme.divider), radius: AppTheme.inputRadius })
          .borderRadius(AppTheme.inputRadius).enabled(this.available)
          .accessibilityDescription(this.error.length > 0 ? this.error : this.description)
          .onChange(this.valueChanged).onSubmit(this.submitted)
      }
      if (this.error.length > 0) {
        Text(this.error).width('100%').fontSize(12).fontFamily(AppTheme.fontFamily)
          .fontColor(this.darkMode ? AppTheme.darkDanger : AppTheme.danger)
      }
    }
    .width('100%').alignItems(HorizontalAlign.Start)
  }
}

/** 带错误和只读状态的多行文本输入。 */
@Component
export struct AppGlassTextArea {
  @Prop value: string = '';
  @Prop label: ResourceStr = '';
  @Prop darkMode: boolean = false;
  @Prop placeholder: ResourceStr = '';
  @Prop error: string = '';
  @Prop readOnly: boolean = false;
  @Prop limit: number = 1000;
  @Prop areaHeight: number = 140;
  @Prop available: boolean = true;
  valueChanged: (value: string) => void = () => {};

  /** 构建多行输入与错误提示。 */
  build() {
    Column({ space: 6 }) {
      if (this.label !== '') {
        Text(this.label).width('100%').fontSize(14).fontFamily(AppTheme.fontMedium).fontWeight(500)
          .fontColor(this.darkMode ? AppTheme.darkTextPrimary : AppTheme.textPrimary)
      }
      if (this.readOnly) {
        AppGlassTextOutput({ value: this.value, placeholder: this.placeholder, areaHeight: this.areaHeight,
          darkMode: this.darkMode, fontSize: AppTheme.fieldFontSize })
      } else {
        TextArea({ text: this.value, placeholder: this.placeholder })
          .width('100%').height(this.areaHeight).padding(14).maxLength(this.limit)
          .fontSize(AppTheme.fieldFontSize).fontFamily(AppTheme.fontFamily).fontWeight(400)
          .fontColor(this.darkMode ? AppTheme.darkTextPrimary : AppTheme.textPrimary)
          .placeholderFont({ size: AppTheme.fieldFontSize, weight: 400, family: AppTheme.fontFamily })
          .placeholderColor(this.darkMode ? AppTheme.darkTextSecondary : AppTheme.textTertiary)
          .backgroundColor(!this.available || this.readOnly ?
            (this.darkMode ? AppTheme.darkSurfaceTint : AppTheme.surfaceTint) :
            (this.darkMode ? AppTheme.darkSurface : AppTheme.surface))
          .border({ width: 1, color: this.error.length > 0 ? (this.darkMode ? AppTheme.darkDanger : AppTheme.danger) :
            (this.darkMode ? AppTheme.darkDivider : AppTheme.divider), radius: AppTheme.inputRadius })
          .borderRadius(AppTheme.inputRadius).enabled(this.available)
          .accessibilityDescription(this.error)
          .onChange(this.valueChanged)
      }
      if (this.error.length > 0) {
        Text(this.error).width('100%').fontSize(12).fontFamily(AppTheme.fontFamily)
          .fontColor(this.darkMode ? AppTheme.darkDanger : AppTheme.danger)
      }
    }
    .width('100%').alignItems(HorizontalAlign.Start)
  }
}

/** 可选择复制的只读结果，保持与禁用输入不同。 */
@Component
export struct AppGlassTextOutput {
  @Prop fontSize: number = 15;
  @Prop value: string = '';
  @Prop placeholder: ResourceStr = '';
  @Prop areaHeight: number = 140;
  @Prop darkMode: boolean = false;

  /** 构建可复制结果内容。 */
  build() {
    Scroll() {
      Text(this.value.length > 0 ? this.value : this.placeholder).width('100%')
        .fontSize(this.fontSize).fontFamily(AppTheme.fontFamily).fontWeight(400).lineHeight(24)
        .fontColor(this.value.length > 0 ? (this.darkMode ? AppTheme.darkTextPrimary : AppTheme.textPrimary) :
          (this.darkMode ? AppTheme.darkTextSecondary : AppTheme.textTertiary))
        .copyOption(CopyOptions.LocalDevice)
    }
    .width('100%').height(this.areaHeight).padding(16)
    .backgroundColor(this.darkMode ? AppTheme.darkSurfaceTint : AppTheme.surfaceTint)
    .border({ width: 1, color: this.darkMode ? AppTheme.darkDivider : AppTheme.divider,
      radius: AppTheme.inputRadius }).borderRadius(AppTheme.inputRadius)
  }
}

/** 输入、键盘提交和清除保持同一语义的搜索字段。 */
@Component
export struct AppSearchField {
  @Prop value: string = '';
  @Prop darkMode: boolean = false;
  @Prop placeholder: ResourceStr = '';
  @Prop limit: number = 100;
  @Prop available: boolean = true;
  valueChanged: (value: string) => void = () => {};
  submitted: () => void = () => {};
  cleared: () => void = () => {};

  /** 构建搜索输入与独立清除按钮。 */
  build() {
    Row({ space: 8 }) {
      SymbolGlyph($r('sys.symbol.magnifyingglass')).fontSize(18)
        .fontColor([this.darkMode ? AppTheme.darkTextSecondary : AppTheme.textSecondary])
        .accessibilityLevel('no')
        TextInput({ text: this.value, placeholder: this.placeholder }).layoutWeight(1)
          .constraintSize({ minHeight: AppTheme.inputHeight }).padding(0).maxLength(this.limit)
          .fontSize(AppTheme.fieldFontSize).fontFamily(AppTheme.fontFamily).fontWeight(400)
          .fontColor(this.darkMode ? AppTheme.darkTextPrimary : AppTheme.textPrimary)
          .placeholderFont({ size: AppTheme.fieldFontSize, weight: 400, family: AppTheme.fontFamily })
          .placeholderColor(this.darkMode ? AppTheme.darkTextSecondary : AppTheme.textTertiary)
          .backgroundColor(Color.Transparent).enabled(this.available)
          .enterKeyType(EnterKeyType.Search).onChange(this.valueChanged).onSubmit(this.submitted)
        if (this.value.length > 0) {
          AppIconButton({ icon: $r('sys.symbol.xmark'), description: '清除搜索内容', darkMode: this.darkMode,
            available: this.available, action: () => { this.valueChanged(''); this.cleared(); } })
      }
    }
    .width('100%').constraintSize({ minHeight: AppTheme.inputHeight })
    .padding({ left: 14, right: 2 })
    .backgroundColor(this.darkMode ? AppTheme.darkSurface : AppTheme.surface)
    .border({ width: 1, color: this.darkMode ? AppTheme.darkDivider : AppTheme.divider,
      radius: AppTheme.inputRadius }).borderRadius(AppTheme.inputRadius)
  }
}

/** 原生下拉选择保留平台语义并统一文字与选项高度。 */
@Component
export struct AppSelect {
  @Prop options: SelectOption[] = [];
  @Prop selected: number = 0;
  @Prop value: ResourceStr = '';
  @Prop available: boolean = true;
  @Prop description: string = '';
  @Prop darkMode: boolean = false;
  onSelected: (index: number) => void = () => {};

  /** 构建统一来源、模型和参数选择字段。 */
  build() {
    Select(this.options).selected(this.selected).value(this.value)
      .width('100%').constraintSize({ minHeight: AppTheme.inputHeight })
      .padding({ left: 14, right: 14 })
      .font({ size: 16, weight: 400, family: AppTheme.fontFamily })
      .fontColor(this.darkMode ? AppTheme.darkTextPrimary : AppTheme.textPrimary)
      .optionFont({ size: 16, weight: 400, family: AppTheme.fontFamily })
      .selectedOptionFont({ size: 16, weight: 500, family: AppTheme.fontMedium })
      .optionHeight(48)
      .optionFontColor(this.darkMode ? AppTheme.darkTextPrimary : AppTheme.textPrimary)
      .optionBgColor(this.darkMode ? AppTheme.darkSurface : AppTheme.surface)
      .selectedOptionFontColor(this.darkMode ? AppTheme.darkPrimary : AppTheme.primary)
      .selectedOptionBgColor(this.darkMode ? AppTheme.darkAccentSoft : AppTheme.accentSoft)
      .backgroundColor(this.darkMode ? AppTheme.darkSurface : AppTheme.surface)
      .border({ width: 1, color: this.darkMode ? AppTheme.darkDivider : AppTheme.divider,
        radius: AppTheme.inputRadius }).borderRadius(AppTheme.inputRadius)
      .enabled(this.available && this.options.length > 0)
      .accessibilityDescription(this.description)
      .onSelect((index: number) => { this.onSelected(index); })
  }
}

/** 统一设置与入口列表行，静态说明不单独伪装为按钮。 */
@Component
export struct AppSettingsRow {
  @Prop title: ResourceStr = '';
  @Prop subtitle: ResourceStr = '';
  @Prop darkMode: boolean = false;
  action: () => void = () => {};

  /** 构建有明确进入语义的整行入口。 */
  build() {
    Button({ type: ButtonType.Normal }) {
      Row({ space: 12 }) {
        Column({ space: 4 }) {
          Text(this.title).width('100%').fontSize(16).fontFamily(AppTheme.fontMedium).fontWeight(500)
            .lineHeight(24).fontColor(this.darkMode ? AppTheme.darkTextPrimary : AppTheme.textPrimary)
          if (this.subtitle !== '') {
            Text(this.subtitle).width('100%').fontSize(12).fontFamily(AppTheme.fontFamily).lineHeight(18)
              .fontColor(this.darkMode ? AppTheme.darkTextSecondary : AppTheme.textSecondary)
          }
        }
        .layoutWeight(1).alignItems(HorizontalAlign.Start)
        SymbolGlyph($r('sys.symbol.chevron_right')).fontSize(18)
          .fontColor([this.darkMode ? AppTheme.darkTextSecondary : AppTheme.textSecondary])
      }
      .width('100%')
    }
    .width('100%').constraintSize({ minHeight: 64 })
    .padding({ left: 0, right: 0, top: 12, bottom: 12 }).backgroundColor(Color.Transparent).borderRadius(0)
    .border({ width: { bottom: 1 }, color: this.darkMode ? AppTheme.darkDivider : AppTheme.divider, radius: 0 })
    .onClick(this.action)
  }
}
