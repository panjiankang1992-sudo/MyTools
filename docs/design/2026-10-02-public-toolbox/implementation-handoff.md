# 禹宇天的工具箱：Agent 实施交接文档

> 2026-10-03 字帖修改：M03/M04 与字帖相关契约、验收若有冲突，以[统一输入流程修改方案](../2026-10-03-worksheet-workflow-revision/modification-plan.md)为准。最新要求是先选内容、再统一选全局题型；新增图片 OCR、保留原文返编、米字格和紧凑排版，不按字表自动分配题型。其他站点任务仍按本文件执行。

版本：1.0；编写日期：2026-10-02。目标读者：接手开发、测试和发布的 Agent。

本文件是**后续实现的执行规范，不是已完成报告**。最终目标是独立、无需账号登录、兼容手机的公开工具网站。实施应从现有 `toolbox-web/` 原型继续，不另起一个 React 项目，不重新绘制已完成的四个页面。

## 0. 阅读顺序与文档优先级

1. 仓库根 `AGENTS.md`；涉及后端时再读 `service/AGENTS.md`。
2. 本文件：范围、技术决策、任务顺序、每步产物和完成条件。
3. [接口和数据契约](implementation-contracts.md)：实现时统一遵循的字段、状态、错误和幂等规则。
4. [验收用例与证据要求](acceptance-matrix.md)：以可复现结果决定是否通过。
5. [UI 与功能设计](design.md)、[字帖与打印细则](worksheet-and-print-spec.md)：交互与纸面要求。
6. [原型 README](../../../toolbox-web/README.md)、[原型验收记录](../../../toolbox-web/design-qa.md)：已经实现什么、此前实际验证了什么。

发生冲突时：用户最新指令 > 本实施包的最终目标与契约 > 早期设计 > 原型示例行为。早期设计中的“尚未开发”描述属于历史状态；原型中的“待接入”不能据此删减最终需求。旧右侧导航图位于归档目录，不能作为实现依据。

本文路径均相对仓库根 `/Users/pankang/mycode/MyTools`，代码示例中的新接口、新目录和命令目标，除明确注明“现有”外均需要创建。接手 Agent 必须先核对实际仓库，记录发生的差异。

## 1. 最终交付范围

| 编号 | 用户要求 | 最终完成定义 |
| --- | --- | --- |
| R01 | 独立开放网站，名称固定 | 所有公开工具无需注册、登录；独立前端构建和部署入口；不改 MyTools 原登录要求。 |
| R02 | 左侧目录、后续扩展 | 桌面左侧“首页 / PDF / 字帖 / 单词”；手机左抽屉；同一工具注册表驱动导航、搜索、首页和统计。 |
| R03 | 首页使用概览 | 真实全站点击、成功使用、打印发起；7/30 天趋势、排行；统计失败仍可使用工具。 |
| R04 | PDF 与 MyTools 保持一致 | 八项现有能力逐项对齐，尤其 Markdown 的表格/代码/受管图片、分割范围和输出顺序；公开配额可更严格且明确展示。 |
| R05 | 小学语文 1—6 年级 | 12 册经核验的目标教材，支持真实生字/词语/识字等目录、自动选题、两种练习模式。 |
| R06 | 小学英语 3—6 年级 | 8 册经核验的目标教材，译林版优先；中文提示写英文、英文提示写中文。 |
| R07 | 打印和预览 | 四模式共用固定题单，真实 PDF 预览/下载、独立答案、系统打印；手机打印不可用仍可下载。 |
| R08 | 移动适配 | 320/360/390/430/768/1280/1440 px 及手机横屏无页面整体水平溢出，触控、键盘、软键盘和安全区可用。 |

首期不增加登录、会员、支付、公开教材编辑后台、OCR、Office 转换、PDF 转 Word。教材数据不足时可以交付“明确标识的部分可用阶段”，但不能宣布 R05/R06 完成。

## 2. 已有实现与缺口

### 2.1 可继续复用的文件

| 现有文件 | 已有能力 | 后续处理 |
| --- | --- | --- |
| `toolbox-web/src/App.vue`、`src/style.css` | 左侧布局、手机抽屉、主题、搜索；平板图标具有可访问名称 | 保留；抽取工具注册表，完成移动细节验收。 |
| `src/views/HomeView.vue` | 指标卡、ECharts、7/30 天、排行、数据表 | 保留 UI，替换为全站 API；生产不默认展示演示数据。 |
| `src/views/PdfView.vue`、`src/lib/pdf-tools.ts` | 八个本机处理入口 | 保留 UI/本机实现；增加正式服务端执行适配，不能把基础 Markdown 称为完全对齐。 |
| `src/views/WorksheetView.vue` | 四模式编辑器、自定义词条、随机选题、预览和独立答案 | 接真实教材、课文树、快照和校对状态，拆分过大的视图逻辑。 |
| `src/components/WorksheetPreview.vue` | 将导出文件本身渲染为预览 | 保留“预览读取同一文件”的机制。 |
| `src/lib/worksheet.ts` | 四模式投影、确定性抽题、去重、分页和 PDF | 扩展正式内容模型、均衡抽取、快照、可配置版式。 |
| `src/lib/pdf-runtime.ts` | 字体加载/子集、PDF.js 预览、下载 | 保留缺字阻断和 CFF 修复回归；补真实浏览器验收。 |
| `src/data/worksheet-samples.ts` | 语文/英语各 24 条原创示例、各 3 个示例单元 | 保留作测试夹具；生产教材模式不得使用此数据作失败回退。 |
| `src/lib/analytics.ts` | 当前浏览器 localStorage 事件 | 仅作本机/开发工具，不能作为全站统计后台。 |
| `tests/worksheet.test.ts`、`tests/pdf-tools.test.ts` | 11 项字帖测试 + 14 项 PDF 测试 | 全部保留；增加新数据、API、隔离、浏览器 E2E 测试。 |

