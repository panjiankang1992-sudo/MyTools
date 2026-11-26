# 工具箱管理员教材录入 —— 本地隔离运行环境验收

- 日期：2026-10-07
- 范围：`service/toolbox-service`（后端）、`toolbox-web`（前端）、隔离 MySQL 测试容器
- 目的：为「工具箱管理员教材录入」验收搭建**本地隔离**运行环境，并用真实 HTTP 链路证明其可用
- 生产边界：全程只访问本机回环地址（`127.0.0.1:23199` / `127.0.0.1:4173` / `127.0.0.1:13316`），
  **未对 `toolbox.yuyutian.top` 发起任何请求**，未创建线上账号、未发布任何线上教材

---

## 0. 结论一览

| 项 | 结论 | 证据 |
| --- | --- | --- |
| a. 公开接口 `GET /public/v1/tools` | ✅ 200 | §5.a |
| b. 匿名 `GET /admin/v1/textbooks` | ✅ 401 `TOOLBOX_ADMIN_001` | §5.b |
| c. 管理员完整链路（登录→CSRF→建草稿→保存→读回→发布） | ✅ 全部通过（短教材标识） | §5.c |
| c'. 同上，教材标识 ≥ 26 字符 | ❌ 发布 503，**真实缺陷**：MySQL 列宽不足 | §6.1 |
| d. 前端 4173 代理 `/admin/v1` | ✅ 401（非 404/502） | §5.d |
| e. CDP 无头 Chrome 打开 `http://127.0.0.1:4173/#/` 读到 DOM 标题 | ✅ `禹宇天的工具箱` | §5.e |
| 假身份服务仅在测试参数下启用 | ✅ 双重闸门 + 负向对照 | §4.2、§4.3 |

---

## 1. 环境组成

### 1.1 MySQL 测试容器（用户已提供，本次**未创建、未删除、未改动**）

```
$ docker inspect toolbox-mysql-test --format 'Name={{.Name}}
Image={{.Config.Image}}
ImageID={{.Image}}
Running={{.State.Running}}
Ports={{json .NetworkSettings.Ports}}
Mounts={{range .Mounts}}{{.Type}}:{{.Name}}{{.Destination}} {{end}}'

Name=/toolbox-mysql-test
Image=mysql:8.4
ImageID=sha256:6ea90827b1100f8f2ae306a539f86d2c264a26ed435a2a9f75551dd5c3aeb242
Running=true
Ports={"3306/tcp":[{"HostIp":"127.0.0.1","HostPort":"13316"}],"33060/tcp":null}
Mounts=volume:toolbox-mysql-test-data/var/lib/mysql
```

```
$ docker exec toolbox-mysql-test mysql -uroot -p*** -N -e "SELECT VERSION();"
8.4.11
```

| 项目 | 值 |
| --- | --- |
| 容器名 | `toolbox-mysql-test` |
| 镜像 / 实测版本 | `mysql:8.4` / `8.4.11` |
| 端口绑定 | 仅回环 `127.0.0.1:13316 → 3306` |
| 数据卷 | 独立命名卷 `toolbox-mysql-test-data`（挂载到 `/var/lib/mysql`） |
| 库 | `toolbox_test`（root 连接） |
| 本次运行前状态 | 空库（`SHOW TABLES` 无结果），由 Flyway 建表 |

Flyway 迁移（后端启动时自动执行，全部成功）：

```
$ docker exec toolbox-mysql-test mysql -uroot -p*** -N -e \
  "SELECT installed_rank, version, description, success FROM toolbox_test.flyway_schema_history;"
1	1	toolbox public	1
2	2	translation quota	1
3	3	toolbox admin	1
4	4	admin csrf recovery	1
```

### 1.2 端口分配

| 组件 | 地址 | 说明 |
| --- | --- | --- |
| 后端 toolbox-service | `127.0.0.1:23199` | 与前端代理默认目标对齐（`VITE_PUBLIC_API_TARGET` 默认值） |
| 前端 Vite dev | `127.0.0.1:4173` | 严格端口（`strictPort`），代理 `/public/v1` 与 `/admin/v1` 到 23199 |
| MySQL 测试容器 | `127.0.0.1:13316` | 仅回环 |
| 负向对照实例（临时） | `127.0.0.1:23200` | 仅用于证明假身份服务可被关闭，验证后已停止 |

数据库连接串（由测试配置提供）：

```
jdbc:mysql://127.0.0.1:13316/toolbox_test?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
```

---

## 2. 代码改动清单（只有测试专用件，未改业务逻辑）

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `service/toolbox-service/src/test/java/com/yuyutian/mytools/toolbox/config/LocalAdminIdentityTestConfig.java` | 新增（**测试源集**） | 仅本地验收用的假身份服务，`@Profile("local-admin")` + `@ConditionalOnProperty(toolbox.admin.local-identity-stub=true)` 双重闸门 |
| `service/toolbox-service/src/test/resources/application-local-admin.yml` | 新增（**测试资源**） | 指向隔离 MySQL 的测试配置；不含任何真实凭据（口令由环境变量注入） |

