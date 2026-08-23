# OneBot Connector Service 详细设计

## 1. 定位与边界

OneBot Connector 是原子基础适配服务，负责 NapCat/OneBot 的账户路由、凭据引用、固定 `get_file` 调用、本地文件路径映射和受限内容流。Messaging Service 仍拥有消息与附件作业，Download Ingestion 仍拥有下载生命周期；本服务不复制二者的业务状态。

重复的 OneBot 鉴权、文件解析和路径映射从 DownloadBot 与消息链路收敛到此处。调用方只传账户稳定键和不透明 provider file id，不能传动作名、任意 URL、Shell 命令或数据库语句。

## 2. 分层与调用链

```text
Messaging attachment job
  -> resolve_provider_file task step
  -> OneBot Connector /provider-files/resolve
       -> fixed get_file
       -> PUBLIC_URL or STREAM
  -> download_message_attachment task
  -> Messaging bounded proxy
  -> OneBot Connector /provider-files/content
  -> Download Ingestion -> Asset Registry
```

- API 层：内部 Bearer 鉴权、64 KiB 请求上限、管理与运行令牌隔离。
- 应用层：全局开关、账户开关、严格请求字段、解析模式选择。
- 连接层：只允许固定 `get_file`；跳转重新校验并移除认证头；内容流设置硬字节上限。
- 持久层：独立 `mytools_onebot_connector.onebot_account`，只保存 `env://` 凭据引用，不保存明文。

## 3. 数据与安全设计

账户 API 地址只接受 loopback；映射后的真实路径必须仍位于宿主机根目录并为普通文件。`PUBLIC_URL` 仅允许无用户信息、无 query、无 fragment、DNS 全部解析为公网地址的 HTTPS URL。签名 URL 使用 `STREAM`，避免进入任务参数和日志。本机下载 URL 仅在首跳携带令牌；任何重定向必须成为通过 DNS 校验的公网 HTTPS 且剥离令牌。

## 4. 迁移与切流

1. 新建 schema 与最小权限账号，部署服务但保持全局关闭。
2. 从 DownloadBot 配置生成账户注册载荷；只迁移路由和环境变量名称，不复制令牌值。无法稳定识别的账户重新登记。
3. 账户先以 `enabled=false` 写入，离线验证路径根和 `get_file` 契约。
4. 启用单账户和全局开关，在 Messaging 侧执行影子附件解析；不改变旧 DownloadBot 消费入口。
5. 对账解析模式、字节数、SHA-256 和 Asset Registry 记录，通过后按账户灰度。
6. 观察期内保留旧链路快速回退；稳定后仅下线重复文件解析能力。

账户表属于配置数据，能可靠映射则幂等迁移；临时解析结果、过期签名 URL 和下载缓存不迁移，按新任务重新生成。

## 5. 实现与验收

当前已实现独立服务骨架、V1 schema、MySQL 仓储、固定动作客户端、双模式解析、有界流、鉴权 API 和契约测试。后续执行 NapCat 生产副本验证、Messaging 联调、单账户放量和回退演练。

验收要求：默认关闭时不能访问 provider；错误令牌不能跨管理/运行接口；响应不泄露凭据和本地路径；路径穿越、内网公网 URL、超限响应与内容均失败；新旧链路 SHA-256 一致。
