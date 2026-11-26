# 公开工具箱实施状态与证据

更新日期：2026-10-02。对象：`toolbox-web`（公开前端）、`service/toolbox-service`（公开后端）、`toolbox-web/content`（教材发布流水线）、`home-ubuntu` 部署。

**公网地址：https://toolbox.yuyutian.top**（经 Cloudflare tunnel 终止 TLS，源站为 nginx 23113 与 toolbox-service 23112）。

本文只记录实际执行过的命令与真实结果。凡未运行的检查、需要实体设备或人工测量的项目，都在“未完成与阻塞”中列为未通过，不写成通过。

## 1. 总体状态

| 维度 | 状态 | 说明 |
| --- | --- | --- |
| 独立开放前端 | 已完成 | 无登录、无账号入口；左侧目录由工具注册表驱动；生产构建不包含演示数据回退 |
| 公开后端服务 | 已完成 | 匿名会话、工具目录、教材只读接口、事件上报、聚合统计；50 项测试通过 |
| 教材发布流水线 | 已完成 | 校验、构建、覆盖校验三个脚本；20 槽位清单；0 册已发布 |
| 教材真实词库 | **部分完成** | 已发布 5/20 册共 2635 条：语文 1/2/3/4 年级上册（318/760/777/640）、英语 3 年级上册（140）；缺口均在界面列出 |：统编语文一年级上册（2024 版），284 条识字表／写字表，来源为国家中小学智慧教育平台官方电子教材。其余 19 册待采集 |
| 服务端 PDF 执行与匿名文件隔离 | **阻塞（步骤 0—1 已完成，未部署）** | M06.2 派发标识可配置（pdf-service 7 项测试通过）；M06 步骤 1 调度器公开任务域双向守卫已实现（守卫单测 5 项通过，调度器可运行用例 62 项中 61 项通过）。两者都**未部署到生产**。其余步骤见 [公开 PDF 执行链与匿名隔离实施方案](pdf-isolation-plan.md)；隔离全部通过前 `TOOLBOX_PDF_ENABLED` 保持 `false` |
| 公网 HTTPS 发布 | 已完成 | https://toolbox.yuyutian.top；Cloudflare tunnel 入口 + DNS CNAME；`__Host-toolbox_guest`、`Secure`、`HttpOnly`、`SameSite=Lax`、`Path=/`、无 Domain |
| 测试环境部署 | 已完成 | nginx 静态站 + 反向代理，后端 systemd 服务，独立 MySQL schema |
| 实纸打印与多浏览器实机验收 | **未执行** | 需要实体打印机与真实移动设备 |

## 2. 执行命令与结果

### 2.1 前端 `toolbox-web`

| 命令 | 退出码 | 结果 |
| --- | --- | --- |
| `npm run typecheck` | 0 | `vue-tsc --noEmit` 无错误、无警告 |
| `npm test` | 0 | 63 项测试通过，0 失败（原 25 项 + 新增 38 项） |
| `npm run test:curriculum` | 0 | 20 必需槽位、0 已发布版本，结构与引用校验通过 |
| `npm run test:coverage -- --allow-partial` | 0 | 开发报告：0/20 册完成 |
| `npm run build:production` | 0 | `dist/` 生产构建，`VITE_APP_MODE=production`、`VITE_PDF_EXECUTION=local` |
| `npm run test:e2e` | 0 | 真实 Chrome 对公网站点 **42/42** 通过（含自定义词条输入与目录选题）（含真实生成与导出 PDF、提取文本的两种输入、**教材目录按单元／课文选择**、移动端溢出、控制台与网络异常） |
| `npm run verify:release` | 0 | 自动检查 **6/6** 通过（含端到端）；人工项 5 项标记为未验证 |

新增测试分布：`tests/selection.test.ts`（10）、`tests/worksheet-snapshot.test.ts`（8）、`tests/curriculum.test.ts`（7）、`tests/analytics.test.ts`（5）、`tests/pdf-adapter.test.ts`（4）、`tests/stats-contract.test.ts`（4）。

