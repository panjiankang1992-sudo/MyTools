# Gateway 局域网入口

局域网入口固定为私网 IPv4 的 `23201` 端口，经 Nginx 转发至现有 `127.0.0.1:23200` Gateway。旧单体 `23110`、公网 HTTPS、DSH 原监听均保持原有配置。不能将新 App 的局域网请求直接发往旧单体，因为两个进程的认证路由和短期探测状态不同。

Gateway 的 bootstrap 返回 `lanPort: 23201` 与 `_mytools-gateway._tcp.local`。Avahi 使用独立服务名称广播相同端口，避免 App 选择旧单体的 `_mytools._tcp.local`。App 先从认证连接取得短期材料，再对该入口匿名挑战；挑战通过并确认当前身份后才允许发送后续会话请求。

## 生成文件

先在目标主机确认私网网卡地址，以及新端口没有被占用。以下地址为示例，必须使用当前主机实际网卡：

```bash
python3 service/deploy/generate_gateway_lan_config.py \
  --lan-address 192.168.8.9 \
  --dsh-proxy-config /etc/nginx/sites-available/dsh-lan \
  --output /tmp/mytools-gateway-lan-review
```

生成器只创建新目录，不安装文件、不重启服务。输出目录 `0700`，文件 `0600`，控制台仅返回非敏感摘要。Nginx 配置自动复用 `mytools-dsh-auth-secret.conf` 的 include，既不读取也不回显密钥；特殊路径可通过 `--dsh-proxy-key-include` 指定。已有站点直接声明静态密钥时，生成器仅把它写入私有配置，禁止将生成配置放入仓库或输出到聊天。

多物理网卡可重复提供 `--lan-address`，只在显式指定的地址监听；不使用通配地址，因此不会自动暴露 VPN 或容器网卡。

现有 DSH `sub_filter`、`sub_filter_once` 和 `sub_filter_types` 指令会按顺序保留，包含引号内的脚本和分号。DSH 仍保留 WebSocket、3600 秒读取超时和脚本压缩。生成后必须由 `nginx -t` 检查生产上下文中的模块、include 与注入指令；本地生成测试不代替目标 Nginx 检查。

## 部署顺序

1. 发布包含新 `ConnectivityModels`、`ConnectivityService`、`DshWebController` 和 `DshWebSessionService` 的 Gateway 工件。Gateway 继续监听回环 `23200`，不修改旧单体。
2. 将生成的 `mytools-gateway-lan.conf` 安装到独立的 Nginx 站点并启用，文件保持私有权限。先执行 `nginx -t`，成功后再 reload；失败时移除新站点，现有站点不变。
3. 核对当前私网地址和 `23201` 监听，检查入站防火墙是否允许对应网段访问。在确认端口从同网段可达后，将 `mytools-gateway.service` 安装到 `/etc/avahi/services/`，按当前主机的 Avahi 服务方式重新加载。新服务广播 IPv4 的 `23201`。
4. 按下节从同网段独立客户端检查端口和鉴权，再用登录状态下的 App 验证选路。

## 防火墙与同网段验收

部署前用 `ip -4 -o addr show scope global` 确认实际网卡地址和前缀。Nginx 加载后，用 `ss -lnt '( sport = :23201 )'` 核对监听地址与网卡一致。使用 UFW 的主机应同时检查 `sudo ufw status verbose`，因为默认拒绝入站时，Ubuntu 本机请求成功仍可能与其他设备的入站行为不同。

若需要放行，在当前防火墙中为每个实际监听地址添加限定入站网卡、客户端源网段、目标 IPv4 和 TCP `23201` 的规则。以下 UFW 示例使用 `enp3s0` 和 `192.168.8.0/24`，部署时替换为实际网卡和网段。

```bash
sudo ufw allow in on enp3s0 proto tcp from 192.168.8.0/24 to 192.168.8.9 port 23201
```

从与手机处于同一 Wi-Fi 网段的另一台设备请求私网地址。下面的探针使用固定无效材料，并只显示状态码。

```bash
curl --noproxy '*' --max-time 5 -sS -o /dev/null -w '%{http_code}\n' \
  -H 'Content-Type: application/json' \
  --data '{"probeId":"00000000000000000000000000000000","nonce":"abcdefghijklmnop"}' \
  http://192.168.8.9:23201/api/public/connectivity/challenge
curl --noproxy '*' --max-time 5 -sS -o /dev/null -w '%{http_code}\n' \
  http://192.168.8.9:23201/api/app/v1/identity/me
curl --noproxy '*' --max-time 5 -sS -o /dev/null -w '%{http_code}\n' \
  http://192.168.8.9:23201/actuator/health
```

