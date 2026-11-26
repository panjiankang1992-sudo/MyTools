# MyTools 控件与业务组件覆盖盘点 · 1.2

状态：设计审核资料。主范围为鸿蒙客户端，Web只补充场景依据，不自动扩大客户端实施范围。

## 方法与计数

扫描 **188 个ArkTS文件**，其中 **14 个含本次列举的原生控件构造**。检查42种完整控件名，记录 **614 处构造调用**。计数包括基础组件内部实现，不等于运行时组件数、场景数或缺陷数。

- 扫描全部ArkTS源文件；先屏蔽注释、正则字面量和单/双/模板字符串，匹配完整控件名直接调用；排除成员调用、new和函数名子串。
- 弹层API成员调用与指定公共包装单列。业务Builder复用次数不计入原生构造总数。
- Web只匹配Vue模板中的<NComponent起始标签，排除HTML注释；不计import或方法名。
- 静态计数包括基础组件内部实现，不等于运行时数量、独立场景数或缺陷数。源码存在不证明当前路由可达或真机验收通过。
- 手工菜单、树、空态、状态等可由基础元素拼装，某原生构造为零不代表没有该场景。
- 构造计数只覆盖本清单列举的42种交互/展示控件，不包含Text/Span等文本和Row/Column/Stack/Scroll等布局容器。

Web补充：webapp 102 个Vue文件；toolbox-web 19 个Vue文件。完整匹配和准确行号见 `control-inventory.json`。

| 原生构造 | 静态调用数 |
| --- | --- |
| `Button` | 308 |
| `TextInput` | 20 |
| `TextArea` | 8 |
| `Select` | 5 |
| `TextPicker` | 2 |
| `Checkbox` | 3 |
| `Toggle` | 7 |
| `Slider` | 10 |
| `Progress` | 8 |
| `LoadingProgress` | 14 |
| `Refresh` | 1 |
| `Tabs` | 1 |
| `TabContent` | 1 |
| `List` | 8 |
| `ListItem` | 13 |
| `Image` | 35 |
| `SymbolGlyph` | 154 |
| `Video` | 3 |
| `Web` | 2 |
| `Divider` | 11 |

未发现的原生构造：`Search`、`DatePicker`、`TimePicker`、`CalendarPicker`、`Calendar`、`CheckboxGroup`、`Radio`、`Rating`、`Counter`、`Swiper`、`Stepper`、`StepperItem`、`Menu`、`MenuItem`、`MenuItemGroup`、`Grid`、`GridItem`、`RichText`、`Hyperlink`、`Badge`、`TextClock`、`TextTimer`。原生构造为零不代表组合场景不存在。

弹层API：`bindSheet` 1；`bindMenu` 0；`bindPopup` 0；`bindContextMenu` 0；`showAlertDialog` 5；`showToast` 0；`showActionMenu` 0；`openCustomDialog` 0。手工面板另按Builder登记。

PDFKit `PdfView`另作为文件预览证据登记，不追加进上述固定42种控件的614处计数。

## 72 ID覆盖与来源索引

本表逐一覆盖当前完整组件库的72个ID。每项主来源明确归为「原生已有 / Web已有 / 组合已有 / 规范补齐」。同一项可同时有不同平台证据，具体边界在说明列；任何“已有”都不表示已完成本轮原生迁移或真机验证。源码证据详见下方场景映射及JSON索引。

