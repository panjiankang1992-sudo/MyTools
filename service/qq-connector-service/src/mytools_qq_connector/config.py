"""官方 QQ Bot 连接器配置。"""
from __future__ import annotations

from dataclasses import dataclass
import os


@dataclass(frozen=True, slots=True)
class Config:
    """只从服务端环境读取的单账户连接配置。"""

    account_key: str
    app_id: str
    app_secret: str
    owner_id: int
    allowed_sender: str
    api_base_url: str
    token_url: str
    gateway_url: str
    intents: int
    messaging_url: str
    messaging_token: str
    scheduler_url: str
    scheduler_token: str
    onebot_url: str
    onebot_token: str
    onebot_account_key: str
    automation_token: str
    login_wal_path: str = "/opt/yuyutian/mytools/runtime/qq/login-command-wal"
    login_max_attempts: int = 9
    login_retry_max_seconds: float = 60.0
    login_deadline_seconds: float = 300.0
    login_worker_poll_seconds: float = 0.5
    gateway_stale_seconds: float = 120.0
    rejected_event_max_records: int = 10_000
    inbound_wal_path: str = "/opt/yuyutian/mytools/runtime/qq/inbound-wal"
    inbound_max_attempts: int = 9
    inbound_retry_max_seconds: float = 60.0
    inbound_worker_poll_seconds: float = 1.0
    inbound_worker_concurrency: int = 4
    inbound_max_pending_records: int = 10_000
    inbound_max_payload_bytes: int = 16 * 1024 * 1024
    inbound_max_total_payload_bytes: int = 128 * 1024 * 1024
    inbound_done_max_records: int = 10_000
    inbound_dead_max_records: int = 1_000
    outbound_wal_path: str = "/opt/yuyutian/mytools/runtime/qq/outbound-text-wal"
    outbound_max_records: int = 10_000
    outbound_max_attempts: int = 9

    @classmethod
    def load(cls) -> "Config":
        """加载必需配置并拒绝缺失凭据。"""
        value = cls(
            os.getenv("QQ_CONNECTOR_ACCOUNT_KEY", "qq_main"),
            os.environ["QQ_CONNECTOR_APP_ID"], os.environ["QQ_CONNECTOR_APP_SECRET"],
            int(os.environ["QQ_CONNECTOR_OWNER_ID"]),
            os.getenv("QQ_CONNECTOR_ALLOWED_SENDER", "").strip(),
            os.getenv("QQ_CONNECTOR_API_BASE_URL", "https://api.sgroup.qq.com"),
            os.getenv("QQ_CONNECTOR_TOKEN_URL", "https://bots.qq.com/app/getAppAccessToken"),
            os.getenv("QQ_CONNECTOR_GATEWAY_URL", ""),
            int(os.getenv("QQ_CONNECTOR_INTENTS", "33554432")),
            os.getenv("QQ_CONNECTOR_MESSAGING_URL", "http://127.0.0.1:23250"),
            os.environ["MESSAGING_INTERNAL_TOKEN"],
            os.getenv("QQ_CONNECTOR_SCHEDULER_URL",
                      os.getenv("TASK_SCHEDULER_URL", "http://127.0.0.1:23410")),
            os.getenv("TASK_BUSINESS_QQ_TOKEN",
                      os.getenv("MESSAGING_INTERNAL_TOKEN", "")),
            os.getenv("QQ_CONNECTOR_ONEBOT_URL", "http://127.0.0.1:23255"),
            os.environ["ONEBOT_CONNECTOR_INTERNAL_TOKEN"],
            os.getenv("QQ_CONNECTOR_ONEBOT_ACCOUNT_KEY", "qq-napcat"),
            os.environ["MESSAGE_AUTOMATION_INTERNAL_TOKEN"],
            os.getenv("QQ_CONNECTOR_LOGIN_WAL_PATH",
                      "/opt/yuyutian/mytools/runtime/qq/login-command-wal"),
            int(os.getenv("QQ_CONNECTOR_LOGIN_MAX_ATTEMPTS", "9")),
            float(os.getenv("QQ_CONNECTOR_LOGIN_RETRY_MAX_SECONDS", "60")),
            float(os.getenv("QQ_CONNECTOR_LOGIN_DEADLINE_SECONDS", "300")),
            float(os.getenv("QQ_CONNECTOR_LOGIN_WORKER_POLL_SECONDS", "0.5")),
            float(os.getenv("QQ_CONNECTOR_GATEWAY_STALE_SECONDS", "120")),
            int(os.getenv("QQ_CONNECTOR_REJECTED_EVENT_MAX_RECORDS", "10000")),
            os.getenv("QQ_CONNECTOR_INBOUND_WAL_PATH",
                      "/opt/yuyutian/mytools/runtime/qq/inbound-wal"),
            int(os.getenv("QQ_CONNECTOR_INBOUND_MAX_ATTEMPTS", "9")),
            float(os.getenv("QQ_CONNECTOR_INBOUND_RETRY_MAX_SECONDS", "60")),
            float(os.getenv("QQ_CONNECTOR_INBOUND_WORKER_POLL_SECONDS", "1")),
            int(os.getenv("QQ_CONNECTOR_INBOUND_WORKER_CONCURRENCY", "4")),
            int(os.getenv("QQ_CONNECTOR_INBOUND_MAX_PENDING_RECORDS", "10000")),
            int(os.getenv("QQ_CONNECTOR_INBOUND_MAX_PAYLOAD_BYTES", str(16 * 1024 * 1024))),
            int(os.getenv("QQ_CONNECTOR_INBOUND_MAX_TOTAL_PAYLOAD_BYTES", str(128 * 1024 * 1024))),
            int(os.getenv("QQ_CONNECTOR_INBOUND_DONE_MAX_RECORDS", "10000")),
            int(os.getenv("QQ_CONNECTOR_INBOUND_DEAD_MAX_RECORDS", "1000")),
            os.getenv("QQ_CONNECTOR_OUTBOUND_WAL_PATH",
                      "/opt/yuyutian/mytools/runtime/qq/outbound-text-wal"),
            int(os.getenv("QQ_CONNECTOR_OUTBOUND_MAX_RECORDS", "10000")),
            int(os.getenv("QQ_CONNECTOR_OUTBOUND_MAX_ATTEMPTS", "9")))
        if value.owner_id <= 0 or not value.allowed_sender:
            raise ValueError("QQ Connector owner and allowed sender are required")
        if not value.login_wal_path or value.login_max_attempts < 1 \
                or value.login_max_attempts > 20 \
                or value.login_retry_max_seconds < 1 \
                or value.login_retry_max_seconds > 3600 \
                or value.login_deadline_seconds < 30 \
                or value.login_deadline_seconds > 3600 \
                or value.login_worker_poll_seconds <= 0 \
                or value.login_worker_poll_seconds > 30 \
                or value.gateway_stale_seconds < 30 \
                or value.gateway_stale_seconds > 3600 \
                or value.rejected_event_max_records < 1 \
                or value.rejected_event_max_records > 100_000 \
                or not value.inbound_wal_path \
                or value.inbound_max_attempts < 1 \
                or value.inbound_max_attempts > 100 \
                or value.inbound_retry_max_seconds < 1 \
                or value.inbound_retry_max_seconds > 3600 \
                or value.inbound_worker_poll_seconds <= 0 \
                or value.inbound_worker_poll_seconds > 30 \
                or value.inbound_worker_concurrency < 1 \
                or value.inbound_worker_concurrency > 32 \
                or value.inbound_max_pending_records < 1 \
                or value.inbound_max_pending_records > 1_000_000 \
                or value.inbound_max_payload_bytes < 1024 \
                or value.inbound_max_payload_bytes > 256 * 1024 * 1024 \
                or value.inbound_max_total_payload_bytes < value.inbound_max_payload_bytes \
                or value.inbound_max_total_payload_bytes > 4 * 1024 * 1024 * 1024 \
                or value.inbound_done_max_records < 1 \
                or value.inbound_done_max_records > 1_000_000 \
                or value.inbound_dead_max_records < 1 \
                or value.inbound_dead_max_records > 100_000:
            raise ValueError("QQ Connector login reliability settings are invalid")
        if not value.outbound_wal_path or value.outbound_max_records < 1 \
                or value.outbound_max_records > 1_000_000 \
                or value.outbound_max_attempts < 1 or value.outbound_max_attempts > 100:
            raise ValueError("QQ Connector outbound reliability settings are invalid")
        return value
