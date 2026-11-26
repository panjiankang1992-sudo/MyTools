# 公开 PDF 执行链与匿名隔离：实施方案

日期：2026-10-02。范围：设计文档 M06／M07 与契约 3.2、5.4、5.5、7 节。

**当前状态：步骤 0—1 已完成并验证，步骤 2—5 未开始。** 本文同时记录已完成部分、环境限制与后续步骤；已完成项都有可复现命令与退出码。

## 1. 为什么不能先做一半

契约与交接文档对这条链路有硬性约束：

- 公开实例必须使用**独立的 owner 命名空间**，不得让访客与真实用户共用 `owner_id`。
- 现有 Scheduler 的单一 business token **不足以构成领域隔离**，必须新增公开 PDF serviceId 的创建／读取／取消／结果访问守卫。
- 公开 runner **不得向共享 Asset Registry 注册产物**，否则公开 owner 数字会被共享资产系统解释成真实用户。
- Storage Gateway 的单一 internal token **不能假定按 root 隔离**，必须启动独立公开实例并新增受控删除能力。
- 基础设施无法支持上述隔离时，交接文档明确要求：**公开服务端 PDF 的上线验收保持未通过**，不得临时改成共享账号或放开内部接口。

因此本方案按「先隔离、后联通」推进；在隔离项全部通过前，`TOOLBOX_PDF_ENABLED` 必须保持 `false`，前端保持本机处理并在界面如实标注。

## 2. 落点勘察结果

| 组件 | 关键文件 | 需要做的事 |
| --- | --- | --- |
| pdf-service 派发 | `service/pdf-service/src/main/java/com/yuyutian/mytools/pdf/service/PdfUpstream.java`、`PdfService.java`、`src/main/resources/application.yml` | **已完成**：`scheduler-service-id`／`task-name`／`business-type`／`idempotency-prefix` 可配置，私有默认值不变 |
| pdf-service 门面 | `controller/PdfController.java`、`PdfStore.java`、`PdfRules.java` | 公开实例只读自身 owner 命名空间；不新增绕过 owned 的读取捷径 |
| 执行包 | `service/pdf-service/packages/pdf_process/1.0.0`、`1.0.1`、`build_package.py` | 已发布版本**不可原地修改**；新建 `service/toolbox-service/packages/toolbox_pdf_process/1.0.0/` 与独立构建脚本 |
| Runner 注册步骤 | `service/pdf-service/packages/pdf_process/1.0.1/scripts/main.py` | 公开包中关闭共享 Asset Registry 注册 |
| 包装配 | `service/scripts/assemble_executor_packages.py`（含测试） | 新包需按 manifest 正确声明 name／version／所需文件 |
| Scheduler 守卫 | `service/task-scheduler-service/.../config/TaskSecurityProperties.java`、`InternalTokenFilter.java`、`SecurityConfig.java`、`controller/PdfDeploymentController.java` | 参照既有 `service/ReaderAdaptationTaskGuard.java` 的模式，为 `public-pdf-service` 增加创建／查询／取消／结果四类访问守卫 |
| Storage 受控删除 | `service/storage-gateway-service/.../controller/StorageObjectController.java`、`service/InternalAuthorizer.java`、`service/StorageObjectService.java` | 新增仅公开实例启用的幂等受管对象删除；默认关闭；固定 public root |
| 公开门面 | `service/toolbox-service/`（已建） | 新增 `controller/pdf`、`service/pdf`、`mapper/pdf`、迁移 `V2__toolbox_public_pdf.sql` |

## 3. 实施顺序

每一步都以「可独立验证 + 不破坏私有实例」为前提。

### 步骤 1：Scheduler 公开任务域守卫 —— 已完成（未部署）

新增 `PublicPdfTaskAccess`（`service/task-scheduler-service/.../service/PublicPdfTaskAccess.java`），与既有守卫的关键差别是**双向**限制：

| 场景 | 结果 |
| --- | --- |
| 其他业务身份创建／读取／取消／读结果 **公开任务** | 401 `UNAUTHORIZED` |
| 公开身份创建／读取／取消／读结果 **非公开任务** | 403 `TASK_DOMAIN_FORBIDDEN`（新增错误码） |
| 公开身份操作公开任务 | 放行 |
| 其他身份操作自己的任务 | 放行，既有行为不变 |
| 认证关闭或未配置 `public-pdf-service` 身份 | 公开任务**失败关闭**，非公开任务不受影响 |

