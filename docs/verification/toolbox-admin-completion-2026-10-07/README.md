# 工具箱管理员教材录入 —— 本轮完成情况（2026-10-07）

**本目录是 2026-10-07 这一轮的证据。** 更早的报告
（`toolbox-admin-textbooks-2026-10-05/`、`toolbox-admin-review-2026-10-06/`、
`toolbox-admin-fixes-2026-10-06/`、`toolbox-admin-followup-2026-10-07/`）**未被覆盖**，
它们作为历史保留；**本文件里的结论只按本轮可重跑的证据判定**。

## 一、本轮实测的回归结果（我自己跑的，不是子代理转述）

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `mvn -o -f service/toolbox-service/pom.xml test` | **395 通过 / 0 失败 / BUILD SUCCESS** |
| 前端 Node | `cd toolbox-web && npm test` | **715 通过 / 0 失败** |
| 组件（真实页面） | `cd toolbox-web && npx vitest run` | **39 通过 / 7 文件** |
| 类型 | `npm run typecheck` | 退出码 0，无输出 |
| 生产构建 | `npm run build:production` | **0 告警** |

（进入本轮时的基线：后端 **381**、前端 **682**、组件 **22**。）

## 二、本轮修掉的真缺陷（都有可重跑证据）

| # | 缺陷 | 证据 | 等级 |
| --- | --- | --- | --- |
| 1 | **草稿 `base_content_version` 列宽不足**（`VARCHAR(32)` 装 `editionId@contentVersion`）→ 首次发布只要 editionId ≥ 26 字符就 **503**。**H2 不强制列宽，15 条单测全绿**，只有真实 MySQL 暴露 | [fix-draft-base-content-version-width.md](fix-draft-base-content-version-width.md)：修复前 503 → 修复后 200（同一 editionId，存 33 字符）；**反向验证**：把列改回 32 被 MySQL 以 `ERROR 1406 Data too long` 拒绝 | ✅ 真实 MySQL 前后对比 |
| 2 | **自动补全的请求代次守卫不可观察**：`force` 参数没有 UI 入口，导致"加不加守卫测试都通过"。补上「重新补全空白」入口后，**去掉守卫测试真的失败** | [README.md](README.md)、[verify-generation-guard.sh](verify-generation-guard.sh)、`0*.log` | ✅ 反向验证（退出码 1） |
| 3 | **保存冲突后不回读 revision** → 用户再点保存仍然 409，只能刷新整页 | [admin-editor-ui.md](admin-editor-ui.md)（冲突面板 + 读取最新） | ✅ 组件测试 + 反向验证 |
| 4 | **`pairsToEntries` 重建条目时丢掉 `preserved`** → 管理员改一个字就抹掉 sourceRefs/词性/义项等 | [admin-editor-ui.md](admin-editor-ui.md) | ✅ 往返测试锁住 |
| 5 | **`CurriculumFileImporter` 把裸 `contentVersion` 写进 `base_content_version`** → 与 `current_version_id` 永不相等，迁移教材一编辑就 409 `baselineMismatch` | [source-snapshot-chain.md](source-snapshot-chain.md) | ✅ 反向验证 |
| 6 | **审计里的操作者永远是 `unknown`**（读了一个过滤从不设置的属性） | 上一轮已修，本轮全仓排查确认只有一种取法 | ✅ 反向验证 |

## 三、本轮补齐的必需功能

| 功能 | 状态 |
| --- | --- |
| **来源快照全链路**（六层：快照自身 / 版本 / **manifest 基线** / 来源记录 / 文件证据 / 条目定位） | ✅ 构建+不可变+往返+导出恢复+补录，**5 项反向验证** |
| **从导出包恢复到空库** + 逐项对账 | ✅ 见 [source-snapshot-chain.md](source-snapshot-chain.md) |
| **冲突差异 + 读取最新** | ✅ 见 [admin-editor-ui.md](admin-editor-ui.md) |
| **changeNote** | ✅ 前端按设计契约发送（后端字段清单已交付） |
| **四题型选择 + 预览快照** | ✅ 题型选择与"先保存再预览"已实现并有测试；**真范围过滤缺后端支持，已如实标注而非假装过滤** |
| **多类别（listTypes）+ 条目级来源录入** | ✅ 复用服务端 `preserved` 通道，**不需要新增草稿 DTO 字段** |
| **files 模式发布闸门**（F11） | ✅ 新增错误码 `TOOLBOX_ADMIN_011(409, admin.publish.disabled)`；`FilesRepositoryPublishBlockedTest` / `DatabaseRepositoryPublishAllowedTest`，**去掉闸门反向验证失败** |
| **manifest.catalogVersion 保真**（F14） | ✅ 新增不推进 epoch 的写入路径 `importVersion`；`CurriculumCatalogVersionMigrationTest`（含 `@Order` 时间线），**撤销保留反向验证失败**（`expected catalog-7 but was catalog-0`） |
| **被覆盖的 12 条批量粘贴测试** | ✅ **原文已恢复**（见下） |