端到端套件位于 `tests/e2e/`（`cdp.mjs` 驱动、`scenario.mjs` 场景、`run.mjs` 入口），用 Node 22 自带的 WebSocket 直接驱动 Chrome DevTools Protocol，不依赖 Playwright 等额外依赖。找不到浏览器时以退出码 2 报告「未执行」，不会被计为通过。

### 2.2 后端 `service/toolbox-service`

| 命令 | 退出码 | 结果 |
| --- | --- | --- |
| `mvn -f service/toolbox-service/pom.xml clean compile` | 0 | 编译成功，0 编译警告 |
| `mvn -f service/toolbox-service/pom.xml test` | 0 | 50 项测试通过，0 失败、0 错误 |
| `mvn -f service/toolbox-service/pom.xml package -DskipTests` | 0 | `toolbox-service-0.1.0-SNAPSHOT.jar`，28,674,065 字节 |

测试类：`CurriculumApiTest`(10)、`EventIngestApiTest`(11)、`SessionApiTest`(7)、`StatsApiTest`(6)、`ErrorEnvelopeTest`(5)、`ToolCatalogApiTest`(4)、`OriginAllowlistTest`(3)、`TimezoneTest`(2)、`StatsDisabledApiTest`(2)。

后端未加入根 `pom.xml` 与 `service/pom.xml`，只能通过独立 POM 构建。

## 3. 测试环境部署

| 项目 | 值 |
| --- | --- |
| 主机 | `home-ubuntu`（Ubuntu 26.04，通过 `~/.ssh/config` 配置的通道） |
| 前端发布目录 | `/opt/yuyutian/toolbox/frontend`（23 MB，含 16 MiB 中文字体与 PDF.js 资源） |
| 后端 JAR | `/opt/yuyutian/toolbox/backend/toolbox-service.jar` |
| 内容目录 | `/opt/yuyutian/toolbox/content` |
| 配置 | `/opt/yuyutian/toolbox/config/toolbox.env`（权限 600，只存放于服务器） |
| 服务单元 | `/etc/systemd/system/toolbox-service.service`（已 enable） |
| 日志 | `/opt/yuyutian/logs/toolbox/service.log` |
| nginx | `/etc/nginx/sites-available/toolbox`，监听 `23113` 与 `80`（`server_name toolbox.yuyutian.top`） |
| 公网入口 | Cloudflare tunnel `eeb9a165-…` ingress：`toolbox.yuyutian.top → http://127.0.0.1:23113`；DNS CNAME 由 `cloudflared tunnel route dns` 创建 |
| 后端端口 | `23112`，context-path `/public/v1`（不在 23110—23119 之外） |
| 数据库 | MySQL `toolbox_public`，独立用户 `toolbox_public`，Flyway `V1__toolbox_public.sql` |

部署产物哈希（本地构建与服务器实际文件一致）：

- 前端 `index.html` SHA-256：`34d57506d34c15bcc82280db489b73c4999466ab06ab80b54a2fb395cbbfdfdc`
- 后端 JAR SHA-256：`7806d4d56207c3a6c9bc12ebc46330086c0e09c4b08a06be06f3b6a19cc12a2f`

### 3.1 部署期间发现并修复的真实缺陷

以下问题都在真实服务器上复现、定位、修复并重新验证，不是推测：

