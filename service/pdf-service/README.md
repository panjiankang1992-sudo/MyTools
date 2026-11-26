# PDF 工具服务

Java 21 / Spring Boot 独立服务，默认监听 `127.0.0.1:23342`。不加入根 Maven reactor。APP 统一入口为“工具 → PDF 工具”，MCP 使用相同账户 API。

## 已实现的首期核心

- `PDF_PAGE_CUT`：左右/上下切页、5%～95% 分割位置、逆序、指定页；未选页面原样保留。裁切不是脱敏。
- `PDF_SPLIT`：逐页、每 N 页、指定页组，保留最后不足 N 页的文件；多文件带 ZIP。
- `PDF_MERGE`：按输入顺序合并。
- `IMAGES_TO_PDF`：JPG/PNG，EXIF 方向、白底透明合成、A4/A5、FIT/FILL、边距。
- `TEXT_TO_PDF` / `MARKDOWN_TO_PDF`：内联内容或 UTF-8 文件、指定中文字体、纸张、页码、表格和代码块；Markdown 图片只接受已授权 `attachmentIds`，语法 `![caption](attachment:input-uuid)`。
- `PDF_TO_IMAGES`：PNG/JPG、72～300 DPI、页范围、ZIP。
- `PDF_TO_TEXT`：文字层抽取和分页符。任一所选页缺少文字层就返回 `PDF_013`，避免把扫描件当作成功空文本。

APP 包含分类入口、文件选择/排序、可拖动分割线、参数、冻结预览、历史恢复、保存设备、PDFKit 阅读和中断/账户切换保护。Office、OCR 等未实现操作不显示。

## 运行边界

`pdf_record` 的 INPUT/PLAN/JOB 分别表示上传、冻结计划和已发布结果。QUEUED 状态兼任事务 Outbox；Scheduler 幂等键固定为 `pdf:<workId>`。Worker 以任务 ID 与递增 fencing token 领取和提交，取消和过期执行不能覆盖终态。用户级数据库行锁串行化幂等/配额修改。

计划阶段完成实际运算、验证、预览并暂存产物，点击开始只发布已验证的冻结结果，避免二次转换。修改参数必须创建新计划。上传和文件名不会成为 shell 参数或任意上游 URL。

输入最大 100 MiB；每账户预留上传最多 500 MiB、活动工作 10 个、每天最多 30 个计划；输入 500 页，输出 1000 页，100 个文件，单页 3000 万像素/长边 16000，产物合计 500 MiB。多输入合计仍不超过 100 MiB。上传使用最多 1 MiB 分块及显式偏移，重复块必须逐字节相同。

输入租期 24 小时，计划 30 分钟，发布结果可访问 24 小时。到期拒绝下载并清理本地上传；**目前 Storage Gateway 产物的物理回收仍需运维保留策略，不能将访问过期理解为已物理删除**。

加密、签名、表单、页面注释文档当前明确拒绝。密码解锁、云文件导入/保存、输出继续处理、匿名下载票据、完整 OAuth、分页加载超过 40 页的预览仍是总体方案中的待完成项。本版本不开放对应空接口，不宣称整个长期方案已全部完成。当前下载始终鉴权；产物标识为 jobId + artifact index。

## API

APP：`/api/app/v1/pdf`。内部：`/internal/v1/pdf`。APP JSON 返回标准 `{code:"0000",message:"OK",data:...}`，二进制下载流式转发。内部接口使用独立 Bearer 凭据，owner 仅由已验证的 Gateway 主体注入，Worker 路由不向 APP 开放。

| 方法与相对路径 | 用途 |
|---|---|
| GET `/capabilities` | 真正启用的 operation 和限制 |
| POST `/uploads` | `{name,bytes,mediaType}`；必填 `Idempotency-Key` |
| PUT `/uploads/{id}/content` | 原始字节；`X-Upload-Offset`，最多 1 MiB |
| POST `/uploads/{id}/complete` | 封存并排队检查 |
| GET `/inputs/{id}`、`/inputs/{id}/thumbnail` | 检查状态、首屏 |
| POST `/plans` | `{operation,inputIds?,content?,attachmentIds?,options,output?,clientRevision?}`；稳定幂等键 |
| GET `/plans/{id}`、`/plans/{id}/pages/{index}/thumbnail` | 冻结结果、前 40 张预览，index 从 0 开始 |
| POST `/plans/{id}/cancel` | 取消后台处理 |
| POST `/jobs` | `{planId}`；稳定幂等键 |
| GET `/jobs/{id}`、`/jobs?page=0` | 本人结果和已发布历史 |
| GET `/history?page=0` | 包含待完成/失败计划，便于 APP 重进后恢复 |
| POST `/jobs/{id}/cancel` | 已发布结果保持真实终态 |
| GET `/jobs/{id}/artifacts/{index}/content` | 本人结果下载，index 从 0 开始 |

