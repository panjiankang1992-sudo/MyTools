# OneBot Connector Service

NapCat 运行配置统一放在 `/opt/yuyutian/mytools/runtime/napcat`，登录状态和缓存仍使用
`/opt/napcat` 的独立业务数据目录。Docker 仅保留 `1m × 1` 的故障缓冲，完整标准输出由
`mytools-container-log@downloadbot-napcat.service` 转发到
`/opt/yuyutian/logs/mytools/downloadbot-napcat/service.log`，再按天、容量和十天上限轮转。

OneBot Connector owns NapCat/OneBot account routes, credential references, the fixed `get_file` call, safe local-path mapping, and bounded provider-content streaming. It is an atomic infrastructure connector used by Messaging Service; it does not own message or download business state.

## Safety defaults

- `ONEBOT_CONNECTOR_ENABLED` defaults to `false`.
- HTTP binds to `127.0.0.1:23255` by default.
- Account routes must point to loopback; credentials must use `env://NAME` references.
- Admin and provider-resolution APIs use separate bearer tokens.
- Only stable credential-free public HTTPS URLs are returned to Messaging. Local files, signed URLs, and authenticated OneBot URLs use the bounded `STREAM` endpoint.
- The service uses independent schema `mytools_onebot_connector`; it never reads or writes the legacy DownloadBot database.
- Relogin control uses only server-configured request and QR paths. Callers cannot provide a path or command, and the QR endpoint only returns a fresh bounded PNG.
- Inbound WebSocket events are persisted before Messaging delivery, then use bounded retry, leases, and fencing tokens; exhausted events enter `DEAD` instead of retrying forever.
- Before MySQL import, every inbound frame is atomically written with `0600` mode and `fsync` under the server-configured `ONEBOT_CONNECTOR_INBOUND_WAL_PATH`; restart recovery imports it with bounded retry and quarantines exhausted records for diagnosis.
- Text replies use a persistent idempotency key and `PREPARED` / `IN_FLIGHT` / terminal states so ambiguous provider outcomes are never sent again blindly.
- `/health/live` reports process liveness only. `/health` reports readiness and returns `503` after the startup/disconnection grace period, on a fatal or stale inbound worker, or while any WAL/database `DEAD`, failed reply, or uncertain reply remains. It exposes only booleans, ages, safe error classes, and aggregate counts; it never exposes message payloads or credentials.
- The last WebSocket frame age is diagnostic only. An idle but connected QQ account remains ready; connection state and worker scan progress determine readiness.

## Internal API

| Method | Path | Token | Purpose |
| --- | --- | --- | --- |
| `GET` | `/health/live` | none | Process liveness |
| `GET` | `/health` | none | Inbound delivery readiness and safe backlog counts |
| `POST` | `/internal/v1/accounts` | admin | Idempotently register a provider route |
| `POST` | `/internal/v1/provider-files/resolve` | internal | Return `PUBLIC_URL` or `STREAM` |
| `POST` | `/internal/v1/provider-files/content` | internal | Stream a `STREAM` resolution |

Provider requests contain only `channelType`, `accountKey`, `attachmentType`, and opaque `providerFileId`. Responses never contain provider credentials, local paths, or secret references.

Apply every versioned migration with `service/deploy/apply_python_migrations.py` before starting a newer connector. Register accounts disabled first, validate path mapping and `get_file`, then enable the account and finally the global gate.

旧 DownloadBot 配置可先生成不含凭据值的 dry-run 清单；`--apply` 只向回环地址 Connector 幂等登记强制禁用的账户，清单存在任何拒绝项时不会写入：

```bash
mytools-onebot-account-migration --config /path/to/downloadbot/config.yaml
mytools-onebot-account-migration --config /path/to/downloadbot/config.yaml --apply
```

The `onebot_relogin` executor package calls the fixed relogin endpoint, waits for a fresh PNG,
and records only the account key, request ID, and request timestamp in its result.

## Verification

```bash
PYTHONPATH=src python -m pytest
```