原型此前通过 25 项 Node 测试、类型检查和构建，做过桌面/手机/平板截图核对。浏览器下载事件等待工具超时，不能视为磁盘下载验收通过；系统打印和实纸测量也未完成。接手时需要重跑基线，不把本文件日期当作当前状态。

### 2.2 必须补齐的真实缺口

- 年级和上下册目前不切换教材数据；教材选择器处于禁用状态，不能只解除禁用就宣称接入。
- `WordEntry` 仅有 `id/text/pinyin/meaning/unit/kind`，没有版次、课文、来源、义项或校对字段。
- 目前只有平铺候选集合随机抽题，没有课文树、单元均衡抽取和不可变教材版本快照。
- 本机 PDF 与服务端的限制和参数不同，文本/Markdown 尤其不能逐字段直传。
- 本机统计没有 PDF 子工具 `toolId`；刷新后生成去重状态丢失；PDF 每次执行会产生新操作号；首页日期在跨零点时不会自动更新；非法本机日期需防御。
- 目前部分小图标操作触控区小于 44×44 px，不能以主按钮已达标替代逐项检查。
- 现有排版器会把标题截为 40 个码点；正式实现应在输入侧提示长度限制，不能继续静默截断。
- 生产 API、匿名文件隔离、全站统计、完整教材、稳定发布与回滚均尚未完成。

## 3. 固定技术路线

### 3.1 前端

继续使用现有 Vue 3 / TypeScript / Vite / Naive UI / UnoCSS / ECharts / Pinia / Vue Router。以 `toolbox-web/package.json` 与 `webapp/package.json` 实际版本为基线，不为了安装方便静默换框架或降级。

保留 hash 路由，浏览器地址为 `/#/copybook` 等；本文写 `/copybook` 指前端逻辑路由。为 PDF 新增 `/pdf/:toolId`，兼容当前 `/pdf?tool=...` 入口并归一化。不要同时要求服务器 history fallback 和 hash 路由。

开发模式可以保留演示数据和本机 PDF；正式构建由显式配置 `VITE_APP_MODE=production`、`VITE_PDF_EXECUTION=server` 切换。配置不得包含服务密钥。生产在依赖不可用时显示真实错误，不自动回落成演示统计或样例教材。

### 3.2 公开服务与 PDF 执行

默认路线确定为：

```text
浏览器 toolbox-web
  ├─ 语文/英语排版：在本机生成，个人词条不上传
  └─ 同域 /public/v1/* → 新 toolbox-service（Java 21 + Spring Boot）
       ├─ 发布教材的只读目录/版本化内容
       ├─ 最小化事件与全站聚合
       └─ 匿名 PDF 任务门面
            → 复用 pdf-service 代码的独立公开实例
            → 专用 Scheduler 任务定义 / 不可变执行包
            → 复用 PdfWorker 与 render_text 引擎
            → 独立公开 Storage Gateway / 独立存储根
```

这样保留已有 PDF 引擎行为，同时避免访客与 MyTools 真实用户混用 `owner_id`。**不是**给 `/api/app/v1/pdf` 加匿名放行，也不是所有访客共用一个代理账号。

具体约束：

1. 新建 `service/toolbox-service/`，独立 `pom.xml`；按 `service/AGENTS.md`，不隐式加入根 Maven 构建。
2. 公开 PDF 实例复用现有 `service/pdf-service` 源码和构建产物，但数据库 schema、上传目录、服务/Worker token 独立。匿名 owner 为公开命名空间内的正整数，不通过随机负数避碰；现有内部接口拒绝 `owner <= 0`。
3. `PdfUpstream` 的 Scheduler serviceId、`PdfService` 的 taskName/businessType 改为可配置，现有私有默认值保持不变。公开实例使用明确的独立值。
4. 现有 Scheduler 不能仅靠换 business token 就被当作领域隔离。为公开 PDF serviceId 增加创建/读取/取消/结果访问守卫，只能操作公开 PDF 任务定义和其所属任务；其他业务主体不得访问公开任务。
5. 现有 Storage Gateway 的单一 internal token 不能假定按 root 隔离。默认启动独立公开实例，只配置公开 root、独立元数据库与 token；不要把原站全存储 token交给公开 runner。
6. 现有 runner 会向共享 Asset Registry 注册 `PDF_TOOL` 产物。公开专用执行包关闭这一步，产物仅保存在公开存储；否则公开 owner 数字仍可能被共享资产系统解释成真实用户。私有执行包不修改既有行为。
7. 公开 runner 的服务地址、存储地址从固定受控配置读取，不接受浏览器或任务参数提供任意 URL。输入参数只接受受验证的工作 ID 和已冻结参数。
8. 如果基础设施无法支持上述隔离，可以先完成本机原型增强，但公开服务端 PDF 的上线验收必须保持未通过；不得临时改成共享账号或放开内部接口。