`PDF_PAGE_CUT` options 示例：`{"axis":"VERTICAL","position":0.5,"reverse":false,"ranges":[[1,2]]}`。
`PDF_SPLIT` options 示例：`{"pagesPerFile":5}` 或 `{"groups":[[[1,3]],[[4,8]]]}`；组与每 N 页/统一范围互斥。
`output:{"name":"report.pdf"}` 只影响结果显示文件名；真实扩展名由服务端确定，多文件时作用于 ZIP。内部存储路径始终服务端生成。

错误码：001 参数/页范围、002 类型、003 大小/像素、004 密码、005 权限、006 不支持的文档特性/附件、008 状态或幂等冲突、009 暂不可用、010 校验失败、012 过期、013 需要 OCR、014 认证、015 不存在/跨账户、016 配额。

## 构建和发布材料

从仓库根目录执行：

```sh
mvn -f service/pdf-worker/pom.xml test
mvn -f service/pdf-service/pom.xml package
python3 service/pdf-service/build_package.py --destination service/pdf-service/packages --prepare-source
python3 service/scripts/assemble_executor_packages.py --output /absolute/new/executor-release
```

内核 JAR 与 SHA-256 位于被 git 忽略的包 `lib/`。组包器检查 `required-files.txt`，拒绝缺少内核的发布包。发布目录及 package-index 一旦发出不可修改；修改运行逻辑后必须发布新版本并切换 Scheduler 定义。独立暂存也可用 `build_package.py --destination /absolute/new/staging`。

运行需要 MySQL 独立 schema `mytools_pdf` 和最小权限账户。Flyway 自动建表。配置键见 `src/main/resources/application.yml`，不在仓库写任何真实密码或令牌。

| 进程 | 必需配置 |
|---|---|
| PDF 服务 | `PDF_DB_HOST/PORT/NAME/USER/PASSWORD`、`PDF_ROOT`、`PDF_INTERNAL_TOKEN`、`PDF_WORKER_TOKEN`、`TASK_BUSINESS_PDF_TOKEN`、`TASK_SCHEDULER_URL`、`STORAGE_GATEWAY_URL`、`STORAGE_INTERNAL_TOKEN` |
| Gateway | `PDF_SERVICE_URL`、`PDF_INTERNAL_TOKEN`、`PDF_ROUTE_ENABLED=true`，可选 `PDF_OWNER_ALLOWLIST`；`PDF_MCP_ROUTE_ENABLED=true`、`PDF_MCP_URL` |
| Scheduler | `TASK_BUSINESS_PDF_TOKEN`，迁移 V165 注册禁用集群/任务，V166 创建发布审计，V167 固定 Worker 1.0.1 |
| Executor | `PDF_SERVICE_URL`、`PDF_WORKER_TOKEN`、Storage/Asset 服务 URL 与令牌、`PDF_STORAGE_ROOT`、`PDF_JAVA_BINARY/HOME`、`PLAYWRIGHT_BROWSERS_PATH`、`PDF_FONT_FILE` |

Ubuntu Worker 必须有 Java 21 或更高版本、Python 3.12、bubblewrap；安装任务包 requirements 并将匹配的 Chromium 安装在 `PLAYWRIGHT_BROWSERS_PATH`。配置有授权的固定中文 TTF/OTF 文件并通过中文/分页验收后才开启 `PDF_TEXT_ENABLED=true`。字体摘要及引擎版本写入结果。Linux 没有 bwrap 时失败关闭；离线计算进程无网络，不继承服务凭据，只读运行时/字体/包、读写专属任务目录。不要把本地测试开关用于生产。

按现有受管流程将 Executor 加入 `pdf-processing` 并设置 `pdf.processing=enabled` 标签，限制并发 2。确认包摘要、字体、存储、任务领取和取消后显式启用任务/集群，再设置 `PDF_ENABLED=true`，最后开启 Gateway 灰度。2026-09-26 已部署并完成真实链路验收，最终使用 Worker 1.0.1；发布状态与记录见 `docs/verification/2026-09-26-pdf-tools/deployment-status.md`。

回退时先关闭 `PDF_ROUTE_ENABLED` 和 `PDF_ENABLED`，让已接单工作完成/取消；保留数据库和已发布结果直到其保留期结束，切回上一版不可变包，不覆盖正在执行的版本。

## MCP

```sh
python3.12 -m venv /opt/mytools-pdf-mcp
/opt/mytools-pdf-mcp/bin/pip install -r service/pdf-service/mcp/requirements.txt
/opt/mytools-pdf-mcp/bin/python service/pdf-service/mcp/server.py --transport stdio
```

stdio 使用 `MYTOOLS_GATEWAY_URL`、当前账户 `MYTOOLS_ACCESS_TOKEN`、显式允许的 `PDF_MCP_FILE_ROOT`。本机文件工具仅在该目录内读写，下载排他创建且校验长度和摘要。

