# DSH 应用连接修复

## 故障与修复

1. 线上 DSH 会话服务仍要求登录主体必须有 `sessionId`，旧认证契约不提供该字段。部署仓库已有的兼容实现：缺少会话 ID 时，以服务端验证过的访问令牌区分登录；继续检查账号白名单、令牌撤销状态、票据过期和一次性使用。
2. 真机换票仍返回 HTTP 500。`DshWebController.ticket` 声明 `Map<String, String>`，统一响应包装器加入嵌套 `data` 后，Jackson 仍按字符串值序列化，抛出 `Map1 cannot be cast to String`。返回类型改为 `Map<String, Object>`。
3. 换票修复后，真机完成 200 换票、303 跳转和后续 204 鉴权，但 ArkWeb 下载脚本反复超时。该页面首次加载约 13 MB 脚本、插件和样式，DSH 代理为 HTML 注入清除了上游压缩，却没有重新压缩 JavaScript/CSS。仅在 DSH 的 Nginx server 内开启 gzip，对脚本、样式和 JSON 压缩。

## 验证

- 新增成功换票的 MVC 回归测试，修复前稳定复现 `expected 200 but was 500`，修复后验证 `code=0000`、`data.url` 和 `Cache-Control: no-store`。
- 新增旧登录态重复换票、重连撤销和不同设备登录隔离测试。
- Java 21 下网关全套 185 项测试通过，其中 DSH 网页连接专项 11 项。初次默认 Java 26 运行因 Mockito/Byte Buddy 不兼容失败，改用项目要求的 Java 21 后通过。
- APP 一次性票据 URL 限制测试通过；无需修改或重装 APP。
- 生产网关健康检查通过，匿名换票、伪造票据和匿名网页访问仍返回 401。
- 真机已观测到换票 200、连接跳转 303、鉴权 204，HTML、JavaScript、CSS 和插件返回 200。
- Nginx 配置校验及 reload 通过；工作区白名单、代理鉴权、上游 DSH 认证保持启用。服务器使用相同资源进行 gzip 对比，原始总量 13,029,859 字节，gzip 总量 5,184,273 字节；此数据为服务器资源对比，不等同于手机完整下载验收。
- 启用压缩后真机再次加载，Nginx 的两个插件包响应量降为 320,761 和 3,974,678 字节，均返回 200。期间曾出现持续加载及 ArkWeb 的 `NetworkTransactionTimeout`、`connectionInfo:h3` 日志；随后用户明确反馈“已经可以连接dsh了”，连接恢复由用户确认。未单独验收聊天消息往返。
- 用户确认连接恢复后，不再进行 Cloudflare 协议调整；本轮未修改 Cloudflare 配置。保留已上线的认证兼容、换票序列化及资源压缩修复。

## 发布及回退

生产最终工件：`/opt/yuyutian/mytools/releases/dsh-connection-20261102-v2/mytools-gateway.jar`。

增量只包含 DSH 控制器、会话服务及其内部记录类；逐成员校验确认其他线上类、资源和依赖未变。`deployment.json` 记录最终摘要、原工件和专用 systemd drop-in。第一阶段会话兼容发布记录在 `deployment-v1.json`。

需要回退最终网关修复时，删除 `deployment.json` 指向的专用 drop-in，执行 daemon-reload 并重启网关，确认健康。回退全部本次网关修改还需移除 `deployment-v1.json` 指向的 drop-in。

压缩配置备份：`/opt/yuyutian/mytools/releases/dsh-connection-20261102-v2/dsh-lan.before-gzip.conf`。恢复该文件到 `/etc/nginx/sites-available/dsh-lan` 后，先执行 `nginx -t` 再 reload。

不记录生产凭据、票据、Cookie 或环境变量；检查流量时仅输出请求路径及响应状态。
