# 下载聊天工具验收（2026-09-26）

最新补测结论见 [EXTENDED.md](EXTENDED.md)：图库视频、512 MiB 后端文件、ZIP、磁链重试通过；APP 公网 512 MiB 上传速度未通过，测试已停止。

工具页增加“下载工具”入口，保持三列图标布局。进入后为独立聊天页面：右侧用户消息、左侧机器人回复、底部输入框和发送按钮，“＋”提供图片/视频与本地文件选择。支持发送失败重试、上传进度、取消任务、恢复最近 30 条记录。键盘弹出时标题和输入框仍可见。

## 后端路径

APP → 身份网关 `/api/app/v1/download-tools` → Download Ingestion → 现有 Task Scheduler/Executor → Storage Gateway、Asset Registry、Media Intelligence。

- 链接复用 QQbot 的 `MESSAGE_URL_BATCH` 与 `download_message_url_batch`，不是转发一条 QQ 消息。
- 上传文件只使用服务端生成的托管地址，图片和视频直接走 `download_storage_object`，跳过外链下载，然后复用现有入库、标签及归档步骤。
- 压缩包复用 `LOCAL_IMPORT`。普通文本无匹配时给出无匹配回复，不创建任务。
- 单文件 512 MiB；上传块 1 MiB；每账户最多 10 个未完成上传；24 小时过期。
- Gateway 注入可信 owner；内部 Bearer 认证；跨账户查询返回 404；消息、上传块及完成操作幂等。

接口：`GET /history`、`POST /messages`、`POST /uploads`、`PUT /uploads/{id}/content`、`POST /uploads/{id}/complete`、`GET /messages/{id}`、`POST /messages/{id}/cancel`。
消息体 `{text,idempotencyKey}`，上传初始化 `{name,bytes,idempotencyKey}`，上传块使用 `X-Upload-Offset`。遵循 APP 的 code/data 信封。

## 已完成验收

- HarmonyOS 虚拟机 `127.0.0.1:5555`：入口、普通消息发送/回复、链接发送、通过本地文件选择器发送合成 PNG、重新打开页面恢复历史。
- PNG 实际完成 `SUCCEEDED` / `TAGGED`，标签为山、太阳、草地，产生正式托管归档地址。
- 后端真实接口：example.com 链接、合成 MP4 均 `SUCCEEDED` / `TAGGED`，结果在 APP 中展示。
- 系统图库的图片/视频选择器正常打开。图库没有本次合成素材，因此未从图库选择素材提交；图片提交由本地文件选择器完成，视频提交由正式 HTTP 接口完成。
- 匿名访问、跨账户读取被拒绝；重复文本、重复上传块、重复完成操作通过。
- Python：58 tests + 13 subtests passed（包含现有 MCP 回归）。网关：8 tests passed。APP 签名构建通过，无 WARN/ERROR。
- 仅使用合成图片、合成视频及公开 example.com 页面。没有上传用户文档。

证据：`acceptance.json`、`python-tests.log`、`gateway-tests.log`、`app-build.log`；截图 `05-text-success.png`、`12-upload-result.png`、`13-tools-final.png`、`15-final-replies.png`、`20-keyboard-final.png`、`23-photo-picker.png`。`build.json` 记录安装包摘要。

## 部署与回滚

通过本地配置的 SSH 通道部署，未使用飞书。既有 QQ 下载服务源码摘要已核对后增量覆盖；没有修改任务定义或调度数据库。

- 网关：`/opt/yuyutian/mytools/releases/download-chat-20260926-v1/mytools-gateway.jar`。
- Ingestion：`/opt/yuyutian/mytools/releases/download-chat-20260926-v2/python`；v2 兼容含连字符的用户名，归档任务使用稳定 `owner_{id}` 名称。
- 网关 systemd drop-in 为 `zzzzzz-download-chat.conf`，须排在既有 PDF 配置之后。
- 私有配置 `config/download-chat.env` 与 `config/download-chat-gateway.env`，权限 0600。网关的 `DOWNLOAD_INGESTION_TOKEN` 对齐实际服务 `DOWNLOAD_INTERNAL_TOKEN`，不回显值。
- 运行目录 `runtime/download-chat`，私有 SQLite 保存聊天条目与上传元数据。运行包通过 PYTHONPATH 携带项目 Storage SDK。
- 两个服务均 active，健康端点 UP；实际登录后的消息/上传验收通过，不能只以进程健康作为路由验收。
- 回滚：将仓库 `service/deploy/deploy_download_chat.py` 上传到 `/tmp/deploy_download_chat.py` 后，执行 `sudo python3 /tmp/deploy_download_chat.py --rollback`。脚本移除两个本次 drop-in，恢复此前网关及 QQ 下载服务并检查健康；保留历史数据和归档文件。

本轮没有验证所有外站下载规则、磁链账号、压缩包格式与 512 MiB 大文件。其后端使用既有流程。本轮新增的是 APP 聊天入口；原下载 MCP 服务保持不变。