| ID | 组件 | 主来源 | 证据与边界 |
| --- | --- | --- | --- |
| `a01` | 动作按钮 / Button | 组合已有 | 公共按钮包装与原生Button。 |
| `a02` | 文字操作 / TextAction | 组合已有 | RSS管理/刷新等文字Button。 |
| `a03` | 图标按钮 / IconButton | 组合已有 | 搜索/返回/更多等图标Button。 |
| `a04` | 成组操作 / ButtonGroup | 组合已有 | 工具动作和确认主次按钮组合。 |
| `a05` | 链接 / Link | Web已有 | Web真实外链；客户端资源许可/隐私说明为组合入口。 |
| `a06` | 悬浮操作 / FloatingAction | 规范补齐 | 未发现专用FAB，按规范补齐。 |
| `i01` | 单行输入 / TextField | 原生已有 | 原生TextInput和公共包装。 |
| `i02` | 多行输入 / TextArea | 原生已有 | 原生TextArea和公共包装。 |
| `i03` | 密码与敏感字段 / PasswordField | 原生已有 | 原生密码输入，显示密码保留既有验证。 |
| `i04` | 数值输入 / NumberField | 原生已有 | PDF按页数等数字TextInput。 |
| `i05` | 搜索栏 / SearchBar | 组合已有 | 公共AppSearchField组合。 |
| `i06` | 搜索触发按钮 / SearchButton | 组合已有 | 书源独立搜索/停止按钮。 |
| `i07` | 搜索建议与历史 / SearchSuggestions | 规范补齐 | 未发现统一历史/联想建议，媒体检索是选择器内筛选，不能混同。 |
| `i08` | 表单分组与校验 / FormGroup | 组合已有 | 现有标签/输入/帮助/错误/提交组合。 |
| `i09` | 验证码与重发 / VerificationCode | Web已有 | Web验证码/重发UI已有，request为占位；客户端规范补齐。 |
| `s01` | 下拉单选 / Select | 原生已有 | 原生Select和TextPicker。 |
| `s02` | 可搜索选择 / SearchSelect | 组合已有 | 媒体目录/标签可搜索面板。 |
| `s03` | 多选选择器 / MultiSelect | 组合已有 | ZIP目录条目勾选组合。 |
| `s04` | 级联选择 / CascadeSelect | Web已有 | Web教材科目/年级/教材的依赖选择。 |
| `s05` | 单选组 / RadioGroup | Web已有 | Web NRadio；客户端按钮式互斥选择另统一。 |
| `s06` | 复选与全选 / CheckboxGroup | 原生已有 | 原生Checkbox。 |
| `s07` | 开关 / Switch | 原生已有 | 原生Toggle.Switch。 |
| `s08` | 筛选标签 / FilterChip | 组合已有 | 公共筛选标签与媒体筛选。 |
| `d01` | 日期输入 / DateField | Web已有 | Web生日NDatePicker；客户端未发现。 |
| `d02` | 日历选择 / Calendar | Web已有 | Web NDatePicker内含日历选择形态；客户端独立日历规范补齐。 |
| `d03` | 日期范围 / DateRange | 规范补齐 | 未发现日期范围，规范补齐。 |
| `d04` | 时间选择 / TimePicker | 规范补齐 | 未发现时刻选择，RSS间隔不等于时间选择。 |
| `d05` | 日期时间与时区 / DateTime | 规范补齐 | 未发现日期时间组合，规范补齐。 |
| `d06` | 快捷日期范围 / QuickDate | 规范补齐 | 未发现统一快捷日期范围，规范补齐。 |
| `n01` | 单值滑杆 / Slider | 原生已有 | 原生Slider：阅读设置、PDF参数和播放进度。 |
| `n02` | 双端范围滑杆 / RangeSlider | 规范补齐 | 未发现双端范围，规范补齐。 |
| `n03` | 数值步进器 / Stepper | 组合已有 | 阅读字号A-/A+组合。 |
| `n04` | 评分 / Rating | 规范补齐 | 未发现评分，规范补齐。 |
| `n05` | 颜色选择 / ColorPicker | Web已有 | Web NColorPicker；客户端只有预设阅读主题。 |
| `v01` | 页头 / PageHeader | 组合已有 | 公共工具页头和各页头Builder。 |
| `v02` | 标签页 / Tabs | 组合已有 | RSS/创作页标签按钮组合。 |
| `v03` | 分段切换 / Segment | 组合已有 | 公共AppSegmentItem与互斥模式。 |
| `v04` | 底部主导航 / BottomNav | 原生已有 | 原生Tabs/TabContent承载五个入口。 |
| `v05` | 路径导航 / Breadcrumb | 组合已有 | 当前路径文本+返回上级组合，非统一Breadcrumb。 |
| `v06` | 分页 / Pagination | Web已有 | Web NPagination页码；客户端游标加载另见f09。 |
| `v07` | 步骤与流程进度 / StepIndicator | 组合已有 | DSH阶段显示组合；通用可跳转步骤条需补齐。 |
| `v08` | 操作菜单 / Menu | 组合已有 | 书源筛选菜单和媒体动作面板组合。 |
| `c01` | 通用列表行 / ListRow | 组合已有 | 文件、RSS与结果等业务列表行。 |
| `c02` | 设置行 / SettingsRow | 组合已有 | 公共AppSettingsRow。 |
| `c03` | 内容卡片 / Card | 组合已有 | 工具入口/提示/结果等组合卡片。 |
| `c04` | 结构化表格 / Table | Web已有 | Web NDataTable；客户端表格需补规范。 |
| `c05` | 层级树 / Tree | 组合已有 | 客户端ZIP目录展开与Web目录树。 |
| `c06` | 标签与徽标 / TagBadge | 组合已有 | RSS未读点、状态标签等组合。 |
| `c07` | 头像与身份标识 / Avatar | 组合已有 | 头像图片与失败回退组合。 |
| `c08` | 详情描述与键值 / Description | 组合已有 | ProfileInfoRow标签值属性组合。 |
| `f01` | 就近提示 / InlineNotice | 组合已有 | RSS就近状态和DSH持久提示。 |
| `f02` | 短暂操作反馈 / Toast | Web已有 | Web message.success已有；客户端showToast未发现。 |
| `f03` | 确定进度 / Progress | 原生已有 | 原生Progress。 |
| `f04` | 不定式加载 / Spinner | 原生已有 | 原生LoadingProgress。 |
| `f05` | 骨架占位 / Skeleton | Web已有 | Web NSkeleton；客户端网络占位组合。 |
| `f06` | 空状态 / EmptyState | 组合已有 | 公共ToolEmptyState与业务空态。 |
| `f07` | 内容区错误 / ErrorState | 组合已有 | 重试动作+错误说明的业务组合。 |
| `f08` | 流程结果 / Result | 组合已有 | ZIP完成结果、DSH恢复等组合。 |
| `f09` | 刷新与加载更多 / RefreshLoadMore | 组合已有 | 客户端Refresh与onReachEnd/RSS游标加载组合。 |
| `o01` | 对话框与确认 / Dialog | 原生已有 | showAlertDialog原生确认。 |
| `o02` | 底部操作与选择面板 / Sheet | 原生已有 | 原生bindSheet与手绘Sheet并存。 |
| `o03` | 锚点浮层 / Popover | Web已有 | Web NPopconfirm等锚点浮层；客户端需补规范。 |
| `o04` | 简短辅助说明 / Tooltip | Web已有 | Web NTooltip；手机必要帮助另设计。 |
| `b01` | 文件上传 / Upload | 组合已有 | 文件传输上传+进度/状态组合。 |
| `b02` | 来源与文件选择 / FilePicker | 原生已有 | 系统文件/媒体选择API入口；保留系统交互。 |
| `b03` | 文件与目录行 / FileRow | 组合已有 | 远程文件行和ZIP输出行组合。 |
| `b04` | 媒体播放控制 / MediaControls | 组合已有 | 音频/视频控制与队列组合。 |
| `b05` | 书架图书行 / BookRow | 组合已有 | 书封面/文字手势/缓存进度组合。 |
| `b06` | 阅读参数面板 / ReaderSettings | 组合已有 | 字号/间距/亮度/主题等组合。 |
| `b07` | 会话输入与附件 / ChatComposer | 组合已有 | 原生聊天构建器和实际DSH WebView分别验收。 |
| `b08` | 异步任务行 / TaskRow | 组合已有 | DSH、下载和生成任务阶段/动作组合。 |
| `b09` | 文件预览 / FilePreview | 组合已有 | PDFKit预览、媒体预览卡与Web抽屉预览。 |

