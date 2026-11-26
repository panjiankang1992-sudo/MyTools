# 禹宇天的工具箱

字帖与单词页的最新交互以 [三步流程说明](../docs/worksheet-three-step-workflow.md) 为准：**数据来源 → 数据确认 → 数据预览**。它优先于旧设计中“一屏三栏”等冲突描述；[2026-10-03 修改方案](../docs/design/2026-10-03-worksheet-workflow-revision/modification-plan.md) 保留为改造背景。本 README 描述本地实现，生产发布与验收状态应查看对应发布记录。

2026-10-04 已发布简化版：常用操作保留三步，高级设置按需展开。部署与分项验收状态见 [简化版发布记录](../docs/verification/worksheet-simple-2026-10-04/RELEASE.md)；此前 [三步流程发布记录](../docs/verification/worksheet-deploy-2026-10-04/RELEASE.md) 保留作历史依据。发布成功不代表完整浏览器验收通过。

同日已更新教材选择：语文和英语的单元、课文默认不勾选，范围旁可直接“全选”或“清除选择”；切换教材后重新选择。349 项回归及线上资源校验通过，见 [教材选择发布记录](../docs/verification/curriculum-selection-2026-10-04/RELEASE.md)。

独立、无需登录的浏览器工具站。公网地址：**https://toolbox.yuyutian.top**。沿用 MyTools Web 的 Vue 3、Naive UI、UnoCSS、ECharts 与图标组件，通过独立入口、独立构建和独立部署运行。

前端不引入管理端登录、账号菜单、权限状态与业务请求客户端。PDF 文件处理在浏览器本机完成；教材目录、使用事件与全站聚合统计通过同域 `/public/v1` 由 `service/toolbox-service` 提供。

## 当前可用功能

| 页面 | 已实现 |
| --- | --- |
| 首页 | 真实全站点击／成功使用／打印发起／累计成功四项指标、7／30 天趋势、分类筛选、数据表与文字摘要、工具排行与快捷入口；统计不可用时如实提示且不影响工具使用。 |
| PDF | 图片转 PDF、文本转 PDF、基础 Markdown 转 PDF、合并、按范围或每 N 页拆分、页面左右／上下分割、转 PNG／JPG、提取文字层；结果预览、下载与多结果 ZIP。 |
| 字帖 | 教材、自定义文本、图片识别三种来源；统一确认原文、拼音、题型、选题和版式；看拼音写汉字、看汉字写拼音；真实 PDF 预览、导出、打印预览和独立答案页。 |
| 单词 | 与字帖共用三步流程，在确认页填写和校对中文释义；看中文写单词（空四线三格）、看英文写中文（汉义横线）。 |

桌面端为左侧目录，1280px 以下收为图标栏，768px 以下改为左侧抽屉。字帖与单词页在移动端仍按来源、确认、预览三个独立步骤操作；来源卡片及表单随窄屏改为单列，纸张预览缩放不改变 PDF 分页。

### 字帖与单词三步流程

1. **数据来源**：首次只显示教材、自定义文本、图片识别三张入口卡片，不自动载入演示题单。教材页选择年级、册次、版本、字表和课文范围；自定义页输入文本并选择分项方式；图片页调整图片并识别、编辑候选文本。
2. **数据确认**：三种来源进入同一页面，编辑原文、校对拼音或释义，再选择题型、数量、重复次数、标题和格距。题型与来源、教材字表类别解耦。候选内容可以追加或替换；替换已有内容需确认，返回后继续相同的最近一次来源候选不会重复追加。
3. **数据预览**：成功生成当前内容的 PDF 后进入，可翻页、缩放、导出及打开打印预览。返回确认或来源保留原文、校对和设置；内容、题型或版式发生有效变化时旧产物失效，需要重新生成。答案所需拼音或释义未完整确认时，对应答案输出不可用。

完整的输出条件、草稿恢复边界和手动验收步骤见 [三步流程说明](../docs/worksheet-three-step-workflow.md)。

## 教材与内容来源

教材内容按**版本化 JSON 发布**，位于 `content/`：

```text
content/catalog/requirements.json    # 20 个必需槽位（语文 1—6 年级、英语 3—6 年级，各上下册）
content/catalog/published.json       # 已发布版本清单，editions 元素为完整 TextbookEdition（含正文哈希）
content/textbooks/<editionId>/<contentVersion>/book.json
content/sources/catalog-sources.json # 来源与核验记录
```

截至 2026-10-04，本地 [published.json](content/catalog/published.json) 记录 **5 个已发布版本，全部为 `partial`（部分覆盖）**：

| 教材范围 | 目录记录条数 | 覆盖状态 |
| --- | ---: | --- |
| 统编语文一年级上册 | 318 | 部分覆盖 |
| 统编语文二年级上册 | 760 | 部分覆盖 |
| 统编语文三年级上册 | 777 | 部分覆盖 |
| 统编语文四年级上册 | 640 | 部分覆盖 |
| 译林版英语三年级上册 | 140 | 部分覆盖 |

