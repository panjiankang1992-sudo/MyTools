# MyTools PDF 工具：统一入口、生成、转换、处理与 MCP

日期：2026-09-26。状态：首期核心已实现并完成本地验收，尚未部署；总体方案仍含未实现项。实际接口、边界和部署条件见 [服务说明](../../../service/pdf-service/README.md)。

本文是 PDF 工具的最新总体方案，优先于此前仅针对分割的入口和接口命名；[分割详细方案](./2026-09-26-pdf-split-proposal.md)与[分割编辑器 UI](./2026-09-26-pdf-split-ui.md)继续作为子能力规格。分割已有旋转坐标、权限、异步任务、资产发布、取消与验收要求全部保留。

## 1. 产品范围与统一入口

APP 工具页新增一个 **PDF 工具** 入口，标识 `pdf-tools`，副标题“生成、转换、分割与合并”。不把每一种转换铺满一级工具页面，也不新增底部 Tab。

入口层级：`工具 → PDF 工具 → 具体能力 → 配置与预览 → 后台任务 → 结果`。从云文件 PDF 的“用工具处理”可直接进入子能力，但仍使用同一流程与 API。

PDF 工具主页按用户意图分四区：

| 分区 | 能力 | 主要场景 |
|---|---|---|
| 生成 PDF | 图片生成、文本/Markdown 生成、模板生成 | 照片成册、笔记、报告 |
| 转成 PDF | Word、Excel、PPT、HTML 转 PDF | 分享、打印、固定版式 |
| PDF 转出 | 转图片、提取文本、转 Word/Excel、OCR | 导出页面、编辑、识别扫描件 |
| 整理 PDF | 页面裁切、文件拆分、合并、旋转/重排 | 试卷拆页、资料整理 |

“生成 PDF”默认指排版和文件生成，不默认调用 AI 写内容。Agent 可提交已生成的文本或结构化内容，同样经过模板与排版规则。

主页只展示通过后端能力检查且对当前用户开放的工具。设计图展示目标布局，不表示所有能力首发上线。

## 2. 功能矩阵与阶段

| operation | UI 名称 | 输入 → 输出 | 阶段/限制 |
|---|---|---|---|
| `PDF_PAGE_CUT` | 页面裁切 | PDF → PDF | P0，左右/上下、比例、选页、顺序 |
| `PDF_SPLIT` | 拆分文件 | PDF → PDF/ZIP | P0，页范围、每 N 页、逐页 |
| `PDF_MERGE` | 合并 PDF | 多 PDF → PDF | P0，按明确文件与页顺序 |
| `IMAGES_TO_PDF` | 图片生成 PDF | JPG/PNG → PDF | P0，多图排序、纸张、边距、适应方式 |
| `TEXT_TO_PDF` | 文本生成 PDF | UTF-8 TXT/受限内联文本 → PDF | P0，字体、标题、页码、换页 |
| `MARKDOWN_TO_PDF` | Markdown 生成 PDF | MD + 受管附件 → PDF | P0，表格、代码、图片；不执行原始 HTML/脚本 |
| `PDF_TO_IMAGES` | PDF 转图片 | PDF → PNG/JPG/ZIP | P0，页范围、分辨率、背景 |
| `PDF_TO_TEXT` | 提取文本 | 有文字层的 PDF → UTF-8 TXT | P0，不包含 OCR；保持分页标记，不保证语义阅读顺序 |
| `OFFICE_TO_PDF` | Word/Excel/PPT 转 PDF | DOCX/XLSX/PPTX → PDF | P1，字体和排版专项验收后开放 |
| `HTML_TO_PDF` | HTML 转 PDF | 本地 HTML + 受管资源 → PDF | P1，无外部 URL 抓取、无任意 JavaScript |
| `TEMPLATE_TO_PDF` | 模板生成 PDF | 已发布模板版本 + JSON 数据 → PDF | P1，报告/清单，版本不可变 |
| `PDF_REORDER` | 旋转与重排 | PDF → PDF | P1，单页旋转、移动、删除 |
| `PDF_OCR` | 扫描件识别 | PDF → 可搜索 PDF/TXT | P2，独立语言和质量评估 |
| `PDF_TO_DOCX` | PDF 转 Word | PDF → DOCX | P2，实验能力，不承诺还原版式 |
| `PDF_TO_XLSX` | PDF 转 Excel | PDF 表格 → XLSX | P2，表格区域、合并单元格、数值校验 |