覆盖：72/72；未映射0项。来源分布：组合已有37项、Web已有13项、规范补齐8项、原生已有14项。这是来源归类，不是实施完成率。

## 场景与组件库映射

组件ID对应完整组件库。规范补齐不代表新增业务功能已获批准。

### 动作、文字/图标按钮与按钮组

状态：已有原生和公共包装。组件ID：`a01`、`a02`、`a03`、`a04`。

- [components/AppControls.ets:27](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/components/AppControls.ets:27)：公共主按钮。
- [pages/Index.ets:1926](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:1926)：工具动作按钮组。
- [features/rss/RssHomePage.ets:417](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/rss/RssHomePage.ets:417)：管理订阅文字动作。

按行为角色迁移，不能将所有Button变成同一种外观。

### 单行、多行与只读结果

状态：已有公共和原生实现。组件ID：`i01`、`i02`、`i08`。

- [components/AppControls.ets:127](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/components/AppControls.ets:127)：单行输入。
- [components/AppControls.ets:159](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/components/AppControls.ets:159)：多行输入。
- [components/AppControls.ets:189](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/components/AppControls.ets:189)：可复制只读结果。

只读与禁用不同；可复制结果、计数、字段标签、帮助和就近错误都要覆盖。

### 密码输入与受保护显示