条数是目录元数据，不代表整册内容完整或全部 20 个必需槽位已覆盖；其余 15 个槽位尚无已发布版本。页面展示版本、内容数量和具体缺口，按当前目录提供可用范围；无教材内容的范围可改用自定义文本或图片识别，不用示例冒充教材。线上实际目录以同域教材接口返回为准。

译林三年级上册英语内容版 `1.0.1` 已按原书 Word lists 重整：I 表 129 项按 8 个单元排列，III 表 11 项专有名词单列附录，II 表不重复收录；保留原表内的短语、句型、星号和变体说明。英语选择器直接展示所选词条与中文释义，默认不勾选。核对与发布结果见 [英语单词表修正记录](../docs/verification/english-wordlist-2026-10-04/RELEASE.md)。

自定义文本支持按空白或每行一项分词，英语短语可用引号保留为一项，例如 `"ice cream" apple`；拼音和中文释义在统一确认页校对。图片识别支持 PNG／JPEG／WebP，每张最多 10 MiB、最多 5 张，可旋转、框选及编辑识别候选后再应用。识别在浏览器本机执行，OCR 资源由本站提供；候选需人工校对。原文和校对内容保存在当前会话，只有显式保存草稿时才写入本机存储，图片不写入草稿，内容不上传服务器。

### 数据流水线

```bash
cd toolbox-web
npm run test:curriculum                       # 校验 schema、引用、拼音对齐、审核字段与哈希
npm run test:coverage -- --allow-partial      # 开发报告；不加参数时作为发布门禁，缺失即失败
npm run build:curriculum                      # 依据 textbooks 目录重建 published.json 的正文哈希
```

已发布版本不可原地覆盖：内容变化必须提升 `contentVersion`，否则构建失败。

## 字帖、PDF 与打印

- 字帖使用 A4 纵向、15 mm 边距、连续题号和页码；题目按文字、拼音及书写区的实际宽度紧凑装行，长题使用续行分组并保持题组分页一致。
- 中文格距默认 12 mm，可在确认页选择 10／12／14 mm；返回步骤时保留设置。
- 英语四线三格总高 9 mm，内部三格各 3 mm；看英文写汉义时使用横线。
- 单次最多选择 200 条、重复 1—5 次、展开后最多 500 题。标题上限 40 个 Unicode 码点，在**输入侧**提示而不是导出时静默截断。
- 题目身份与排版版本分离：改标题、格子或纸张只重新排版并递增 revision，不产生新的成功使用；改题目、模式、顺序或重复次数才形成新的操作身份。
- 学生卷与答案页由独立数据投影生成独立 PDF，答案不通过隐藏元素留在学生卷中。预览直接读取将要下载的同一份文件，并记录 `sha256`、页数、`layoutHash` 与 revision。
- 「打印预览」打开独立窗口，只放入所选 PDF 渲染后的纸张图像；系统打印对话框由用户点击触发。统计只记录打印发起，不能证明打印机实际出纸。

### PDF 工作台边界

八项能力当前全部在浏览器本机执行，页面在开始前明确显示「本机处理，不上传文件」。

| 项目 | 当前限制 |
| --- | --- |
| 输入 | 单文件 30 MiB、总计 50 MiB、最多 30 个文件；单个 PDF 与合并结果最多 100 页。 |
| 拆分／页面分割 | 输出合计最多 200 页；页面分割比例 5%—95%，可交换两半顺序。 |
| 图片 | 输入 PNG／JPG；单图最多 2400 万像素、最长边 16384，输入图片合计最多 7200 万像素。 |
| PDF 转图片 | 单次最多 30 页，72—300 DPI。 |
| 文本／Markdown | 输入最多 256 KiB；可选择 A4／A5／Letter、方向和边距。 |

Markdown 当前是基础文字排版：保留标题、列表、代码的文字内容，不绘制表格网格，不渲染原始 HTML 与外部图片。没有 OCR、Office 转 PDF、PDF 转 Word、压缩或密码解锁。加密、交互表单或损坏的 PDF 会被拒绝。结果保存在当前页面内存中，刷新后不保留。

**服务端 PDF 执行链（公开实例、匿名文件隔离与受控清理）尚未实现**，因此当前部署不提供「上传服务器处理」，能力接口也不会声明该能力。

## 统计与匿名数据

首页默认读取**全站聚合统计**（`GET /public/v1/stats`）。开发构建额外提供「本机统计」用于对照，生产构建不显示也不回退到演示数据。