边界证明：

```
$ find service/toolbox-service/target/classes -name 'LocalAdminIdentityTestConfig*' | wc -l
0
$ find service/toolbox-service/target/test-classes -name 'LocalAdminIdentityTestConfig*'
target/test-classes/com/yuyutian/mytools/toolbox/config/LocalAdminIdentityTestConfig$LocalAcceptanceIdentityClient.class
target/test-classes/com/yuyutian/mytools/toolbox/config/LocalAdminIdentityTestConfig.class
```

即：两个文件都在**测试源集/测试资源**，默认不进 `target/classes`、不进生产构建产物。
（未新增或修改 `src/main/resources` 下的任何配置；未改动 `AdminProperties`、`ErrorCode`、
控制器与服务等业务代码。）

---

## 3. 启动与停止

### 3.1 后端（后台长跑）

启动命令（实际执行）：

```bash
export DEVELOPER_DIR=/Library/Developer/CommandLineTools
cd /Users/pankang/mycode/MyTools/service/toolbox-service
export TOOLBOX_DB_PASSWORD=toolbox-test-only     # 测试容器口令，只由环境变量注入，不落仓库
export TOOLBOX_PORT=23199
mvn -o spring-boot:run \
  -Dspring-boot.run.profiles=local,local-admin \
  -Dspring-boot.run.additional-classpath-elements=/Users/pankang/mycode/MyTools/service/toolbox-service/target/test-classes
```

- 后台作业 id：`bash-4711`
- Java 进程 PID：`58280`（`lsof -nP -iTCP:23199 -sTCP:LISTEN`）
- 监听端口：`127.0.0.1:23199`
- 日志路径：`/tmp/toolbox-local-env/backend.log`

> **为什么必须显式加 `additional-classpath-elements`**：`spring-boot:run` 的
> `-Dspring-boot.run.useTestClasspath=true` 实测**只把 test scope 依赖加入 classpath，
> 不加入 `target/test-classes`**（`-X` 日志中 `Classpath for forked process:` 里没有 `target/test-classes`，
> 且 Spring 报 `Skipping missing resource classpath:/application-local-admin.yml`，导致用 3306 默认库连接失败）。
> 改用 `additional-classpath-elements` 指向 `target/test-classes` 后，测试配置与假身份类都能被装载。

启动日志关键行：

```
2026-10-07T01:22:35.145  INFO ... : The following 2 profiles are active: "local", "local-admin"
2026-10-07T01:22:35.843  INFO ... FlywayExecutor : Database: jdbc:mysql://127.0.0.1:13316/toolbox_test (MySQL 8.4)
2026-10-07T01:22:35.861  INFO ... DbValidate     : Successfully validated 4 migrations (execution time 00:00.008s)
2026-10-07T01:22:36.957  INFO ... DbMigrate      : Migrating schema `toolbox_test` to version "4 - admin csrf recovery"
2026-10-07T01:22:37.011  INFO ... DbMigrate      : Successfully applied 4 migrations to schema `toolbox_test`, now at version v4
2026-10-07T01:22:37.343  INFO ... TomcatWebServer: Tomcat started on port 23199 (http) with context path ''
2026-10-07T01:22:37.349  INFO ... ToolboxServiceApplication : Started ToolboxServiceApplication in 2.329 seconds
```

停止命令：

```bash
kill 58280            # 结束 Java 进程
# 或结束后台作业：job_kill bash-4711 / pkill -f spring-boot:run
```

### 3.2 前端（后台长跑）

```bash
cd /Users/pankang/mycode/MyTools/toolbox-web
export VITE_PUBLIC_API_TARGET=http://127.0.0.1:23199
npm run dev
```

- 后台作业 id：`bash-4573`
- Node 进程 PID：`49094`（`lsof -nP -iTCP:4173 -sTCP:LISTEN`）
- 监听端口：`127.0.0.1:4173`
- 日志路径：`/tmp/toolbox-local-env/frontend.log`

```
> yuyutian-toolbox-web@0.1.0 dev
> vite

1:17:50 AM [vite] (client) Re-optimizing dependencies because lockfile has changed
  VITE v8.0.8  ready in 309 ms

  ➜  Local:   http://127.0.0.1:4173/
```

停止命令：`kill 49094`（或 `job_kill bash-4573`）。

---

## 4. 假身份服务说明

### 4.1 背景与接入点

管理登录必须复用上游 MyTools 身份服务（`AdminIdentityClient`），本地隔离环境没有上游。
因此按仓库既有扩展点接入一个替身，**不重构**：