状态：已有原生与业务保护。组件ID：`i03`。

- [features/tools/archive/ArchivePage.ets:389](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/tools/archive/ArchivePage.ets:389)：压缩包密码输入。
- [features/tools/archive/ArchivePage.ets:214](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/tools/archive/ArchivePage.ets:214)：保存密码查看前的身份验证。
- [features/tools/pdf/PdfToolsPage.ets:385](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/tools/pdf/PdfToolsPage.ets:385)：PDF文件密码。

显示/隐藏按钮不能绕过既有身份验证；评审样例不使用真实密码。

### 数值与步进

状态：原生数值输入和组合步进已有。组件ID：`i04`、`n03`。

- [features/tools/pdf/PdfToolsPage.ets:345](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/tools/pdf/PdfToolsPage.ets:345)：按页数拆分。
- [pages/Index.ets:6936](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:6936)：阅读A-/A+。

未发现原生Counter/Stepper调用；字号步进由按钮组合实现，需保留步长和边界禁用。

### 搜索框、提交、停止和清除

状态：已有公共与组合实现。组件ID：`i05`、`i06`。

- [components/AppControls.ets:214](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/components/AppControls.ets:214)：带onSubmit与清除的公共搜索。
- [pages/Index.ets:4773](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:4773)：搜索模式+输入+搜索/停止。
- [pages/Index.ets:4808](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:4808)：独立搜索按钮。
- [pages/Index.ets:4800](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:4800)：搜索中停止动作。
- [features/rss/RssHomePage.ets:388](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/rss/RssHomePage.ets:388)：RSS键盘提交。

公共搜索框左侧放大镜是装饰SymbolGlyph；提交、停止、清除分别命名，不能一律把图标当按钮。

### 搜索型选择与搜索建议

状态：可搜索选择已有；统一建议列表补齐。组件ID：`i07`、`s02`。

- [pages/Index.ets:4033](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:4033)：标签与目录检索。
- [pages/Index.ets:2603](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:2603)：媒体目录/来源选择。

区分本地即时筛选、网络提交、历史与联想，不能共用一种防抖和空态文案。

### 单项下拉与滚轮选择

状态：已有Select、TextPicker和手工面板。组件ID：`s01`。

- [features/rss/RssHomePage.ets:426](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/rss/RssHomePage.ets:426)：RSS分组。
- [features/rss/RssHomePage.ets:498](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/rss/RssHomePage.ets:498)：RSS刷新间隔。
- [features/reader/ChapterAdaptationPanel.ets:501](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/reader/ChapterAdaptationPanel.ets:501)：改编风格。
- [pages/Index.ets:4003](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:4003)：媒体滚轮。
- [pages/Index.ets:12573](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:12573)：远程书源滚轮。

按选项数量、是否需搜索、是否需确认决定样式；取消不提交滚轮草稿。

### 多选、级联与树选择

状态：客户端组合和Web已有；通用规范补齐。组件ID：`s03`、`s04`、`c05`。

- [features/tools/archive/ArchivePage.ets:408](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/tools/archive/ArchivePage.ets:408)：压缩包目录条目多选。
- [toolbox-web/src/components/worksheet/WorksheetCurriculumPicker.vue:65](/Users/pankang/mycode/MyTools/toolbox-web/src/components/worksheet/WorksheetCurriculumPicker.vue:65)：Web单元多选树。
- [toolbox-web/src/views/admin/TextbookListView.vue:186](/Users/pankang/mycode/MyTools/toolbox-web/src/views/admin/TextbookListView.vue:186)：Web科目-年级依赖选择。