部署连接采用当前项目约定的本机已配置 SSH 通道，先解析当前配置并验证连通，再做操作；不通过飞书或 UbuntuHermes 中转。本文不记录固定主机、密码、私钥或生产环境内容。实际部署须处于届时用户授权范围内。

### 3.3 匿名访问语义

访客不用登录。浏览器使用短期随机 cookie 隔离本次 PDF 文件，相当于临时取件凭据，不是账号系统。公共教材、工具目录、聚合统计可直接读取；个人任务、文件及其预览必须验证 cookie 对应的归属。

独立站使用独立域名或至少独立 cookie/storage 命名空间。生产 cookie 使用 `__Host-toolbox_guest`、`Secure`、`HttpOnly`、`SameSite=Lax`、`Path=/`，不设置 Domain；本机 HTTP 开发使用单独名称。token 不放 URL，不允许跨访客分享任务链接。

### 3.4 数据原则

教材按版本化 JSON 发布；业务状态和统计用独立 MySQL schema。公开站没有无鉴权的教材写接口。词库从录入、校对到发布通过仓库脚本和审查流程完成。字帖快照默认只在浏览器内存；用户主动保存草稿时使用本机 IndexedDB，并可一键清除。

## 4. 执行顺序与并行边界

```text
M00 基线 → M01 工程/契约
             ├─ M02 教材数据 → M03 选题与编辑 → M04 字帖/打印
             ├─ M05 匿名服务/隔离 → M06 PDF执行 → M07 PDF前端
             └─ M08 全站统计
        M03/M04/M07/M08 → M09 响应式与可访问性 → M10 联调验收 → M11 发布准备/发布
```

教材来源核验从 M00 开始并行推进，不能等 UI 完成才发现没有内容。可由不同 Agent 分别负责教材、PDF 后端、统计、前端，但接口契约由一名集成 Agent 维护。给每个实现 Agent 明确文件所有权；共享的 `types.ts`、`App.vue`、`WorksheetView.vue`、契约文件改动必须协调，不能相互覆盖。

每完成一个任务，更新 `docs/verification/public-toolbox/implementation-status.md`：任务 ID、状态、改动文件、执行命令、实际结果、证据链接、未完成事项。以下任务编号为交接工作的稳定标识。

## 5. 分步实现

### M00：建立基线和工作边界

**操作：**

1. 查看当前分支、`git status --short`、相关 AGENTS；记录现有非本任务修改，不能 reset、清理或提交他人的改动。
2. 对照第 2 节确认原型文件、服务接口和测试仍存在；若已有更新，在状态文档记录差异。
3. 使用 Node 24、Java 21；核验本机版本。现环境可用 Node 路径见 README/工具运行时，交接给其他机器时不要硬编码本机 cache 路径。
4. 优先 `npm ci`（已有且有效的 lockfile）或 `npm install`（首次生成 lockfile）。若精确框架版本暂不可从源安装、但同仓库已有精确依赖，可运行现有 `npm run prepare:local` 继续本机开发；发布前仍须补干净环境安装验证。
5. 执行下面的基线检查，保存输出；不要把工具不可用或 Xcode 许可问题解释为代码失败，也不要替用户接受系统许可。

```bash
cd /Users/pankang/mycode/MyTools/toolbox-web
node --version
npm run typecheck
npm test
npm run build
```

**产物：** 基线记录、依赖安装说明、20 册教材获取/校对责任表。

**完成标准：** 25 项现有测试重新通过，首页和两类字帖可打开，已有无关工作未被覆盖。存在环境阻塞时如实记录，并继续不依赖该环境的任务。

### M01：固定契约、工具注册表和工程骨架

**文件：** 新 `src/config/tools.ts`、`src/services/public-api.ts`、`src/services/pdf-adapter.ts`、`src/domain/`；新 `service/toolbox-service/`。

**操作：**

1. 将 `App.vue`、首页、搜索中的重复入口合并为一份工具注册表。区分 category 和具体 toolId；四种练习模式也分别有稳定 toolId。
2. 按附录创建请求/响应 DTO、前端类型和 API 测试 fixture。统一 HTTP 错误处理，不使用管理端的 JWT 刷新/退出客户端。
3. 前端 API 固定同域 `/public/v1`、`credentials: 'same-origin'`，不设置 MyTools Authorization/X-Owner-Id。
4. PDF 适配器提供能力、输入校验、执行、进度、取消、结果方法。本机适配器包裹现有 `pdf-tools.ts`；服务端适配器按 M06/M07 实现。执行位置始终在 UI 明示，禁止失败后静默上传本来承诺本机处理的文件。
5. 新后端建立 controller/service/mapper/model/common 分层，迁移脚本、集成测试配置及稳定错误码。遵循根项目的中文注释和英文标识规则；中文前端资源仍集中转义管理。
6. 生成并提交项目独立 lockfile；本地符号链接和 `.runtime-deps` 不能打包为部署依赖。