首期即交付生成、双向基础转换与整理三个实用方向；Office 转 PDF 单列第二阶段，避免用单一 PDFBox 引擎假装支持全部格式。若用户优先 Office，可调整排期，但完成条件不降低。

暂不纳入：任意公网网页转 PDF、PDF 转 PPT、自动去水印、签名编辑、脱敏。旧 Office 二进制 DOC/XLS/PPT、宏文档 DOCM/XLSM/PPTM、动画图片、HEIC 等须单独验证，P0/P1 默认拒绝而非猜测支持。

## 3. 技术方案与调研结论

[iLovePDF 官方工具目录](https://www.ilovepdf.com/)按方向提供 Office/PDF/图片等工具，可借鉴“转入/转出”命名；不能据其工具列表推导某个开源库也能完成相同转换。

| 引擎 | 责任 | 选择理由与边界 |
|---|---|---|
| Apache PDFBox 3.x | 裁切、拆分、合并、图片生成、PDF 渲染、文本抽取、结果检查 | Java 主栈；不承担 Office 编辑版式重建。[官方能力](https://pdfbox.apache.org/) |
| 受限 Markdown 解析器 + 固定 HTML/CSS 模板 + Chromium | 文本/Markdown、后续 HTML/模板生成 | 统一中英文排版、表格、代码与打印样式；Playwright `page.pdf` 可控制纸张、边距等。[官方 API](https://playwright.dev/docs/api/class-page#page-pdf) |
| LibreOffice headless | DOCX/XLSX/PPTX → PDF | 官方 CLI 支持转换/导出筛选器，独立任务 profile；不能仅凭进程退出码判定成功。[启动参数](https://help.libreoffice.org/latest/en-GB/text/shared/guide/start_parameters.html)、[PDF 导出参数](https://help.libreoffice.org/latest/en-GB/text/shared/guide/pdf_params.html) |
| OCR/版面分析适配器 | 扫描件、PDF → 可编辑文档 | 后续专项选型；只在验收通过后进入 capabilities |

Pandoc 可以生成 PDF 和转换多种文档，但其官方 FAQ 明确 PDF 不是通用可读取源格式；不采用“Pandoc 万能双向转换”的设计。[官方 FAQ](https://pandoc.org/faqs.html)

### 3.1 进程隔离与可复现

仍为一个 `pdf-service` 业务服务，内部按 operation 选择受控 worker，不为每个按钮创建微服务。所有任务继承现有 Scheduler/Executor 生命周期，任务包版本和引擎版本固定。

- PDFBox Worker：Java 21；Python 薄包装器接入 SDK 和任务上下文。
- Browser Worker：固定 Chromium/Playwright 和模板版本、字体包、时区/语言；专属浏览器上下文，硬超时、页数与内存上限。
- Office Worker：每任务独立 `UserInstallation` 和目录，低并发，禁用宏/外部链接刷新；不与用户桌面 Office 共用进程/profile。
- 子进程按参数数组启动，禁止用户提供命令、过滤器名、可执行文件路径或启动参数。
- HTML/Markdown 中的 script、iframe、事件属性、外部 CSS、网络字体、`file://` 和外部资源一律禁用。图片仅由 `attachmentId` 映射到该任务受管资源；进程网络与文件可见范围双重隔离。
- Chromium 等待本地字体/图片完成后导出；字体缺失、资源缺失返回警告或失败，不能静默用方框替代文字。
- Office 输入可能是 ZIP 容器，应限制解压比、条目数、嵌套层级；对生成的 PDF 重新解析、检查实际页数和渲染。

引擎不同的结果文件大小可能不同。保存 engine/template/font 版本以便排查，不承诺二进制逐字节相同。

## 4. 各能力参数与质量边界

### 图片生成

输入为有序 `inputIds[]`，一个文件一张静态图片，默认一图一页。选项：A4/A5/原图尺寸、纵向/横向/逐页自动、边距、`FIT`（完整显示留白）或 `FILL`（铺满可能裁边）。默认 FIT；FILL 必须在预览中标出被裁部分。

先应用 EXIF 方向，PNG 透明区域默认白底，保留原图优先；可选压缩必须明确质量影响。JPEG 可在支持时直接嵌入，其他编码不要笼统称为“完全无损”。总像素与单图解码内存均有限制。

### 文本/Markdown 生成

小文本可内联提交，建议 UTF-8 上限 256 KiB；更大内容走上传。标题、A4/Letter、字体白名单、字号、页边距、页码可配置。UTF-8 TXT 默认按纯文本转义；Markdown 使用明确方言，代码块不执行。

首期定义标题、列表、表格、代码块和图片支持；公式、脚注与复杂 HTML 按能力字段标示，不自动承诺。长表格允许跨页并重复表头；长代码按设置折行，禁止溢出纸面。中文字体使用有部署授权的固定字体包。

### Office 转 PDF

Word 默认文档自身版式；PPT 按幻灯片，是否含备注作为独立能力；Excel 必须显示工作表选择、打印区域和缩放策略“沿用打印设置/所有列适应一页宽”。禁止默认把整张长表缩到一页导致不可读。

字体替换、分页变化和公式结果可能与 Microsoft Office 不同，预览中展示检测到的警告；外部数据不刷新，不声称还原了源系统的数据计算结果。

### PDF 转图片/文本

图片默认 PNG、150 DPI，开放 72～300 DPI，额外控制长边和每页像素上限；例如 A4 300 DPI 约 2480×3508，实际按页尺寸计算。超像素上限拒绝或要求降分辨率，不静默改变用户选项。多页提供逐页图片和 ZIP，manifest 标明文件页序。

文本抽取对无文字层的页面返回 `OCR_REQUIRED`/逐页警告；混合扫描/文字文件不能把遗漏页面的空文本当作完整成功。用户明确允许部分提取才发布带 incomplete 标记的产物。阅读顺序与表格结构不能保证，默认不启用不存在的 OCR。

## 5. 统一后端 API

前缀统一 `/api/app/v1/pdf`，领域服务 `/internal/v1/pdf`。所有任务使用 **operation + 类型化参数**，保留一个 `/jobs` 创建接口，不设计 `/convert-anything` 的无约束参数透传。

统一流程：`capabilities → inputs → inspect → plans → jobs → artifacts`。生成任务的输入可以是受限文本/模板数据，不能强制先有 PDF documentId。

| 接口 | 统一职责 |
|---|---|
| `GET /capabilities` | operation、接受类型、输入数、参数 schema、是否支持预览、配额、引擎状态 |
| `POST /uploads`、`PUT /uploads/{id}/content`、`POST /uploads/{id}/complete` | 通用受控上传，完成返回 inputId；不再固定仅接受 PDF |
| `POST /inputs/import` | 授权云文件固化为输入，返回 inputId |
| `GET /inputs/{id}` | 检查状态、检测类型、页/图/表信息、warnings |
| `POST /inputs/{id}/unlock` | 加密 PDF/后续支持文档的受控解锁 |
| `POST /plans`、`GET /plans/{id}` | 校验 operation 与 inputs/options，冻结计划、预览与预计产物 |
| `GET /plans/{id}/pages`、`GET /plans/{id}/pages/{index}/thumbnail` | 输出页面分页预览，沿用原方案 |
| `POST /jobs`、`GET /jobs/{id}`、`POST /jobs/{id}/cancel` | 各能力统一执行、查询与取消 |
| `GET /jobs?operation=...` | 统一任务历史，按类型筛选 |
| `GET /jobs/{id}/artifacts`、`GET /artifacts/{id}/content` | PDF、图片、TXT、ZIP 等类型安全的产物 |
| `POST /artifacts/{id}/download-tickets` | 受权限约束的短期下载 |
| `GET /templates`、`GET /templates/{id}/versions/{version}` | P1，只读已发布模板和数据 schema |
| `DELETE /inputs/{id}`、`DELETE /jobs/{id}/artifacts` | 延续原清理/租约与引用保护规则 |

原方案 `/documents` 是尚未实现的 PDF 专属草案，本次替换为 `/inputs`；无需同时实现两套路由。input 的 PDF 元数据中可保留 document 模型。旧 `PAGE_CUT` 等草案操作名统一为上述 operation 枚举，分割轴、选页、顺序规则不变。

统一计划示例：

```json
{
  "operation": "IMAGES_TO_PDF",
  "inputIds": ["input-uuid-1", "input-uuid-2"],
  "options": {
    "paperSize": "A4",
    "orientation": "AUTO",
    "fit": "FIT",
    "marginMm": 10
  },
  "output": {"name": "photos.pdf"},
  "clientRevision": 2
}
```

```json
{
  "operation": "MARKDOWN_TO_PDF",
  "content": {"format": "MARKDOWN", "text": "# Weekly report\n\nSummary."},
  "attachmentIds": [],
  "options": {"paperSize": "A4", "fontPreset": "CJK_SANS", "pageNumbers": true},
  "output": {"name": "report.pdf"},
  "clientRevision": 1
}
```

各 operation 对 `inputIds/content/template` 使用互斥的 schema 分支；无关字段拒绝。输入文件真实类型匹配操作白名单，不能靠扩展名或客户端 `Content-Type` 放行。

`POST /jobs` 只接受已就绪 planId 与幂等键。plan 冻结有序输入摘要、参数、引擎/模板/字体版本；输入租约保留到任务收敛。

生成和 Office 的预览本身可能消耗完整转换成本：在 plan 阶段生成有界 staging 结果、只开放缩略图；正式 job 复核摘要/版本后可复用 staging，避免重复转换。暂存有独立 TTL、配额与清理，不因没有点“开始”无限保留。

结果统一返回 jobId、operation、status、warnings、artifacts[]。artifact 至少有 id、mediaType、filename、bytes、sha256、expiresAt 和适用的 pageCount/imageDimensions。输出类型由服务端确定；下载使用正确 Content-Type 与 nosniff。

## 6. 数据与任务扩展

原 `pdf_upload/pdf_document/pdf_plan/pdf_job/pdf_artifact/pdf_outbox` 草案调整为：

- 增加 `pdf_input` 作为多格式输入；`pdf_document/pdf_document_page` 为 PDF 专属检查结果。
- `pdf_plan` 增加 operation、ordered_input_manifest、typed_options、template/font 版本和 preview_artifact_ref。
- `pdf_job` 增加 operation、engine_family；不为每种格式单建 job 表。
- `pdf_artifact` 明确 media_type、role（PRIMARY/PAGE_IMAGE/TEXT/ARCHIVE）、来源映射。
- 所有 owner、幂等、outbox、fencing、引用与保留规则继承分割方案；跨用户相同内容不能共享访问权。

不记录正文、表格数据、Office 内容、文档密码。操作摘要记录数量、类型和引擎版本即可。

能力按 worker 实际可用性开放。PDFBox 正常但 LibreOffice 不可用时，仅 Office 转换标为不可用，不关闭整个 PDF 工具。配额按 operation 区分；多图聚合大小、总像素、Office 解压资源、输出总字节都应计费/计额，不能仅限单文件上传大小。

## 7. 统一 MCP 服务

服务名建议 `mytools-pdf-tools`，stdio 与受鉴权 Streamable HTTP 共用应用层。传输、协议兼容与 OAuth 要求沿用原方案。

统一工具：

| 工具 | 能力 |
|---|---|
| `pdf_get_capabilities` | 发现可用的生成/转换/整理操作与参数 |
| `pdf_prepare_upload`、`pdf_complete_upload` | 多格式受限上传，返回 inputId |
| `pdf_import_input`、`pdf_get_input` | 云文件导入与检查 |
| `pdf_create_plan`、`pdf_get_plan` | 使用严格 operation schema，预览与冻结 |
| `pdf_start_job`、`pdf_get_job`、`pdf_cancel_job` | 通用任务生命周期 |
| `pdf_get_result`、`pdf_create_download_ticket` | 结构化产物/资源链接与下载 |
| `pdf_list_templates` | P1，生成模板发现 |

原 `pdf_start_split/pdf_get_document` 等未发布名称由通用名称替代；不同时暴露几十个含糊转换工具。工具描述给出操作示例，避免模型混淆方向。schema 中提供 operation 的判别分支，不允许任意透传 options。

示例意图映射：

- “把这两张图片生成 PDF” → `IMAGES_TO_PDF`，按用户指定或上传顺序。
- “把这份 Markdown 生成 PDF” → `MARKDOWN_TO_PDF`。
- “把 Word 转 PDF” → 先发现 `OFFICE_TO_PDF` 是否可用，不能退化为纯文本然后假报转换成功。
- “把 PDF 每页转 PNG” → `PDF_TO_IMAGES`，返回图片清单和 ZIP。
- “把试卷左右切开” → `PDF_PAGE_CUT`。

不额外调用 LLM，不自动将文件发第三方转换平台。PDF/Office/Markdown 的文字只是输入数据，不能作为工具执行指令；生成内容中的 URL 不自动抓取。

## 8. APP 入口和页面设计

入口图见 [PDF 工具主页示意](./pdf-tools-entry.svg)。保持当前 AppTheme 绿色主题，48 vp 触控区；二级页面隐藏底部导航，返回时恢复工具搜索/滚动位置。

```text
工具
  搜索工具
  最近使用
  全部工具
    文件管理   压缩解压
    PDF 工具   …

PDF 工具                         任务记录
  搜索 PDF 功能
  生成 PDF
    图片生成       文本 / Markdown
  转成 PDF
    Word           Excel           PPT
  PDF 转出
    转图片         提取文本
  整理 PDF
    页面裁切       拆分文件        合并
```

首页只开放已启用项；不把未实现的 OCR/转 Word/Excel 伪装成可点击按钮。可用性暂时变化显示“暂不可用”及原因；长期未发布功能直接不展示。

### 图片生成页

多图网格展示序号，长按拖动排序，添加/移除图；选择纸张、方向、边距与 FIT/FILL。底部摘要“6 张图片 → 6 页 PDF”，按钮“预览 PDF”。默认图像不裁切。允许重新进入选择器继续添加，但不扫描系统相册。

### 文本/Markdown 生成页

“输入内容 / 导入文件”切换；明确纯文本与 Markdown 模式，标题可选。手机不做复杂富文本编辑器；编辑区支持粘贴、字数提示与预览。附件通过用户主动选择上传，Markdown 图片引用绑定附件，不任意联网。

模板生成 P1 先选模板，再展示 schema 驱动字段，不能让用户上传任意模板脚本。用户文本草稿默认仅保留页面会话，保存草稿应是显式操作。

### 转换页

顶部固定方向，如“PDF → 图片”“Word → PDF”，选择文件后只出现该模式相关参数。Office 显示字体/工作表/打印设置警告。图片导出选择页范围、PNG/JPG、清晰度；避免让普通用户先理解 DPI，可提供“屏幕/清晰/打印”并展示对应数值。

PDF 提取文本遇扫描页显示“第 3、5 页没有文字层，需要 OCR”，不把空文本当完成；可以返回、改范围，或在未来 OCR 可用时明确转入识别任务。

### 统一结果页

按 artifact 类型呈现：PDF 阅读、图片网格、TXT 预览、多文件清单/ZIP。显示实际大小和到期时间，提供保存设备/云文件；不自动公开分享。不在产物真正写入前提示“保存成功”。

结果底部“继续处理”复用本人的输出资产建立新 input，仍检查有效期与权限；每一步有独立 plan/job，首期不做任意 DAG 编排。

## 9. 实施增量与验收

先交付统一能力目录和任务契约，再实现 P0 各引擎适配；分割已有设计作为第一条验证链。新增 APP `PdfToolsHomePage`、`PdfGeneratePage`、`PdfConvertPage`，共用 `PdfToolsApi/Models/JobResultPage`。Index 只接一个 `pdf-tools` 路由。

原分割方案的 16～25 人日估算不覆盖新增功能；本次范围扩展后需重新估算。新增 P0 的生成/转换、多输入预览和 UI 联调建议额外 6～10 人日，Office P1 再额外 4～7 人日；均为未压测的工程估算，OAuth 差距另计。

新增验收：

1. 图片顺序、EXIF、透明度、FIT/FILL、混合尺寸、超像素与恶意图片。
2. 中英文/特殊字符、长文本、长表格、代码溢出、Markdown 注入和附件越权。
3. PDF 输出逐页渲染检查，图片导出像素/DPI正确、ZIP 完整、提取文本不漏报扫描页。
4. Office 缺字体、打印区域、隐藏表、公式缓存、外部链接、损坏 ZIP、超时、profile 互不干扰。
5. 每个 operation 的 REST 与 MCP 同样输入产生一致计划语义，禁止通过 MCP 绕过配额/特性检查。
6. 无 Chromium/LibreOffice、后台恢复、输入过期、取消发布竞争、跨账号历史和结果访问。
7. 手机真实选择/排序/生成/预览/保存，平板分栏，读屏和大字体。

本轮产物是总体方案更新和入口设计，不代表后端接口、MCP 或 APP 功能已经上线。
