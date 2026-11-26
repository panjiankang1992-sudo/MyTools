# DSH 使用 MyTools 登录态

## 范围与授权

用户要求 APP 内使用 MyTools 登录态直接进入 DSH，并保留服务端认证。用户明确指定仅允许 `yuyutian` 访问当前共享 Ubuntu DSH 工作区。此方案不把共享工作区改造成多租户隔离工作区。

## 实现

- APP 删除口令输入入口，通过已授权 API 客户端调用 `POST /api/app/v1/dsh/web-ticket`。
- Gateway 使用现有 PrincipalValidator 校验登录态，并检查工作区账号白名单；缺失登录返回 401，无工作区权限返回 403。
- 票据使用 256 位随机数，30 秒有效、一次性消费；连接 URL 严格限定为 `https://dsh-app.yuyutian.top/_mytools/connect?ticket=...`。
- 消费票据再次校验登录态，设置 `__Host-mytools-dsh` Cookie（Secure、HttpOnly、SameSite=Lax、Path=/），立即跳转不带票据的根路径。
- 浏览器会话最长 30 分钟，Gateway 只在内存保存与 MyTools 登录会话的关联。每个新的代理请求重新校验原登录令牌及白名单；退出/撤销登录后拒绝后续请求。重新连接同一登录会话会撤销旧浏览器凭据。
- APP 退出账号或离开 DSH 时加载空白页，丢弃过时异步响应。进入页面及手动重试重新换票。
- Nginx auth_request 验证浏览器会话，再将服务器持有的上游 DSH Cookie 注入代理请求。上游 Cookie、启动口令、内部代理密钥均不返回 APP。
- 上游 DSH 认证保持启用。原生 DSH 直连入口仍使用自己的认证；APP 入口额外要求 MyTools 身份。入口拒绝跨站 Origin 和旧启动 token 参数。
- `refresh_upstream_cookie.py` 仅在 Ubuntu 内存中解析启动日志、换取 Cookie，并写入受限运行文件；5 分钟定时刷新。凭据没有进入仓库、终端输出或报告。
- DSH 网页统一走 HTTPS，不使用不具备 Secure Cookie 条件的局域网 HTTP 入口。

## 验证

- Gateway 全套 160 项测试通过；随后新增重连撤销测试，重新运行 DSH 专项 7 项测试，全部通过。
- 覆盖未登录、其他账号、登录撤销、会话 ID 变化、禁用开关、票据过期/重放、网页会话过期、伪造 Cookie、缺失内部代理凭据、异常上游 Cookie 和 Cookie 安全属性。
- APP URL 策略测试通过，覆盖合法票据、非 HTTPS、恶意域名、额外参数、片段、旧启动口令等。
- ArkTS HAP 构建通过；仍有恢复手动粘贴时已有的 READ_PASTEBOARD 静态告警，非本次 DSH 修改引入。
- Ubuntu 网关健康检查 UP。
- 本机网关匿名换票 401、伪造票据 401、无内部代理密钥 401。
- Nginx 未登录访问 401、直接访问内部鉴权路径 404、跨站 Origin 403、旧 token 参数 403。
- 服务端持有的 Cookie 访问上游 DSH 返回 200。
- 公网 DSH 匿名访问 401，公网 MyTools 换票匿名访问 401，公网内部鉴权缺失代理密钥返回 401。

## 尚未验收

2026-10-12 真机重新连接后，新 HAP 已覆盖安装，安装返回成功，EntryAbility 启动成功。

实际点击 DSH 标签发现 WebView 常驻主栈，首次挂载发生在其他标签，导致进入 DSH 时未触发换票、停留空白页。已在 ChangeMainTab 进入 DSH 时显式重新换票，重新构建、覆盖安装并复测；现在能触发连接并显示错误恢复界面，口令输入框已移除。

进一步检查发现旧认证接口没有 sessionId。已修复 Gateway 对旧登录态的兼容：用服务端已校验的访问令牌区分旧登录，继续逐次校验撤销状态和白名单；增加回归测试，DSH 专项 8 项通过。此项后端修复尚未部署。

当次公网 MyTools 入口返回 HTTP 530，home-ubuntu SSH 转发连接重置，备用 ssh.yuyutian.top SSH 通道握手超时。无法部署后端兼容补丁，尚未完成自动进入成功、DSH 会话切换和按钮点击验收。不能将安装成功视为完整功能验收。

已建立的长连接在 APP 离开页面/退出登录时关闭；服务端登录撤销在下一次 HTTP 请求或长连接重连时生效，不宣称即时中断所有已经建立的连接。

## 部署与回退

- 网关工件：`/opt/yuyutian/mytools/runtime/dsh-web/mytools-gateway.jar`。
- 增量启动配置：`mytools-mytools-gateway.service.d/zzzzzzz-dsh-web.conf`，保留此前服务环境和配置来源。
- 凭据目录：`/opt/yuyutian/mytools/config/dsh-web/`，仅服务端受限访问。
- 回退：恢复 `/opt/yuyutian/mytools/runtime/dsh-web/dsh-lan.before-sso.conf` 到原 Nginx 配置；移除本次网关启动覆盖项，daemon-reload 并重启网关；nginx -t 通过后 reload；停用 `mytools-dsh-cookie.timer`。原网关 JAR 未覆盖。
