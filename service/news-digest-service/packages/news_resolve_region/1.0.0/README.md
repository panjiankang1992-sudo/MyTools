# Execution node region probe

Python 3.12 节点探测包。用途是为 News 提供指定执行节点的出口地区证据；不是用户设备定位，也不发送消息。当前包是待发布源码，尚未登记 Scheduler 任务定义或安装到生产 Executor。

## 运行流程

1. 从 Executor 保留环境 `TASK_EXECUTOR_NODE_AFFINITY` 取得节点名，以 `TASK_EXECUTION_ID` 为报告 ID；任务参数不能指定城市、节点或 IP。
2. 根据固定地址族调用 ipify；默认 IPV4 使用 `api.ipify.org`，IPV6 使用 `api6.ipify.org`。不在两种地址族之间静默回退。
3. 优先查询本地 MaxMind 城市库；也可显式启用 MaxMind Web。Web 按步骤 2 得到的地址查询，而非按另一个连接的出口推测。
4. 再次探测出口；前后不一致或任一次探测失败，返回 EGRESS_UNAVAILABLE，不复用旧地区。
5. 只保存行政区、供应商版本及 HMAC 指纹，原始 IP 只在进程内存中使用。
6. 原子写入工作目录 `region-observation.json`（权限 0600），通过独立节点令牌上报 News。响应丢失后同一工作目录重试复用原报告，结果文件仅含 observationId/status/observationStatus。

News 接收凭据绑定一个预先指定的节点名称。这是可信节点代理上报协议，不是硬件证明或 Scheduler 实时租约验证。N14 将再接入 Scheduler 业务身份、fence 和版本化任务编排；本包现在不能视为已上线的定时任务。

## 配置

Executor 的 `script-environments.news_resolve_region` 已添加环境映射，值都由部署环境注入。首次启用前需在 News 设置匹配实际节点名的 `NEWS_REGION_NODE_ID`，并给该节点配置专用报告令牌。

| 环境变量 | 说明 |
| --- | --- |
| NEWS_SERVICE_URL | 默认 http://127.0.0.1:23350；跨主机必须 HTTPS。回传不走公网探测代理，也不继承系统代理。 |
| NEWS_REGION_REPORT_TOKEN | 至少 32 字符、独立于 News 管理与用户代理令牌，只供指定节点。 |
| NEWS_REGION_FINGERPRINT_KEY | 节点侧至少 32 字符的独立 HMAC 密钥；不传给 News，不放入任务参数。轮换后旧指纹缓存自然失效。 |
| NEWS_REGION_PROVIDER | MAXMIND_MMDB（默认）或 MAXMIND_WEB。 |
| NEWS_REGION_MMDB_PATH | 本地 GeoLite2-City / GeoIP2-City 文件；构建时间不得超过 30 天。文件更新由部署运维按供应商许可完成。 |
| NEWS_REGION_ADDRESS_FAMILY | IPV4（默认）或 IPV6，必须与新闻生成任务采用的主出口族一致。 |
| NEWS_REGION_PROXY_URL | 可选、无内嵌凭据的 `http://host:port` CONNECT 代理；HTTPS 目标仍验证证书。显式代理不受 NO_PROXY 绕过，未配置时不继承 HTTP_PROXY/HTTPS_PROXY。 |
| NEWS_REGION_MAXMIND_HOST | MAXMIND_WEB 时选择 geolite.info（默认）或 geoip.maxmind.com；不能填任意服务地址。 |
| NEWS_REGION_MAXMIND_ACCOUNT / NEWS_REGION_MAXMIND_LICENSE | 仅 Web 模式使用。配置 GeoIP 付费入口前需自行准备相应账号与额度；本次未调用付费服务。 |

MMDB 模式需要在 Executor 的 Python 3.12 环境预装 requirements.txt，当前固定 `maxminddb==3.2.0`。安装依赖不会自动下载 GeoLite 数据库，也不提供数据库使用授权。Web 模式仅用 Python 标准库。

探测和回传响应上限 128 KiB、单 socket 超时 8 秒、读取预算 20 秒。任务包总超时 90 秒由 Executor 强制执行；DNS 耗时不能由 socket 读取预算完全保证。所有重定向被拒绝，令牌不会随跳转传递。

网络隔离开启的现有 Executor 不能仅凭 allowed-domains 列表运行此联网包。必须先选择已配置、可联网的专用节点或完成受控网络能力，不能为此全局关闭其他任务的网络隔离。

## 本地验证

~~~bash
python3.12 -m unittest discover -s service/news-digest-service/packages/news_resolve_region/1.0.0/tests -v
python3 service/scripts/assemble_executor_packages.py
python3 service/scripts/executor_environment_contract_gate.py
~~~

默认单元测试不访问网络，不上报真实节点。可通过 `NEWS_REGION_TEST_MMDB` 指定官方二进制测试库，额外验证实际读取器；测试将时钟固定在该测试库构建时间附近，不能用于生产定位或绕过真实库的有效期。

包的网络探测和字段口径依据 [ipify](https://www.ipify.org/)、[MaxMind 请求协议](https://dev.maxmind.com/geoip/docs/web-services/requests/)、[响应字段](https://dev.maxmind.com/geoip/docs/web-services/responses/) 和 [Python MMDB 读取器](https://github.com/maxmind/MaxMind-DB-Reader-python)。