工具：`pdf_capabilities`、`pdf_create_upload`、`pdf_complete_upload`、`pdf_plan`、`pdf_get`、`pdf_start`、`pdf_cancel`、`pdf_history`、`pdf_artifacts`；stdio 额外 `pdf_upload_local`、`pdf_download_local`。operation 使用枚举、options 有有界 schema 并在领域层再次严格校验。MD 附件使用 `attachment_ids` 参数。

Streamable HTTP：`--transport http`，默认 `127.0.0.1:23343/mcp`。公网必须反向代理 TLS，设置 `PDF_MCP_PUBLIC_URL` 为精确地址。**此版本采用每账户专用预共享令牌，不是 OAuth 服务**。`PDF_MCP_CREDENTIALS_FILE` 指向权限 0600 的私有 JSON：键为 MCP 随机令牌 SHA-256，值为该账户的 `{"gatewayToken":"...","refreshToken":"...","expiresAt":0}`。服务按账户串行刷新即将过期的正式会话，并以 0600 文件原子保存轮换结果；refreshToken 失效或被撤销后需重新登录。不同调用者不能共用同一账户映射，文件每请求重读支持撤销。不接受外部 ownerId、不透传调用者 APP 令牌、不开放宿主文件工具，日志不记录 Authorization。

远程 `pdf_create_upload` 返回 `/files/uploads/{id}/content`；以 MCP 专用令牌执行二进制 PUT、携带 `X-Upload-Offset`。`pdf_artifacts` 返回 `/files/jobs/{id}/artifacts/{index}/content`，同令牌下载。不要把 PDF 字节或令牌塞入 MCP 参数/提示词。远程 `/files` 与 `/mcp` 必须使用同一实例和账户映射。

## 验证

```sh
mvn -f service/pdf-worker/pom.xml test
mvn -f service/pdf-service/pom.xml test
mvn -f service/mytools-gateway/pom.xml -Dtest=PdfGatewayControllerTest test
python -m pytest service/pdf-service/mcp/tests service/scripts/test_assemble_executor_packages.py
mvn -f service/pdf-service/pom.xml test-compile dependency:build-classpath -Dmdep.outputFile=/tmp/pdf-service-classpath.txt
python service/pdf-service/tests/acceptance.py --input /path/two-page.pdf --package service/pdf-service/packages/pdf_process/1.0.1 --classpath /tmp/pdf-service-classpath.txt --output /tmp/pdf-acceptance
```

集成验收使用真实服务、H2、真实 Worker 和本机 Scheduler/Storage/Asset 替身；开启中文测试时设置 `PDF_FONT_FILE` 和固定 `PLAYWRIGHT_BROWSERS_PATH`。本机 macOS 验收不能替代 Ubuntu bubblewrap、真实 Scheduler/Storage/MySQL 或 HarmonyOS 真机验收。


## 生产接入与验收

MCP 地址：`https://mytools.yuyutian.top/api/app/v1/pdf-mcp`。Gateway 将固定协议和二进制路由转发到本机 MCP，保留独立账户 Bearer；不会将 MCP 令牌当作 APP 身份。

连接时设置 `Authorization: Bearer <专用令牌>` 和真实的 `User-Agent`（例如 `MyTools-PDF-Agent/1.0`）。公网 Cloudflare 当前会拒绝默认 `Python-urllib` 标识。服务端配置 `PDF_MCP_FILES_URL=https://mytools.yuyutian.top/api/app/v1/pdf-mcp/files`，工具返回完整 `uploadUrl` / `downloadUrl`。

专用客户端凭据仅保存在主机私有文件 `/opt/yuyutian/mytools/runtime/pdf-tools/mcp/client.json`，不提交仓库、不写进提示词。APP 使用原账户登录，不使用该令牌。

生产采用独立 `mytools-pdf-service`、`mytools-pdf-executor-service`、`mytools-pdf-mcp-service` systemd 服务。执行器并发 1，systemd 内存上限 3 GiB；Java 运算子进程堆上限 512 MiB。Chromium 使用较大的虚拟地址保留，专用执行器不施加地址空间限制，由 systemd 控制实际内存。

Python 虚拟环境的解释器须直接链接到实际基础解释器，不能经其他业务虚拟环境的中间链接；沙箱只读挂载当前虚拟环境、基础 Python 和 Debian 的 `/etc/java-*-openjdk`。任务工作目录及相邻 `tasks-report-journal` 必须由 mytools 拥有。不要开放整个 `/etc` 或将服务令牌传入运算子进程。

发布与验收脚本位于 `service/deploy/`：`deploy_pdf_tools.py`（首次灰度/回滚）、`upgrade_pdf_worker.py`（1.0.1 固定版本）、`accept_pdf_tools.py`、`accept_pdf_mcp.py`、`finalize_pdf_tools.py`。脚本内发布号为本次审计标识，不能在其他版本上盲目重复运行。回滚停止 PDF 服务、禁用 PDF 调度任务并恢复原网关和调度器启动项，保留数据库与迁移。