1. **`.mjs` 资源 MIME 类型错误**：nginx 把 PDF.js 的 ES 模块 worker 作为 `application/octet-stream` 返回，浏览器拒绝执行，字帖预览一直停在加载状态。已在 nginx 站点配置中为 `^/assets/.+\.mjs$` 显式设置 `default_type application/javascript`；修复后预览正常渲染出 A4 纸张。
2. **统计聚合从不生效**：`toolbox_stat_outbox.lease_until` 是 `TIMESTAMP`（0 位小数），而占用后按 `lease_until = ?` 带纳秒精度回读，条件永远匹配不到任何行，事件被反复占用（attempts 增长到 9—10）却从不写入 `toolbox_stat_applied` 与 `toolbox_daily_stat`，首页累计恒为 0。已改为按占用时返回的主键集合回读。修复后同一批事件正确产生 3 条 applied 与 3 条日聚合。
3. **当天统计桶被标为“已完成”**：水位存在时当天一律 `complete=true`，会把尚未聚合的事件显示成真实零。已改为“水位越过自然日结束时刻才算完成”；当天返回真实滚动值并标记 `complete=false`，与设计的“今日尚未结束”一致。
4. **教材清单结构与后端模型不一致**：`requirements.json` 的说明字段触发严格反序列化失败，服务无法启动。已为仓库内容文件模型开启 `ignoreUnknown`（接口请求体仍严格校验），并把 `published.json` 的 `editions` 改为完整 `TextbookEdition` 结构。
5. **能力接口虚报服务端执行**：`/tools` 在 PDF 执行链未启用时仍返回 `execution=server`。已改为按 `toolbox.pdf-enabled` 如实返回 `local`，并补充断言测试。
6. **前端默认无选题且加载状态不结束**：进入字帖页时未应用默认选题策略，且 `busy` 在无题目时不会复位，页面停在加载提示。已补充默认“选中范围全部生成”的初始化与忙状态复位。
7. **PDF 取消语义失效**：本机适配器的 `cancel()` 在中断后立刻替换 AbortController，导致处理中的 `execute()` 读到的新信号永远未中断，取消形同虚设。已改为在执行开始时重置信号、`cancel()` 只中断当前信号；新增 4 项适配器测试覆盖执行位置、工具标识映射、真实转换与取消行为。

## 4. 线上 smoke 验证

通过 nginx（`http://127.0.0.1:23113`，经 SSH 端口转发）实际请求：

| 检查 | 结果 |
| --- | --- |
| `GET /` 首页 | 200；无登录跳转；左侧目录为 首页／PDF／字帖／单词 |
| `GET /fonts/NotoSansCJKsc-Regular.otf` | 200；字体可从本站加载 |
| `GET /public/v1/tools` | 200；12 个工具；响应中无 `ownerId`/`storageUri`/`assetId`；`execution=local` |
| `GET /public/v1/health` | 200 `{"status":"UP"}`，不泄漏配置 |
| `GET /public/v1/stats?days=7&category=all` | 200；7 个连续日期桶；真实零；`partial=false` |
| `GET /public/v1/stats?days=14` | 422 `TOOLBOX_001`，`details` 只含字段名与允许值 |
| `GET /public/v1/textbooks` | 200；0 个已发布版本；20 个槽位全部 `missing` |
| `POST /public/v1/session` | 200；返回 `expiresAt` 与 `csrfToken`，不返回原始 token 或 owner |
| `POST /public/v1/events`（3 条） | 200；3 条 `accepted` |
| 重放同一批事件 | 200；3 条 `duplicate`，不重复计数 |
| `worksheet_success` + `pdf.merge` | 逐条 `rejected`，原因 `worksheet_tool_required`，不影响同批其它事件 |
| 缺少 CSRF 头 | 403 `TOOLBOX_010` |
| 缺少会话 cookie | 401 `TOOLBOX_002` |
| 聚合后 `GET /stats` | 今日点击 1、今日成功 1、今日打印 1、累计成功 1；排行 `worksheet.chinese-dictation`=1 |
| 数据库核对 | 3 条 outbox 全部 `APPLIED`；3 条 `toolbox_stat_applied`；3 条 `toolbox_daily_stat`（`copybook` 分类，含 `__category__` 入口桶） |

浏览器渲染检查（真实 Chrome headless，截图为实际输出）：

- 首页：指标卡、7/30 天切换、趋势折线、文字摘要、排行空状态、三个快捷入口、执行位置说明均正常；统计可用时不再显示不可用提示。
- 字帖页：示例内容提示、年级／册次／版本选择、覆盖状态「未发布」、三种选题方式、练习方式、纸张设置（14 mm 默认、0/40 标题计数）、A4 预览实际渲染出拼音提示与空白田字格、`第 1/2 页`、产物元信息（文件名、大小、页数、SHA-256）。
- 统计不可用时的降级：后端未启动期间首页显示「统计暂时不可用」、真实错误码与重新加载按钮，四个指标为真实 0，工具入口仍可用；生产构建没有回退到演示数据。

