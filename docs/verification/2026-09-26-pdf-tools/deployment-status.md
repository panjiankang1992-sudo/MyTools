# PDF 生产部署与验收（2026-09-26）

## 结论与开放范围

后端、独立执行器及公网 MCP 已部署，真实生产验收通过。用户已明确确认全量开放，已执行 `finalize_pdf_tools.py --publish`，**PDF 工具现已向所有已登录用户开放**。匿名访问仍返回 401，账户间文件隔离保持有效。最终状态见 [生产发布记录](./production-deployment.json)。

HarmonyOS 签名 HAP 已安装到 CopilotPerf0912 模拟器，已完成上传、中分四页、逐页预览、阅读、保存、重启历史恢复及中文文本生成验收，详见 [模拟器报告](./simulator/README.md)。物理真机尚未验收。

## 实际发布

- 主机：用户已明确授权的 `home-ubuntu`，使用本机 SSH 配置连接。
- PDF 服务：`mytools-pdf-service`，本机 23342，独立 MySQL schema `mytools_pdf`。
- 专用执行器：`mytools-pdf-executor-service`，节点 `pdf-executor-1`，集群 `pdf-processing`，并发 1。
- MCP：`mytools-pdf-mcp-service`，本机 23343，经现有网关提供 `https://mytools.yuyutian.top/api/app/v1/pdf-mcp`。
- 初始发布：`/opt/yuyutian/mytools/releases/pdf-tools-20260926-v1`。
- PDF 服务当前发布：`pdf-tools-20260926-v4`，修复 JDBC 会话时区，避免预览被清理任务提前过期；校正既有截止时间，不复活已过期对象。
- 最终 Worker：`pdf_process:1.0.1`，发布目录 `pdf-tools-20260926-v3`；V167 固定调度版本。v1/v2 保留追溯。
- 网关与调度器逐 JAR 成员核对，除 PDF 代理、认证路由、发布控制器及 V165/V166/V167 迁移外保留原成员；原主执行器未替换或重启。
- systemd 自启已配置。凭据在远端私有配置，不进入仓库、报告或提示词。

## 验收证据

- [生产 API 机器记录](./production-acceptance.json)：八项操作全部成功，真实 MySQL / Scheduler / Executor / Storage / Asset Registry，无测试替身。
- [公网 MCP 机器记录](./production-mcp.json)：HTTPS、协议握手、9 个工具、正式会话刷新、上传 URL、冻结计划、下载 URL、SHA-256 全部通过。
- Gateway 4 项 PDF 专项测试通过；调度器认证过滤测试通过；新增 Python 文件编译及 `git diff --check` 通过。

操作：PDF_PAGE_CUT、PDF_SPLIT、PDF_MERGE、PDF_TO_IMAGES、IMAGES_TO_PDF、TEXT_TO_PDF、PDF_TO_TEXT、MARKDOWN_TO_PDF。

检查：重复上传块、幂等提交、匿名 401、跨账户 404、取消、中文文字层提取、受管 Markdown 图片、鉴权下载摘要。

生产使用**纯合成两页 PDF**，中分后输出四页。已逐页渲染核对页码 9、10、11、12，中文标题及 Markdown 附件显示正常。产物在 `output/pdf/production-acceptance/`。原《第一单元》未上传生产：自动审批拒绝发送原文档，故使用合成材料验收；原文件仅在之前的本地验收中验证为四页。

## 生产修复与回滚验证

- 首次发布检查使用了不适用的执行器健康入口，触发自动回滚，原网关和调度器恢复；改为节点 ONLINE 和包能力摘要后重新激活。
- 补齐执行器私有 `tasks-report-journal` 目录权限。
- Debian JDK 安全配置经符号链接位于 `/etc/java-*-openjdk`，Worker 1.0.1 增加该目录的只读挂载，未开放整个 `/etc`。
- 独立 Python 解释器直接链接基础 Python，避免跨其他业务虚拟环境的中间链接；基础标准库与当前虚拟环境只读挂载。
- Chromium 在无网络、无服务凭据的 bubblewrap 沙箱运行；固定中文字体已验收。

## MCP 接入

采用每账户专用 Bearer，非 OAuth。专用客户端信息在远端 `/opt/yuyutian/mytools/runtime/pdf-tools/mcp/client.json`（私有权限）；不能多人共享映射。客户端应设置真实 User-Agent，例如 `MyTools-PDF-Agent/1.0`，当前公网 Cloudflare 拒绝默认 `Python-urllib` 标识。

文件传输 URL 由工具返回，使用同一 MCP 专用令牌；不把 PDF 字节或令牌放进工具参数。服务器自动刷新正式 APP 会话，refreshToken 失效或被撤销后需重新登录。

## 待完成

物理真机及其他六项操作的完整 APP UI 流程仍待补充；本轮模拟器已覆盖核心分割与文本生成链路。此前快速 API 验收未覆盖清理周期，模拟器发现的时区问题现已修复并回归。

长期方案中 Office/OCR、云文件对接、密码解锁、OAuth、超过 40 页的分页预览、存储产物物理回收仍未实现，本次不将其列为验收通过项。
