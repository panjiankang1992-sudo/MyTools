# 电子书界面与交互验收

更新时间：2026-08-15

## Source truth

- 电子书首页：`/Users/pankang/.codex/generated_images/019ff016-e8dc-7791-9a6e-091a4b309673/exec-b32da925-3ef8-4eb2-b881-947aecd580f3.png`
- 图书详情：`/Users/pankang/.codex/generated_images/019ff016-e8dc-7791-9a6e-091a4b309673/exec-daa8a62d-6c5e-487a-bb52-83489830c6c8.png`
- 用户确认：顶部顺序为书源、远程、本地；搜索框右侧是搜索；添加能力只在打开具体电子书后提供。
- 用户确认：正常阅读、中部点击操作栏、设置面板是同一个阅读页面的三个状态。

## Current implementation evidence

- 模拟器：`127.0.0.1:5555`，`1320 x 2856`物理像素，保留系统状态栏与手势区。
- 首页：`build/acceptance/ebook-audit-20260815-post/10-home-title-final.jpeg`
- 远程目录：`build/acceptance/ebook-audit-20260815-post/03-remote-loaded-post.jpeg`
- 来源滚轮：`build/acceptance/ebook-audit-20260815-post/04-source-sheet-post.jpeg`
- 远程详情：`build/acceptance/ebook-audit-20260815-post/11-detail-title-final.jpeg`
- 正常阅读：`build/acceptance/ebook-audit-20260815-post/06-reader-post.jpeg`
- 阅读操作栏：`build/acceptance/ebook-audit-20260815-post/08-reader-controls-fixed.jpeg`
- 阅读设置：`build/acceptance/ebook-audit-20260815-post/09-reader-settings-post.jpeg`
- 首页合并比较：`build/acceptance/ebook-audit-20260815-post/home-comparison-final.jpg`
- 详情合并比较：`build/acceptance/ebook-audit-20260815-post/detail-comparison-final.jpg`

源图为`852 x 1846`，验收时等比缩放到实现高度后与`1320 x 2856`模拟器截图拼接。首页数据状态不同：源图是有书架/有书源，真实账号当前为零书源/空书架，因此不对动态书封和进度作伪精度比较。

## Interaction verification

| 场景 | 结果 | 健康度 |
| --- | --- | --- |
| 书源/远程/本地切换 | 三段控件可点击，顺序与约定一致 | 通过 |
| MyTools远程目录 | 真实`EBOOK`目录成功加载，分页列表可滚动 | 通过 |
| 远程来源选择 | 单一入口打开底部滚轮，三个来源完整可见 | 通过 |
| 标题与标签 | `_tags_…_user`传输字段被清理，多标签进入列表与搜索 | 通过 |
| 图书详情 | 显示清洗书名、格式、来源、大小、时间、标签和固定操作栏 | 通过 |
| 详情后添加 | 列表点击只打开详情，加入书架仍由详情主按钮触发 | 通过 |
| 远程TXT试读 | 真实8.2MB TXT成功下载并解析为74页 | 通过 |
| 中部点击操作栏 | 并行轻触手势不再被正文/分页组件吞掉 | 通过 |
| 阅读设置 | 从同一阅读页操作栏进入，字体、字号、行距、主题、翻页和方向可见 | 通过 |
| OPDS详情后添加 | 代码路径与策略测试通过，缺少当前运行的公开测试目录 | 受限 |
| EPUB/PDF/MOBI/CBZ逐格式视觉 | 本轮只完成真实TXT运行态 | 待补 |

## Findings

- [P1] 远程书缺真实封面、作者、简介、章节数和字数。当前文件信息已真实展示，但仍不能达到源图内容丰富度。需要后端电子书元数据索引与封面接口。
- [P1] EPUB、PDF、MOBI和CBZ尚未用同一运行环境逐格式完成视觉与交互验收，不能从TXT结果外推全部格式。
- [P2] 空书架与源图有书状态不可同态比较。实现空态操作清晰，但有书状态仍需真实书架数据验证继续阅读卡和四列书架。
- [P2] 来源滚轮解决了横向截断；当来源数量显著增长时还需要在弹层增加名称搜索。
- [P2] 详情页当前用原生文件图标表达缺封面状态；在后端没有真实封面前，不生成与书籍内容无关的伪封面。

## Required fidelity surfaces

- 字体与层级：页面标题、详情标题已进一步收紧；正文保持可读字号并尊重系统字体缩放。长书名最多三行并省略。
- 间距与布局：三段入口、搜索、来源选择、文件列表、详情信息卡和固定底栏已形成稳定手机单栏节奏；触控区保持48vp。
- 颜色：延续浅灰背景、白色表面和蓝色主色；阅读器使用纸张主题，设置面板保持同一令牌体系。
- 图像与资产：真实账户头像正确显示；没有用代码绘图或无关图片伪造书封，缺封面使用原生系统文件图标。
- 文案与内容：远程文件名传输字段不再暴露；标签、大小、来源和更新时间来自真实后端数据。

## Comparison history

1. 前一轮P0：真实远程TXT因`Content-Encoding`被拒绝，阅读器不可用。
   - 修复：请求强制`Accept-Encoding: identity`，仍拒绝服务端意外压缩响应。
   - 证据：`06-reader-post.jpeg`显示真实正文和`1/74`页。
2. 前一轮P1：来源胶囊横向截断。
   - 修复：改为单一来源入口和底部`TextPicker`滚轮。
   - 证据：`04-source-sheet-post.jpeg`完整显示三个来源。
3. 前一轮P1：原始远程文件名直接显示，详情缺标签/大小/时间。
   - 修复：新增纯元数据策略并把清洗标题、多标签、文件信息接入列表、搜索和详情。
   - 证据：`03-remote-loaded-post.jpeg`和`05-detail-post.jpeg`。
4. 前一轮P0：正文中部点击不能稳定唤起操作栏。
   - 修复：阅读内容容器改用并行轻触手势。
   - 证据：`08-reader-controls-fixed.jpeg`和`09-reader-settings-post.jpeg`。

## Final result

final result: blocked

阻塞原因：远程TXT核心闭环已经通过，但真实封面/深层元数据以及EPUB、PDF、MOBI、CBZ四格式运行态仍存在P1验收缺口。
