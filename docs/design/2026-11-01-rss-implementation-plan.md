# RSS App 实施方案与目标

日期：2026-11-01。用户已授权制定目标并推进开发，App 优先，视觉采用第 1 版。

## 目标与验收边界

完成可运行的 RSS 订阅闭环：鸿蒙 App 添加公开 HTTPS RSS/Atom → 后台自动抓取 → 图文阅读 → 已读/收藏跨设备持久化。首版同时交付分组、搜索、订阅管理、OPML 和失败状态。设计依据：[领域设计](2026-11-01-rss-reader-design.md)。视觉依据：[首页](2026-11-01-rss-ui/home.png)、[阅读](2026-11-01-rss-ui/reader.png)、[添加](2026-11-01-rss-ui/add-subscription.png)。图中来源、文章和缩略图仅为视觉样例，生产页面显示真实 Feed 数据。

## 工作包

| 编号 | 内容 | 状态 | 验收 |
| --- | --- | --- | --- |
| R01 | 契约、独立构建、数据库迁移 | DONE | 可从空库初始化；接口与 App 模型一致 |
| R02 | Feed Core：公网受限 HTTP、安全 XML、RSS/Atom、HTML 清洗、OPML | DONE | GUID、Atom XHTML、相对链接、XXE、SSRF、大小限制测试 |
| R03 | 订阅/文章/分组/状态持久化与轮询 | DONE | owner 隔离、幂等、修订保留状态、租约、退避、重启恢复 |
| R04 | Gateway 双前缀和认证 | DONE | 禁止伪造 owner，稳定响应信封，默认关闭开关 |
| R05 | App 入口、列表、阅读、添加与管理 | DONE | 选定视觉落地；真实接口；加载、空态、错误、取消与账号隔离 |
| R06 | OPML、搜索、批量已读与生命周期 | DONE | 导入部分失败与去重、版本冲突、收藏保留、清理 |
| R07 | 测试、编译/HAP、数据库与端到端验收 | DONE | 真实命令和证据；明确模拟与真实环境边界 |

## 实施决定

- Java 21、Spring Boot、Maven；新增 `service/feed-core` 与 `service/rss-service`，不加入根 reactor。借鉴既有新闻抓取安全策略，先为 RSS 提供中立库，不把新闻服务的构建迁移设为首版依赖。
- RSS 使用独立 schema；四张业务表及一张用户写入协调表 `rss_owner`，UUID 对外为字符串。内部请求 owner 来自 Gateway 认证主体。普通用户无管理令牌。
- 新接口使用 PUT 设置完整状态/设置，复用 App 已有 `putJson`，显式 expectedRevision；不新增 PATCH 通道。与设计草案的语义相同。
- Gateway 默认关闭 RSS 路由，独立内部令牌，无真实密钥进入仓库。建议端口 23360，实施时检查清单冲突。
- App 复用 AppTheme、AppControls、AuthorizedApiClient；RSS 独立目录，Index 仅接入口。图文不运行脚本、不携带业务登录态；图像按用户动作加载。
- 自动测试使用本地固定 XML 与注入传输器，不能放开生产公网校验以方便测试。真实网络验收另行显式启动。
- 当前工作区存在大量未提交变更，保留既有改动，只修改 RSS 集成必要位置。
- 本地 `java` 为 21，Maven 默认运行于 26，所有 Java 构建显式设置 `JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home`。首次检查 HDC 未连接设备，设备验收状态单独记录。

## 进度与证据

详细更新记录放在 [验收进度](../verification/rss-reader/PROGRESS.md)。只在上述工作包真实完成后标记 DONE；设计稿、测试夹具不等于功能上线。