- 扩展点：`AdminIdentityClient`（接口，`service/admin/identity/AdminIdentityClient.java`）
- 真实实现：`HttpAdminIdentityClient`（`@Component`，保持原样，未被修改）
- 替身：`LocalAdminIdentityTestConfig` 提供 `@Bean @Primary AdminIdentityClient`
  → 在 `local-admin` profile 下所有注入点（`AdminAuthService`）拿到替身
- 登录/会话/CSRF/权限判定全部走**原有** `AdminAuthController` + `AdminAuthService` +
  `AdminAuthFilter` + `AdminSessionService`，没有绕过任何服务端校验

替身行为：`login` 用内存里的假账号口令比对；`validate` 只认自己签发的假令牌并返回
`AdminIdentity(active=true, roles=[ADMIN])`；`refresh` 轮换假令牌；`logout` 空实现。
未识别的令牌返回**无效身份**（走 401），而不是抛异常（那会被上层解释为 503）。

### 4.2 「仅测试」的三重边界

1. **物理边界**：文件位于 `src/test/java` / `src/test/resources`，不进 `target/classes`，
   不进生产构建产物（§2 已实测）。
2. **Profile 闸门**：`@Profile("local-admin")` —— 默认 profile 与生产 profile 都不激活。
3. **配置闸门**：`@ConditionalOnProperty(name = "toolbox.admin.local-identity-stub", havingValue = "true")`
   —— 即使 `local-admin` profile 被激活，只要该键不为 `true`，替身仍不装载。

并且没有硬编码任何生产账号或真实凭据：假账号/口令默认值 `local-admin` / `local-admin`
（可用 `TOOLBOX_ADMIN_STUB_ACCOUNT` / `TOOLBOX_ADMIN_STUB_PASSWORD` 覆盖），
`upstream-base-url` 显式设为不可达回环地址 `http://127.0.0.1:1`，`internal-token` 为
`local-acceptance-only-not-a-secret`，`encryption-key` 为 Base64 的 32 字节零值。

### 4.3 负向对照：关掉替身后管理员**登不进去**

在 `local-admin` profile 仍然激活的前提下，只把替身开关置为 `false`，另起一个实例（端口 23200）：

```bash
mvn -o spring-boot:run -Dspring-boot.run.profiles=local,local-admin \
  -Dspring-boot.run.additional-classpath-elements=.../target/test-classes \
  -Dspring-boot.run.arguments="--server.port=23200 --toolbox.admin.local-identity-stub=false"
```

```
$ curl -s -o /dev/null -w 'HTTP %{http_code}\n' http://127.0.0.1:23200/admin/v1/auth/csrf
HTTP 200

$ curl -s -b nc-cookies.txt -H 'Content-Type: application/json' \
    -H "X-Toolbox-Admin-Csrf: $NONCE" -d '{"account":"local-admin","password":"local-admin"}' \
    -w '\nHTTP %{http_code}\n' http://127.0.0.1:23200/admin/v1/auth/login
{"code":"TOOLBOX_ADMIN_009","messageKey":"admin.unavailable","details":{},"requestId":"6cb9399a-..."}
HTTP 503
```

说明：替身被关闭时，注入的是真实的 `HttpAdminIdentityClient`，它去连不可达的
`http://127.0.0.1:1`，于是返回 `TOOLBOX_ADMIN_009`（依赖不可用）——**账号根本登录不进来**。
对照之下，替身开启的实例（23199）用同一组假账号返回 200（§5.c 步骤 2）。
该对照实例验证后已停止（`lsof -iTCP:23200` 无监听）。

---

## 5. 验收证据 a–e（原始命令与输出）

### 5.a 公开接口 200

```
$ curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:23199/public/v1/tools
200
```

（响应体开头：`{"code":"OK","data":{"policyVersion":"2026-10-02","execution":"local","tools":[{"id":"pdf.images",...`）

### 5.b 匿名管理接口 401

```
$ curl -s -i http://127.0.0.1:23199/admin/v1/textbooks
HTTP/1.1 401
X-Request-Id: 3c192700-17b0-4eb8-ac4d-2c7489effc08
Cache-Control: no-store
Content-Type: application/json;charset=UTF-8
Content-Length: 156
Date: Tue, 06 Oct 2026 17:24:41 GMT

{"code":"TOOLBOX_ADMIN_001","messageKey":"admin.session.required","details":{"reason":"sessionRequired"},"requestId":"3c192700-17b0-4eb8-ac4d-2c7489effc08"}
```

### 5.c 管理员完整链路

脚本：`/tmp/toolbox-local-env/admin-chain.sh`（全文见 §8）。
下面是**短教材标识** `lab-a` 的一次完整运行（每一行都是真实状态码）：