接入点只有两处，因此覆盖面完整：`TaskInstanceService.create()` 与 `TaskInstanceService.get()`。`cancel()` 与 `TaskInstanceController` 的 `GET /{id}/results` 都先调用 `get(id)`，所以取消与结果访问自动受同一守卫约束，不存在只保护其中一条路径的缺口。

同时在 `application.yml` 的 `business-clients` 下登记 `public-pdf-service: ${TASK_BUSINESS_PUBLIC_PDF_TOKEN:}`；空令牌会被既有规范化逻辑忽略，因此对当前部署无任何影响。

公开配置建议值（与契约 7 节一致）：`serviceId=public-pdf-service`、`taskName=toolbox_pdf_process`、`businessType=PUBLIC_PDF_TOOL`、幂等前缀 `public-pdf:`。

### 步骤 2：公开执行包

- 新建 `service/toolbox-service/packages/toolbox_pdf_process/1.0.0/`，从 `1.0.1` 复制为**新版本**，保留沙箱、fencing、校验与 Markdown 能力。
- 移除共享 Asset Registry 注册；产物只写入公开存储。
- 新增 `service/toolbox-service/build_public_pdf_package.py`（现有 `build_package.py` 写死了私有包名与版本，不能只换输出目录）。
- manifest 的 name／version／目录名与 SHA256 必须一致；已发布版本拒绝覆盖。
- 验证：`service/scripts/assemble_executor_packages.py` 的现有测试仍通过，且能发现新包；对比私有与公开包在固定 fixtures 上的语义结果（页数、尺寸、顺序、文字、图像方向一致，不要求二进制相同）。

### 步骤 3：Storage 受控删除

- 扩展 `storage-gateway-service` 内部对象服务，新增**仅公开实例启用**的受控幂等删除。
- 固定 public root；拒绝任意磁盘路径与 `storageUri`；验证根内解析结果、无活动读写租约、上游引用已撤销。
- 使用墓碑／删除租约防止检查后重新发布；删除失败保留持久重试。
- 默认关闭；私有实例行为不变。
- 验证：路径穿越、符号链接逃逸、共享引用、并发下载、重复删除、中断恢复各有集成测试。

### 步骤 4：公开门面

- 新增 `POST /pdf/uploads`、`PUT /pdf/uploads/{id}/content`、`POST /pdf/uploads/{id}/complete`、`POST /pdf/tasks`、`GET /pdf/tasks/{id}`、`POST /pdf/tasks/{id}/cancel`、`GET /pdf/tasks/{id}/artifacts/{index}/content`、`GET /pdf/tasks/{id}/artifacts.zip`。
- 会话归属校验：upload 与 task 关联前校验 owner 相等；其他访客资源统一按不存在返回。
- 幂等：先本地事务写任务 + outbox，再用稳定上游键调用；响应丢失后重放返回原资源。
- 持久执行槽位、2 小时排队／15 分钟执行／5 分钟发布预算，重启不重置期限（契约 5.4）。
- 响应剥离 `storageUri`、`assetId`、磁盘路径与内部 ID；字节流由服务端代理，不整文件读入内存。
- 验证：契约 5.3 的 DTO 契约测试（非 Markdown 请求不得含 `attachmentIds`）、范围去重与升序、分组顺序、取消竞争、重启恢复。

### 步骤 5：前端接通（M07）

- `services/pdf-adapter.ts` 的 `ServerPdfAdapter` 已按契约写好提交与轮询骨架，需补齐：1 MiB 固定分块续传、状态分阶段呈现、`clientRevision` 冻结、损坏／加密／无文字层的错误码解释。
- `VITE_PDF_EXECUTION` 切到 `server` 后，界面必须显示「文件将上传到服务器处理」。
- 验证：刷新恢复、掉线重试、参数改动后旧结果标注、过期提示。

## 4. 上线门禁

以下条件全部满足前，**不得**把 `TOOLBOX_PDF_ENABLED` 置为 `true`，也不得在界面显示「上传服务器处理」：

1. 两个匿名浏览器无法互读任务与文件（猜测、替换 ID 均失败）。
2. 伪造身份头（`X-Owner-Id`、内部 token）被拒绝。
3. 公开 token 无法读写私有任务域，私有 token 无法读写公开任务域。
4. 公开 runner 不向共享 Asset Registry 注册产物。
5. Storage 受控删除完成，且不影响原站存储。
6. 计算子进程在 bubblewrap 沙箱中无网络、无私有目录与服务凭据；缺沙箱时失败关闭。
7. 八项工具与私有引擎在固定 fixtures 上语义一致。
8. 取消、超时、重启、清理各有可复现证据。

