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
    onebot_url: str
    onebot_token: str
    onebot_account_key: str

    @classmethod
    def load(cls) -> "Config":
        """加载必需配置并拒绝缺失凭据。"""
        value = cls(
            os.getenv("QQ_CONNECTOR_ACCOUNT_KEY", "qq_main"),
            os.environ["QQ_CONNECTOR_APP_ID"], os.environ["QQ_CONNECTOR_APP_SECRET"],
            int(os.environ["QQ_CONNECTOR_OWNER_ID"]), os.environ["QQ_CONNECTOR_ALLOWED_SENDER"],
            os.getenv("QQ_CONNECTOR_API_BASE_URL", "https://api.sgroup.qq.com"),
            os.getenv("QQ_CONNECTOR_TOKEN_URL", "https://bots.qq.com/app/getAppAccessToken"),
            os.getenv("QQ_CONNECTOR_GATEWAY_URL", ""),
            int(os.getenv("QQ_CONNECTOR_INTENTS", "33554432")),
            os.getenv("QQ_CONNECTOR_MESSAGING_URL", "http://127.0.0.1:23250"),
            os.environ["MESSAGING_INTERNAL_TOKEN"],
            os.getenv("QQ_CONNECTOR_SCHEDULER_URL", "http://127.0.0.1:23210"),
            os.getenv("QQ_CONNECTOR_ONEBOT_URL", "http://127.0.0.1:23255"),
            os.environ["ONEBOT_CONNECTOR_INTERNAL_TOKEN"],
            os.getenv("QQ_CONNECTOR_ONEBOT_ACCOUNT_KEY", "qq-napcat"))
        if value.owner_id <= 0 or not value.allowed_sender:
            raise ValueError("QQ Connector owner and allowed sender are required")
        return value
