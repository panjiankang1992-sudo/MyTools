"""Telegram Connector 配置。"""
from __future__ import annotations

from dataclasses import dataclass
import os


@dataclass(frozen=True, slots=True)
class Config:
    """单个 Telegram Bot 账户配置。"""

    account_key: str
    bot_token: str
    owner_id: int
    allowed_chat_ids: frozenset[str]
    api_base_url: str
    proxy_url: str | None
    messaging_url: str
    messaging_token: str
    internal_token: str
    poll_timeout: int
    maximum_file_bytes: int

    @classmethod
    def load(cls) -> "Config":
        """从环境变量加载并校验配置。"""
        chats = frozenset(value.strip() for value in
                          os.environ["TELEGRAM_CONNECTOR_ALLOWED_CHAT_IDS"].split(",")
                          if value.strip())
        value = cls(
            os.getenv("TELEGRAM_CONNECTOR_ACCOUNT_KEY", "telegram_main"),
            os.environ["TELEGRAM_CONNECTOR_BOT_TOKEN"],
            int(os.environ["TELEGRAM_CONNECTOR_OWNER_ID"]), chats,
            os.getenv("TELEGRAM_CONNECTOR_API_BASE_URL", "https://api.telegram.org").rstrip("/"),
            os.getenv("TELEGRAM_CONNECTOR_PROXY_URL") or None,
            os.getenv("TELEGRAM_CONNECTOR_MESSAGING_URL", "http://127.0.0.1:23250").rstrip("/"),
            os.environ["MESSAGING_INTERNAL_TOKEN"],
            os.environ["TELEGRAM_CONNECTOR_INTERNAL_TOKEN"],
            int(os.getenv("TELEGRAM_CONNECTOR_POLL_TIMEOUT", "45")),
            int(os.getenv("TELEGRAM_CONNECTOR_MAXIMUM_FILE_BYTES", "2147483648")))
        if value.owner_id <= 0 or not value.bot_token \
                or not 1 <= value.poll_timeout <= 50 or value.maximum_file_bytes <= 0:
            raise ValueError("Telegram Connector configuration is invalid")
        return value