## 4.1 公网 HTTPS smoke 验证

对 `https://toolbox.yuyutian.top` 实际请求：

| 检查 | 结果 |
| --- | --- |
| TLS 与首页 | `GET /` 200，证书校验通过（`ssl_verify_result=0`） |
| 静态资源 | 16 MiB 中文字体 200；构建产物按内容哈希长期缓存 |
| `POST /session` | 200，`Set-Cookie: __Host-toolbox_guest=…; Path=/; Max-Age=86400; Secure; HttpOnly; SameSite=Lax`（无 Domain，符合契约 3.3） |
| `POST /events`（正确 Origin + CSRF） | 200，2 条 `accepted` |
| `POST /events`（伪造 Origin） | 403 `TOOLBOX_010` |
| `GET /stats` | 200；30 天窗口返回 今日点击 2／成功 2／累计 2／打印 1，`partial=false`，排行含两个工具 |
| 分类过滤 | `category=words` 返回 1/1/1/0 且排行只含英语工具 |
| 浏览器渲染 | 真实 Chrome 打开公网地址，左侧目录、四项指标、趋势折线、文字摘要、快捷入口全部正常；聚合未追平时显示「数据不完整」提示 |

读取路径按设计带 5 分钟内存缓存：刚聚合完成时同一 `(days, category)` 组合可能短暂返回旧值并显示 `partial=true`，属预期行为而非缺陷。

## 5. 未完成与阻塞

| 需求 | 状态 | 缺口与解除条件 |
| --- | --- | --- |
| R05 语文 1—6 年级 12 册 | 部分完成（1/12） | 一年级上册已发布：284 条（识字表 284 条含写字表 100 条，去重 280 字），与教材自印「共 280 个生字」「共 100 个字」完全一致。其余 11 册待采集。 |
| R06 英语 3—6 年级 8 册 | 阻塞 | 同上（译林版优先）。 |
| R04 服务端 PDF 与匿名隔离 | 阻塞 | 需要公开 `public-pdf-service` 领域守卫、独立 Storage 根与受控删除、专用 Executor 包与沙箱验证。当前八项 PDF 能力在浏览器本机执行，页面已明确标注「本机处理，不上传文件」；因此 PDF 成功使用不由客户端上报，全站统计中不含 PDF 成功数。 |
| R07 实纸打印 | 未执行 | 需要实体打印机与 A4 实测；系统打印窗口与独立答案 PDF 已实现并可下载。 |
| R08 多浏览器／实机 | 部分 | 320/360/390/430/768/1280/1440 px 的 CSS 断点与触控区（≥44 px）已实现；Chrome 桌面已实测。Edge、Safari、iPhone Safari、Android Chrome 与软键盘未在真实设备验证。 |
| 回滚演练 | 未执行 | 回滚步骤已写入发布清单，但尚未在公网环境实际演练一次回滚。 |

## 5.1 本轮新增：pdf-service 派发标识可配置（M06.2）

| 命令 | 退出码 | 结果 |
| --- | --- | --- |
| `mvn -f service/pdf-service/pom.xml test` | 0 | 7 项测试通过（原 5 项 + 新增 2 项） |

改动只增加可配置性：未设置新配置项时，服务身份仍为 `pdf-service`、任务定义 `pdf_process`、业务类型 `PDF_TOOL`、幂等前缀 `pdf:`，与改造前逐字段一致（由新增测试用本机 HTTP 替身捕获真实出站请求断言）。**未重新部署 pdf-service**，服务器上运行的生产实例未受影响。

## 5.2 本轮新增：调度器公开 PDF 任务域守卫（M06 步骤 1，未部署）