```
===== 1) GET /admin/v1/auth/csrf（领取一次性 nonce 与预认证 Cookie） =====
HTTP 200
Set-Cookie: toolbox_admin_login_local=FgIw8FSx5SAPwE2O4k_Fm51FScoldzHeXVyI3lCRL6U; Path=/; Max-Age=600; ...; HttpOnly; SameSite=Lax
csrfToken=21yzBXSkphMfkD_EVNCLyDdoMYnQStgORncga-zglUs

===== 2) POST /admin/v1/auth/login（假身份服务：local-admin / local-admin） =====
HTTP 200
Set-Cookie: toolbox_admin_login_local=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly; SameSite=Lax
Set-Cookie: toolbox_admin_local=X1_Wj0MWw7no57CM0WBjoIwUtfASielsRcwtHsDsq08; Path=/; Max-Age=28800; ...; HttpOnly; SameSite=Lax
body={"code":"OK","data":{"authenticated":true,"username":"local-admin","userId":"local-admin-1001","csrfToken":"7a9M_IaBLAFI8A7bUhtY7H0dZ5R0hOHvmKeE1dzhHeA"},"requestId":"852d9135-d114-4406-b222-e13994535df9"}
sessionCsrfToken=7a9M_IaBLAFI8A7bUhtY7H0dZ5R0hOHvmKeE1dzhHeA

===== 3) GET /admin/v1/textbooks（带会话 Cookie） =====
HTTP 200
body={"code":"OK","data":{"items":[{"editionId":"local-acceptance-2026-10-07","contentVersion":null,"published":false,...}],"total":1,...}}

===== 4) POST /admin/v1/textbooks（新建草稿，Idempotency-Key） =====
HTTP 201
ETag: "draft-2"
body={"code":"OK","data":{"editionId":"lab-a","draftId":"draft-lab-a","revision":2},"requestId":"fb4bfc3e-ff9f-40a0-bd5c-61b3d38c6194"}

===== 5) GET /admin/v1/textbooks/lab-a/draft（读回草稿与修订号） =====
HTTP 200
ETag: "draft-2"
revision=2

===== 6) PUT /admin/v1/textbooks/lab-a/draft（If-Match: "draft-2" + Idempotency-Key） =====
HTTP 200
ETag: "draft-3"
body={"code":"OK","data":{"revision":3},"requestId":"031b6d46-e335-47e9-8420-43e52111386a"}

===== 7) GET 草稿读回（确认保存生效：条目数应为 2） =====
HTTP 200
revision=3
entryCount=2
entryTexts=["山","水"]

===== 8) POST /admin/v1/textbooks/lab-a/publish（发布，Idempotency-Key） =====
HTTP 200
body={"code":"OK","data":{"editionId":"lab-a","contentVersion":"1.0.0","versionId":"lab-a@1.0.0","contentSha256":"f6668c373dade85a186daaf7da59bc0b3e82985bc8c1a07c057fa9ce5fa19915","snapshotReloaded":true},"requestId":"01fff1de-24bf-455e-b60c-7d5a5b0a2ce4"}

===== 9) 公开接口读回已发布版本（验证发布真的对外可见） =====
HTTP 200
publicBody={"code":"OK","data":{"edition":{"editionId":"lab-a",...,"contentVersion":"1.0.0","contentSha256":"f6668c37...","reviewSta...
```

要点：

- Cookie 传递正确：预认证 Cookie（`toolbox_admin_login_local`）用于登录，
  正式会话 Cookie（`toolbox_admin_local`）用于后续请求，登录响应里预认证 Cookie 被 `Max-Age=0` 清除。
- CSRF 传递正确：`X-Toolbox-Admin-Csrf` 的取值来自登录响应的 `data.csrfToken`。
- 并发前提正确：`PUT` 带 `If-Match: "draft-2"`，服务端返回新修订号 3 与新 ETag `"draft-3"`。
- 幂等键正确：每次写操作用**新**的 `Idempotency-Key`（`create-<uuid>` / `save-<uuid>` / `publish-<uuid>`）。
- 发布确实对外可见：`snapshotReloaded=true`，随后公开接口读回该版本为 200。

数据库侧佐证：

```
$ docker exec toolbox-mysql-test mysql -uroot -p*** -e \
  "USE toolbox_test; SELECT edition_id, current_version_id FROM toolbox_textbook;
   SELECT version_id, content_version FROM toolbox_textbook_version;
   SELECT draft_id, revision, base_content_version FROM toolbox_textbook_draft;"
edition_id                    current_version_id
lab-a                         lab-a@1.0.0
local-acceptance-2026-10-07   NULL

version_id      content_version
lab-a@1.0.0     1.0.0

draft_id                              revision   base_content_version
draft-lab-a                           4          lab-a@1.0.0
draft-local-acceptance-2026-10-07     3          NULL
```

### 5.d 前端 4173 代理可达（401 而不是 404/502）

```
$ curl -s -i http://127.0.0.1:4173/admin/v1/textbooks
HTTP/1.1 401 Unauthorized
Vary: Origin
x-request-id: e20e4d10-b4aa-4123-b0ce-2bf9042bbda4
cache-control: no-store
content-type: application/json;charset=UTF-8
content-length: 156

{"code":"TOOLBOX_ADMIN_001","messageKey":"admin.session.required","details":{"reason":"sessionRequired"},"requestId":"e20e4d10-..."}
```