**完成标准：** 新增一个测试工具注册项即可在导航/搜索/首页按配置出现；生产构建中未混入示例 API 回退；干净安装和类型检查可复现。对应 A01—A03。

### M02：建立并发布真实教材内容

**文件：** 新 `toolbox-web/content/catalog/`、`content/textbooks/<editionId>/<contentVersion>/`、`content/sources/`、`scripts/validate-curriculum.mjs`、`scripts/build-curriculum.mjs`、`scripts/verify-curriculum-coverage.mjs`。

**操作：**

1. 建立 20 行覆盖清单：语文 1—6 年级 × 上下册、英语 3—6 年级 × 上下册。每行记录拟接入出版社/版次、来源、负责人、核验日期和状态。ISBN/版次未知就留明确缺口，不能编造。
2. 江苏适用教材优先；英语允许不同学校采用不同版本。用出版社和官方教材入口核验当前版次，再取得可使用的词表来源；不得将旧网文直接标成“最新版”。文档中的旧来源链接只作为查找线索，应在实施时重新验证。
3. 将原始内容导入 staging，保留来源页码/课文定位。原始教材 PDF/图片不直接加入公共仓库或公开下载区；发布所需的内容范围与来源依据单独记录。
4. 录入本附录定义的 Edition / Unit / Lesson / Entry；语文逐字拼音与上下文读音，英语按单元义项、大小写、短语保留。某册没有词语表时展示真实类别，不能合成一个所谓原版词语表。
5. 用脚本校验 ID 唯一、引用完整、册次匹配、排序稳定、拼音音节与字格对应、字段和字形完整；疑似重复输出报告，不自动合并不同读音或义项。
6. 人工逐项校对原始来源，记录 reviewedBy/reviewedAt/sourceLocator。AI/OCR 输出默认 draft；脚本通过不能自动变成 reviewed。
7. 发布脚本仅接受通过审核的版本，生成内容 hash、不可变 contentVersion 和 coverage manifest。更新版本创建新目录，不覆盖旧版本。
8. 新教材 API 只读发布产物，返回状态与条目数量；未发布的正文不可被参数猜测获取。部分版本明确列出缺失课文，不能返回假空数组并标 complete。

**完成标准：** A10—A16；全范围完成时 20/20 册 complete，发布条目 100% 有核验来源与审核记录，课堂/类别数量与来源逐册核对。没有合法可用来源时只阻塞对应内容，不停止其他开发，也不伪造补齐。

### M03：把示例选择器改为真实教材编辑器

**文件：** `WorksheetView.vue`、`types.ts`；新 `components/CurriculumPicker.vue`、`components/EntrySelector.vue`、`domain/curriculum.ts`、`domain/selection.ts`、`composables/useWorksheetDraft.ts`。

**操作：**

1. 选择链改为学科 → 年级 → 上/下册 → 版本 → 单元/课文 → 类别；第一次不猜年级。只提供 manifest 中真实可用的选项，显示来源和覆盖状态。
2. `NTree` 使用稳定字符串 ID，父节点支持半选；列表在手机使用卡片。全册选择必须取完所有分页并校验版本一致，不能只选当前加载的前 500 条。
3. 书册切换时将教材选中集合重置；有自定义内容时明确保留在独立区，用户决定是否合并，防止不同教材悄悄混用。
4. 按契约实现规范化 → 去重 → 抽取 → 排序 → 重复展开。支持全部、随机 N、按单元均衡 N；候选不足使用全部并显示实际数量，不重复补题。
5. 随机使用稳定种子和算法版本；“换一组”生成新种子，“恢复上一组”恢复完整上次快照，不只恢复数量。
6. 添加 TSV/CSV 导入和逐条编辑。CSV 用能正确处理引号、逗号、换行的解析器；当前 `|` 简单分隔可保留为辅助格式，不冒充完整 CSV。
7. 多音字、缺读音、义项和缺字用校对状态表达；阻断项定位到词条。不要自动使用拼音库的第一个读音。
8. 保留已修复的“清空后导入只选新项”；词条搜索、随机、类别变化不得复活手动取消的示例/教材项。

**完成标准：** A11—A18；年级/册次改变确实请求并显示另一册内容；选择数量、来源和答案顺序一致；缺数据、慢请求和旧请求返回不会覆盖新选择。

### M04：固定题单、完善纸面与打印/下载

**文件：** `src/lib/worksheet.ts`、`pdf-runtime.ts`、`WorksheetPreview.vue`；新 `domain/worksheet-snapshot.ts`、`lib/worksheet-print.ts`、`workers/worksheet.worker.ts`（性能测试需要时启用）。

**操作：**

