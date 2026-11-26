# DSH 手机布局适配

适配已实测的 DSH 0.2.1-rc.2。`mobile.css` 负责页面布局，`mobile.js` 负责窄屏侧栏、可视区域及 randomUUID 兼容。

`20261122a` 为 DSH 临时消息显示服务端确认状态。等待 20 秒后提示尚未收到确认，确认状态随上游临时标记移除。新版 MyTools 使用系统文件和相册选择器。此前的窄屏工具栏及附件菜单调整见 [DSH Web 能力设计](../../docs/design/2026-11-19-dsh-web-parity.md) 和 [单行工具栏历史记录](../../docs/verification/2026-11-14-dsh-composer/README.md)。

`20261122b` 增加公网附件分片传输。ArkWeb 的图片和文件使用 DSH 提供的上传钩子，超过 256 KiB 的二进制请求按片传输。带内联图片的大 JSON 消息也走同一中继。已收到的分片保留在当前网页会话内，提交意图先持久化，再一次性转交 DSH。结果丢失时，使用同一编号核对服务器回执。网页会话换票或关闭页面后，重新发送需要建立新的传输。

`20261123b` 调整窄屏输入区。加号打开文件和指令菜单时保持软键盘关闭，点输入框仍可继续输入。底部统计减少留白，只显示能在窄屏完整呈现的主要数据，点统计项仍可查看详细信息。

部署需同时处理：

| 仓库文件 | 服务端目标 |
| --- | --- |
| mobile.css | /var/www/mytools-dsh-mobile/mobile.css |
| mobile.js | /var/www/mytools-dsh-mobile/mobile.js |
| ../nginx/dsh-lan.conf | /etc/nginx/sites-available/dsh-lan |

先备份目标文件，写入适配资源，再更新 Nginx 配置；`nginx -t` 成功后才 reload。失败恢复原文件。不要改动认证 Cookie、内部授权密钥、MyTools 换票接口或上游 DSH 安装包。验证静态资源 200、匿名业务页面仍 401，并检查真实手机页面。

公开资源与受限生产配置目录分开存放。当前 Nginx 以 `www-data` 运行，不能读取 `/opt/yuyutian/mytools/config`；不要通过放宽该目录权限解决静态资源访问。重载后等待新工作进程开始服务，再检查资源状态和摘要。Cloudflare 公网缓存策略可能覆盖源站 `no-cache`，每次更改内容都必须更新 HTML 引用中的版本参数。

`20261126a` 为 DSH 插件组合包缓存版本。DSH 的 `/plugins/??...&rev=` 响应是一年 `immutable`，URL 只带按文件时间派生的 `rev`；升级 DSH 后 `rev` 可能不变，手机 WebView 会继续使用旧包，表现为文件预览报“文件资源服务不可用”（0.2.1 的 workspace-files 客户端没有 `file` 资源 provider，0.2.2 才有）。Nginx 入口给组合包 URL 追加 `mytools=20261126a`，**每次升级 DSH 必须把该参数加一**，见 [验证记录](../../docs/verification/2026-11-26-dsh-plugin-cache/README.md)。

启用分片中继时，将 `upload.js` 安装到适配资源目录，将 `../dsh-upload/server.py` 安装到 `/opt/mytools-dsh-upload/server.py`，并安装同目录下的 systemd 套接字、服务和 Nginx 注入片段。验证 unit 后仅启用 `mytools-dsh-upload.socket`，再检查并重载 Nginx。回滚时先还原公网 Nginx 入口，再停中继，保留状态目录供未完成请求核对。

新版 App 的外部窗口中转需要安装对应 HAP；浏览器 CSS 更新不能代替客户端修复。

版本升级后先在本机预览，再重新检查登录、全部设置分类（含登录后的账号与余额）、模型表单、插件详情、自动化、320px/390px/横屏/桌面。CSS 中构建类名失效时，应重新确认 DOM，不能只放宽全局选择器。


## 返回与验证框架

App 调用 `window.mytoolsDshMobileBack()`，返回 true 表示消费当前浮层；false 表示回到 MyTools 上一个主标签。App 与资源必须配套更新。脚本不导航到旧网页票据，也不读取账号或模型数据。

`tests/back-harness.html` 只用于同源本机预览：iframe 保留 DSH 焦点，底部测试按钮在 pointerdown 时调用同一个返回函数。不要把该测试页部署到生产。浏览器中依次检查二级菜单、嵌套弹窗、右侧文件面板与左侧抽屉，再完成真机系统返回验收。


## 第三轮完整性检查

见 `docs/verification/2026-11-02-dsh-mobile/round3/` 的覆盖表和当前运行截图。适配资源版本为 `20261102d`。

- DockKit 不能按 `disabled` 状态隐藏分栏按钮，否则测量宽度可能在两种状态间循环并触发 React #185。标签行和工具行使用稳定尺寸。
- 小屏插件组件说明与状态分行；开关不能继承普通按钮的最小高度；短屏次级对话框整体滚动。
- App 使用 ArkWeb 的 WebDownloadDelegate 处理会话日志等下载，保留网页 Cookie，并调用系统保存器；离页/切换账号取消。来源策略与下载生命周期有单独测试。
- 本轮文件上传当时依赖 SDK 默认选择器。新版选择器沿用 DSH 原附件输入及上传流程。麦克风声明仅用于 DSH 主 HTTPS 页的音频请求，仍需系统授权。DSH 默认优先 HTTPS，局域网备用入口受 HTTP 安全上下文限制。
- 普通主框架 HTTPS 外链转给系统浏览器，不替换 DSH 主会话。票据、同源下载和 Blob 单独验证。
- 上游桌面能力（打开服务器配置文件、NodeJS DevTools、桌面伴侣窗口）不能声称是手机原生能力。服务端语音模型准备和大肥鱼 Host 连接另列为前置条件；本轮不安装或修改服务端插件。

## 第四轮：终端触控与文件预览

本节记录当时发布的资源版本 `20261103a`。当前 CSS 为 `20261114b`，JS 保持 `20261103a`；仅更新网页样式，不要求重新安装 App。此前发布预览记录见 `docs/verification/2026-11-03-dsh-mobile/round4/`。

- 终端聚焦时添加一条可横向滚动的快捷按键栏：Tab、Esc、Ctrl+C、Ctrl+L、方向键、Home 和 End。每条按键栏只向所属且仍挂载的终端 textarea 发送键盘事件；弹窗打开时拒绝后台终端输入；没有自动执行命令或读取输入内容。
- 通过事件委托按需增强终端，不引入随聊天输出反复扫描全页的 MutationObserver。桌面尺寸下隐藏按键栏并拒绝遗留按键事件。
- 图片、PDF 和 Office 的缩放按钮为 44px；比例保持单行，工具条按内容定宽；滚动区域为底部缩放条留出 76px。HTML iframe 去除短屏最小高度，保持上游 sandbox 不变。
- 合成样本验证了 PNG、两页 PDF、HTML 和 DOCX。当前上游对 MP4/WAV 显示不支持预览，此限制单独记录，不归因于手机布局。
- 本机内置浏览器不能显示测试 Blob iframe，HTML 应使用 Chrome 复核；不要为绕过预览工具限制移除 sandbox 或放开同源访问。

检查入口和样本说明见 [tests/README.md](tests/README.md)。后续部署只复制 `mobile.css`、`mobile.js` 和 Nginx 候选配置，不复制 tests。