对照（公开前缀同样经代理）：

```
$ curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:4173/public/v1/tools
200
```

### 5.e CDP 无头 Chrome 读到 DOM 标题

驱动脚本：`/tmp/toolbox-local-env/browser-smoke.mjs`（全文见 §8），
它直接复用仓库自带的 `toolbox-web/tests/e2e/cdp.mjs`（`findChrome` / `launchChrome` / `connect` /
`evaluate` / `waitFor`），用 `mkdtemp` 建**独立临时 profile**，验证完删除。

```
$ cd /tmp/toolbox-local-env && E2E_TARGET=http://127.0.0.1:4173 node browser-smoke.mjs
E2E_TARGET=http://127.0.0.1:4173
CHROME=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome
临时 profile 目录=/var/folders/18/m880fd0x23b5pp66h8631x9c0000gn/T/toolbox-browser-smoke-HD9FCs/chrome-profile
DOM 标题=禹宇天的工具箱
URL=http://127.0.0.1:4173/#/
readyState=complete
根节点=DIV 子节点数=1
可见文本片段=禹宇天的工具箱 首页 PDF 字帖 单词 更多工具，持续扩展。 首页 搜索工具 开始制作字帖 管理员登录 每一次使用，都让工具更有价值 免费开放 · 无需登录
EXIT=0
```

**如何避免误连生产**（三道）：

1. 脚本**强制要求** `E2E_TARGET` 存在，缺失即退出码 2（不落回默认值）；
2. 脚本拒绝任何非 `http://127.0.0.1:` 开头或含 `yuyutian.top` 的目标，直接退出码 2；
3. 仓库里 `tests/e2e/run.mjs` 的默认目标是 `https://toolbox.yuyutian.top`——
   本次**没有**直接跑 `npm run test:e2e`，而是用 `cdp.mjs` 的导出函数自己驱动，
   并显式传入本机目标；仓库的 `scripts/verify-release.mjs` 同样对未设置 `E2E_TARGET`
   或指向公网的取值记为「未执行」，与本次做法一致。

---

## 6. 未达成项与真实错误

### 6.1 ❌ 教材标识 ≥ 26 字符时「发布」失败（真实缺陷，H2 测试未覆盖）

**现象**：用 `editionId = "local-acceptance-2026-10-07"`（27 字符）走完整链路时，
前 7 步（登录、建草稿、保存、读回）全部成功，第 8 步发布返回 503：

```
===== 8) POST /admin/v1/textbooks/local-acceptance-2026-10-07/publish（发布，Idempotency-Key） =====
HTTP 503
body={"code":"TOOLBOX_008","messageKey":"service.unavailable","details":{},"requestId":"849860cd-8387-44be-8ab4-d742c70478e7"}

===== 9) 公开接口读回已发布版本 =====
HTTP 404
```

**根因**（`/tmp/toolbox-local-env/backend.log` 中的真实栈）：

```
ERROR ... GlobalExceptionHandler : unexpected request failure,
  path=/admin/v1/textbooks/local-acceptance-2026-10-07/publish

org.springframework.dao.DataIntegrityViolationException: PreparedStatementCallback;
  SQL [UPDATE toolbox_textbook_draft SET base_content_version = ? WHERE draft_id = ?];
  Data truncation: Data too long for column 'base_content_version' at row 1
  at ...AdminTextbookRepository.updateDraftBaseVersion(AdminTextbookRepository.java:172)
  at ...CurriculumPublisher.publishDraft(CurriculumPublisher.java:187)
  at ...AdminTextbookService.publishLocked(AdminTextbookService.java:336)
```

**事实链**：

- `AdminTextbookRepository.publishVersion` 里 `versionId = editionId + "@" + contentVersion`（第 324 行）；
- `CurriculumPublisher.publishDraft` 把该 `versionId` 写入 `toolbox_textbook_draft.base_content_version`；
- 而 `V3__toolbox_admin.sql:51` 定义 `base_content_version VARCHAR(32) NULL`：

```
$ docker exec toolbox-mysql-test mysql -uroot -p*** -N -e \
  "SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA='toolbox_test' AND COLUMN_NAME IN
   ('base_content_version','version_id','current_version_id','edition_id','draft_id') ORDER BY TABLE_NAME;"

toolbox_curriculum_audit	draft_id	varchar(64)
toolbox_curriculum_audit	edition_id	varchar(128)
toolbox_textbook	current_version_id	varchar(64)
toolbox_textbook	draft_id	varchar(64)
toolbox_textbook	edition_id	varchar(128)
toolbox_textbook_draft	base_content_version	varchar(32)      <-- 唯一放不下 versionId 的列
toolbox_textbook_draft	draft_id	varchar(64)
toolbox_textbook_draft	edition_id	varchar(128)
toolbox_textbook_version	edition_id	varchar(128)
toolbox_textbook_version	version_id	varchar(64)
```

