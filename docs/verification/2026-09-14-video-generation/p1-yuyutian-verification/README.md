# yuyutian 账号验证（虚拟机 + 生产链路）

按你的要求，用 **yuyutian** 账号做验证。结论先说：**账号在 App 里登录成功，视频生成全链路以该账号跑通并产出成片，
Range 播放也在真实会话下验过**；唯一没能在模拟器里跑的是 App 的"选图"那一步（模拟器图库为空且无法注入图片），
因此提交动作改由 App 背后的同一批接口驱动。

## 一、虚拟机里做到的（截图与后台证据）

| 步骤 | 证据 |
|---|---|
| 在 App 登录页填入 `yuyutian` 并提交 | identity 库里该账号会话 `last_seen_at` 从 07:34 刷新到 **2026-09-19 14:20:51**，说明是一次真实登录 |
| 登录后的界面 | `after-login-submit.jpeg`（右上角头像变成绿底 `Y`）、`tool-page-yuyutian.jpeg`（视频工作台：`Wan 2.1 VACE 1.3B · 已就绪`、`1 / 3 个模式已验收`、单图首帧=已验收、其余带原因码） |
| 素材与提交门禁 | `create-slots.jpeg`：素材槽位是按角色生成的「首帧图片（必填 · PNG/JPEG ≤ 20 MB）」，未选素材时「生成视频」为禁用态并写明 `需要 1 - 1 个素材 · 固定 832 × 480 · 49 帧 · 16 fps` |

模拟器操作过程中踩到的坑（都记下来，避免下次重复浪费）：

1. **`uitest dumpLayout` 默认合并窗口**，会把别的窗口节点混进来导致点击落错；要用 `-m false`，并按
   `hostWindowId` 定向 dump。
2. **`uitest uiInput inputText` 的正确用法是 `inputText <x> <y> <text>`**；只传文本会退化成"在已聚焦处输入"，
   而点击并不会真的聚焦输入框。
3. **弹出键盘会把表单上移**，坐标必须每次重新取；而且**键盘会盖住「登录」按钮**，点击被键盘吃掉，
   必须先收起键盘再点提交。
4. **密码框/安全键盘期间 `snapshot_display` 拍到的是全黑帧**（系统的安全输入保护），不是显示故障。
5. 重装 App 后曾有一个存活 46 小时的旧进程加载了不一致的字节码而 abort
   （`cppcrash-...-20260919135300565.log`：`index_header is invalid, from method: GetProtoIndex`），
   它让 a11y 树与屏幕内容长期不一致；`aa force-stop` 后重新启动才干净。

## 二、模拟器**没能**做的一步：选图

`PhotoViewPicker` 打开后直接返回（没有窗口弹出），根因是模拟器图库为空，且注入图片的路都被堵死：

- `/storage/media/100/local/files/Pictures` 对 `shell` 用户 `Permission denied`，`hdc file send` 同样被拒；
- 设备上没有 `su`（`/bin/sh: su: inaccessible or not found`）；
- `mediatool` 只有 `recv/query/ls/delete`，**没有导入（send）**；
- 设备里没有相机应用（`bm dump -a` 只有 `com.huawei.hmos.photos` 与 `appgallery`），无法拍照入相册。

因此"在 App 里选一张图然后点生成"这一步在当前模拟器上无法完成。为了不让验证停在这里，
提交改为用 **yuyutian 的真实会话**调用 App 背后的同一批接口（App 的页面只是这些接口的界面层）。

## 三、以 yuyutian 会话跑通的生产链路

脚本 `/tmp/host-verify.py`（在 Ubuntu 上直连网关 `127.0.0.1:23200`；公网入口被 Cloudflare
Error 1010 拦掉了 Python 的 UA，App 走系统 HTTP 栈不受影响）。完整输出见 `verification.log`：

| 步骤 | 结果 |
|---|---|
| 登录 | `200`，拿到 access/refresh 令牌，`sessionId` |
| 能力目录 | `wan-vace-1.3b-local` = `READY`，**唯一 validated 模式 = `FIRST_FRAME`** |
| 上传首帧图 | `200`，952822 字节，sha256 前缀 `712c98518e3c`（与 P0 用的那张原图逐字节相同） |
| 创建任务 | `200`，任务 `0e5a60b8-8acd-4b7a-a646-844ce1b697d9`，状态 `QUEUED` |
| 状态流转 | `14:26:39 QUEUED → 14:26:49 RUNNING → 14:34:59 SUCCEEDED`，`elapsedMillis=497000`（8分17秒，P0 同模式 483–489 s） |
| 结果 | `{frames:49, fps:16, durationMs:3063, width:832, height:480, hasAudio:false}` |
| 封面 | `200`，286310 字节 PNG |
| **播放票据 + Range** | 签发 32 位票据 → `GET /tickets/<token>`（**免登录**，播放器实际走的路）返回 **`206` + `Content-Range: bytes 0-1023/159252` + `Accept-Ranges: bytes`**，且字节以 `ftyp` 开头 |
| 作品列表 | `得到 1 条作品`，包含本次任务且状态 `SUCCEEDED`（即"退出后仍可查看"） |
| 取消路径 | 另建一条任务后立即取消 → 状态 `CANCELLED` |