## 4.1 环境限制：调度器测试套件无法在本机完整运行

`mvn -f service/task-scheduler-service/pom.xml test` 在本机**必然失败**，原因是仓库既有的 `V141__bound_remote_download_paths.sql` 使用了 MySQL 专有的 `JSON_SET`，而测试数据库是 H2：

```text
Detected failed migration to version 141 (bound remote download paths).
```

已独立复现该限制（与本次改动无关）：

```java
// H2 2.2.224 + MODE=MySQL
UPDATE t SET doc=JSON_SET(doc,'$.a',1) WHERE id=1
→ Function "json_set" not found
```

影响：所有基于 `@SpringBootTest` 且启用 Flyway 的用例在迁移阶段即失败（84 个）。这不是本次改动引入的，也不能通过修改生产迁移来"修好"——改动已发布迁移会改变校验和，让已迁移的数据库校验失败。

因此本次改动的验证采用以下可执行证据：

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 主代码与测试代码编译完整 | `mvn -f service/task-scheduler-service/pom.xml test-compile` | 退出码 0 |
| 守卫双向限制 | `mvn ... test -Dtest=PublicPdfTaskAccessTest` | 5 项通过 |
| 不依赖 Spring 上下文的既有用例 | `mvn ... test -Dtest=<18 个无 @SpringBootTest 的类>` | 62 项运行，0 失败，1 错误 |

唯一的错误是 `SchedulerRestartRecoveryTest`：它在运行时自行启动上下文，因此同样撞上 V141 的 H2 限制。**其余 61 项全部通过，说明既有行为未被破坏。**

## 5. 上一轮已完成的部分

**M06.2：派发标识可配置（私有默认值不变）**

| 文件 | 改动 |
| --- | --- |
| `service/pdf-service/src/main/resources/application.yml` | 新增 `pdf.scheduler-service-id`、`pdf.task-name`、`pdf.business-type`、`pdf.idempotency-prefix`，默认值分别为 `pdf-service`、`pdf_process`、`PDF_TOOL`、`pdf:` |
| `PdfUpstream.java` | `X-Task-Service-Id` 由配置读取，未配置时回退 `pdf-service`；`pdf.scheduler-token` 仍为必填 |
| `PdfService.java` | `taskName`／`businessType`／`idempotencyKey` 由配置读取，未配置时回退既有取值；其余参数（`businessId`、`priority`、`parameters.workId`）不变 |
| `PdfDispatchDefaultsTest.java`（新增） | 用本机 HTTP 替身捕获真实出站请求，断言**默认配置下**服务身份、任务定义、业务类型、幂等键与改造前完全一致 |
| `PublicPdfDispatchConfigTest.java`（新增） | 断言**显式配置公开取值**时四项全部替换，其余参数不变 |
| `SchedulerStub.java`（新增） | 测试替身，只监听回环地址与随机端口，不访问真实调度器 |

验证：`mvn -f service/pdf-service/pom.xml test` 退出码 0，7 项测试通过（原 5 项 + 新增 2 项）。**未重新部署 pdf-service**，生产实例未受影响；改动只增加可配置性，默认行为逐字节等价。

## 6. 部署状态与下一步

**本轮所有改动都只在本地仓库，未部署任何共享生产服务。** 服务器上运行的 `task-scheduler-service` 与 `pdf-service` 仍是改动前的构建产物。

部署步骤 1 之前需要明确确认，因为它会改动 MyTools 的调度服务：

1. 在服务器环境变量中为 `TASK_BUSINESS_PUBLIC_PDF_TOKEN` 生成独立随机令牌（不得与任何现有业务令牌重复——`TaskSecurityProperties` 会因令牌重复而拒绝启动）。
2. 运行完整 `task-scheduler-service` 测试套件（需要一个 H2 之外可用的 MySQL 测试库，或在具备 MySQL 的环境执行）。
3. 按现有发布流程替换 JAR 并重启，确认私有任务的创建／查询／取消／结果访问全部正常。
4. 记录回滚用的上一版 JAR 与配置引用。

下一步实现顺序仍是本文第 3 节的步骤 2（公开执行包）→ 步骤 3（Storage 受控删除）→ 步骤 4（公开门面）→ 步骤 5（前端接通）。