- 触发条件：`len(editionId) + 1 + len(contentVersion) > 32`。首次发布 `contentVersion="1.0.0"`，
  因此 **`editionId` 长度 ≥ 26 就必然失败**（`lab-a` 成功，27 字符的 `local-acceptance-2026-10-07` 失败）。
- 为什么单测没能拦住：测试 profile 用 H2（`MODE=MySQL`），`VARCHAR(32)` 的截断不会被强制，
  而测试用例用的都是短 `editionId`（如 `preview-match-book`，17 字符）。也就是说，
  **在 H2 上这条路径永远不会因为该列失败**。本任务按边界要求没有运行全量测试，
  因此这里不断言测试结果，只陈述"该缺陷不可能被 H2 用例发现"这一机制性事实：
  只有把同一套代码接到**真实 MySQL** 才会暴露。
- 事务已正确回滚：失败的教材 `current_version_id` 仍为 `NULL`，
  `base_content_version` 仍为 `NULL`，`toolbox_textbook_version` 里没有它的行（见 §5.c 数据库佐证）。
- **本次未修改任何业务代码或迁移脚本**（超出授权范围）。修复方向（供业务线决策）：
  加宽该列（例如与 `version_id` 一致的 `VARCHAR(64)`，或按 `edition_id(128) + '@' + version` 取 `VARCHAR(160)`），
  以新增迁移的方式 `ALTER TABLE`，并给 `base_content_version` 补一条真实 MySQL 的用例。

### 6.2 ⚠️ 构建期间工作树被并发修改，一次编译中断

- `service/toolbox-service/` 与 `toolbox-web/` 在本仓库中是**未跟踪目录**（`git status` 显示 `??`），
  没有可回退的 HEAD 版本。
- 01:19–01:22 期间 `AdminTextbookService.java`、`CurriculumPublisher.java`、
  `CurriculumSnapshotLoader.java`、`DraftToBookMapper.java` 的 mtime 持续变化；
  01:21:31 的一次 `mvn spring-boot:run` 因源码处于中间态而编译失败：

```
[ERROR] .../AdminTextbookService.java:[105,32] 无法将记录 ...StoredDraft中的构造器 StoredDraft应用到给定类型;
[ERROR]   需要: AdminTextbookDraft,long,String
[ERROR]   找到: AdminTextbookDraft,long,String,String
[ERROR] .../AdminTextbookService.java:[128,37] 找不到符号 方法 buildDraftSnapshot(String,AdminTextbookDraft,String)
```

- 01:22 重试 `mvn -o test-compile` 通过，随后启动成功。上述缺陷 6.1 是**在一个可编译的版本上**复现的。
- 影响：本文所有证据对应 commit 未知的工作树快照（无法用 git 定位）。
  若业务线随后修改了这些文件，本文结论需要对新快照重跑一次。

### 6.3 其他观察（非失败）

- Flyway 9.22.3 对 MySQL 8.4 打印 `Flyway upgrade recommended: MySQL 8.4 is newer than this version of Flyway`
  ——只是提示，4 个迁移全部成功。
- 启动时 `curriculum requirements not available, using canonical slots`（`catalog-path` 下无
  `catalog/requirements.json`）——测试配置 `repository=database` 下使用内置规范槽位，符合预期。

---

## 7. 复现步骤（从零开始）

```bash
# 0) 前置：OrbStack/docker 运行中；toolbox-mysql-test 容器已存在且 toolbox_test 库为空
docker ps --filter name=toolbox-mysql-test --format '{{.Names}} {{.Image}} {{.Ports}}'

# 1) 编译（主源集 + 测试源集）
export DEVELOPER_DIR=/Library/Developer/CommandLineTools
cd /Users/pankang/mycode/MyTools/service/toolbox-service
mvn -o test-compile -DskipTests

# 2) 后端（后台）——注意 additional-classpath-elements 不可省略
export TOOLBOX_DB_PASSWORD=toolbox-test-only
export TOOLBOX_PORT=23199
mvn -o spring-boot:run \
  -Dspring-boot.run.profiles=local,local-admin \
  -Dspring-boot.run.additional-classpath-elements="$PWD/target/test-classes"

# 3) 前端（后台）
cd /Users/pankang/mycode/MyTools/toolbox-web
VITE_PUBLIC_API_TARGET=http://127.0.0.1:23199 npm run dev

# 4) a/b/d
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:23199/public/v1/tools          # 200
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:23199/admin/v1/textbooks       # 401
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:4173/admin/v1/textbooks        # 401

# 5) c 完整链路
bash /tmp/toolbox-local-env/admin-chain.sh lab-a

# 6) e 浏览器
E2E_TARGET=http://127.0.0.1:4173 node /tmp/toolbox-local-env/browser-smoke.mjs

# 7) 停止（PID 为本次实测值，见 §3）
kill 58280    # 后端 Java
kill 49094    # 前端 Vite
# 或按后台作业：job_kill bash-4711 / job_kill bash-4573
```

