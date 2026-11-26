# 管理页五尺寸截图（目标第 3 项的一部分，2026-10-07）

## 结论

**3 个管理页 × 5 个宽度 = 15 张真实浏览器截图，全部 `横向溢出 0px`。**
另有 3 张我标为 `dark-` 的文件**已删除** —— 理由见下"深色未达成"。

| 页面 | 路径 | 尺寸 |
| --- | --- | --- |
| 教材列表 | `#/admin/textbooks` | 320×720 / 375×812 / 768×1024 / 1280×900 / 1440×900 |
| 新建教材 | `#/admin/textbooks/new` | 同上 5 个 |
| 编辑教材 | `#/admin/textbooks/local-acceptance-2026-10-07/edit` | 同上 5 个 |

文件在 [screenshots/](screenshots/)：`light-<页面>-<宽>x<高>.png`，另有 `report.json`
逐张记录 `theme/page/size/file/overflowPx`。

**实测的横向溢出全部为 0**（`documentElement.scrollWidth - clientWidth`）——
这是**测量值**，不是"看起来没问题"。

## 怎么绕开"CDP 真实鼠标点击不稳定"

两条线都报告过：同一坐标的真实鼠标点击**有时渲染进程完全收不到**
（`document` 捕获阶段计数为 0，而 `elementFromPoint` 命中该按钮且无遮挡），成因未定位。

**截图不需要交互**，所以脚本走的是：

1. 用 `curl` 登录，取会话 Cookie（`toolbox_admin_local`）；
2. 启动无头 Chrome + CDP，`Network.setCookie` 注入该会话；
3. `Emulation.setDeviceMetricsOverride` 设定视口 → `Page.navigate` → `Page.captureScreenshot`。

**全程零点击**。可重跑：
```bash
cd toolbox-web
E2E_TARGET=http://127.0.0.1:4173 ADMIN_COOKIE='toolbox_admin_local=<值>' \
  node tests/e2e/local-admin-screenshots.mjs
```
脚本**强制 `E2E_TARGET` 必须是 `http://127.0.0.1:<端口>`**，否则退出码 2（不误连生产）。

## 截图本身验证了什么（这是本次最有价值的部分）

`light-editor-1280x900.png` 与 `light-list-1280x900.png` 一图之内可核对本轮多项交付：

| 在图上可见 | 对应交付 |
| --- | --- |
| **预览题型下拉：「汉字字帖」** | 四题型选择（目标第 2 项）|
| **「重新补全空白」按钮** | 代次守卫测试所需的 UI 入口（本会话新增）|
| **「修改摘要（可选，写入版本记录）」** | changeNote |
| **「当前范围：整册」+「预览整册内容」** | 如实标注范围，**没有假装过滤** |
| **「表类别与来源」区块 +「按表类别筛选」** | 多类别 UI |
| 列表页 **「恢复导入包」按钮** | 恢复入口 UI（目标第 8 项）|
| 列表页 **真实名称 +「部分收录」+「已发布/草稿」** | F12 三条界面信息 |
| 真实内容 **山/shān、水/shuǐ、「修订 7」** | 编辑器真在编辑本地真实教材 |
| 管理员 **`管理员 · local-admin`** | 本地假身份（仅测试源集）|

## ⚠️ 深色未达成，如实记录

我先按 `Emulation.setEmulatedMedia({features:[{name:'prefers-color-scheme',value:'dark'}]})`
拍了 3 张并命名为 `dark-*`，**随后发现它们实际是浅色** —— 该应用用**自己的深色开关**
（`App.vue` 里的 `dark` ref）而不是跟随系统 `prefers-color-scheme`。

**因此我把那 3 个文件删掉了**（保留会让人以为深色已覆盖），
并**不声称深色主题已验收**。要覆盖它需要真实点击那个月亮/太阳开关 ——
而点击正是上面那条未定位的不稳定路径，所以这一项**留作未达成**。

## 未达成项

| 项 | 状态与原因 |
| --- | --- |
| **深色主题截图** | ❌ 未达成。应用用自带开关而非系统偏好；切换到它需要真实点击，而 CDP 真实鼠标点击不稳定（成因未定位）|
| **键盘路径**（Tab 顺序、焦点陷阱、`Escape` 关弹窗） | ❌ 未做 —— 需要真实按键事件序列 |
| **44px 命中区实测** | ❌ 未做 —— 只看了截图，**没有量过**命中区尺寸，所以不声称满足 |
| 目标第 2 项剩下的**打印预览 / 窗口关闭即取消** | ❌ 见 [four-mode-pdfs.md](four-mode-pdfs.md) |