1. 在现有函数前增加不可变 WorksheetSnapshot，包括内容版本、选中词条副本、顺序、种子、重复、模式、字体/排版版本、revision。
2. 将“题目内容身份”和“排版 revision”分开：改标题/格子/纸张只重排，不增加成功使用；内容、模式、顺序或重复改变形成新有效题单后才计一次使用。
3. 保留先投影再排版机制。student 投影删除答案字段；answer 为另一个文件。学生打印窗口只接收学生页像素或安全投影，不能带父页面完整草稿对象。
4. 补齐低年级 14 mm、高年级 12 mm 默认值，用户手动选定后不自动覆盖；田字格/方格、拼音四线/横线等按设计提供；英语四线三格确为四条线三个 3 mm 间隔。
5. 题号按重复展开连续编号；整题移到下一页，长词先整行排版。无法容纳时返回可定位错误，禁止缩成难以阅读的小字或静默截断。
6. 预览和下载引用同一 PdfArtifact；记录 sha256、pageCount、layoutHash、revision。快速切换时仅接收最新结果，过期结果释放 URL/Worker/PDF 文档，不允许继续打印旧卷。
7. 字体加载失败/缺字时禁用相关输出并允许重试；保留现有 CFF regression，不通过删除声调或替换方框“修好”字体。
8. 系统打印在用户点击后创建独立窗口；加载所有需要的纸面并准备完成后才能调用 print。若浏览器拦截，保留 PDF 下载入口。500 题多页打印测试峰值内存；需要时按页渲染/释放并降低屏幕缩略图分辨率，导出格线仍为矢量。
9. 增加显式保存/清除本机草稿；默认不保存学生信息、不上传手动词条。恢复草稿时使用原版本，不静默换成最新教材。

**完成标准：** A20—A33。四模式真实下载文件、文本层/元数据/附件答案隔离、系统预览和 A4 实纸测量均提供证据；没有实体打印机时不能勾选实纸项。

### M05：匿名会话、资源归属和公开服务基础

**文件：** 新后端 `controller/session`、`service/session`、`mapper/session`、`common/ErrorCode.java`、迁移 `V1__toolbox_public.sql`，以及相应测试。

**操作：**

1. 按契约建表和索引，匿名随机 token 至少 256 bit；服务端只存 hash。建立匿名 owner 与 public PDF 实例的映射，客户端不能自选 owner。
2. 新会话默认固定 24 小时，不续回已经过期的旧会话。资源 expiresAt 取业务保留时间、会话到期和底层资源到期的最早值。
3. 所有任务、上传、计划映射、结果读取先验证同一会话；其他访客资源统一按不存在返回，不泄漏文件名或状态。
4. 所有变更接口校验 same-origin/CSRF；精确 CORS 白名单。拒绝或丢弃浏览器注入的 Authorization、X-Owner-Id、内部 token、work 路由等。
5. 幂等键先在本地事务建立保留记录，再调用上游，使用稳定的上游键；响应丢失后相同请求返回原资源，不能双创建。
6. 限制 JSON 大小、上传声明大小与实际流量、会话活动任务数、IP 请求速率和全局队列。限额是可配置建议初值，压测后记录实际值。
7. 上传超时、会话过期、结果过期和存储删除各自有状态；使用持久清理任务重试，不能只将数据库行标 EXPIRED 就宣称文件已删除。

**完成标准：** A40—A47、A62；两个匿名浏览器、伪造身份头、猜测 ID、CSRF、超额、过期、重试、重启均通过。MyTools 私有接口不登录仍被拒绝。

### M06：复用 PDF 服务的独立公开执行链

**现有入口：** `service/pdf-service/.../PdfController.java`、`PdfService.java`、`PdfRules.java`、`PdfUpstream.java`；`service/pdf-worker/.../PdfWorker.java`；`service/pdf-service/packages/pdf_process/1.0.1/`。

**操作：**

1. 按第 3.2 节完成公开 PDF、Storage、Scheduler 任务和 runner 隔离。不可修改已发布 `1.0.0/1.0.1` 包；创建新的公开包版本并记录内容 hash。
   新包放入 `service/toolbox-service/packages/toolbox_pdf_process/1.0.0/`，新增 `build_public_pdf_package.py`；现有 `pdf-service/build_package.py` 写死私有包名/版本，不能仅换输出目录。组包脚本已有按 manifest 发现能力，目录与 manifest 名称/版本必须一致。
2. 私有 serviceId/taskName 保持默认；公开配置显式启用。新增 Scheduler 的公开 PDF 领域守卫，覆盖创建、查询、取消及结果访问，不能只限制创建路径。
3. 公开 runner 禁止向共享 Asset Registry 注册匿名产物；Storage 只具有独立公开根权限；固定 endpoint，不接收任意目标 URL。
   专用 Executor 进程固定绑定 public PDF/Storage；新 label/cluster/并发与私有任务分开，初期并发 1。Ubuntu 必须验证现有 bubblewrap 离线计算沙箱：子进程无网络、无私有目录/服务凭据；缺沙箱时失败关闭，不临时关闭沙箱上线。
4. 新门面将 public upload/task ID 映射到底层 ID，公开响应去掉 storageUri、assetId、磁盘路径、内部 task/work ID 和 token。
5. 实现完整现有链路：上传会话 → 固定 offset 分块 → complete → INPUT READY → PLAN 创建/轮询 → PLAN READY → JOB 发布 → artifact 下载。**现有实际转换发生在 PLAN 阶段，JOB 是发布已有结果，不能再创建第二次转换。**
   非 Markdown 请求不得包含 attachmentIds，空数组也不允许；映射参数时按 operation 白名单构造请求。范围在单组内去重后按原页码升序，分组按外层顺序输出，详见契约 5.3。
