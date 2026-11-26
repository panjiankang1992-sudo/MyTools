# 教材优先补全：后端验证与构建

日期：2026-10-18。仅本地构建与隔离测试，未部署、未操作生产数据。

## 实现

- 英语先查已发布教材的单词及表达，包含中文到英语、英语到中文；完整条目优先于括号外拆出的独立中文义项。
- 多份教材有不同答案时优先最近发布的教材，同日按教材标识及条目顺序稳定选择。无子串/模糊匹配。
- 已发布语文教材提供整词逐字拼音；未命中返回 failed，留给前端本机拼音库处理，不访问英语翻译服务或额度。
- 请求新增可选 subject，省略仍为英语；旧英语响应不增加 pinyinSegments 字段。
- 词典只读取已发布目录的单次不可变快照，不读草稿；目录内容与 generation 同步原子替换，发布后刷新英语/拼音索引及外部缓存。
- 管理端和公开入口共用服务，保留原身份与 CSRF 校验。

## 验证

Java 21，执行 `mvn -q test`：**429 tests，0 failures，0 errors，0 skipped**，共 63 个测试类。

新增 LocalTextbookTranslationTest 7 项涵盖教材先于常用词和外部渠道、短语及双向匹配、最新发布排序、完整词条先于别名、括号内不拆分、草稿/阻断/混合答案排除、精确拼音及未命中不扣额度、发布覆盖旧缓存、旧 JSON 兼容。公开 HTTP 测试增加 Chinese 契约与非法 subject；管理员真实创建发布测试增加即时教材补全断言。

完整结果在 [backend-test-summary.json](backend-test-summary.json)。历史 CurriculumBaselineReconciliationTest 本次 2 项均通过，本轮未改基线文件或基线测试。

测试日志含已有 Byte Buddy 动态代理、Flyway/H2 版本兼容提示和空测试目录提示；构建日志无 Maven WARNING/ERROR。不能将测试环境提示表述为零运行时告警。

## 产物

执行 `mvn package -DskipTests`：**BUILD SUCCESS**。

- JAR：`service/toolbox-service/target/toolbox-service-0.1.0-SNAPSHOT.jar`
- 字节数：28945602
- SHA256：`34f76d2ae27d39bbc9d2f505b4ed5dd10a9798563bf2e421e5e82c58b78eb98c`
- 数据库迁移：原 V1–V6，未新增。打包内校验值见 [backend-migrations.json](backend-migrations.json)。
- 构建输出：[backend-build.log](backend-build.log)。
