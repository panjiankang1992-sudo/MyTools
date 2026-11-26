# 运维接口（无界面入口）

本文件记录**只通过 API 提供、界面上没有入口**的管理端接口。

**这不是缺陷**：设计文档没有要求为它们做界面；§8 把"切换公开内容来源"描述为
**运维的显式动作**，并提供了验证手段。但运维需要知道这些接口存在、以及怎么调用。

## 调用前提

所有接口都需要**管理员会话**（`__Host-toolbox_admin` Cookie）与 **CSRF 头**，
写请求还需要 `Idempotency-Key`。因此**不能直接用浏览器地址栏打开**。

最简方式：在已登录管理页的浏览器控制台里执行（同源、自动带 Cookie）：

```js
// 双读对账：判断"能否安全切换到数据库来源"
await fetch('/admin/v1/curriculum/reconciliation', { credentials: 'same-origin' })
  .then(r => r.json()).then(d => console.table(d.data));
```

```js
// 回退导出清单：含每册的 currentVersion 与全部历史索引
// 写请求才需要 CSRF 头；这两个都是 GET。
await fetch('/admin/v1/curriculum/export-manifest', { credentials: 'same-origin' })
  .then(r => r.json()).then(d => console.log(JSON.stringify(d.data, null, 2)));
```

## 两个接口的用途

| 接口 | 用途 | 何时用 |
| --- | --- | --- |
| `GET /admin/v1/curriculum/reconciliation` | 比较**文件仓储**与**数据库**的内容，给出 `switchSafe` | 把 `toolbox.curriculum.repository` 改为 `database` **之前** |
| `GET /admin/v1/curriculum/export-manifest` | 导出 `current` 清单与**全部历史版本索引** | 需要**回退到文件仓储**时，先导出以免丢失新发布内容 |

## 与验收矩阵的关系

- **D01**（迁移前后全量对账）由第一个接口支撑，已有 7 项单测 + 2 项 API 测试。
- **D02**（导出与回退）由第二个接口支撑；**回退演练本身**仍需部署环境与授权，尚未执行。

## 为什么不做界面

设计文档没有要求；而这两个动作**发生频率极低**（一次迁移只做一次），
为一个一次性动作建界面会增加长期维护面。

**若你希望有界面入口，请明确**——那是一个明确的范围扩张，我不会自行加。
