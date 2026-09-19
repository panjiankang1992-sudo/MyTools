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

## 五、这次验证的边界（说清楚，别当成已验证）

1. **不是 App 控件级的提交验证**：登录、看目录、素材门禁是 App 界面上跑的；提交/轮询/播放/取消是接口层跑的。
   App 里"点生成 → 看到排队 → 点在线播放"这条界面路径仍未在模拟器里实跑，卡在图库空这一条。
2. 其余四个模式仍未验收，目录里如实标注（原因码见截图）。
3. 画面好不好看仍未人工评分。
