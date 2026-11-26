# DSH 持续重新连接修复

2026-11-14（Asia/Shanghai），用户反馈 MyTools 已进入 DSH 首页，但页面持续提示重新连接。

## 根因与修复

DSH 服务及上游认证正常。已安装版本使用 `/api/remote.mux` 承载实时连接；IPv6 和局域网统一入口将该请求按通用 `/api/` 规则转给 MyTools Gateway，返回 404，导致 DSH 持续重试。直接访问真实 DSH 上游的同一通道返回 101。

已修复两个入口的 DSH 路由，同时更新仓库生成模板。路由仅匹配当前 DSH 的实时通道、文件读取、事件结果及明确列出的 RPC 命名空间；MyTools 的业务接口和相似未知路径保持原分流。所有 DSH 请求继续经过网页会话认证，上游 Cookie 只在服务端使用，带 Origin 的请求必须同源。

首轮实时路由上线后，用户确认“已恢复，可以打开会话”。后续补齐同类 RPC 路由，避免会话、设置等普通 HTTP 调用被同一通用规则误转。

## 验证

| 检查 | 结果 |
| --- | --- |
| 修复前真实上游 WebSocket | 101。 |
| 修复前 IPv6 / LAN 实时路径 | 均为 Gateway JSON 404。 |
| 生成器与路由回归 | 17 项通过，覆盖现有业务分流、DSH 路径、相似未知路径、认证及代理设置。 |
| 最终生产 Nginx 配置 | `nginx -t` 通过，平滑重载成功。 |
| 最终 IPv6 / LAN 未登录请求 | WebSocket、RPC、无 Origin 的文件请求均为 401。 |
| 最终 IPv6 / LAN 跨站 Origin | 403。 |
| 实际配置块到真实 DSH 的隔离验证 | 两入口均完成 101 握手、WebSocket ping/pong；只读 `account/getState` 返回 200，RPC 关联标识和成功标记正确。 |
| 手机端 | 用户确认连接恢复、会话可打开；此反馈发生在首轮实时路由修复后。 |

隔离验证使用生产配置中的路由块、独立的回环 Nginx 进程和内存认证夹具，连接真实 DSH。它验证代理和上游协议，不冒充真实 MyTools 换票端到端验收。账号响应正文、Cookie 和其他凭据没有输出或保存；临时验证进程和目录均已清理。

测试命令（在 `service/deploy` 下执行）：

```bash
python3 -m unittest test_generate_gateway_lan_config test_gateway_lan_routes test_ipv6_direct_entry
```

本机默认 Python/Git 受未接受的 Xcode 许可阻塞，本轮使用已有独立 Python 3.12 和备用 Git 完成检查，没有修改系统许可或安装依赖。没有 Java 变更。

## 发布与回退

最终只修改生产 `/etc/nginx/sites-available/mytools-gateway-lan` 和 `mytools-gateway-ipv6` 中的 DSH 路由块，未重启 DSH 或 Gateway。

- 首轮备份：`/opt/yuyutian/mytools/config/dsh-web/stream-route-backup-20261114T011153Z`，包含故障前配置。
- 最终发布备份：`/opt/yuyutian/mytools/config/dsh-web/stream-route-backup-20261114T011821Z`，包含已恢复实时连接的首轮配置。若仅回退补充 RPC 路由，使用此备份。
- 最终 LAN 配置 SHA-256：`a5b8eff3505056b702c5de2be94bd2a3c95de8f601864374a99e3dc43cf22b5e`。
- 最终 IPv6 配置 SHA-256：`70593644dc59f024cbc6fa14e2694cc1833aef62ea4f9c55c484802ad463a5b8`。

补充发布的早期检查在旧 worker 尚未退出时读到旧 RPC 路由，触发自动回退。随后将检查改为等待新增 RPC 路由生效，最终发布通过。回退前应确认当前配置摘要未发生其他变更，恢复相应备份后先执行 `nginx -t`，再平滑重载。

DSH 升级或新增远程 RPC 命名空间时，应复核此路由允许列表并补充检查，不能将整个 `/api/` 改为转发到 DSH。