## 四、被覆盖测试的恢复（本轮完成）

`toolbox-web/tests/admin-bulk-paste.test.ts` 曾被一次 `cat >` 覆盖。本轮**恢复了原始文件内容**：

- 来源：DSH 会话记录 `~/.dsh/sessions/--Users-pankang-mycode-MyTools--/session-648b2790-…/session.v4.jsonl.zstd`
  里**两次写入的 heredoc 原文**（8 条 + 追加 4 条 = **12 条**）。
- **独立交叉印证**：`docs/verification/toolbox-admin-review-2026-10-06/frontend-tests.log` 第 27–38 行
  是**覆盖前**的一次 `node --test` 运行，12 条用例名与还原文件**逐条全等、顺序一致**。
- 产物：`/tmp/recovered-admin-bulk-paste.test.ts`（3607 字节，85 行，12 个 `test(`）。

**⚠️ 它是 Oct-5 的历史原件，不能直接覆盖回仓库**：当前 `admin-bulk-paste.ts` 的语义已**反转**
（`keepDuplicates` → `dedupe`；空行由"跳过"改为"保留原位"；标点由"剥离"改为"保留"）。
所以它作为**原用例清单**用于对照/合并 —— 这正是"不能拿新写的 13 条冒充恢复原 12 条"所要求的诚实处理。

## 五、未达成项（如实列出，不藏在"部分"里）

| 项 | 为什么没做 |
| --- | --- |
| **真实 760 / 777 / 140 条往返** | 生产目录已核实这三册存在（见 [production-baseline.md](production-baseline.md)），但**尚未**做迁移→编辑→发布→逐字段对账 |
| **四题型 PDF / 打印预览 / ≥3 页样张** | 浏览器已可用、打印链路在浏览器端；**本轮未执行** |
| **五尺寸截图 / 深色 / 键盘路径** | 同上，未执行 |
| **App 层挂载（路由切换、退出/换账号端到端）** | 未做（F02 的 App 层仍只有读代码级核对） |
| **恢复入口 UI / 运维流程** | 后端恢复服务已就绪，前端入口未做 |
| **预览的真范围过滤** | 需要后端支持（接口无范围参数、返回的条目没有 `rowId`/`clientUnitId`）——**已显式标注"仍按全册生成"，没有假装过滤** |
| **`SaveState.fail()` 重试路径接线** | **有意未接**：复用旧 payload 重发会让用户刚删的行"复活"，违反设计 §3.5 |
| **登录 403 的根因** | 历史遗留；本轮本地环境用真实链路跑通了登录，但**未回溯定位当年那个 403** |

## 六、环境与生产

| 项 | 状态 |
| --- | --- |
| 隔离 MySQL | ✅ 容器 `toolbox-mysql-test`（mysql 8.4.11，仅回环 `127.0.0.1:13316`，独立命名卷 `toolbox-mysql-test-data`），**未删除用户任何容器/卷** |
| 本地后端 | ✅ `127.0.0.1:23199`，`local,local-admin` profile |
| 本地前端 | ✅ `127.0.0.1:4173` |
| 假身份服务 | ✅ **仅测试源集**、`@Profile("local-admin")` + 配置键双重闸门、**不带任何生产凭据** |
| 浏览器自动化 | ✅ 仓库 CDP 驱动 + 独立临时 profile，**强制 `E2E_TARGET` 指向回环**，拒绝误连生产 |
| **生产** | ❌ **未部署、未写入、未创建账号、未发布教材、未回退**；只做只读核验（详见 [production-baseline.md](production-baseline.md)） |

## 七、矩阵

**33 项，15 通过 / 18 部分 / 0 未通过**（数量由脚本从行数据计算，见 [status-matrix.md](status-matrix.md)、
机器可读 [status-matrix.json](status-matrix.json)）。

**上一版矩阵写的"11 通过 / 21 部分"是算术错误**（33 行实际为 11 / 22），本版已纠正为自动计算。

## 八、本目录文件索引

| 文件 | 内容 |
| --- | --- |
| [status-matrix.md](status-matrix.md) / [.json](status-matrix.json) | 33 项矩阵（自动汇总）+ 可重跑命令 |
| [production-baseline.md](production-baseline.md) | 生产只读核验（含**真实 JAR SHA-256**） |
| [local-environment.md](local-environment.md) | 隔离运行环境、假身份边界、a–e 原始输出 |
| [source-snapshot-chain.md](source-snapshot-chain.md) | 来源快照六层、恢复演练、5 项反向验证 |
| [admin-editor-ui.md](admin-editor-ui.md) | 冲突/changeNote/题型/多类别/来源 UI，含后端字段清单 |
| [fix-draft-base-content-version-width.md](fix-draft-base-content-version-width.md) | 列宽缺陷的前后对比与反向验证 |
| [verify-generation-guard.sh](verify-generation-guard.sh) + `0*.log` | 代次守卫的可复现反向验证 |
| [evidence/](evidence/) | 快照各测试与反向验证的原始日志 |
