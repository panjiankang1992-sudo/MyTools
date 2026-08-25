# QQ Connector Service 详细设计

## 定位

QQ Connector 是官方 QQ Bot 的原子渠道适配器，负责 App Access Token、Gateway 长连接、C2C 入站转交和文本/图片出站。Messaging 保存标准消息，Task Scheduler 保存重登录执行状态，OneBot Connector 控制 NapCat；本服务不下载附件、不执行 Shell，也不拥有通用自动化规则。

## 登录命令链路

```text
QQ C2C “登录/登陆”
  -> QQ Connector 校验固定发送者并写入 Messaging
  -> Task Scheduler 创建 onebot_relogin 即时任务
  -> Executor 调用 OneBot Connector 固定重登录接口
  -> root path unit 重启 NapCat 并生成新二维码
  -> Executor 等待新鲜 PNG，任务成功
  -> QQ Connector 读取同一 requestedAt 的二维码
  -> 官方 QQ 文件上传与被动图片回复
```

只有精确命令、C2C 事件和服务端配置的发送者可以触发。幂等键绑定官方 QQ message ID；二维码路径、Bot Secret、OneBot Token 均不进入任务参数、结果或日志。

## 数据与恢复

独立 schema `mytools_qq_connector` 保存 Gateway 检查点和命令 Outbox。首版代码已完成 Gateway、Messaging 入站、任务创建/查询、二维码读取和图片回复；下一步把当前内存检查点和后台命令恢复接入该 schema，确保进程在任务创建后崩溃仍能继续回发。

## 迁移与验证

1. 部署但保持 `defaultEnabled=false`，以独立测试 App 或冻结事件验证协议。
2. 注册 `onebot_relogin` 定义和步骤包，验证任务结果不包含二维码或路径。
3. 停止旧 QQ Gateway 后启用新 Connector，避免同一 AppId 双消费者。
4. 真机发送“登录”，核对 Messaging 入站、任务实例、二维码生成和 QQ 图片回传。
5. 发送普通 URL、magnet 和附件，确认只由新自动化链路创建一次业务请求。
6. 观察稳定后停止旧 DownloadBot QQ/OneBot 入口，保留数据库与文件只读备份。
