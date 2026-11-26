# 教材录入简化：生产部署验收

2026-10-18 16:15（北京时间）已部署到 https://toolbox.yuyutian.top/ 。

## 结果

- 工具箱后端与生产前端均已切换，服务 active/running，进程 1647754，NRestarts=0。
- 发布标识：`simple-textbook-20261018-160439`。
- 后端 SHA256：`ed3d21002bf1ab6ed5ad09cdadf50ae5d1f4daf86db650f9017d25b7bbd63e45`。
- 线上 index.html SHA256：`118068330477c342b85568e06c5ae25530b4661b36683033c3e9703e8173a3fa`。
- 发布前核对远端 259 个前端文件的大小及 SHA256，全部匹配本地产物；发布后再从公网核对 29 个入口、页面和关联资源，全部匹配。
- 新增/编辑路由默认使用简洁的 TextbookEntryView；高级维护仅保留在 advanced 路由。
- 公网首页 HTTP 200，`/public/v1/health` 返回 UP，匿名 `/admin/v1/textbooks` 返回 401。
- 部署前后 6 本教材、2791 条内容，元数据、单元/课文结构、版本、逐条内容和覆盖清单一致。目录 epoch 不参与内容一致性比较。
- 未修改教材正文、数据库配置或服务环境；V1–V6 迁移与旧版完全一致，没有新增迁移。

## 验证边界

前端生产构建及相关测试通过。后端全模块 417 项中 416 通过，唯一失败是历史基线固定要求 5 本、而现状已为 6 本；原 5 本字节哈希和计数全部一致。本次不部署教材内容，线上全量数据对比也确认未变。详细原因保留在 backend-build.md，不将全模块测试称为全绿。

本次线上验收为匿名只读检查及产物一致性检查，没有管理员会话，因此没有向生产新增或修改教材来验证保存发布。此前本地组件、浏览器隔离接口及后端实际接口测试覆盖了简洁录入和发布流程。生产浏览器打开管理路由可观察到未登录重定向；浏览器渲染快照工具超时，未把它记作界面验收通过。

部署前最初探测曾观察到英语四上由 1.0.3 更新为 1.0.4，说明当时有并发编辑发布。正式 before 快照固定在该更新之后，抓取前后目录一致；after 与该正式 before 一致。

## 备份与回退

服务器目录：

`/opt/yuyutian/toolbox/releases/simple-textbook-20261018-160439/`

- `previous-backend.jar`：旧后端。
- `candidate-frontend/`：原子交换后保存的旧前端。
- `database-before.sql`：服务器本地数据库一致性备份，权限 0600，未下载到本地。
- `deployment.json`、`release-manifest.json`：部署回执与核对清单。
- `rollback-remote.py`：回退本次代码，拒绝覆盖已发生的后续部署，不回退数据库。

如需要回退，仅由已授权运维执行：

```sh
ssh home-ubuntu 'sudo -n python3 /opt/yuyutian/toolbox/releases/simple-textbook-20261018-160439/rollback-remote.py'
```

新前端候选保留旧构建的哈希资源，兼容部署前已打开的浏览器页面；不会把保留资源误计为本次新增文件。没有变动 MyTools 的其他服务。

## 证据

- deployment.json：服务器切换回执。
- frontend-build.md、frontend-dist-manifest.json：生产构建与逐文件清单。
- backend-build.md：后端测试、已知历史基线失败及 JAR 核对。
- public-before.json、public-after.json、PUBLIC-CHECK.md：公网全量只读对比。
- public-delivery.json：29 个公网资源的字节哈希核对。
- prepare-remote.py、deploy-remote.py、rollback-remote.py：本次已审查的发布与回退脚本。
