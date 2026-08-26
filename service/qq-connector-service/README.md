# QQ Connector Service

官方 QQ Bot 原子连接器。它维护 Gateway、把 C2C 消息及附件标准化写入 Messaging，并仅对授权发送者的精确“登录/登陆”命令创建 `onebot_relogin` 任务。任务成功后从 OneBot Connector 读取新鲜二维码并回复原消息。授权发送者提交的 URL 或附件由 Message Automation 创建下载任务，连接器提供内部鉴权文本接口发送任务完成回执；被动回复窗口失效时自动降级为主动消息。

凭据只来自服务端环境；连接器不执行 Shell、不接受路径、不直接修改业务数据库。同一 AppId 不得同时启用两个 Gateway 消费者。
