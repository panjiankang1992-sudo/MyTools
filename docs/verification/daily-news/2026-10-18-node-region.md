# 每日新闻速报：第三批执行节点地区链路验证

日期：2026-10-18。范围：N08 工程实现及其内容/Gateway 接入；实际节点和首个本地来源包仍未验收。相关材料：[实施计划](../../design/2026-10-18-daily-news-implementation-plan.md)、[服务说明](../../../service/news-digest-service/README.md)、[节点探测包](../../../service/news-digest-service/packages/news_resolve_region/1.0.0/README.md)、[接口协议](../../../service/contracts/news-digest-v1.md)。

## 1. 本批交付

- `news_resolve_region/1.0.0` Python 3.12 包：实际节点名、固定 IPv4/IPv6 出口探测、本地城市库/MaxMind Web、显式 CONNECT 代理、前后出口一致性校验、HMAC 指纹、私有重放日志和最小回执。
- News 独立节点报告鉴权，绑定单个部署配置节点；不接受普通用户自选城市、Web 请求 IP 或完整公网 IP 字段。
- V3 地区观测、不可变来源包和预览地区快照三张表；重试前已经绑定地区，切换节点或发生新观测不改变同一请求的地区。
- 城市半径策略、省级补充、24 小时观测有效期、同一连续出口定位失败时最多七日缓存。出口变动或未知观测阻断旧缓存。
- LOCAL 仅使用对应来源包名单，Gateway 新增鉴权下的 `/local-region`，用户查询参数不参与节点或城市选择。

以上节点身份目前由专用令牌和固定节点名保证，尚未接入 Scheduler 实时租约/fence；任务定义、自动调度、生产包登记属于 N14。源码包不代表已发布到 Executor。

## 2. 验证结果

| 验证 | 结果 | 说明 |
| --- | --- | --- |
| News Java 21 回归 | 97 项：93 通过，4 默认跳过，0 失败/错误 | 包括新增 12 项地区集成、节点鉴权与既有内容回归；跳过的是既有实网来源测试 |
| Gateway 定向回归 | 21 项通过 | 验证登录、忽略伪造城市/节点参数、地区查询转发及已有路由 |
| Python 默认测试 | 11 项：10 通过，1 跳过 | 默认无网络、无真实上报；官方 MMDB 二进制夹具测试按显式配置运行 |
| Python + 官方 MMDB 测试库 | 11 项全部通过 | 使用真实 maxminddb 3.2.0 读取器；固定测试时钟，结果是官方夹具地址，不是执行节点所在地 |
| 带数据迁移 | V1→V2→V3 通过 | H2 MySQL 模式；旧日报 JSON 保持不变 |
| 文件库重启 | 通过 | 地区报告和生成前绑定快照跨 Spring 应用上下文恢复；既有日报、技术快照与冷却也保持 |
| News 编译/打包 | 通过 | Java 21，`-Xlint:all -Werror` |
| 全仓库任务包装配校验 | 通过，149 个版本包 | 仅校验；没有写入生产发布目录 |
| 新包环境契约 | 通过 | news_resolve_region 所需环境变量均已映射 |
| 全仓库环境门禁 | 未通过 | 其他既有包存在缺项，见下文；未替其他工作区改动填写部署值 |
| 本机直连 ipify | 未通过 | ConnectionRefusedError；没有记录公网 IP，也未将本机定位当作执行节点证据 |
| 实际 Executor / 本地源覆盖 / MySQL | 未验证 | 缺少本批已配置的真实节点定位链路和真实数据库验收环境 |

机器可读证据：[node-region-test-summary.json](node-region-test-summary.json)。Java 测试仍有既有 Flyway/H2 兼容与动态 agent 提示；没有通过屏蔽提示宣称真实 MySQL 已验证。

## 3. 关键场景

1. 内部代理和管理员令牌不能上报节点地区，节点令牌不能读取用户日报或管理来源；空令牌拒绝访问，节点报告令牌必须独立且至少 32 字符。
2. 错节点、过旧/过未来/非毫秒时间、状态字段矛盾和额外 publicIp 字段被拒绝；同一报告完整重放成功，改变内容返回冲突。
3. 报告接收时间和观测时间均参与 cutoff 约束；迟到报告、后来登记的来源包不进入过去窗口。
4. 同城未列入包的来源、其他城市来源均不能进入本地栏目；来源包拒绝跨地区、跨分类、重复来源及同版本改写。
5. 城市精度不足与缺城市包按显式策略退到省级；只有国家证据保持空榜。关闭省级补充后不能静默扩大范围。
6. 新出口或未知出口中断旧缓存；即使稍后回到旧指纹，也需新的成功定位，不能直接恢复中断前缓存。过期观测和超过七日缓存不延长有效期。
7. 并发相同 owner/key 只保留首次地区快照；内容生成失败后的重试仍绑定同一地区。不同 owner 独立，历史日报不被新地区覆盖。
8. 固定地址族、不自动回退，排除内网/回环/保留/组播/IPv4-mapped IPv6 地址；显式代理不受 NO_PROXY 绕过。
9. Web 供应商限制官方 HTTPS 端点，结果地址须匹配已探测出口；禁止重定向。公共 HTTP 请求与节点报告回传的凭据、代理分别控制。
10. 任务日志权限 0600，仅存去标识材料；响应丢失重放原报告，标准错误输出只含固定错误码，不输出 URL、地址或凭据。

## 4. 复现

~~~bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -f service/news-digest-service/pom.xml test package
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -f service/mytools-gateway/pom.xml test -Dtest=NewsGatewayControllerTest,GatewayRequestFilterTest
python3.12 -m unittest discover -s service/news-digest-service/packages/news_resolve_region/1.0.0/tests -v
python3 service/scripts/assemble_executor_packages.py
python3 service/scripts/executor_environment_contract_gate.py
~~~

官方 MMDB 读取测试在独立 Python 3.12 虚拟环境安装 requirements.txt 后运行，使用 `NEWS_REGION_TEST_MMDB` 指向官方测试库。夹具来源为 [MaxMind-DB 测试数据](https://github.com/maxmind/MaxMind-DB/tree/main/test-data)，本次文件 GeoIP2-City-Test.mmdb 为 18,335 字节，SHA256 为 `7ecd1a263834096e3948b459713102ef350412a9f18367b4a88efec018153004`；测试库不随生产包发布。

全仓库环境门禁当前列出的其他包缺项：media_generate_tags、reader_analyze_audiobook_book 缺 TASK_ERROR_FILE；pdf_process 缺 PDF_ALLOW_UNSANDBOXED_TEST；video_generate 缺部分运行配置。详细键名保留在机器可读结果。本次新增包无缺项，但全仓库发布不能据此视为已通过门禁。

## 5. 下一步依赖与验收

- 配置实际新闻主节点和它的联网方式；节点网络隔离开启时，当前 allowed-domains 声明不能自动提供联网。
- 准备更新中的合法城市数据库或 MaxMind 账户，报告令牌及 HMAC 密钥通过部署环境注入。没有从用户环境搜集或展示真实密钥。
- 真实节点探测成功后，核对地区精度并登记首个来源包，检查 24 小时事件量与详情覆盖；不能保证每个地区都有 20/10。
- N14 接入 Scheduler 不可变版本包、业务身份、租约与到期扫描；随后实施订阅与渠道投递。真实 MySQL、节点联网和七日影子运行需单独验收。

本批未连接生产主机，未创建定时任务或发送真实消息。
