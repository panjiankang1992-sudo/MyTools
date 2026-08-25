# QQ Connector Service

官方 QQ Bot 原子连接器。它维护 Gateway、把 C2C 消息标准化写入 Messaging，并仅对授权发送者的精确“登录/登陆”命令创建 `onebot_relogin` 任务。任务成功后从 OneBot Connector 读取新鲜二维码并被动回复原消息。

凭据只来自服务端环境；连接器不执行 Shell、不接受路径、不直接修改业务数据库。旧 DownloadBot QQ Gateway 在影子验证完成前保持运行，但同一 AppId 不得同时启用两个 Gateway 消费者。