| 命令 | 退出码 | 结果 |
| --- | --- | --- |
| `mvn -f service/task-scheduler-service/pom.xml test-compile` | 0 | 主代码与测试代码编译完整 |
| `mvn -f service/task-scheduler-service/pom.xml test -Dtest=PublicPdfTaskAccessTest` | 0 | 5 项通过：双向限制、大小写不敏感域判定、未配置身份失败关闭 |
| `mvn -f service/task-scheduler-service/pom.xml test -Dtest=<18 个不依赖 Spring 上下文的类>` | 1 | 62 项运行、0 失败、1 错误 |

新增 `PublicPdfTaskAccess`：其他身份不得触碰公开 PDF 任务（401），公开身份不得触碰其他任务域（403 `TASK_DOMAIN_FORBIDDEN`，新增错误码）。接入点只有 `TaskInstanceService.create()` 与 `get()` 两处，而 `cancel()` 与 `GET /{id}/results` 都先调用 `get(id)`，因此创建／查询／取消／结果四条路径全部受同一守卫约束。

**环境限制（预先存在，与本次改动无关）**：`mvn -f service/task-scheduler-service/pom.xml test` 全量套件在本机必然失败——仓库既有迁移 `V141__bound_remote_download_paths.sql` 使用 MySQL 专有的 `JSON_SET`，而测试库是 H2。已用独立 H2 探针复现：`Function "json_set" not found`。因此 84 个启用 Flyway 的 `@SpringBootTest` 用例在迁移阶段即失败。唯一的 1 项错误 `SchedulerRestartRecoveryTest` 属于同一原因。

**未部署**：服务器上的 `task-scheduler-service` 与 `pdf-service` 仍是改动前的构建产物。部署步骤 1 需要先确认（生成独立 `TASK_BUSINESS_PUBLIC_PDF_TOKEN`、在 MySQL 环境跑完整套件、记录回滚产物）。

## 5.3 本轮自查发现并修复的真实缺陷（排行榜）

对公网做真实浏览器交互验证时，控制台暴露出首页每次渲染都会抛出的异常：

```text
TypeError: Cannot read properties of undefined (reading 'toLocaleString')
    at HomeView-...js
```

根因是前后端字段不一致：**服务端排行榜条目只有 `toolId` 与 `success`，前端却按 `{toolId, category, count}` 读取**，因此 `item.count` 为 undefined，模板里的 `item.count.toLocaleString()` 直接抛错；同时快捷入口的分类计数恒为 NaN 而被隐藏。

为什么上一轮没有发现：当时的验证只用 `curl` 打印了接口返回，没有逐字段核对；浏览器检查只截了静态页面且断言过弱（仅判断排行榜数组非空），而该错误发生在渲染阶段、不会让页面整体白屏。

修复方式：

1. `src/services/stats-api.ts` 的排行类型对齐真实响应（`toolId` + `success`），并新增纯函数 `toRankingRows`，把分类**由本地工具注册表推导**而不是依赖接口字段，成功次数按数字归一化，字段缺失时降级为 0 而不是抛错。
2. `HomeView.vue` 改用该映射，快捷入口计数同样由注册表推导。
3. 新增 `tests/stats-contract.test.ts`：内嵌从线上原样捕获的真实响应体，逐字段断言 UI 依赖的字段名与类型，并断言排行映射不产生 undefined。字段再改名会先让测试失败，而不是等到页面上报错。

修复后公网重新构建部署，端到端 23/23 通过，`无控制台 error` 一项由 FAIL 转为 PASS。

## 5.4 公网连接的已知现象（非本应用引入）

对公网做多轮请求时观察到约每 8—12 次出现一次 `000`。定位结果：

```text
FAIL 000 | time_connect=0.000000 | Connection timed out after 20011 milliseconds
```

- 失败发生在 **TCP 连接阶段**（`time_connect` 为 0，未进入 TLS），不是应用或 nginx：源站 `http://127.0.0.1:23112/public/v1/*` 连续请求全部 200，nginx 日志无 error。
- 同一 Cloudflare tunnel 上的其他主机名表现相同：`toolbox.yuyutian.top` 7 成功/1 失败、`alist.yuyutian.top` 7/1、`mytools.yuyutian.top` 8/0。