---

## 8. 附录：本次使用的两个脚本全文

### 8.1 `/tmp/toolbox-local-env/admin-chain.sh`

```bash
#!/usr/bin/env bash
# 仅本地验收用：管理员完整链路（登录 → CSRF → 建草稿 → 保存 → 读回 → 发布）。
# 目标固定为本机隔离环境 127.0.0.1:23199，不访问任何线上地址。
set -u
BASE=http://127.0.0.1:23199
DIR=/tmp/toolbox-local-env
JAR="$DIR/cookies.txt"
EDITIONS="${1:-local-acceptance-2026-10-07}"
rm -f "$JAR"

say() { printf '\n===== %s =====\n' "$1"; }

say "1) GET /admin/v1/auth/csrf（领取一次性 nonce 与预认证 Cookie）"
curl -s -D "$DIR/h1.txt" -o "$DIR/r1.json" -c "$JAR" -w 'HTTP %{http_code}\n' "$BASE/admin/v1/auth/csrf"
NONCE=$(jq -r '.data.csrfToken' "$DIR/r1.json")
grep -i '^set-cookie' "$DIR/h1.txt"
echo "csrfToken=$NONCE"

say "2) POST /admin/v1/auth/login（假身份服务：local-admin / local-admin）"
curl -s -D "$DIR/h2.txt" -o "$DIR/r2.json" -b "$JAR" -c "$JAR" \
  -H 'Content-Type: application/json' -H "X-Toolbox-Admin-Csrf: $NONCE" \
  -d '{"account":"local-admin","password":"local-admin"}' \
  -w 'HTTP %{http_code}\n' "$BASE/admin/v1/auth/login"
grep -i '^set-cookie' "$DIR/h2.txt"
CSRF=$(jq -r '.data.csrfToken' "$DIR/r2.json")
echo "body=$(cat "$DIR/r2.json")"
echo "sessionCsrfToken=$CSRF"

cat > "$DIR/draft-create.json" <<JSON
{"editionId":"$EDITIONS","subject":"chinese","grade":1,"semester":"first",
 "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
 "sourceNote":"本地验收用样例来源，第 1 页","coverageDeclaration":"partial",
 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1}],
 "lessons":[],
 "entries":[{"rowId":"row-1","entryId":null,"unitId":null,"clientUnitId":"cu1",
             "lessonId":null,"clientLessonId":null,"text":"山","pinyin":"shān",
             "meaning":null,"listTypes":["writing"],"confirmed":true}]}
JSON

say "3) GET /admin/v1/textbooks（带会话 Cookie）"
curl -s -o "$DIR/r3.json" -b "$JAR" -w 'HTTP %{http_code}\n' "$BASE/admin/v1/textbooks?page=1&pageSize=5"
echo "body=$(head -c 400 "$DIR/r3.json")"

say "4) POST /admin/v1/textbooks（新建草稿，Idempotency-Key）"
curl -s -D "$DIR/h4.txt" -o "$DIR/r4.json" -b "$JAR" \
  -H 'Content-Type: application/json' -H "X-Toolbox-Admin-Csrf: $CSRF" \
  -H "Idempotency-Key: create-$(uuidgen)" \
  -d @"$DIR/draft-create.json" -w 'HTTP %{http_code}\n' "$BASE/admin/v1/textbooks"
grep -i '^etag' "$DIR/h4.txt"
REV=$(jq -r '.data.revision' "$DIR/r4.json")
echo "body=$(cat "$DIR/r4.json")"

say "5) GET /admin/v1/textbooks/$EDITIONS/draft（读回草稿与修订号）"
curl -s -D "$DIR/h5.txt" -o "$DIR/r5.json" -b "$JAR" -w 'HTTP %{http_code}\n' \
  "$BASE/admin/v1/textbooks/$EDITIONS/draft"
grep -i '^etag' "$DIR/h5.txt"
echo "revision=$(jq -r '.data.revision' "$DIR/r5.json")"

say "6) PUT /admin/v1/textbooks/$EDITIONS/draft（If-Match: \"draft-$REV\" + Idempotency-Key）"
jq '.entries += [{"rowId":"row-2","entryId":null,"unitId":null,"clientUnitId":"cu1",
 "lessonId":null,"clientLessonId":null,"text":"水","pinyin":"shuǐ",
 "meaning":null,"listTypes":["writing"],"confirmed":true}]' \
  "$DIR/draft-create.json" > "$DIR/draft-save.json"
curl -s -D "$DIR/h6.txt" -o "$DIR/r6.json" -b "$JAR" -X PUT \
  -H 'Content-Type: application/json' -H "X-Toolbox-Admin-Csrf: $CSRF" \
  -H "If-Match: \"draft-$REV\"" -H "Idempotency-Key: save-$(uuidgen)" \
  -d @"$DIR/draft-save.json" -w 'HTTP %{http_code}\n' \
  "$BASE/admin/v1/textbooks/$EDITIONS/draft"
grep -i '^etag' "$DIR/h6.txt"
echo "body=$(cat "$DIR/r6.json")"

say "7) GET 草稿读回（确认保存生效：条目数应为 2）"
curl -s -o "$DIR/r7.json" -b "$JAR" -w 'HTTP %{http_code}\n' \
  "$BASE/admin/v1/textbooks/$EDITIONS/draft"
echo "revision=$(jq -r '.data.revision' "$DIR/r7.json")"
echo "entryCount=$(jq -r '.data.draft.entries | length' "$DIR/r7.json")"
echo "entryTexts=$(jq -c '[.data.draft.entries[].text]' "$DIR/r7.json")"

say "8) POST /admin/v1/textbooks/$EDITIONS/publish（发布，Idempotency-Key）"
curl -s -o "$DIR/r8.json" -b "$JAR" -X POST \
  -H "X-Toolbox-Admin-Csrf: $CSRF" -H "Idempotency-Key: publish-$(uuidgen)" \
  -w 'HTTP %{http_code}\n' "$BASE/admin/v1/textbooks/$EDITIONS/publish"
echo "body=$(cat "$DIR/r8.json")"

say "9) 公开接口读回已发布版本（验证发布真的对外可见）"
VERSION=$(jq -r '.data.contentVersion' "$DIR/r8.json")
curl -s -o "$DIR/r9.json" -w 'HTTP %{http_code}\n' \
  "$BASE/public/v1/textbooks/$EDITIONS/versions/$VERSION"
echo "publicBody=$(head -c 400 "$DIR/r9.json")"
```

