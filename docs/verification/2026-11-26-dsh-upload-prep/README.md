# DSH 选图到进度条之间的等待

2026-11-26，用户反馈 App 内给 DSH 选完照片后，到附件进度条出现之间明显变慢；上传本身不是主要等待段。

## 现状与原因

- 系统相册选择器把 `file://media/photo/...` 原图 URI 直接交给 ArkWeb，网页侧再通过 `<input type=file>` 读取整份文件。手机原图常见 10–15 MB，读取、拷贝和随后 DSH 的 `URL.createObjectURL` + 全尺寸解码探尺寸都发生在附件进度条出现之前。
- DSH 只在 `beginFileUpload` 之后才写入上传状态，因此上面的耗时没有任何界面反馈。
- 同一份原图随后才进入上传；公网入口对超过 256 KiB 的请求走分片中继，传输时间另算。2026-11-25 20:48 的一次图片上传递交字节为 2,402,762，中继转发耗时 346 ms，但手机到中继的耗时没有被记录。

## 修复

`DshWebFileChooser` 在把文件交回 ArkWeb 之前，对相册图片做一次本机预处理：

- 使用 ImageKit 读取图片信息；只处理 `image/jpeg`、`image/png`、`image/heic`、`image/heif` 这类可重新编码的静态位图。
- 长边超过 2560 或体积超过 2 MiB 时，等比缩放到长边 2560；JPEG/HEIC 输出 JPEG（质量 88），PNG 保持无损 PNG。
- 输出写入应用缓存，用 `fileUri.getUriFromPath` 得到沙箱 URI 交回 ArkWeb。华为文档明确 `handleFileList` 接受 file 协议路径与应用沙箱目录 URI。
- 处理开始时弹一次“正在压缩图片…”提示，填补进度条出现前的空白。
- 单次选择最多预处理 4 张；其余文件原样交给网页。
- 任一环节失败、格式不在白名单、或文件本来就不超阈值时，保留原图 URI，不阻断附件流程。

## 验证

| 检查 | 结果 |
| --- | --- |
| HAP 构建（`hvigorw assembleHap`） | 通过；仅保留既有的 READ_PASTEBOARD 剪贴板告警 |
| ArkTS 类型检查 | 通过 |
| `handleFileList` 沙箱 URI 支持 | 华为《使用 Web 组件上传文件》明确支持 file 协议路径与沙箱目录 |
| 失败回退 | 代码路径在解码、编码、写文件异常时返回原 URI |
| 真机 | 待安装本次 HAP 后复测：大图选图到进度条的时间、JPEG/PNG/HEIC 各一张、以及发送后的图片预览 |

构建产物：`app/entry/build/default/outputs/default/entry-default-signed.hap`（52 MB，2026-11-26 11:31）。

## 回退

移除 `DshImageUploadPreparation.ets`、恢复 `DshWebFileChooser.ets` 与 `string.json` 中 `dsh_web_upload_preparing` 即可；服务端和网页资源不需要改动。