因此这是**该 tunnel／边缘链路的既有特征，早于本项目存在**，不是本次部署引入的缺陷，也没有被"修好"。浏览器端因连接复用与自动重试，实际使用未受影响（真实浏览器端到端 23 项反复通过）。若要让公网访问更稳定，需要单独排查 Cloudflare tunnel 与本地出口网络。

## 5.5 提取文本：行为核实与补齐的测试覆盖

用户反馈出现「所选页面没有可提取的文字层，可能是扫描件。当前不提供 OCR。」。对该提示做了实测核实，并补上了此前完全缺失的测试覆盖。

**实测结论：工具行为正确，提示与原因一致。**

| 输入 | 结果 |
| --- | --- |
| 内置示例 PDF（pdf-lib 绘制的真实文字） | 提取成功，内容为 `TOOLBOX / SAMPLE 1 Create. Organize. Convert. Page 1 of 2 … Page 2 of 2`，两页齐全、顺序正确 |
| 字帖导出的 PDF（嵌入中文字体子集） | 提取成功，产出 `extracted-text.txt` |
| 由「图片转 PDF」产出的纯图片 PDF | 正确提示 `所选页面没有可提取的文字层，可能是扫描件。当前不提供 OCR。`，且**不产出**结果文件 |

即：出现该提示说明输入 PDF 确实没有文字层（扫描件或纯图片），与设计一致——OCR 不在首期八项能力内。

**为什么此前没覆盖**：`tests/pdf-tools.test.ts` 里唯一涉及文字的用例直接调用 `pdfjs-dist` 解析，绕过了 `runPdfTool` 的真实路径（`openPdf` → worker → `getTextContent`）。已把两个提取场景加入 `tests/e2e/`，用真实浏览器与真实产物执行：

1. 有文字层的 PDF 必须提取出**正确内容**（断言包含两页文字），而不是只产出一个空文件；
2. 无文字层的 PDF 必须给出准确提示且不产出结果。

**过程中修掉的三个测试自身缺陷**（都不是产品问题，但会掩盖真实结论）：

- `waitForDownload` 只判断目录非空，会把上一次导出的旧文件当成新结果 → 改为等待一个**新**文件名。
- CDP 的 `DOM.setFileInputFiles` 在 Vue 重新渲染后命中失效句柄且静默失败 → 改为在页面内构造 `File` 并用 `DataTransfer` 派发 `change`，走应用真实的表单处理链路。
- 用 `input.files.length` 判断文件是否被接受是错的：应用在 `onFileChange` 里会执行 `input.value = ''`（便于重复选择同一文件），读完即清空 → 改为回查界面是否列出了该文件名。

## 5.6 教材词库：已发布一年级上册，并打通单元／目录选择

用户要求「自动搜索网络教材获取识字表、词语表」与「按单元、目录选择」。两项都已落地并在公网核实。

**已完成：**

1. **发布统编语文一年级上册（2024 版，2022 课标修订版）**，共 **284 条**：8 个单元、38 篇课文。
   - 来源是国家中小学智慧教育平台官方电子教材 PDF（有真实文字层，ISBN 9787107383007），数据取自该教材自身的**识字表**（第 110—112 页）与**写字表**（第 113 页），不是二手字表。
   - 数量对账：识字表 284 条（教材脚注印「共 280 个生字」，差额 4 条是教材用蓝色标注的多音字复现，教材明确不计入）、写字表 100 条（教材印「共 100 个字」）、去重后 **280 个不同汉字** —— 与教材自印数字完全吻合。
   - 交叉核对：pypinyin 0.55.0 全量比对 310/318 一致，8 处差异全部是上下文读音与默认读音不同（地/长/只/着/数/爪/子），已按教材读音保留；另有内蒙古智慧教育平台 PDF（184 字全一致）与搜狐字表（89 字中 88 一致）作独立对照。
   - **一年级上册教材没有词语表**：其附录只有识字表与写字表。因此本册不含词语条目，也没有用推测内容补足。
   - 版本选择记录在 `content/sources/cn-1-first-2024.json`：网络流传的多数「一上生字表」是 2016 版，本册采用 2024 年秋季起使用的修订版（第 3 课「口耳目手足」、第 4 课「日月山川」等）。旧版如需接入应另建 editionId，不覆盖本版本。