客户端没有统一原生级联/树选择；补父子选择、异步选项、子级清空、多选上限。

### 单选、复选、开关

状态：原生Checkbox/Toggle已有；Radio未发现。组件ID：`s05`、`s06`、`s07`。

- [features/reader/AdaptationConsentPanel.ets:61](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/reader/AdaptationConsentPanel.ets:61)：改编处理同意。
- [pages/Index.ets:5035](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:5035)：书源开关。
- [features/tools/archive/ArchivePage.ets:387](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/tools/archive/ArchivePage.ets:387)：自动解压。
- [toolbox-web/src/components/CurriculumPicker.vue:125](/Users/pankang/mycode/MyTools/toolbox-web/src/components/CurriculumPicker.vue:125)：Web课程单选。

按钮式单选仍需互斥语义；开关即时生效与表单暂存不能混用。

### 筛选与分段

状态：已有公共与组合。组件ID：`s08`、`v03`。

- [components/AppControls.ets:103](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/components/AppControls.ets:103)：筛选标签。
- [components/AppControls.ets:5](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/components/AppControls.ets:5)：阅读模式分段。
- [pages/Index.ets:3939](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:3939)：媒体筛选触发器。

筛选是缩小结果，分段是互斥视图；不能复用提交按钮语义。

### 日期、日历与日期范围

状态：客户端未发现；Web单日期已有；其余补齐。组件ID：`d01`、`d02`、`d03`。

- [webapp/src/views/profile/index.vue:427](/Users/pankang/mycode/MyTools/webapp/src/views/profile/index.vue:427)：Web个人资料生日。

188个ArkTS文件未发现DatePicker/CalendarPicker/Calendar；日期范围不能宣称是现有客户端能力。补齐空值/清除/合法范围/跨月/开始结束/禁用日期。

### 时间、日期时间与快捷范围

状态：客户端原生未发现，规范补齐。组件ID：`d04`、`d05`、`d06`。

- 源码扫描未发现本类专用实现，按规范补齐。

RSS刷新间隔是时长，不能称为时刻选择；规范需定义时区、24小时制、日期组合和快捷范围。

### 滑杆与双端范围

状态：单值已有；双端范围补齐。组件ID：`n01`、`n02`。

- [pages/Index.ets:6951](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:6951)：阅读行段距/边距/亮度。
- [pages/Index.ets:6747](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:6747)：漫画缩放/预加载。
- [features/tools/pdf/PdfToolsPage.ets:351](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/tools/pdf/PdfToolsPage.ets:351)：PDF页边距。
- [features/tools/pdf/PdfToolsPage.ets:376](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/tools/pdf/PdfToolsPage.ets:376)：PDF位置草稿。

保留拖动中预览与结束提交的区别；单值不是双端范围的替代品。

### 评分

状态：客户端和两Web未发现，规范补齐。组件ID：`n04`。

- 源码扫描未发现本类专用实现，按规范补齐。

仅补组件规范，不代表业务已有评分流程。

### 页头、分组和底部导航

状态：已有公共与原生。组件ID：`v01`、`v02`、`v04`。

- [features/tools/ToolUi.ets:5](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/tools/ToolUi.ets:5)：工具页头。
- [pages/Index.ets:11086](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:11086)：五个一级入口。

安全区不硬编码进普通内容；需覆盖长标题、操作溢出和返回。

### 目录路径与面包屑

状态：已有目录组合，统一组件补齐。组件ID：`v05`。

- [pages/Index.ets:1947](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:1947)：文件传输当前路径。
- [pages/Index.ets:3652](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:3652)：网盘目录页。

文本路径、上级返回与可点击面包屑区分。

### 分页、加载更多和下拉刷新

状态：客户端游标/刷新、Web页码已有。组件ID：`v06`、`f09`。

