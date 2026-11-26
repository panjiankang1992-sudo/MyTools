# 修复交付复核与下一轮 Agent 提示词

日期：2026-10-07。此文件补充既有设计和 2026-10-06 审查，不替代、不改写历史记录。本轮做了交付复核和环境探测，没有重新执行 381/682/22 全套测试，没有修复业务代码或部署。

## 已核实的事实

1. 当前验收矩阵有 33 行，逐行统计为 **11 通过、22 部分**；汇总写 21 部分是算术错误。且“必需实现缺失算未通过”的定义，与多类别 UI、冲突差异、四题型/当前快照/范围等未实现却标部分不一致。不要将汇总纠正理解为所有部分项已获验收。
2. 本次公网只读请求：`https://toolbox.yuyutian.top/public/v1/textbooks` 成功，catalogVersion=`catalog-5`、5 册、2635 条；匿名 `https://toolbox.yuyutian.top/admin/v1/textbooks` 返回401。公开前缀是 `/public/v1`，管理前缀是 `/admin/v1`。某个内网端口404不能证明公网前缀错误。
3. 当前 `/Applications/Google Chrome.app` 可用：已用仓库 CDP 驱动和独立临时 profile 成功启动 HeadlessChrome 154，打开本地 data 页面并读取 DOM。这仅证明自动化环境可用，不是管理功能已验收。
4. 项目已有隔离 H2 test profile、真实随机端口 Spring 测试、Vitest 挂载和 CDP 浏览器驱动。Docker CLI 与 OrbStack App 已安装，但当前 `~/.orbstack/run/docker.sock` 不存在，守护进程未运行；没有确认可用的隔离 MySQL。
5. `source_metadata_json` 仍在 `AdminTextbookRepository.java` 发布 INSERT 中写死 NULL。原设计 §5.4、§8 和 `toolbox-web/content/sources/*.json` 已提供来源规格基础，无需重新向用户索要字段定义。
6. 交付记录承认覆盖了 `toolbox-web/tests/admin-bulk-paste.test.ts` 原有测试。直接运行 `/Library/Developer/CommandLineTools/usr/bin/git` 可用，但该路径当前未被 Git 跟踪，不能假设 `git show HEAD:...` 能恢复原内容，更不能把失败命令重定向到现文件。
7. “JAR 大小和 mtime 一致”不是原始字节一致的充分证据。本轮没有重新 SSH 核对生产 JAR，不将该旧描述写成本轮生产二进制结论。

## 可直接复制给下一 Agent

---

继续完成 `/Users/pankang/mycode/MyTools` 工具箱管理员教材录入修复。已经进行过一轮修复，但尚未完成。不要从头重写，也不要把工作重点放在追加逐轮长日志。

先读：

- `AGENTS.md` 与 `service/AGENTS.md`。
- `docs/design/2026-10-05-toolbox-admin-textbooks/design-and-implementation.md`。
- `docs/verification/toolbox-admin-review-2026-10-06/review-and-plan.md` 及 `agent-prompt.md`。
- `docs/verification/toolbox-admin-fixes-2026-10-06/acceptance-matrix.md`、`deployment-and-unexecuted.md`；349KB README 仅按具体问题检索，不要求反复通读。
- 本文件的已核实事实。

以下取舍已经明确，可以直接继续实现，不必再询问用户：

### 一、来源快照按现有设计补齐

这是已有需求的内部数据实现，不需要另起产品决策。最小完整快照应有：

| 层 | 内容与来源 |
| --- | --- |
| 快照自身 | `snapshotSchemaVersion`、服务端 `capturedAt`、`capturedBy`、导入批次或发布操作ID；与原教材核对时间分开。 |
| 对应教材版本 | editionId、contentVersion、原book原始字节SHA-256；关联当前发布/导入版本。 |
| 导入目录基线 | 原manifest的catalogVersion/schemaVersion及本册声明元数据；保留原manifest快照或可恢复的原文件内容及SHA-256。不得拿contentVersion代替catalogVersion。 |
| 来源记录 | 按edition.sourceIds、entry.sourceRefs解析现有`sources/*.json`，冻结相关完整来源对象，包括sourceId、kind、标题、出版社、ISBN/版次、URL/locator、accessBasis、原verifiedAt、reviewedBy、reviewNotes、revisionReviews/evidence、knownGaps等已有字段。没有的值保持空并标记缺失，不编造。 |
| 文件证据 | 内容根目录内相对文件名、原始来源JSON UTF-8文本及SHA-256，供导出验证；不把机器绝对路径、凭据、环境配置装入包。 |
| 条目定位 | 原样保留 `{sourceId,locator,page}` 与来源记录的对应关系；不只保存笼统sourceNote。 |

实现原则：

1. 先在内存构建并验证快照，再沿 importer/service/repository 串到现有 source_metadata_json，草稿→发布→管理详情→导出→恢复都不能丢。
2. 来源元数据随发布版本不可变。导出从该版本保存的快照读，不能重新读取已变动的本地sources目录并冒充当年的来源。
3. 新的手工录入保留管理员填写的来源说明、页码和链接等，转换为明确来源记录；机器补全不生成“已经人工核对”的证据。不自动抓取外部来源，也不要求上传原照片。
4. 旧版本若历史上没存来源，仅在有可核实的同版留存文件时通过显式修复流程补充，并标注补录时间/依据，不回写原book正文或谎称原始发布时已经保存；无法对应的记录保留缺失说明。
5. 正常发布应生成新catalogVersion；**初始文件→DB迁移**应保留manifest.catalogVersion。当前导入复用了publishVersion递增catalog-N，不能宣称已经满足目录版本保真。
6. 验收：发布后修改本地sources，旧版快照不变；导出→隔离空库恢复后，book原bytes/hash、目录版本、来源对象/文件hash、sourceRefs、current指针、历史留存逐项一致。