2. **修复教材选择链路并打通目录选择**，修复两个真实缺陷：
   - **范围变化后不重新选题**：勾选单元／课文只会重新拉取候选集，选中集合仍为空，界面出现「已选 0 项」却没有内容。已改为范围或书册变化后按当前策略重新选题。
   - **课文层级默认折叠**：范围树只显示单元，用户看不到课文，无法按课文选择。已改为默认展开。

**公网实测（真实浏览器，`npm run test:e2e` 已固化为回归）：**

| 检查 | 结果 |
| --- | --- |
| 范围树两级渲染 | 8 个单元、38 篇课文、46 个复选节点 |
| 覆盖状态 | 「部分可用 · 1 已发布版本」，不再是「未发布」 |
| 按**课文**选择 | 勾选「1 天地人」→ 已选 6 项 · 6 题，父单元显示半选 |
| 按**单元**选择 | 勾选「第一单元·识字」→ 已选 33 项 · 33 题，与接口 `unitIds` 返回的 33 条完全一致 |
| 题目顺序 | 天 地 人 你 我 他 一 二 三 四 五 上 …… 与教材顺序一致 |
| 控制台 | 无异常、无 error |

**端到端用例加固**：公网 tunnel 偶发 TCP 连接超时会把基础设施抖动误报成产品缺陷，因此导航改为可重试，但**重试次数会打印出来**而不是静默掩盖；超过上限仍判失败。

**下一步**：其余 11 册语文与 8 册英语按同一流水线采集（`import:curriculum` → `build:curriculum` → `test:curriculum`），每册都需要同样的来源记录与数量对账。

## 5.7 打印允许输出为 PDF：预览窗口可直接下载

设计要求「打印预览打开专注阅读界面：……打印、导出 PDF」「点击『打印』才调起系统打印对话框」。原实现只给了「打印」和「关闭窗口」，而且**打开预览时自动弹出系统打印对话框**，与此不符。

已修正：

1. 打印预览窗口新增 **导出 PDF** 下载入口，指向与预览完全相同的那一份 PDF（`blob:` 地址 + 正确的 `download` 文件名），系统打印不可用或用户只想存档时仍拿得到文件。
2. **取消自动弹出**系统打印对话框，改为点击「打印」才调起；提示文案同步改为「已打开打印预览。点击『打印』调起系统对话框，或直接导出 PDF。」
3. 工具栏显示总页数，按钮点击区不小于 44 px；窗口关闭时释放 blob 地址。

公网端到端已验证（`npm run test:e2e`）：`下载名=chinese-pinyin-student.pdf blob=true`、`按钮=打印/关闭窗口 页数=2 页`、`页图=2`。

## 5.8 教材缺口必须显式显示

二年级上册部分单元缺识字表，接口清单已用 `missingScopes` 记录，但界面只显示「部分可用」，用户无从得知哪些单元不完整。已在范围选择器中列出该册的全部已知缺口。

同时修掉导入脚本两个真实缺陷：

- **去重过宽**：去重键原先只含文本与读音，导致同一个字出现在不同课文或不同清单（识字表／写字表）时被合并，丢失课次归属并让「按写字表筛选」漏字。一年级 318 条被压成 284 条、二年级 760 条被压成 661 条。已把单元、课文、清单类别纳入去重键，两册条目数现与来源 CSV 完全一致。
- **缺口字段被覆盖**：`missingScopes` 在对象里写了两次，后者把 `--missing-scopes` 的值覆盖成空数组。