## 四、顺带得到的结果：同输入同种子是**逐字节可复现**的

本次（yuyutian，14:34）与 9-19 00:07 那次（admin）用的是同一张原图、同一段提示词、同一个 seed，
两次产出的 **`video.mp4` 完全相同**（sha256 `d0642d4c7dbb…`），封面 PNG 的**解码像素也完全相同**
（像素 md5 `16cff7974b6a…`；文件字节不同只是因为 ComfyUI 在 PNG 的 `tEXt` 块里写了每轮不同的运行信息）。

## 五、用 MyTools 生图当首帧（本轮新增能力）

上一节说的"选图"卡点，按你的要求改成了**用 MyTools 自己生成/已生成的图片**：视频工具的首帧槽位新增
「用我的图片作品」入口，点开后列出当前账号的图片作品，选中即把该图下载下来、按原始字节上传给视频服务，
直接当首帧使用。这样不必先把图存进设备相册。

为什么不做"保存到相册"：`ohos.permission.WRITE_IMAGEVIDEO` 是系统级权限，普通应用拿不到；
本套 SDK 又没有 `SaveButton` 安全控件（`@ohos.arkui.advanced.SaveButton.d.ts` 不存在），
而 `showAssetsCreationDialog` 同样标了该权限，所以第三方应用在本机 SDK 上无法写媒体库。
图片工具原有的「保存图片」（DocumentViewPicker 存成文件）仍然可用，只是相册选择器看不到 Downloads 里的文件。

虚拟机实测（全部在 App 界面上点完）：点「我的作品」弹出作品面板 → 选一张 →「用作素材」→ 填描述 →
「生成视频」，后端随即出现属于 yuyutian 的新任务。证据：`05-imagework-panel.jpeg`（面板列出该账号作品）、
`07-slot-filled.jpeg`（槽位已填：我的作品 / 重新选择 / 清除，提示"已用图片作品作为素材"）、
`08-app-submitted.jpeg`。本次 App 提交的任务是 `db51f053-0ef7-4deb-93ca-c2369aa7a3ec`。

同一批接口在 yuyutian 会话下的完整链路（含计时与 Range）也已单独验过：

| 步骤 | 结果 |
|---|---|
| 生图 | `e4c8522e-18ea-4a44-871e-f908054528b2`：`QUEUED → RUNNING → SUCCEEDED`，**60 秒** |
| 下载该图 | `200`，934489 字节 PNG |
| 作为视频首帧上传 | `200`，`3d7b9b9c-dcb9-452a-8679-a11cc20162d3` |
| 视频任务 | `edf2035b-26f5-42b4-a807-42bd139c136f`：`15:40:51 QUEUED → 15:41:01 RUNNING → 15:49:12 SUCCEEDED`（491 s） |
| 结果 | 49 帧 / 16fps / 3062ms / 832×480 |
| 票据 + Range | `206`、`Content-Range: bytes 0-1023/171807`、`ftyp` |
| 作品列表 | 图片 12 条、视频 3 条（含本次两条） |

产物在 `gen2video/`（`verification.log`、`generated-first-frame.png`、`cover.png`、`video.mp4`）。

## 六、本轮暴露并修掉的生产回归（我自己引入的）

**症状**：App 里生图排队 5 分钟后失败，`image_job.error_code = IMAGE_008`；
任务库里是 `TaskTimedOut / TASK_DISPATCH_DEADLINE_EXCEEDED`。

**根因**：`image_generate` 步骤钉的是 **1.1.2**，但我在做视频发布时把脚本包根基线取成了
`config/image-generation.env` 里的旧值（image-production 的包根，只有 `image_generate 1.0.0`），
而线上执行器实际用的是 `image-extension-20260914-v3` 的包根（有 1.1.1/1.1.2）。
节点声明不出 1.1.2，调度器就永远派发不出去。