- [pages/Index.ets:2554](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:2554)：媒体加载更多。
- [pages/Index.ets:2526](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:2526)：媒体下拉刷新。
- [features/rss/RssHomePage.ets:412](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/rss/RssHomePage.ets:412)：RSS继续加载。
- [webapp/src/views/ai-tools/token/index.vue:256](/Users/pankang/mycode/MyTools/webapp/src/views/ai-tools/token/index.vue:256)：Web令牌页码。

三种变体单独规范；刷新不闪空旧内容，加载更多失败保留已有列表。

### 步骤与任务阶段

状态：业务阶段已有；通用步骤条补齐。组件ID：`v07`、`b08`。

- [pages/Index.ets:2469](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:2469)：DSH任务阶段。

不可跳转阶段不能伪装可点击导航，状态不能只靠符号和颜色。

### 菜单与上下文动作

状态：手工菜单/面板及Web下拉已有。组件ID：`v08`。

- [pages/Index.ets:5163](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:5163)：书源筛选菜单。
- [pages/Index.ets:15920](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:15920)：媒体文件动作面板。
- [toolbox-web/src/App.vue:160](/Users/pankang/mycode/MyTools/toolbox-web/src/App.vue:160)：Web管理菜单。

原生Menu调用为零不代表没有菜单，Column/Button拼装也要归类。

### 列表、设置和属性说明

状态：已有公共与业务组合。组件ID：`c01`、`c02`、`c08`。

- [components/AppControls.ets:272](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/components/AppControls.ets:272)：设置整行入口。
- [pages/Index.ets:2160](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:2160)：个人资料标签值。
- [pages/Index.ets:1996](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:1996)：文件/目录行。

整行点击和尾部菜单避免手势冲突；标题、说明与尾部值分别布局。

### 卡片、标签、徽标和头像

状态：已有组合内容。组件ID：`c03`、`c06`、`c07`。

- [pages/Index.ets:2142](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:2142)：工具入口。
- [pages/Index.ets:1441](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:1441)：连接远程书库提示。
- [pages/Index.ets:1230](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:1230)：头像图片和回退。
- [features/rss/RssHomePage.ets:360](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/rss/RssHomePage.ets:360)：RSS未读点。

Badge原生为零，但未读点/状态/头像已经存在。

### 表格与树

状态：Web已有，客户端压缩包树组合已有。组件ID：`c04`、`c05`。

- [webapp/src/views/ai-tools/token/index.vue:254](/Users/pankang/mycode/MyTools/webapp/src/views/ai-tools/token/index.vue:254)：Web令牌表格。
- [webapp/src/views/cloud-file/browse/index.vue:654](/Users/pankang/mycode/MyTools/webapp/src/views/cloud-file/browse/index.vue:654)：Web目录树。
- [features/tools/archive/ArchivePage.ets:279](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/tools/archive/ArchivePage.ets:279)：ZIP目录展开。

手机优先列表/属性说明；表格可横向滚动，不压成密集小字。

### 就近通知、结果与恢复

状态：客户端业务状态组合已有；Web消息已有；原生Toast规范补齐。组件ID：`f01`、`f02`、`f08`。

- [pages/Index.ets:2496](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:2496)：任务恢复提示。
- [features/rss/RssHomePage.ets:524](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/rss/RssHomePage.ets:524)：RSS就近状态。
- [features/tools/archive/ArchivePage.ets:318](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/tools/archive/ArchivePage.ets:318)：ZIP结果卡。
- [toolbox-web/src/views/WorksheetView.vue:156](/Users/pankang/mycode/MyTools/toolbox-web/src/views/WorksheetView.vue:156)：Web文件下载开始消息。

客户端showToast调用未发现；Web已有message.success消息提示。短反馈与持久错误分开，不能将需要处理的错误只放Toast。

### 进度、加载和骨架

状态：进度/加载已有；骨架组合和规范补齐。组件ID：`f03`、`f04`、`f05`。

- [pages/Index.ets:1953](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:1953)：上传进度。
- [pages/Index.ets:2342](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:2342)：DSH加载。
- [pages/Index.ets:5285](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:5285)：列表占位行。
- [toolbox-web/src/views/HomeView.vue:280](/Users/pankang/mycode/MyTools/toolbox-web/src/views/HomeView.vue:280)：Web首页骨架。