另外补上「已发布版本不可原地覆盖」的正确处理：内容变化时构建脚本会拒绝更新清单，必须提升 `contentVersion`。因此一年级与二年级均由 1.0.0 提升到 1.0.2，旧版本目录保留作审计但不进发布清单（构建脚本现在每个版本只发布最新的 contentVersion）。

## 5.9 自定义词条：桌面端原本完全进不去

用户反馈「字帖和英语字帖都需要支持自定义模式」。核实后发现能力本身是好的——用真实浏览器实测，语文与英语都能导入自定义词条、进入练习并重新生成纸面。**真正的问题是桌面端根本进不去词条面板**：

- 进入词条面板的入口只有移动端的分屏签（`设置／词条／预览`），而它在桌面端被隐藏；
- 我重写页面时丢掉了原有的「编辑词条」按钮，`editEntries` 文案从此没有任何地方引用；
- 结果：≥1280px 的桌面用户没有任何办法打开自定义输入。

修复：

1. 在「选择内容」下方新增**自定义词条**区块与「导入词条」按钮，桌面与移动端都可见；
2. 点一下即切到词条面板**并直接弹出输入框**，不需要再找第二个按钮（按钮文案与实际行为一致）；
3. 按钮显示已有自定义条数，下方说明「只在本机生成，不会上传」。

公网端到端已覆盖两个学科：`语文：自定义行=2 含「春风」=true 数量=已选 26 项`、`英语：自定义行=2 含「apple」=true 数量=已选 26 项`。

## 5.10 英语三年级上册已发布

首次接入英语：译林版《英语》三年级上册（2024 年版，ISBN 9787575300377），**140 条**（单词 120、短语 20；词汇 126、句型 14）。

该册在平台上**被限制下载**（`download_policy=2`，PDF 直链 403），因此数据取自同一官方资源的页面图像判读，并用独立镜像逐条比对：可比对的 127 条**全部一致、0 冲突**，另以 OCR 复核 135/140。已知缺口（专有名词缺独立来源、单元归属为推定等）已写入清单并在界面显示。

## 5.11 教材扩展到 5 册，并定位一个测试夹具缺陷

新增语文三年级上册（777 条）与四年级上册（640 条），均来自国家中小学智慧教育平台的官方电子教材 PDF（有文字层），三类清单数量与教材页脚自报数字完全一致。四年级抓取过程还靠交叉核对发现并修正了一处跨页坐标串行导致的读音错误（催 cuī / 君 jūn）。

**测试夹具缺陷**：目录选题的端到端检查在整套运行中稳定失败，但用同一套辅助函数单独跑却成功。根因是前面的字帖用例要加载 16 MiB 字体并连续生成 PDF，**主线程被占满时鼠标点击不会被处理**；把目录检查前移到字体密集用例之前即可通过。这说明「用例之间互相干扰」是真实存在的失败模式——单独跑绿不代表整套跑绿。

同时修掉两个夹具自身的问题：`centerOf` 无条件调用 `scrollIntoView` 会让下拉弹出层关闭（改为仅在不可见时滚动）；`pickSelectOption` 只看「点到了选项」而不看「值真的变了」（改为回读选中值，并增加合成 click 兜底）。

## 6. 复现步骤

```bash
# 前端：类型检查、测试、数据校验、生产构建、汇总发布检查
cd toolbox-web
npm run typecheck && npm test
npm run test:curriculum
npm run test:coverage -- --allow-partial
npm run build:production
npm run test:e2e          # 真实浏览器验收，目标可用 E2E_TARGET 覆盖
npm run verify:release

# 后端：编译、测试、打包
cd ..
mvn -f service/toolbox-service/pom.xml clean test
mvn -f service/toolbox-service/pom.xml package -DskipTests

# 共享私有服务：确认公开实例改造未改变既有默认行为
mvn -f service/pdf-service/pom.xml test
```

发布与回滚步骤见 [release-manifest.json](release-manifest.json)；20 槽位覆盖明细见 [curriculum-coverage.json](curriculum-coverage.json)；自动检查原始结果见 [release-verification.json](release-verification.json)。
