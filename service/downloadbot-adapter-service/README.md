# DownloadBot Adapter Service

DownloadBot 到 Download Ingestion 的独立旁路适配器，使用 Python 3.12 和独立 `mytools_downloadbot_adapter` schema。该目录不加入根工程，也不修改或替换现有 DownloadBot。

默认 `DOWNLOADBOT_ADAPTER_MODE=DISABLED`：事件只进入新 schema 的幂等收件箱，不调用下载服务。仅在部署方显式设为 `SHADOW` 后，适配器才使用 `downloadbot:{eventId}` 幂等键创建新下载请求；旧 DownloadBot 始终保持权威执行和返回路径。

内部接口为 `POST /internal/v1/downloadbot/events`，要求 `DOWNLOADBOT_ADAPTER_INTERNAL_TOKEN`。数据库账号只需本 schema 的 DML 权限；调用 Download Ingestion 使用单独的 `DOWNLOAD_INGESTION_TOKEN`。

首版只接收请求事件。结果摘要双跑对账将在旧 DownloadBot 得到干净、可提交基线后增加，届时仍不允许适配器读取旧库密码、物理存储路径或改变旧任务状态。