**修复**：
1. 发布包根补齐 `image_generate 1.1.1/1.1.2` 并重建索引（134 个包）；
2. 修 `deploy_video_generation.py`：基线改为从**运行中执行器的环境变量**取实际包根
   （`current_script_root()`），并并入历史里程碑包根里基线缺失的新版本；
3. 增加发布前预检 `assert_pinned_packages_indexed()`：已启用任务定义钉的每个
   `包:版本` 必须在索引里，否则 `stage()` 直接失败（这次是 103 个被钉版本全查）。
4. 修完重启执行器时踩到自己的坑：手工 `copytree` 出来的包目录是 `root:root`，
   执行器用户读不到，索引校验直接失败、服务崩溃重启循环；已按发布脚本的方式收敛为
   `root:mytools` + 目录 750 / 文件 640。

修复后：执行器 `ONLINE`，声明 134 个包（含 `image_generate:1.1.1/1.1.2` 与 `video_generate:1.0.0`），
生图重新可用（见上一节的 60 秒成功）。

## 七、新发现的质量缺陷：模型会把补边涂成蓝色（未修，待你决定）

首帧模式的补边在**控制输入**里是中性灰（`vace_control.PAD_COLOR_HEX = 0x808080`，
`padColor` 也写进 manifest），但**成片里这两条边是蓝色的**，而且随输入变化：

| 素材 | 左边条像素（RGB） | 亮度 |
|---|---|---|
| P0/P1 那张白底产品图（p1-live-run） | (125,126,131) ≈ 中性灰 | 0.496 |
| 本轮 Krea 生成的青瓷茶壶图（接口提交那条） | f0 (26,109,186)、f24 (23,115,207) 偏蓝 | f0 0.36 |
| **同一张生成图、App 提交那条（描述略不同）** | 成片两侧**偏黄**（见 `09-...jpeg` 上方卡片） | 约 0.6–0.7 |

同一张素材在两次不同描述的运行里一次偏蓝一次偏黄，说明这两条边完全是模型自由发挥的结果，
和"中性灰补边"的既定决定不符；`09-app-job-playback-and-colour-drift.jpeg` 里两条成片并排就能直接看出来。

也就是说：模型对"参考图之外、只给了灰补边"的区域是自由发挥的，遇到暖色调素材时会漂成蓝色。
P0 当时的边缘亮度判据（0.4998–0.5304）是**因为那张素材恰好是白底**才通过的，换成暖色素材就会失败——
首帧模式的验收结论实际上**依赖输入**，这一点之前没有暴露。

建议（择一，需要你定）：
1. 出片后按已知几何把两侧补边区**合成回 0x808080**（改动小，和"保持中性灰补边"的既定决定一致）；
2. 增加"裁切到素材比例"的输出模式，让补边不进入成片；
3. 维持现状，但在能力目录里如实标注"补边颜色可能随素材漂移"。

无论选哪种，修完都应重跑一次 P0 窗口（暖色 + 冷色两张素材）再给首帧模式下定论。

## 八、界面回归：素材行"窜行"已修

加了「我的作品」按钮后，素材行把角色与规格文字挤成了一列竖排（`Text` 被压到 ~90px 宽）。已改为
**标签独占一行、按钮另起一行**（`Flex` 换行 + 按钮外边距），并把作品面板做成贴着按钮的紧凑内联块
（高度 150），点完即可见。设备上复验：`07-slot-filled.jpeg` 里三按钮（我的作品 / 重新选择 / 清除）
与说明文字都不再换行挤压。

顺带记两个 SDK 结论，避免再走弯路：`bindSheet` 与 `bindPopup` 在本机 SDK + 外层 `Scroll` 结构下都不渲染
（`SaveButton` 安全控件也不存在），所以面板只能用内联块；`LengthMetrics` 在该 SDK 只能当类型用，
Flex 间距改用按钮外边距实现。

## 九、这次验证的边界（说清楚，别当成已验证）

1. **App 控件级的"选作品 → 用作素材 → 提交"已在模拟器里走通**（见七、八两节与截图）；
   App 提交的任务 `db51f053-0ef7-4deb-93ca-c2369aa7a3ec` 也已在 App 里播到结尾（`04:18` 截图，进度 00:03/00:03）。
2. **不是 App 控件级的提交验证**：登录、看目录、素材门禁是 App 界面上跑的；提交/轮询/播放/取消是接口层跑的。
   App 里"点生成 → 看到排队 → 点在线播放"这条界面路径仍未在模拟器里实跑，卡在图库空这一条。
2. 其余四个模式仍未验收，目录里如实标注（原因码见截图）。
3. 画面好不好看仍未人工评分。