已知总量用确定进度，未知用不定式；加载、空与失败分开。

### 空、无结果、未连接与错误

状态：已有公共与组合。组件ID：`f06`、`f07`。

- [features/tools/ToolUi.ets:23](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/tools/ToolUi.ets:23)：公共工具空态。
- [pages/Index.ets:3958](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:3958)：无结果与空目录。
- [pages/Index.ets:5298](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:5298)：失败重试。
- [features/rss/RssHomePage.ets:405](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/rss/RssHomePage.ets:405)：未连接/无来源/无条目。

首次加载中不先显示无来源；旧内容刷新失败不清空列表。

### 确认与底部面板

状态：原生API和手工组合并存。组件ID：`o01`、`o02`。

- [features/tools/ImageGenerationPage.ets:245](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/tools/ImageGenerationPage.ets:245)：切换/删除等确认。
- [features/tools/pdf/PdfToolsPage.ets:443](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/tools/pdf/PdfToolsPage.ets:443)：PDF参数面板。
- [pages/Index.ets:3746](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:3746)：网盘选择面板。

草稿、确认、取消、返回键、键盘和焦点回到触发点统一；系统层保留平台语义。

### 气泡和说明提示

状态：Web已有；客户端规范补齐。组件ID：`o03`、`o04`。

- [toolbox-web/src/App.vue:154](/Users/pankang/mycode/MyTools/toolbox-web/src/App.vue:154)：Web页头说明。
- [webapp/src/views/app-market/components/AppMarketDrawer.vue:144](/Users/pankang/mycode/MyTools/webapp/src/views/app-market/components/AppMarketDrawer.vue:144)：Web上下架确认。

手机必要帮助不只依赖hover；危险操作影响不能只放Tooltip。

### 上传、文件选择和文件行

状态：已有业务和系统选择器。组件ID：`b01`、`b02`、`b03`。

- [pages/Index.ets:11933](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:11933)：文件传输上传。
- [features/tools/download/DownloadToolsPage.ets:138](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/tools/download/DownloadToolsPage.ets:138)：媒体/文件选择。
- [features/tools/archive/ArchivePage.ets:145](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/tools/archive/ArchivePage.ets:145)：ZIP文件选择。
- [pages/Index.ets:1996](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:1996)：远程文件行。

系统文件/相册选择器保留原生流程；统一入口、选中文件、进度/取消/失败。网盘浏览不新增写操作。

### 播放器和预览

状态：已有原生和业务组合。组件ID：`b04`。

- [pages/Index.ets:3441](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:3441)：视频详情。
- [pages/Index.ets:17790](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:17790)：播放进度。
- [pages/Index.ets:18237](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:18237)：音频队列。

覆盖缓冲/暂停/拖动/错误/静音/深色沉浸，保持内容优先。

### 图书行、缓存与阅读设置

状态：已有业务组合。组件ID：`b05`、`b06`。

- [pages/Index.ets:5308](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:5308)：封面与阅读点击区/缓存状态。
- [pages/Index.ets:6936](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:6936)：字号。
- [pages/Index.ets:6951](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:6951)：阅读行段距/边距/亮度。

保留封面详情/文字阅读分区、缓存状态与正文专用字体偏好。

### 聊天、附件、任务和权限

状态：原生构建器及实际DSH WebView并存。组件ID：`b07`、`b08`、`i08`。

- [pages/Index.ets:2293](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:2293)：聊天输入。
- [pages/Index.ets:2289](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:2289)：附件入口。
- [pages/Index.ets:2477](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:2477)：仅本次授权确认。
- [pages/Index.ets:2308](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:2308)：DSH内嵌网页。

原生Copilot与实际DSH网页分开验收；保留授权/附件移除/任务恢复/停止，上游不可控区域登记例外。

### 链接与资源/协议说明入口

状态：Web链接已有，客户端说明入口组合已有。组件ID：`a05`。

- [features/tools/QrToolsPage.ets:186](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/tools/QrToolsPage.ets:186)：二维码资源许可说明入口。
- [pages/Index.ets:4308](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:4308)：客户端隐私说明入口。
- [webapp/src/views/app-market/components/AppMarketDrawer.vue:124](/Users/pankang/mycode/MyTools/webapp/src/views/app-market/components/AppMarketDrawer.vue:124)：Web应用市场外部下载链接。