### 二、接口路径无需再猜

- 公网：`https://toolbox.yuyutian.top/public/v1`；管理：`https://toolbox.yuyutian.top/admin/v1`，已于本轮GET核实。
- 本地 Vite 代理默认到 `http://127.0.0.1:23199`，以当前 vite.config.ts 和实际启动端口为准。
- 若需查后端部署路由，用当前本机 SSH 配置连接并先检查连通，再只读核对 systemd运行程序/监听地址/nginx精确代理段。不要输出环境文件、密钥或token；不要因23120返回404继续盲猜前缀。
- 生产仅只读，写入与故障注入都在隔离环境。不要把真实生产403作为所有测试的前置阻塞。

### 三、使用已有环境完成测试

- 浏览器已经可用。复用 `toolbox-web/tests/e2e/cdp.mjs`，独立profile，显式 `E2E_TARGET=http://127.0.0.1:<本地端口>`；不能忘记仓库E2E默认指向生产。
- 管理浏览器流程使用实际本地toolbox-service、独立测试库和仅测试profile可启用的假MyTools身份服务。不能在生产启用该身份替身，不硬编码生产账号。
- 检查并启动已有OrbStack，再创建命名明确、独立数据卷、仅回环绑定的MySQL测试容器，版本按项目实际要求；不复用生产数据库或其SSH隧道，不清理用户已有容器/卷。若运行时启动或镜像获取受阻，只报告该项实际错误，其他工作继续。
- 隔离H2可先覆盖恢复功能；真实MySQL用于事务/唯一键/锁及恢复复核。App挂载、四题型实现、来源接线、冲突UI都不依赖MySQL就绪。
- jsdom无法展开某个Naive UI折叠不等于功能不可测：单元层验证数据，真实浏览器验证懒渲染控件；用真实router或createMemoryHistory挂载App，勿只模拟useRouter然后声称已测路由切换。
- PDF和打印预览不需要实体打印机。先完成四题型PDF、至少三页、当前快照/范围、后台渲染、窗口关闭取消。实体出纸单独列状态，不能阻塞这些功能。

### 四、不删既定必需范围

保留完整设计目标和方案C：公开只提供当前版本；后台历史及不可变存储继续保留。冲突差异、四题型/当前编辑快照/选择范围、来源快照、files发布闸门、changeNote、多类别、OCR/历史等原定首期内容，按当前代码逐项补齐，不因预算自行取消。

原设计明确后续扩展的Excel、下架、恢复旧版为新版、独立审计页仍不做。不要借机增加新范围。

### 五、按实际产出推进

1. **先保护测试资产。** 调查被覆盖的admin-bulk-paste.test.ts，从已存原始输出/会话记录/编辑器本地历史/备份/实际Git历史寻找原用例，再与当前正确新语义合并。不能用新写13条宣称恢复原12条。无原件则如实记录不可恢复的缺口，重建必要覆盖，但不让该项阻塞独立功能。
2. **先补仍缺的业务实现。** 冲突差异与读取最新、四题型选择+当前编辑快照+范围、来源全链路、changeNote绑定、files模式发布闸门、多类别/来源录入和相应页面。files闸门导致旧测试失败时修正测试场景的数据源配置，不为全绿撤掉正确闸门。
3. **补确定性并发测试。** 前端用deferred Promise控制翻译返回顺序；后端用CountDownLatch/CyclicBarrier或测试专用hook控制刷新争抢与双册发布，给超时和清理。不要靠反复随机sleep等复现，更不能把“暂时造不出”判为需要用户决定。
4. **数据与真实页面验收。** 真实760/777/140条教材往返；空库从完整导出包恢复；App层退出/换账号/路由；五尺寸/深色/键盘；四题型PDF/打印预览。
5. **最后统一回归与交付。** 每个独立改动先运行针对性测试；没有新改动时不反复全量跑来消耗时间。完成一组改动或集成里程碑再跑全套。保留所有他人工作区修改，按模块分工并集成验证。

关于补全代次的测试：让两次请求使用相同rowId、相同原文与空白目标；替身在收到abort后仍允许旧Promise完成（模拟已到达客户端的迟到响应）；第二次保持pending，先完成第一次，断言目标仍空且旧结果不得合入；再完成第二次，断言只出现新结果。这样原文校验和“非空不覆盖”不会掩盖代次守卫。若UI当前busy阻止并发，则在允许的编辑/切页/取消/显式重试路径或提取出的调度层制造真实生命周期，不伪造不可达场景。

### 六、交付要求

- 新建精简的当前状态说明及机器可汇总矩阵；33项编号齐全，数量自动计算。
- 将“已实现且已验证”“明确缺实现”“已实现但未验证”“外部环境阻塞”分开。必需功能缺失不能一律藏在“部分”，旧证据标明日期和本轮是否重跑。
- 删除当前状态页互相矛盾的旧结论，历史逐轮记录可保留。至少修正“编辑器从未挂载”“刷新从未接线”等与当前代码冲突的表述。
- 提供命令、退出码、测试报告、五尺寸截图、四题型PDF、恢复对账及关键并发控制证据，不只给测试总数。
- 汇报本地构建与生产状态分开；生产二进制要核实hash/实际运行对象，mtime/大小不能证明全站版本一致。没有部署就明确未部署。
- 本轮测试新增证据放 `docs/verification/toolbox-admin-completion-<date>/`，不要覆盖10-05/10-06历史报告。

完成能独立做的工作后，只针对实际出现且无法自行解决的外部限制提问，说明具体错误与已尝试步骤；不要重复让用户定义已有来源规格、提供已知接口前缀或提供现成浏览器。
