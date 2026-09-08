# Task Scheduler Client

独立领域服务访问 Task Scheduler 的公共 Java 客户端。

职责边界：

- 创建、查询、取消任务及读取步骤结果。
- 统一发送 `X-Task-Service-Id` 与 `X-Task-Business-Token`，将调用方身份绑定到独立可轮换凭据。
- 将 Scheduler 结构化错误转换为包含 HTTP 状态、错误码和可重试属性的异常。
- 只包含平台公共请求/响应模型，不包含任何领域任务名称、幂等键或业务参数组装。

领域服务保留一个薄适配器，负责定义任务名称、幂等键、优先级和业务参数，并委托本客户端发送请求。

Messaging、Drive、Media Library、Reader 与 Storage Gateway 均已迁移为领域薄适配器。它们的源码不再直接拼接 Scheduler API 路径。

从干净工作树构建已迁移服务时使用 reactor，确保公共模块先被构建：

```bash
cd service
mvn test
```

当前桌面环境运行 Java 26 时，现有 Byte Buddy 版本需要附加
`-Dnet.bytebuddy.experimental=true` 才能运行包含 Mockito 的测试；项目目标运行时仍为 Java 21。
