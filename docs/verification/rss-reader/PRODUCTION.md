# RSS 生产发布

日期：2026-11-01。状态：**已上线，自动更新已启用**。已安装的 App 从「阅读 → RSS 订阅」进入，无需再次安装。

同日补丁：RSS 阅读服务已升级为 `rss-reader-20261101-v2`，解决一周 506 篇新闻超过旧 500 条上限而无法更新的问题，现有订阅已补入全部逐条详情。Gateway 仍保持以下 v1 发布版本；见[修复与验收](2026-11-01-week-feed-recovery.md)。

## 发布范围

- 版本：`rss-reader-20261101-v1`，远程目录 `/opt/yuyutian/mytools/releases/rss-reader-20261101-v1`。
- 新增 `mytools-rss-service.service`，以 `mytools` 用户运行，仅监听回环端口 23360，已设置开机启动和独立日志轮转。
- MySQL 8.4.11 新建独立 `mytools_rss` schema 和专用账号；Flyway V1 执行成功，账号无全局权限，只授权自身 schema。
- 专用配置位于远程 `/opt/yuyutian/mytools/config/rss`，目录 0700、环境文件 0600。RSS 轮询和 Gateway 路由均已启用。
- Gateway 基于正在运行的版本增量加入 11 个 RSS 所需 class。重建的既有过滤逻辑经过字节码对照；保留现有 DSH、PDF、下载等其他类、依赖、配置和启动参数，未发布工作区其他未上线功能。
- Gateway 的 RSS 配置使用独立 `gateway-rss.properties`。原有 `application.yml`、共享 `services.env`、全局 `current` 软链接、Cloudflare 和 Nginx 配置均未修改。
- RSS JAR 与本地验收产物逐字节一致。SSH 大文件通道较慢，改为从 Maven Central 获取固定版本依赖并逐项核对本地 SHA-256，再还原原始包；最终完整 JAR SHA-256 再次一致。

## 验收

- 发布用 Gateway 增量独立通过 23 项针对性测试，包括已有认证路由、RSS 双前缀、owner 注入和请求体边界：[测试结果](production-patch-tests.json)。
- 正式身份服务的两个专用验收账号完成登录，覆盖 Atom/RSS 检测、后台自动抓取、正文、已读/收藏写回、账号隔离、OPML 导出/重复预览和服务健康，共 15 项检查通过。测试订阅已取消、收藏已解除、分组已删除、两个验收会话已注销。
- 从开发机通过公网 `https://mytools.yuyutian.top`，以真实验收会话确认 `/api/app/v1/rss` 与 `/api/v1/rss` 均能认证访问、未登录均返回 401，读取接口及发现少数派 Feed 的 POST 请求成功；公网验收会话已注销。
- Gateway、RSS、Identity 均为 active/running。实际服务账号、回环监听、独立数据库权限、V1 迁移和配置文件权限核对通过。
- 生产链路：[服务与业务验收](production-verification.json)、[公网认证验收](production-public-verification.json)。

真机正式包已在前一轮覆盖安装并启动。本次上线后设备处于 HDC Offline，未补做手机界面的生产联调；公网验证使用正式身份和 API，没有使用本地身份替身。折叠屏与完整弱网矩阵仍属于后续兼容性验证。

2026-11-01 后续 App 更新：按用户要求改为文章列表左图右文、正文图片默认自动加载，移除加载确认按钮。原生模拟器已验证真实图片显示，正式 HAP 已重新构建并覆盖安装到 BRA-AL00 真机且启动成功；仅调整 App，生产后端无需重发。最新 HAP 摘要见 [artifacts.json](artifacts.json)。

同日再次调整文章卡片：来源/日期放顶部，图片与标题并排，摘要横跨下方整行，消除左下方空栏。构建与原生视觉验证通过；用户重新连接后，本次新包已覆盖安装到 BRA-AL00 真机并成功启动。[排版记录](layout-v2-review.md)。

## 回滚

以下命令在部署主机执行，移除本次 Gateway 覆盖配置、停用 RSS 并恢复旧 Gateway，随后核对旧包摘要和健康。数据库及订阅数据保留，不执行删库或删表：

```bash
sudo python3 /opt/yuyutian/mytools/releases/rss-reader-20261101-v1/deploy-production.py rollback
```

旧 Gateway 包保存在版本内 `apps/previous-gateway.jar`。发布差异、脚本、配置位置和摘要均已保留；凭据未写入仓库或验收报告。
