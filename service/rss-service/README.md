# RSS Reader

MyTools 个人 RSS 阅读服务，Java 21，独立 Maven 构建；Gateway 登录主体隔离每位用户。支持 RSS 2.0、Atom 1.0、分组、搜索、已读与收藏、OPML、自动抓取和 90 天正文清理。

## 构建

```bash
bash service/rss-service/scripts/build.sh
```

产物：`target/rss-service-0.1.0-SNAPSHOT.jar`。先安装 `feed-core`，再构建服务；不要将本服务加入根项目 reactor。运行配置见 `src/main/resources/application.yml` 和 `../deploy/env.example`。API 见 [契约](../contracts/rss-v1.md)。

## 启用顺序

1. 为 `mytools_rss` 独立 schema 配置 MySQL 8.4 账户；字符集使用 utf8mb4。迁移由 Flyway 执行，不使用 root 作为服务账户。
2. 设置独立的 `RSS_INTERNAL_TOKEN`（至少 32 字符），Gateway 与服务使用同一值。密钥仅写入主机受保护的环境文件。
3. 部署服务 jar，检查回环端口 23360 的 `/actuator/health`。部署清单默认不启动 RSS。
4. 设置 `RSS_POLLING_ENABLED=true` 和 `GATEWAY_RSS_ROUTE_ENABLED=true` 后重启对应服务。Gateway 使用已有登录验证。
5. App 从「阅读 → RSS 订阅」进入，检测公开 HTTPS 来源、确认添加、等待首次轮询。

首版部署一个 RSS 服务进程，抓取总并发 4、同主机并发 1。数据库租约可防止重复提交，但主机并发闸门属于进程，不宣称集群全局限流。扫描间隔 30 秒，来源间隔可选 15/30/60/180 分钟；暂停与取消会使旧租约结果失效。失败退避并遵循 Retry-After，410 标记失效。仅公开 HTTPS 443；不支持需要 Cookie、登录或内网的来源。

单份 Feed 最多 2,000 条、原始及解压后均最多 8 MiB，可容纳十个新闻分类每日各 20 篇的完整七日窗口。2026-11-02 通过 `rss-reader-20261102-v3` 扩展容量，仍保留明确的条数、字节和 XML 深度限制。不会为了满足条数限制静默裁掉文章；超限仍返回 `RSS_006` 并保留已存内容。正文分页列表与单篇详情独立读取，阅读和收藏状态不因重新抓取而重置。

关闭 Gateway 路由可停止用户入口；关闭 polling 可暂停后台抓取，数据保留。卸载前先备份 schema；不要回滚删除迁移后的表。

## 验证

- 默认 `mvn test` 使用 H2 的 MySQL 模式、固定传输器与时钟，覆盖业务和并发。
- 真实 MySQL：设置 `RSS_TEST_DB_URL`、`RSS_TEST_DB_USERNAME` 后运行 `mvn test`。**只能指向专用测试库，测试会清空 RSS 业务表。**
- `bash app/scripts/test-rss.sh` 校验 ArkTS 响应边界与正文加载策略。
- `app/scripts/build-rss-acceptance.sh` 只临时替换入口，输出 `/private/tmp/rss-acceptance.hap`，退出后恢复生产入口。测试身份服务 `scripts/acceptance_identity.py` 只监听回环；不部署到服务器。
- 实际 MySQL、公开 Feed、模拟器与重启验收见 [记录](../../docs/verification/rss-reader/PROGRESS.md)。