### 8.2 `/tmp/toolbox-local-env/browser-smoke.mjs`

```javascript
// 仅本地验收用：用仓库自带 CDP 驱动启动一次无头 Chrome，打开本地前端并读取 DOM 标题。
// 显式要求 E2E_TARGET，并主动拒绝任何指向线上站点的取值，避免误连生产。
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { connect, evaluate, findChrome, launchChrome, sleep, waitFor }
  from '/Users/pankang/mycode/MyTools/toolbox-web/tests/e2e/cdp.mjs';

const base = process.env.E2E_TARGET;
if (!base) {
  console.error('未设置 E2E_TARGET：拒绝回落到默认的公网目标，本次未执行。');
  process.exit(2);
}
if (/yuyutian\.top/i.test(base) || !/^http:\/\/127\.0\.0\.1:/.test(base)) {
  console.error(`E2E_TARGET 必须是本机回环地址，当前为 ${base}：本次未执行。`);
  process.exit(2);
}
const port = Number(process.env.E2E_CDP_PORT ?? 9444);
const executablePath = findChrome();
if (!executablePath) {
  console.error('未找到 Chrome/Chromium。');
  process.exit(2);
}

const workDir = await mkdtemp(join(tmpdir(), 'toolbox-browser-smoke-'));
const userDataDir = join(workDir, 'chrome-profile');
console.log(`E2E_TARGET=${base}`);
console.log(`CHROME=${executablePath}`);
console.log(`临时 profile 目录=${userDataDir}`);

let chrome;
let cdp;
let exitCode = 1;
try {
  chrome = await launchChrome({ executablePath, port, userDataDir });
  cdp = await connect(port);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Page.navigate', { url: `${base}/#/` });

  const title = await waitFor(cdp, `document.title && document.title.trim() ? document.title : ''`,
    '页面标题', 60000);
  await waitFor(cdp, `document.readyState === 'complete'`, 'document.readyState=complete', 60000);
  const info = await evaluate(cdp, `
    const root = document.querySelector('#app') ?? document.body;
    return {
      url: location.href,
      title: document.title,
      readyState: document.readyState,
      rootTag: root ? root.tagName : null,
      rootChildCount: root ? root.children.length : 0,
      textSample: (root?.innerText ?? '').replace(/\\s+/g, ' ').trim().slice(0, 80)
    };
  `);
  console.log(`DOM 标题=${title}`);
  console.log(`URL=${info.url}`);
  console.log(`readyState=${info.readyState}`);
  console.log(`根节点=${info.rootTag} 子节点数=${info.rootChildCount}`);
  console.log(`可见文本片段=${info.textSample}`);
  exitCode = title ? 0 : 1;
} catch (cause) {
  console.error(`浏览器验收异常：${cause.message}`);
} finally {
  try { cdp?.close(); } catch { /* 已断开 */ }
  try { chrome?.kill('SIGKILL'); } catch { /* 已退出 */ }
  await sleep(300);
  await rm(workDir, { recursive: true, force: true });
}
process.exit(exitCode);
```