客户端二维码许可和个人隐私入口是按钮/设置行组合，不是已经统一的Hyperlink控件；Web真实链接需保留目标、外部打开和安全语义。

### 悬浮操作

状态：未发现专用浮动动作，规范补齐。组件ID：`a06`。

- 源码扫描未发现本类专用实现，按规范补齐。

未发现客户端专用FAB或Web NFloatButton/NBackTop。媒体浮层和固定底部操作不是浮动新增按钮，不强行视作已有；标准需覆盖安全区、遮挡、滚动显隐和唯一主要动作。

### 验证码与重发

状态：Web模板已有，客户端未发现；真实流程未验证。组件ID：`i09`。

- [webapp/src/views/_builtin/login/modules/code-login.vue:49](/Users/pankang/mycode/MyTools/webapp/src/views/_builtin/login/modules/code-login.vue:49)：Web验证码输入。
- [webapp/src/views/_builtin/login/modules/code-login.vue:50](/Users/pankang/mycode/MyTools/webapp/src/views/_builtin/login/modules/code-login.vue:50)：Web重发/倒计时按钮UI。
- [webapp/src/hooks/business/captcha.ts:42](/Users/pankang/mycode/MyTools/webapp/src/hooks/business/captcha.ts:42)：Web验证码请求占位和倒计时实现。

Web代码登录已有验证码输入、重发按钮和倒计时状态，但handleSubmit与useCaptcha中的request仍是占位；不可据此宣称真实短信发送/校验已经实现。规范补齐粘贴、自动填充、错误、过期、重发限流与倒计时读屏。

### 颜色选择与预设主题

状态：Web颜色选择已有；客户端预设主题组合已有。组件ID：`n05`。

- [webapp/src/layouts/modules/theme-drawer/modules/appearance/modules/theme-color.vue:72](/Users/pankang/mycode/MyTools/webapp/src/layouts/modules/theme-drawer/modules/appearance/modules/theme-color.vue:72)：Web主题颜色与色板选择。
- [pages/Index.ets:6965](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:6965)：客户端预设阅读主题色按钮。

客户端阅读主题按钮是有限预设，不是任意颜色拾取；Web NColorPicker已有色板/禁用状态。自定义输入需补HEX合法性、确认取消、回退和非颜色反馈。

### 文件预览

状态：客户端PDF/媒体预览与Web文件抽屉已有。组件ID：`b09`。

- [features/tools/pdf/PdfToolsPage.ets:400](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/features/tools/pdf/PdfToolsPage.ets:400)：客户端PDFKit文件预览。
- [pages/Index.ets:4167](/Users/pankang/mycode/MyTools/app/entry/src/main/ets/pages/Index.ets:4167)：客户端图片/视频预览卡。
- [webapp/src/views/cloud-file/alist/index.vue:288](/Users/pankang/mycode/MyTools/webapp/src/views/cloud-file/alist/index.vue:288)：Web文件预览抽屉与不支持时外部打开。

预览与播放、下载、外部打开分别规范；需要加载/损坏/不支持/权限失效/重新打开/关闭返回状态。系统预览器与PDFKit继续保留平台操作，不强行自绘。

## 迁移与审核边界

1. 客户端没有原生日期/日历/时间/日期范围、双端范围和评分构造；这些是规范补齐，不代表新增业务已获批准。
2. 只读可复制结果、受保护密码查看、授权同意、加载更多/下拉刷新、系统文件/相册选择器、DSH网页例外必须明确覆盖。
3. 全量迁移应逐路由/业务Builder登记，不以原生调用归零或样例数量作为完成标准。
4. 搜索、选择、日期、文件和任务各自具有状态差异，不用一个默认/禁用截图替代全部场景。
5. 原生、系统弹层与DSH上游网页分别验收；静态源码和样稿不能证明键盘、读屏、命中区域、大字与真实任务回归已经通过。
6. Web验证码输入/重发为现有模板UI，request仍为占位，不宣称短信发送/校验已可用。悬浮操作等规范补齐项不强行寻找不匹配证据。
