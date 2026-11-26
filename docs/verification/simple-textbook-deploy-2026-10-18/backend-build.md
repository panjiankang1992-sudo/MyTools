# 简化教材录入部署前后端构建（2026-10-18）

## 结论

- 已用 Java 21 完成 toolbox-service 全模块测试，并产出可部署 Spring Boot JAR。
- 全模块 417 项：416 通过、1 失败、0 错误、0 跳过。唯一失败是 2026-10-05 冻结目录总数基线仍为 5 本，实际目录已增加英语四年级上册为 6 本；原 5 本逐字节与条目数对账全部通过。没有修改冻结基线或测试来掩盖失败。
- 本轮简化教材录入及列表计数相关回归全部通过；真实生产行为仍须部署后验收。本报告作者没有连接或修改生产环境。

## 产物

- 路径：`service/toolbox-service/target/toolbox-service-0.1.0-SNAPSHOT.jar`
- 大小：28,937,641 字节。
- SHA-256：`ed3d21002bf1ab6ed5ad09cdadf50ae5d1f4daf86db650f9017d25b7bbd63e45`
- 打包完成时间：2026-10-18 16:07:15 +08:00。
- JAR 内包含 6 份 Flyway 迁移，不包含 `content/manifest.json`；教材内容使用运行时外部目录或数据库，本次仅构建服务产物。

## 执行命令与结果

工作目录：`service/toolbox-service`；`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`。

```sh
mvn clean verify
mvn package -DskipTests
```

第一条完整执行 417 项测试后因上述单项基线过时而退出 1；第二条在保留全部测试结果后单独打包成功，并非声称测试全绿。编译和打包未输出 Maven 编译告警；测试 JVM 有既有 ByteBuddy 动态代理与类共享提示。

日志：

- `/tmp/toolbox-deploy-backend-2026-10-18.log`
- `/tmp/toolbox-deploy-backend-package-2026-10-18.log`
- `service/toolbox-service/target/surefire-reports/`

关键回归：

| 测试类 | 结果 | 覆盖 |
| --- | --- | --- |
| AdminTextbookApiTest | 66 通过 | 最简语文/英语教材创建、保存、发布与公开读取；101 本符合条件草稿跨页计数一致、无遗漏 |
| CurriculumValidatorTest | 20 通过 | 出版社和版次可选、必要内容校验 |
| SourceSnapshotRoundTripTest | 4 通过 | 来源快照往返保留 |
| AdminTextbookRepositoryTest | 6 通过 | 教材持久化 |
| AdminTextbookPublishAndPreviewScopeTest | 8 通过 | 发布与预览作用域 |

## 唯一失败的定位与发布判断

`CurriculumBaselineReconciliationTest.catalogTotalsMatchBaseline` 仍对完整现状目录断言 2026-10-05 的 5 本 / 2,635 条。当前本地目录为 6 本 / 2,791 条，新增 `en-yl-4-1-yilin-2024` 的 156 条。协作主代理已只读确认线上也为 6 本；本轮不会部署 content 或改教材数据，因此该总数差异不是本轮服务修改引起的内容变动。

`everyEditionMatchesBaselineByteForByte` 通过。再次独立读取冻结基线并核对，原 5 本文件 SHA-256 均一致，条目数依次为：

| 教材标识 | 条目数 | 与冻结字节哈希一致 |
| --- | ---: | --- |
| cn-ty-1-1-pep-2024 | 318 | 是 |
| cn-ty-2-1-pep-2017 | 760 | 是 |
| cn-ty-3-1-pep-2024 | 777 | 是 |
| cn-ty-4-1-pep-2024 | 640 | 是 |
| en-yl-3-1-yilin-2024 | 140 | 是 |

此失败保留为已知历史基线范围问题，不作为本次仅服务与前端产物更新的阻断项；不得将全模块测试描述为全部通过。

## 数据库迁移和启动影响

本轮简化字段及列表计数修改没有新增数据库迁移。当前 JAR 的 V1–V6 与本地资源逐份校验一致。协作主代理从线上运行 JAR 读取并确认这 6 份迁移哈希也完全一致，因此正常 Flyway 启动仅校验已有迁移，不应增加或修改结构。

| 迁移 | SHA-256 |
| --- | --- |
| V1__toolbox_public.sql | 3f0499d4e9e797f3eeb14e799ef3309dcf8e75fc2031a6bf5246e5f0e88fff2d |
| V2__translation_quota.sql | b46ba116055befec8c6b7ec7118172261e2c6b7c4323ccc28e093a3e8644f0f4 |
| V3__toolbox_admin.sql | 93f8b8efc44388b3b64307cff76b717ee41389cd2c424ee7e88558cf511b9c04 |
| V4__admin_csrf_recovery.sql | 13d174fa9c74025bb141b15c2d39ed1dadd4b7299ccb480b49236a655a202ebe |
| V5__widen_draft_base_content_version.sql | dfc223025a502dcdfe2861c9aec915ea05f07c642c66d1c1eb5a8661d7767b3b |
| V6__widen_audit_version_columns.sql | ef30f302fb3228b7163eee6daf16f58feb1f5d6d69bfdc055a2f904c4e5eb012 |

保留线上现有环境配置，特别是教材仓储选择、管理员身份服务和令牌加密配置。旧版本备份与新 JAR 哈希比对应在部署端完成；重启后验收 `/public/v1/health`、公开教材目录及管理员接口鉴权。这里的隔离数据库测试不替代生产连接和启动验证。