6. task 持久化 phase 和上游 ID；使用 outbox/reconciler 在进程重启后恢复。PLAN READY 后使用稳定发布幂等键创建 JOB；一次 public task 只计一次成功。
   按契约 5.4 实现持久执行槽位、2 小时排队/15 分钟执行/5 分钟发布预算。获取槽位后才创建 PLAN，避免队列等待耗尽现有 30 分钟 PLAN TTL；重启不重置期限，上传检查也有全局队列和字节预算。
   重试可以重新计算同一 PLAN，但只接受当前有效 fencing 的结果，保持一个 PLAN/JOB 与一次成功计数；不要要求物理计算恰好一次。已确认失败/超时/取消后由用户主动重新处理时，创建新操作与幂等键，区别于原请求网络重放。
7. 读取取消竞争结果：取消请求不等于已取消；晚到成功不得覆盖已经确认的取消/过期，且要清理孤立产物。
8. 字节流由服务端代理，不能整文件读入内存。完整 PDF 预览读取最终授权 PDF；现有最多 40 张 thumbnail 只用于缩略导航，不能冒充完整文档页数。
9. 实现受控 ZIP 下载：包内文件使用安全名称；遍历/重名处理确定；流式处理并限制结果总量。失效任务停止新的下载，HTTP range 只有在测试完整授权和范围校验后才开放。
10. 公布 limits 为“引擎能力 ∩ 公开策略”的交集。当前底层活动记录数量限制可能先于 30 文件触发，应配置化区分上传与运行任务，并保留私有默认行为；不得前端宣传 30 个文件而后端第 11 个拒绝。
11. 在 `service/storage-gateway-service` 新增仅公开实例启用的内部受管对象删除能力；现有服务没有通用对象删除接口。同步撤销过期 INPUT/PLAN/JOB 引用，检查活动任务/租约后幂等删除并确认真实文件不存在。覆盖共享引用、孤立产物、删除失败重试；默认关闭且只允许公开 root，不能影响原站存储。具体协议见契约 5.5。

**完成标准：** A40—A55。每个工具使用同一组 fixtures 对比私有引擎与公开链路的语义结果；不要求 PDF 二进制完全一致，但页数、尺寸、顺序、文字和图像方向一致。停进程、重放、超时和清理都有可复现证据。

### M07：接通 PDF 前端与真实任务状态

**文件：** `PdfView.vue`、`services/pdf-adapter.ts`、`services/public-api.ts`；新 `components/pdf/`、`composables/usePdfTask.ts`。

**操作：**

1. 展示能力接口实际允许的格式、参数和限额；处理位置为“上传服务器处理”，开始之前明确显示。
2. 将工具 ID 转换为内部 operation；按契约重建参数，不把本机 `orientation` 塞进服务端文本/Markdown options。服务端不支持的选项应隐藏或正式扩展引擎并测试。
3. 上传 1 MiB 固定 offset 分块，续传以服务端确认 offset 为准；响应未知时查询，不盲目追加。刷新仅恢复已持久化的 task ID/上传元信息，本地未保存的 File 对象需要重新选择。
4. Markdown 附件上传并通过受管引用绑定，不读取外部 URL、HTML 或本机路径。缺图可定位提示，不能悄悄丢图。
5. 状态呈现：校验/上传/排队/处理/发布/完成；只有上传显示可测量百分比。轮询支持退避、页面不可见降频和取消；请求超时不自动创建新任务。
6. 表单变更递增 clientRevision，冻结任务使用原快照；旧结果明确标“对应上一份设置”，主下载不能冒充当前参数结果。
7. 页面分割提供切线可视化、数值百分比、轴、顺序、选页；文件拆分展示分组范围。保留上移/下移以支持手机，不仅靠拖拽。
8. 完成结果提供真实文件名/大小/页数/到期时间、逐页预览、单文件和 ZIP 下载。损坏、密码、表单、无文字层等按错误码解释和恢复。

**完成标准：** A48—A56；八项完整链路、刷新恢复、掉线重试、参数改动和过期提示均通过；Markdown 表格/代码/受管图片必须与现有引擎能力对齐。

### M08：全站匿名统计与首页

**文件：** 前端 `analytics.ts`、`HomeView.vue`；后端 `controller/stats`、`service/stats`、`mapper/stats`、聚合迁移和测试。

**操作：**