- 事件只有 `eventId、eventType、category、toolId、occurredAt、operationId、schemaVersion`，不包含文件名、题单内容、题目答案或学生信息。
- 客户端批量上报到 `POST /public/v1/events`，失败时保留队列重试；同一 `eventId` 重发不重复计数，同一题单的 `worksheet_success` 只计一次，每次真实打印发起单独计数。
- 浏览器本机事件保存在 `localStorage` 的 `yuyutian-toolbox:usage:v2`，仅作开发辅助，不代表全站数据，可在首页一键清除。
- 服务端按 `Asia/Shanghai` 自然日聚合并长期保留日聚合；当天桶标记为未结束，聚合未追平时给出 `partial` 提示，缺失桶与真实 0 严格区分。
- PDF 成功使用只由服务端最终任务确认，客户端不上报 PDF 成功；当前 PDF 在本机执行，因此全站统计中不含 PDF 成功数。

## 安装与运行

建议使用 **Node.js 24 LTS**。默认安装与启动：

```bash
cd toolbox-web
npm install
npm run dev
```

开发地址为 `http://127.0.0.1:4173/`。路由使用 URL hash，例如 `/#/pdf`、`/#/pdf/merge`、`/#/copybook`、`/#/words`；旧的 `/#/pdf?tool=merge` 入口会归一化到同一页面。

开发模式下前端直接请求同域 `/public/v1`。若本地未启动后端，接口失败只会影响统计与教材目录，工具仍可正常使用。

本工具站**独立安装依赖**，不与 `webapp/node_modules` 共享目录：过去用符号链接复用框架包的做法会让 `npm install` 去执行链接包的 `prepare` 脚本（例如 echarts 的 `npm run build:lib`）而必然失败，现已废止。

```bash
npm run verify:deps        # 校验已安装版本与 package.json 精确版本一致
npm run prepare:ocr        # 生成同域 OCR 静态资产（构建前需要，产物在 public/ocr/）
```

`postinstall` 会运行 `scripts/prepare-pdf-assets.mjs`，从已安装的 PDF.js 包复制字符映射和标准字体到 `public/pdfjs/`。`public/pdfjs/` 与 `public/ocr/` 都是生成产物，已加入 `.gitignore`。

## 构建、验证与发布

```bash
cd toolbox-web
npm run typecheck          # vue-tsc 类型检查
npm test                   # 单元与集成测试
npm run test:curriculum    # 教材数据校验
npm run build:production   # 生产构建（VITE_APP_MODE=production）
npm run verify:release     # 汇总自动检查并输出证据，不把人工项标为通过
```

构建结果输出到 `dist/`。生产构建通过 `.env.production` 固定 `VITE_APP_MODE=production` 与 `VITE_PDF_EXECUTION=local`，只包含公开开关，不含任何服务端密钥。

后端服务位于 `service/toolbox-service/`，按独立 POM 构建与测试：

```bash
mvn -f service/toolbox-service/pom.xml test
mvn -f service/toolbox-service/pom.xml package -DskipTests
```

该模块不加入根 `pom.xml` 或 `service/pom.xml`。

## 字体与许可证

字帖和文字 PDF 使用随站点分发的 `public/fonts/NotoSansCJKsc-Regular.otf`，许可证为 SIL Open Font License 1.1，全文见 [public/fonts/LICENSE.txt](public/fonts/LICENSE.txt)，分发字体时应一并保留。最终 PDF 只嵌入所用字形的子集。

`src/lib/pdf-runtime.ts` 包含针对固定版本 `@pdf-lib/fontkit@1.1.1` 的 CFF 子集兼容处理；升级字体库时应保留相应回归验证。PDF.js 的字符映射与标准字体由资源准备脚本复制，不依赖第三方字体 CDN。

## 验证记录与边界

本轮实现、缺陷处置和实际执行的验证见 [三步流程修复记录](../docs/verification/worksheet-workflow-v2/fix-2026-10-03/RESULT.md)；2026-10-04 的自动化、类型、教材及依赖检查见 [发布前本地验证](../docs/verification/worksheet-deploy-2026-10-04/preflight/README.md)。这些记录分别列出执行结果和证据边界，不能替代生产浏览器验收，也不表示完整验收清单全部通过。

真实设备、实体打印、浏览器下载落盘、OCR 准确率及教材完整性等项目，仅按对应记录中的实际执行证据判定。未执行或受阻项目保持未完成状态，不以单元测试通过数或移动端模拟视口截图替代。最新手动检查步骤见 [三步流程说明](../docs/worksheet-three-step-workflow.md)。

历史实施与部署背景见 [实施状态与证据](../docs/verification/public-toolbox/implementation-status.md)。初始设计与接口依据见 [设计文档](../docs/design/2026-10-02-public-toolbox/design.md)、[实施交接文档](../docs/design/2026-10-02-public-toolbox/implementation-handoff.md)、[接口与数据契约](../docs/design/2026-10-02-public-toolbox/implementation-contracts.md)；字帖交互冲突以最新三步流程说明为准。
