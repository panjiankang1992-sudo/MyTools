# 虚拟机（HarmonyOS 模拟器）验证

2026-09-19 07:37–07:40（UTC+8），在本地 HarmonyOS 模拟器 `CopilotPerf0912`
（HarmonyOS 6.1.1，API 24，`hdc -t 127.0.0.1:57838`）上安装签名 HAP 并逐屏走查。
App 的后端地址是生产域名 `https://mytools.yuyutian.top`，所以这一轮走的是**真实生产链路**：
App → 公网入口 → 网关 `/api/app/v1/video-generation/**` → video-generation-service。

## 怎么操作的

沿用仓库既有的 VM 验收方式（`hdc shell uitest dumpLayout` 取布局 → 按文本定位控件 →
`uitest uiInput click` 点击 → `snapshot_display` 截图）。App 用 `-r` 覆盖安装，保留原有登录态。

## 看到的（截图在本目录）

| 截图 | 验证到的点 |
|---|---|
| `01-tool-home.jpeg` | 工具入口「视频生成 / 文字、图片或视频生成短视频」可进入；页内显示 `Wan 2.1 VACE 1.3B · 本机运行 · 已就绪`、`固定输出 832 × 480 · 49 帧 · 16 fps`、`1 / 3 个模式已验收`；三入口卡片 **图片动起来=可用**、文字创作/视频改编=**未验收**（灰色）；模式列表里 **单图首帧=已验收**，双图主体参考与首尾帧过渡为灰色并直接写出原因码 `REFERENCE_CONTROL_UNVERIFIED` / `MODE_VALIDATION_REQUIRED` |
| `02-entry-text-unvalidated.jpeg` | 切到「文字创作」：`0 / 1 个模式已验收`，文生视频显示 `未验收，禁止提交 · 原因码 INSTRUCTION_FOLLOWING_UNVERIFIED`，提示区写明「文字创作」下还没有通过验收的模式 |
| `03-entry-video-unvalidated.jpeg` | 切到「视频改编」：`0 / 2 个模式已验收`，视频整体重绘 `SOURCE_QUALITY_UNVERIFIED`、视频局部修改 `LOCALITY_DISPUTED`，同样禁止提交 |
| `04-create-material-and-submit-disabled.jpeg` | 单图首帧下的素材槽位是**按角色**生成的「首帧图片（必填 · PNG/JPEG，服务端实测不超过 20 MB）」；未选素材时 **「生成视频」按钮呈禁用态**，下方写明约束 `需要 1 - 1 个素材 · 固定 832 × 480 · 49 帧 · 16 fps`；描述框标注必填且「不会自动扩写或上传外部服务」 |
| `05-works-empty-owner-isolated.jpeg` | 「我的作品」显示 `0 条 / 还没有视频作品`。**这一条同时验证了所有者隔离**：库里属于 admin（owner=1）的 3 条验证任务是可见的（其中 1 条 SUCCEEDED），但当前登录账号看不到任何一条——ownerId 由服务端从登录态派生，客户端拿不到别人的作品 |

补充：点未验收模式卡片不会有任何动作（卡片是 `enabled(false)`），原因码已直接印在卡片上，
因此不存在"点了没反应又不知道为什么"的情况。

## 这一轮**没有**验证的（重要）

1. **没有提交真实任务**：仓库的 VM 验收约定是不在 VM 上产生内容（改编脚本明确写着"不提交改编"），
   所以加载中/排队/取消/进度这些状态没有在 App 上实跑。
2. **没有验证成片播放**：播放需要当前账号有一条已成功作品，而这个账号一条都没有（见上）。
   `在线播放`（票据 + Range 边下边播）与 `对比原片` 两个按钮因此没有出现，也就没被点击过。
   播放链路的证据目前来自别处：服务端 Range 在生产上实测 206/416 且区间内容与整段逐字节一致、
   网关票据路由在生产上 401/404 行为正确、网关 15 项用例（真实本地 HTTP 上游）覆盖转发与票据绑定。
   另外「对比原片」只在源视频模式（视频改编）下出现，而该模式尚未验收，本账号也无法触发。
3. **没有做人工观感评审**：画面好不好看、运动够不够仍未评分（`reviews.json` 仍为 `pending`）。

## 复现方式

```bash
E=/Applications/DevEco-Studio.app/Contents/tools/emulator/Emulator
HD=/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc
"$E" -start CopilotPerf0912            # 首次启动需写 ~/.Huawei/Emulator，沙箱外
"$HD" tconn 127.0.0.1:57838            # 端口以 Emulator 实例实际监听为准
"$HD" -t 127.0.0.1:57838 install -r app/entry/build/default/outputs/default/entry-default-signed.hap
"$HD" -t 127.0.0.1:57838 shell "aa start -a EntryAbility -b com.yuyutian.mytools"
"$HD" -t 127.0.0.1:57838 shell "uitest dumpLayout -p /data/local/tmp/l.json"
"$HD" -t 127.0.0.1:57838 file recv /data/local/tmp/l.json /tmp/l.json
"$HD" -t 127.0.0.1:57838 shell "snapshot_display -f /data/local/tmp/s.jpeg"
```