1. 按附录实现事件批量 API 和三层去重：eventId 防重投、operationId 防成功重复、服务端 taskId 确认 PDF 成功。
2. 点击、成功使用、打印发起分开；同一浏览器再次主动打印可新增一次 print_request，网络重发同一次请求不新增。不能通过按 operationId 全量去重误删第二次真实打印。
3. 生产 PDF success 只由后端最终任务确认，拒收客户端伪造的 PDF success；同时校验 toolId/category/eventType 注册表映射，不能借 worksheet_success 伪造 PDF 成功。本机字帖 success 是客户端上报，页面和文档承认其统计精度边界。
4. 客户端缓冲队列只存最小字段，不存题单、文件名、学生姓名、正文。存储失败保持工具可用；不要把原型 localStorage 历史导入为真实全站事件。
5. 数据库事务写 event + 聚合 outbox，重试靠唯一键。UTC 时间存储，按 Asia/Shanghai 自然日聚合；跨零点和迟到事件都有测试。原始明细删除后累计计数不能归零。
6. 首页默认真实全站结果，显示更新时间和当天未结束状态；真实零、统计不可用和数据不完整必须区分。无 mock 回退。
7. 图表、排行和 KPI 共享相同工具过滤与周期；累计总数明确不等于最近 30 天。提供表格和文字摘要。

**完成标准：** A60—A67；明确的小样本事件集合可人工算出完全相同结果，双重回调、重放和重启不多计，取消打印不被称作打印成功。

### M09：响应式、键盘与主题收尾

**操作：**

1. 按 1280/768 断点保留既定左侧布局，分别检查 320/360/390/430/768/1280/1440 px；额外测试手机横屏和 200% 页面缩放。
2. 所有图标操作的实际点击区域至少 44×44 px，包括预览翻页、编辑、删除和菜单；图标本身可小。
3. 软键盘弹出时，输入和错误提示可见，底栏不压字段；iOS 安全区、横屏和底部返回位置正常。
4. 菜单和树支持键盘，图标栏保留 aria-label；抽屉/弹窗关闭恢复焦点；错误与生成状态有可访问提示。不要把 Naive 的视觉样式当作自动通过完整键盘验收。
5. 深色主题所有自定义容器使用 token；纸张始终白底。录下实际屏幕，避免仅通过 CSS 代码判断对比度。
6. 按已批准概念图与真实截图并排检查。页面额外的真实来源/状态说明允许占用空间，不能删除必要说明只为贴图。

**完成标准：** A02—A08、A30；无 P0/P1/P2 视觉或操作问题；有完整截图与键盘/软键盘操作记录。

### M10：联调、边界与可重复验收

**操作：**

1. 执行附录全部验收用例，真实浏览器、真实生成文件、必要的实体打印分开记录。
2. 将新增命令实现为项目脚本：`test:curriculum`、`test:e2e`、`verify:release`。这些命令目前不存在，不能只写文档不实现。
3. 下载测试必须取得实际落盘路径，独立读取 `%PDF-`、页数、尺寸、字体/文本及 SHA256；只点按钮或看到 toast 不算通过。
4. 至少覆盖 Chrome、Edge、Safari 桌面；iPhone Safari 和 Android Chrome 实机。自动模拟视口不能替代软键盘、分享/下载和系统打印验证。
5. 对公开 PDF 做有边界的并发测试，观察排队、限流、内存、超时和清理；不要对现有生产 MyTools 发压测流量。
6. 测试失败先写失败证据，再修复并重跑受影响用例；不要修改预期值掩盖功能缺失。

**完成标准：** 验收矩阵逐项带 pass/fail/blocked 和证据；没有以“未测”冒充通过的项；所有 P0/P1 阻塞关闭，必要 P2 也完成；不满足完整教材/实纸条件时不得宣布完整交付。

### M11：发布准备、发布和回滚

**操作：**

1. 输出可复现前端构建、后端 JAR、公开 runner 不可变包、资源和许可证、数据库迁移与配置变量清单。资源字体/PDF.js worker/CMap 必须在构建产物中。
2. 新域名静态资源与 `/public/v1` 同域反代，内部 PDF/Storage/Scheduler 不暴露为公开路由。静态版本文件长缓存，HTML 短缓存，个人文件响应 `no-store`。
3. 建议先部署测试环境并用两个全新浏览器验收。`/health` 不泄漏配置信息；日志只记录 requestId、错误码和必要状态，不记录 cookie、正文和内部凭据。
4. 数据库使用可回滚/向后兼容的增量迁移；数据发布采用不可变版本加 manifest 切换。不要为回滚直接删除用户生成数据或已经发布的旧教材快照。
5. 发布前保存当前静态 release、服务版本、配置引用和任务包版本；开关可独立关闭公开 PDF 提交，已有结果在可保证隔离的前提下保留读取。统计失效不关闭字帖功能。
6. 获得实际生产发布授权后，通过当前配置 SSH 先验证连接，再发布到独立目录/实例；不写出生产 secret。此文档的编写不代表已经部署，也不替代后续发布授权。
7. 发布后运行小规模 smoke：无登录访问、四个目录、四模式样张、八项 PDF、两个匿名访客隔离、真实统计、过期提示；原 MyTools 登录和 PDF 回归正常。
8. 若异常，先关闭新公开提交，再回切上一个静态/服务/教材 manifest/执行包版本，保留审计和文件清理任务。不要回滚到没有隔离校验的版本。

**完成标准：** A70—A75；发布与回滚演练有命令结果和健康检查记录；待办不隐藏。若只获开发授权，则交付完整发布包和运行手册，发布状态保持“未执行”。

## 6. 新增文件建议结构