预期依次返回 `401`、`401`、`404`。超时可核对入站防火墙与 Wi-Fi 客户端隔离，`403` 可核对 Nginx 来源限制，`502` 可核对回环 `23200` Gateway。

保持手机在同一网段，关闭 MyTools 后重新打开，保留登录状态。确认认证 bootstrap 包含当前私网候选，匿名 challenge 的实例、版本、随机值、过期时间与 HMAC 匹配，且该候选的身份请求成功。验收时应观察头像旁的连接标识变为「局域网」，再打开媒体或其他业务页面确认标识和请求正常。对照测试可离开家庭 Wi-Fi，观察 App 切换到可用的外网线路。

Nginx 对 `/api/` 保留原始路径、查询参数和 Authorization。新接口进入 Gateway；尚未迁移的旧接口通过以下明确边界继续访问回环 `23110`，沿用旧服务已有认证过滤器。相似前缀或未知路径不会自动回旧服务。

| 路径 | 旧服务匹配范围 |
|---|---|
| `/api/cloud`、`/api/ebooks`、`/api/localfiles` | 本身及其斜杠分隔子路径 |
| `/api/app/v1/local-media/` | 该前缀下的子路径 |
| `/api/public/feedback`、`/api/app/v1/files/download` | 精确路径 |
| `/api/app/v1/reader/data`、`/api/app/v1/reader/data/summary` | 精确路径 |
| `/api/app/v1/reader/source-discovery` | 本身或单层任务 ID |
| `/api/app/v1/media/thumbnail` | 精确路径 |
| `/api/app/v1/media/tickets` | 仅 POST，其他方法仍进入 Gateway |
| `/api/app/v1/media/tickets/{32位小写十六进制}/metrics` | 精确路径 |

播放票据 `/api/app/v1/media/tickets/{32位小写十六进制}` 优先请求 Gateway。仅收到 `404` 时，GET/HEAD 可在内部命名 location 用原 URI 访问一次旧服务；401、403、5xx 等不兜底，写方法也不兜底。其余 API 的 404 始终原样返回。

媒体上传支持现有 200 MiB 上限，流下载和上传关闭代理缓冲；所有 API 代理关闭缓存和自动上游重放，字节内容保持原样。普通访问日志关闭，避免保存播放或兑换票据。公共代理参数模板在生成阶段内联，部署时仍只需要一份 Nginx 站点文件。

DSH 使用单独的 LAN 桥接：

| 浏览器入口 | 受代理密钥保护的内部接口 |
|---|---|
| `/_mytools/connect?ticket=...` | `/internal/dsh-web/lan/connect` |
| Nginx 内部鉴权子请求 | `/internal/dsh-web/lan/authorize` |

LAN Cookie 为 `mytools-dsh-lan`，HttpOnly、SameSite=Strict、Path=/、30 分钟。其会话空间与公网 `__Host-mytools-dsh` 隔离，不能互换。公网 Secure Cookie 规则不变。新站点不开放 Gateway 的 `/internal/`、`/actuator/` 路径；上游 DSH Cookie 仅在 Gateway 与 Nginx 间传递。

回退时撤销本次添加的防火墙规则，再撤销新 Nginx 站点和独立 Avahi 服务文件并重新加载。以示例网卡和网段为例，使用 `sudo ufw delete allow in on enp3s0 proto tcp from 192.168.8.0/24 to 192.168.8.9 port 23201` 删除精确规则。网关工件回退遵循该次版本原有备份流程；不得覆盖另一会话部署的新工件。

## 本地验证

```bash
python3 -m unittest service/deploy/test_generate_gateway_lan_config.py service/deploy/test_gateway_lan_routes.py
mvn -f service/mytools-gateway/pom.xml test
```

测试覆盖私网地址限制、明确迁移边界、敏感配置权限、密钥 include 复用、原 HTML 注入保留、bootstrap/挑战跨入口共享状态、未知进程材料拒绝，以及 LAN 与公网 DSH 会话隔离。路由测试解析实际生成的 Nginx 配置，按精确、最长前缀、正则和命名 location 的优先级验证旧路径、新接口、方法分流及仅 404 的只读兜底。生产部署和真机网络切换验收需另行记录。