```text
toolbox-web/
  content/{catalog,textbooks,sources}/
  scripts/{validate-curriculum,build-curriculum,verify-curriculum-coverage}.mjs
  src/config/tools.ts
  src/domain/{curriculum,selection,worksheet-snapshot}.ts
  src/services/{public-api,pdf-adapter,curriculum-api,stats-api}.ts
  src/composables/{useWorksheetDraft,usePdfTask}.ts
  src/components/{CurriculumPicker,EntrySelector}.vue
  src/components/pdf/
  src/lib/worksheet-print.ts
  tests/{curriculum,selection,snapshot,analytics}.test.ts
  tests/e2e/
service/toolbox-service/
  pom.xml
  src/main/java/com/yuyutian/mytools/toolbox/
    controller/{session,curriculum,pdf,stats}/
    service/{session,curriculum,pdf,stats}/
    mapper/{session,pdf,stats}/
    model/
    common/ErrorCode.java
  src/main/resources/db/migration/
  src/test/
docs/verification/public-toolbox/
  implementation-status.md
  curriculum-coverage.json
  release-manifest.json
  cases/<caseId>/
```

允许为清晰性调整文件拆分，但必须在状态文档记录对应关系，不改变本实施包的行为契约。新服务的配置、枚举/常量类和 public 方法按 AGENTS 补中文说明，错误码集中管理。

## 7. 构建与验收命令约定

以下前三个前端命令是现有命令；带“待新增”的脚本需在相应任务中实现。Java 模块从独立 POM 运行，不假定根 Maven 会构建所有 service。

```bash
# 从仓库根执行。
npm --prefix toolbox-web run typecheck
npm --prefix toolbox-web test
npm --prefix toolbox-web run build

# 待新增：验证已发布数据的schema、引用、审核和20册覆盖。
npm --prefix toolbox-web run test:curriculum
# 待新增：本地/测试环境浏览器验收，使用合成数据和独立测试数据库。
npm --prefix toolbox-web run test:e2e
# 待新增：汇总自动检查，不得把人工实纸未验标为pass。
npm --prefix toolbox-web run verify:release

mvn -f service/toolbox-service/pom.xml test
mvn -f service/pdf-service/pom.xml test
mvn -f service/pdf-worker/pom.xml test
mvn -f service/mytools-gateway/pom.xml test
# 改到Scheduler时必须运行对应测试。
mvn -f service/task-scheduler-service/pom.xml test
# 新增公开对象删除能力后必须运行存储回归。
mvn -f service/storage-gateway-service/pom.xml test

mvn -f service/toolbox-service/pom.xml package -DskipTests
```

检查结果必须记录退出码。构建通过不等于运行测试通过；`-DskipTests` 只用于在测试已通过后打包。依赖失败、环境缺失、真实打印机不可用分别记录，不修改系统设置或接受许可来掩盖阻塞。

## 8. 交付判定与接手 Agent 的工作方式

### 8.1 阶段可交付

- **工程联调完成：** 新 API/选择器/四模式/八项 PDF/统计在测试环境工作，fixture 验收通过；仍可明确保留内容或实纸阻塞。
- **完整需求完成：** R01—R08 对应必测全部通过，20 册数据核验完成，真实下载/打印验收完成。
- **生产发布完成：** 在完整需求完成基础上，获得发布授权并执行 M11，记录实际 URL、releaseId、smoke 和回滚证据。

不得将第一种状态描述为后两种。每个阻塞应说明缺什么、影响哪些用例、已完成什么，以及如何解除；可以并行推进其余任务。

### 8.2 可直接复制给接手 Agent 的任务说明

```text
请在 /Users/pankang/mycode/MyTools 继续完成“禹宇天的工具箱”。
先读根 AGENTS.md、service/AGENTS.md，以及
docs/design/2026-10-02-public-toolbox/implementation-handoff.md，
再读同目录 implementation-contracts.md 与 acceptance-matrix.md。

这是在 toolbox-web 已有 Vue/Naive UI 原型上继续开发，不能重写成别的框架，
不能把 MyTools 私有接口匿名放行，也不能用示例词库冒充正式教材。
按 M00—M11 的依赖顺序执行；可并行委派独立模块并明确文件所有权。
先核对工作区已有修改，保留其他任务的工作。

将进度、实际命令和证据维护在 docs/verification/public-toolbox/。
逐项实现真实教材、选题与快照、匿名PDF隔离与现有八项能力对齐、全站统计、
移动适配、下载与打印验收。不要只做计划或只修页面后就宣布完成。
外部教材来源、实机或生产配置不可用时，记录具体阻塞并推进独立工作；
未得到生产发布授权时完成发布准备，不执行生产上线。
最终按验收矩阵报告已通过/未通过/阻塞，不把未运行的检查写成通过。
```

### 8.3 最终提交清单

- 实现代码、独立 lockfile、数据迁移、配置样例（无凭据）。
- 完整 20 册覆盖表、每版来源/校对记录和不可变 manifest。
- 实际接口契约与错误码映射，生产不含模拟回退。
- 自动测试输出、各浏览器版本、响应式截图、真实下载文件、打印测量记录。
- 本地启动、测试环境部署、生产发布和回滚手册。
- 遗留事项按需求编号列出，说明是否阻塞完整交付。
