#!/usr/bin/env python3
"""每日新闻发布配置的共享约束，不输出凭据或环境内容。"""
from __future__ import annotations

import base64
import binascii
import ipaddress
import os
import re
import stat
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

TOKENS = (
    "NEWS_INTERNAL_TOKEN", "NEWS_ADMIN_TOKEN", "NEWS_JOB_TOKEN", "NEWS_REGION_REPORT_TOKEN",
    "TASK_BUSINESS_NEWS_TOKEN", "NEWS_REGION_FINGERPRINT_KEY", "MESSAGING_OUTBOUND_CLIENT_TOKEN",
    "MESSAGING_OUTBOUND_WORKER_TOKEN", "MESSAGING_OUTBOUND_ADMIN_TOKEN",
)
SECRET_KEYS = TOKENS + ("MESSAGING_DESTINATION_ENCRYPTION_KEY", "NEWS_DB_PASSWORD")
MAIN_FLAGS = (
    "GATEWAY_NEWS_ROUTE_ENABLED", "NEWS_COLLECTION_ENABLED", "NEWS_PIPELINE_ENABLED",
    "NEWS_SCHEDULING_ENABLED", "NEWS_DELIVERY_ENABLED", "NEWS_RETENTION_ENABLED",
    "MESSAGING_OUTBOUND_ENABLED", "MESSAGING_OUTBOUND_RETENTION_ENABLED",
)
TIMER_FLAGS = (
    "NEWS_PIPELINE_TIMER_ENABLED", "NEWS_SCHEDULING_TIMER_ENABLED", "NEWS_DELIVERY_TIMER_ENABLED",
    "NEWS_RETENTION_TIMER_ENABLED", "MESSAGING_OUTBOUND_RETENTION_TIMER_ENABLED",
)
DEFAULTS = {
    **{key: "false" for key in MAIN_FLAGS + TIMER_FLAGS},
    "NEWS_HTTP_HOST": "127.0.0.1", "NEWS_HTTP_PORT": "23350", "NEWS_DB_HOST": "127.0.0.1",
    "NEWS_DB_PORT": "3306", "NEWS_DB_NAME": "mytools_news", "NEWS_DB_USER": "mytools_news_digest",
    "NEWS_SERVICE_URL": "http://127.0.0.1:23350", "NEWS_SCHEDULER_URL": "http://127.0.0.1:23410",
    "NEWS_ZONE": "Asia/Shanghai", "NEWS_CUTOFF_TIME": "07:30", "NEWS_REGION_NODE_ID": "",
    "TASK_NEWS_NODE_VALUE": "absent", "NEWS_REGION_PROVIDER": "MAXMIND_MMDB",
    "NEWS_REGION_ADDRESS_FAMILY": "IPV4", "NEWS_REGION_MMDB_PATH": "", "NEWS_READER_URL": "",
    "NEWS_EVIDENCE_RETENTION_HOURS": "168", "NEWS_METADATA_RETENTION_DAYS": "90",
    "NEWS_EDITION_RETENTION_DAYS": "180", "NEWS_DELIVERY_RETENTION_DAYS": "90",
    "NEWS_RETENTION_BATCH_SIZE": "250", "MESSAGING_OUTBOUND_CONTENT_RETENTION_DAYS": "90",
    "MESSAGING_OUTBOUND_ATTEMPT_RETENTION_DAYS": "90", "MESSAGING_OUTBOUND_RETENTION_BATCH_SIZE": "250",
    "MESSAGING_OUTBOUND_CAPACITY_DESTINATION_ROWS": "1000",
    "MESSAGING_OUTBOUND_CAPACITY_DELIVERY_ROWS": "100000",
    "MESSAGING_OUTBOUND_CAPACITY_CANCEL_ONLY_ROWS": "100000",
    "MESSAGING_OUTBOUND_CAPACITY_PAYLOAD_BYTES": "536870912",
    "NEWS_CAPACITY_SOURCES": "1000",
    "NEWS_CAPACITY_SOURCE_PACKS": "10000",
    "NEWS_CAPACITY_REGION_NODES": "1000",
    "NEWS_CAPACITY_REGION_JOBS": "100000",
    "NEWS_CAPACITY_DIRECT_OBSERVATIONS": "10000",
    "NEWS_CAPACITY_ARTICLE_ROWS": "250000", "NEWS_CAPACITY_SNAPSHOT_ROWS": "250000",
    "NEWS_CAPACITY_WITHDRAWAL_ROWS": "100000", "NEWS_CAPACITY_PAYLOAD_BYTES": "536870912",
    "NEWS_CAPACITY_SUBSCRIPTIONS": "1000", "NEWS_CAPACITY_TARGET_OWNERS": "1000",
    "NEWS_CAPACITY_DAILY_RUNS": "100000", "NEWS_CAPACITY_PREVIEW_IDENTITIES": "100000",
}
ALL_KEYS = frozenset(DEFAULTS) | frozenset(SECRET_KEYS)
LIMITS = {
    "NEWS_EVIDENCE_RETENTION_HOURS": (1, 168), "NEWS_METADATA_RETENTION_DAYS": (38, 365),
    "NEWS_EDITION_RETENTION_DAYS": (1, 730), "NEWS_DELIVERY_RETENTION_DAYS": (1, 365),
    "NEWS_RETENTION_BATCH_SIZE": (1, 500), "MESSAGING_OUTBOUND_CONTENT_RETENTION_DAYS": (1, 365),
    "MESSAGING_OUTBOUND_ATTEMPT_RETENTION_DAYS": (1, 365), "MESSAGING_OUTBOUND_RETENTION_BATCH_SIZE": (1, 500),
}
CAPACITY_LIMITS = {
    "NEWS_CAPACITY_SOURCES": (1, 100000),
    "NEWS_CAPACITY_SOURCE_PACKS": (1, 1000000),
    "NEWS_CAPACITY_REGION_NODES": (1, 100000),
    "NEWS_CAPACITY_REGION_JOBS": (1, 10000000),
    "NEWS_CAPACITY_DIRECT_OBSERVATIONS": (1, 1000000),
    "MESSAGING_OUTBOUND_CAPACITY_DESTINATION_ROWS": (1, 100_000),
    "MESSAGING_OUTBOUND_CAPACITY_DELIVERY_ROWS": (1, 10_000_000),
    "MESSAGING_OUTBOUND_CAPACITY_CANCEL_ONLY_ROWS": (1, 10_000_000),
    "MESSAGING_OUTBOUND_CAPACITY_PAYLOAD_BYTES": (1, 100_000_000_000),
    "NEWS_CAPACITY_ARTICLE_ROWS": (1, 10_000_000), "NEWS_CAPACITY_SNAPSHOT_ROWS": (1, 10_000_000),
    "NEWS_CAPACITY_WITHDRAWAL_ROWS": (1, 10_000_000), "NEWS_CAPACITY_PAYLOAD_BYTES": (1, 100_000_000_000),
    "NEWS_CAPACITY_SUBSCRIPTIONS": (1, 100_000), "NEWS_CAPACITY_TARGET_OWNERS": (1, 100_000),
    "NEWS_CAPACITY_DAILY_RUNS": (1, 10_000_000), "NEWS_CAPACITY_PREVIEW_IDENTITIES": (1, 10_000_000),
}
MAX_ENV_BYTES = 1024 * 1024


class NewsConfigurationError(ValueError):
    """错误只携带固定标识及允许的配置键，不包含输入值。"""
    def __init__(self, code: str, keys: tuple[str, ...] = ()):
        super().__init__(code)
        self.code = code
        self.keys = keys

    def public(self) -> dict:
        return {"error": self.code, "keys": list(self.keys)}


@dataclass(frozen=True, repr=False)
class PrivateEnvironment:
    """保留原始字节及文件身份，防止更新覆盖中途修改。"""
    raw: bytes
    values: dict[str, str]
    device: int
    inode: int
    uid: int
    gid: int

    def __repr__(self) -> str:
        return "PrivateEnvironment[redacted]"


def parse_environment(raw: bytes) -> dict[str, str]:
    """解析单行 KEY=VALUE 与简单外层引号，不调用 shell 或进行插值。"""
    if len(raw) > MAX_ENV_BYTES or b"\0" in raw or b"\r" in raw:
        raise NewsConfigurationError("NEWS_CONFIG_FILE_INVALID")
    try:
        lines = raw.decode("utf-8").splitlines()
    except UnicodeError as error:
        raise NewsConfigurationError("NEWS_CONFIG_FILE_INVALID") from error
    result: dict[str, str] = {}
    for raw_line in lines:
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key) or key in result:
            raise NewsConfigurationError("NEWS_CONFIG_ASSIGNMENT_INVALID")
        value = value.strip()
        if value.startswith(("'", '"')):
            if len(value) < 2 or value[-1] != value[0] or value[0] in value[1:-1] or "\\" in value[1:-1]:
                raise NewsConfigurationError("NEWS_CONFIG_ASSIGNMENT_INVALID")
            value = value[1:-1]
        result[key] = value
    return result


def read_private(path: Path) -> PrivateEnvironment:
    """拒绝符号链接、硬链接及宽权限，限制一次读取大小。"""
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(descriptor, "rb") as stream:
            info = os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1:
                raise NewsConfigurationError("NEWS_CONFIG_PRIVATE_FILE_REQUIRED")
            raw = stream.read(MAX_ENV_BYTES + 1)
    except OSError as error:
        raise NewsConfigurationError("NEWS_CONFIG_FILE_UNAVAILABLE") from error
    return PrivateEnvironment(raw, parse_environment(raw), info.st_dev, info.st_ino, info.st_uid, info.st_gid)


def unchanged(path: Path, original: PrivateEnvironment) -> None:
    """提交前同时核对文件身份与原始内容，竞争修改直接失败。"""
    current = read_private(path)
    if (current.device, current.inode, current.uid, current.gid, current.raw) != (
            original.device, original.inode, original.uid, original.gid, original.raw):
        raise NewsConfigurationError("NEWS_CONFIG_CHANGED")


def flag(values: dict[str, str], key: str, default: bool = False) -> bool:
    """拒绝拼写错误的开关，而非静默按关闭处理。"""
    value = values.get(key, str(default)).lower()
    if value not in {"true", "false"}:
        raise NewsConfigurationError("NEWS_CONFIG_BOOLEAN_INVALID", (key,))
    return value == "true"


def endpoint(value: str, key: str, *, public: bool = False) -> str:
    """内部地址只允许 HTTPS 或回环 HTTP，拒绝凭据与可疑解析差异。"""
    try:
        if any(character.isspace() or ord(character) < 32 for character in value) or "\\" in value or "?" in value or "#" in value:
            raise ValueError()
        url = urlsplit(value)
        host = url.hostname or ""
        port = url.port
        try:
            loopback = ipaddress.ip_address(host).is_loopback
        except ValueError:
            loopback = host == "localhost"
        secure = url.scheme == "https" or (not public and url.scheme == "http" and loopback)
        if not secure or not host or url.username is not None or url.password is not None or url.query or url.fragment \
                or (not public and url.path not in ("", "/")) or (port is not None and not 1 <= port <= 65535):
            raise ValueError()
        return value.rstrip("/")
    except ValueError as error:
        raise NewsConfigurationError("NEWS_CONFIG_URL_INVALID", (key,)) from error


def validate_configuration(values: dict[str, str], stage: str) -> dict:
    """检查所选阶段所需文件配置，不宣称数据库、提供方或进程已验收。"""
    missing = tuple(key for key in SECRET_KEYS + ("NEWS_DB_USER",) if not values.get(key))
    if missing:
        raise NewsConfigurationError("NEWS_CONFIG_REQUIRED", missing)
    for key in TOKENS:
        token = values[key]
        if len(token) < 32 or any(c.isspace() or ord(c) < 32 for c in token) or "${" in token:
            raise NewsConfigurationError("NEWS_CONFIG_CREDENTIAL_INVALID", (key,))
    # 新身份不能复用彼此或既有内部任务身份，输出只包含本功能的配置键。
    for key in TOKENS:
        if any(other != key and value == values[key] for other, value in values.items()
               if other in TOKENS or other.endswith("_INTERNAL_TOKEN") or other.startswith("TASK_BUSINESS_")):
            raise NewsConfigurationError("NEWS_CONFIG_CREDENTIAL_REUSED", (key,))
    try:
        encrypted = base64.b64decode(values["MESSAGING_DESTINATION_ENCRYPTION_KEY"], validate=True)
        if len(encrypted) != 32:
            raise ValueError()
    except (ValueError, binascii.Error) as error:
        raise NewsConfigurationError("NEWS_CONFIG_ENCRYPTION_KEY_INVALID", ("MESSAGING_DESTINATION_ENCRYPTION_KEY",)) from error
    if values["MESSAGING_DESTINATION_ENCRYPTION_KEY"] == values.get("MESSAGING_REGISTRATION_MAIL_DELIVERY_ENCRYPTION_KEY"):
        raise NewsConfigurationError("NEWS_CONFIG_CREDENTIAL_REUSED", ("MESSAGING_DESTINATION_ENCRYPTION_KEY",))
    for key in ("NEWS_DB_USER", "NEWS_DB_NAME"):
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,63}", values.get(key, DEFAULTS.get(key, ""))):
            raise NewsConfigurationError("NEWS_CONFIG_DATABASE_INVALID", (key,))
    for key in ("NEWS_HTTP_PORT", "NEWS_DB_PORT"):
        if not values.get(key, DEFAULTS[key]).isdigit() or not 1 <= int(values.get(key, DEFAULTS[key])) <= 65535:
            raise NewsConfigurationError("NEWS_CONFIG_PORT_INVALID", (key,))
    for key, (low, high) in LIMITS.items():
        value = values.get(key, DEFAULTS[key])
        if not value.isdigit() or not low <= int(value) <= high:
            raise NewsConfigurationError("NEWS_CONFIG_RETENTION_INVALID", (key,))
    for key, (low, high) in CAPACITY_LIMITS.items():
        value = values.get(key, DEFAULTS[key])
        if not value.isdigit() or not low <= int(value) <= high:
            raise NewsConfigurationError("NEWS_CONFIG_CAPACITY_INVALID", (key,))
    try:
        ZoneInfo(values.get("NEWS_ZONE", DEFAULTS["NEWS_ZONE"]))
    except (ValueError, ZoneInfoNotFoundError) as error:
        raise NewsConfigurationError("NEWS_CONFIG_ZONE_INVALID", ("NEWS_ZONE",)) from error
    if not re.fullmatch(r"(?:[01][0-9]|2[0-3]):[0-5][0-9]", values.get("NEWS_CUTOFF_TIME", DEFAULTS["NEWS_CUTOFF_TIME"])):
        raise NewsConfigurationError("NEWS_CONFIG_TIME_INVALID", ("NEWS_CUTOFF_TIME",))
    urls = {"NEWS_SERVICE_URL": "http://127.0.0.1:23350", "NEWS_SCHEDULER_URL": "http://127.0.0.1:23410",
            "TASK_SCHEDULER_URL": "http://127.0.0.1:23410", "MESSAGING_SERVICE_URL": "http://127.0.0.1:23250"}
    normalized = {key: endpoint(values.get(key, default), key) for key, default in urls.items()}
    if normalized["NEWS_SCHEDULER_URL"] != normalized["TASK_SCHEDULER_URL"]:
        raise NewsConfigurationError("NEWS_CONFIG_SCHEDULER_MISMATCH", ("NEWS_SCHEDULER_URL", "TASK_SCHEDULER_URL"))
    if "GATEWAY_NEWS_URL" in values and endpoint(values["GATEWAY_NEWS_URL"], "GATEWAY_NEWS_URL") != normalized["NEWS_SERVICE_URL"]:
        raise NewsConfigurationError("NEWS_CONFIG_GATEWAY_MISMATCH", ("GATEWAY_NEWS_URL", "NEWS_SERVICE_URL"))
    if "GATEWAY_NEWS_TOKEN" in values and values["GATEWAY_NEWS_TOKEN"] != values["NEWS_INTERNAL_TOKEN"]:
        raise NewsConfigurationError("NEWS_CONFIG_GATEWAY_MISMATCH", ("GATEWAY_NEWS_TOKEN", "NEWS_INTERNAL_TOKEN"))
    states = {key: flag(values, key) for key in MAIN_FLAGS}
    for key in TIMER_FLAGS:
        flag(values, key, True)
    if stage == "staged":
        if any(states.values()):
            raise NewsConfigurationError("NEWS_CONFIG_STAGED_MUST_BE_DISABLED", tuple(key for key, value in states.items() if value))
    elif stage in {"shadow", "delivery"}:
        required = ("NEWS_COLLECTION_ENABLED", "NEWS_PIPELINE_ENABLED", "NEWS_SCHEDULING_ENABLED", "NEWS_RETENTION_ENABLED",
                    "NEWS_PIPELINE_TIMER_ENABLED", "NEWS_SCHEDULING_TIMER_ENABLED", "NEWS_RETENTION_TIMER_ENABLED")
        for key in required:
            if not flag(values, key, key in TIMER_FLAGS):
                raise NewsConfigurationError("NEWS_CONFIG_STAGE_FLAG_REQUIRED", (key,))
        expected_send = stage == "delivery"
        for key in ("NEWS_DELIVERY_ENABLED", "MESSAGING_OUTBOUND_ENABLED"):
            if states[key] != expected_send:
                raise NewsConfigurationError("NEWS_CONFIG_DELIVERY_STAGE_MISMATCH", (key,))
        if expected_send:
            for key in ("GATEWAY_NEWS_ROUTE_ENABLED", "NEWS_DELIVERY_TIMER_ENABLED", "MESSAGING_OUTBOUND_RETENTION_ENABLED", "MESSAGING_OUTBOUND_RETENTION_TIMER_ENABLED"):
                if not flag(values, key, key in TIMER_FLAGS):
                    raise NewsConfigurationError("NEWS_CONFIG_STAGE_FLAG_REQUIRED", (key,))
            endpoint(values.get("NEWS_READER_URL", ""), "NEWS_READER_URL", public=True)
        validate_node(values)
    else:
        raise NewsConfigurationError("NEWS_CONFIG_STAGE_INVALID")
    return {"configurationValid": True, "stage": stage, "liveAcceptance": False}


def validate_node(values: dict[str, str]) -> None:
    """影子或投递运行必须固定节点并保持注册与任务包门禁。"""
    node = values.get("NEWS_REGION_NODE_ID", "")
    if node == "absent" or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", node):
        raise NewsConfigurationError("NEWS_CONFIG_NODE_REQUIRED", ("NEWS_REGION_NODE_ID",))
    for key in ("TASK_NEWS_NODE_VALUE", "TASK_EXECUTOR_NODE_NAME"):
        if values.get(key) != node:
            raise NewsConfigurationError("NEWS_CONFIG_NODE_MISMATCH", ("NEWS_REGION_NODE_ID", key))
    for key in ("TASK_NODE_REGISTRATION_POLICY_ENFORCE", "TASK_EXECUTOR_REQUIRE_PACKAGE_INDEX", "TASK_EXECUTOR_REQUIRE_NON_ROOT"):
        if not flag(values, key, key != "TASK_EXECUTOR_REQUIRE_PACKAGE_INDEX"):
            raise NewsConfigurationError("NEWS_CONFIG_EXECUTOR_GUARD_REQUIRED", (key,))
    clusters = values.get("TASK_ALLOWED_EXECUTOR_CLUSTERS", "news,messaging")
    if not {"news", "messaging"}.issubset({value.strip() for value in clusters.split(",")}):
        raise NewsConfigurationError("NEWS_CONFIG_CLUSTER_MISSING", ("TASK_ALLOWED_EXECUTOR_CLUSTERS",))
    if "EXECUTOR_CLUSTER_NAMES" in values and not {"news", "messaging"}.issubset(set(values["EXECUTOR_CLUSTER_NAMES"].split(","))):
        raise NewsConfigurationError("NEWS_CONFIG_CLUSTER_MISSING", ("EXECUTOR_CLUSTER_NAMES",))
    if values.get("NEWS_REGION_ADDRESS_FAMILY", "IPV4") not in {"IPV4", "IPV6"}:
        raise NewsConfigurationError("NEWS_CONFIG_REGION_INVALID", ("NEWS_REGION_ADDRESS_FAMILY",))
    provider = values.get("NEWS_REGION_PROVIDER", "MAXMIND_MMDB")
    if provider == "MAXMIND_MMDB":
        if not Path(values.get("NEWS_REGION_MMDB_PATH", "")).is_absolute():
            raise NewsConfigurationError("NEWS_CONFIG_REGION_REQUIRED", ("NEWS_REGION_MMDB_PATH",))
    elif provider == "MAXMIND_WEB":
        if not values.get("NEWS_REGION_MAXMIND_ACCOUNT", "").isdigit() or not values.get("NEWS_REGION_MAXMIND_LICENSE") \
                or values.get("NEWS_REGION_MAXMIND_HOST", "geolite.info") not in {"geolite.info", "geoip.maxmind.com"}:
            raise NewsConfigurationError("NEWS_CONFIG_REGION_REQUIRED", ("NEWS_REGION_MAXMIND_ACCOUNT", "NEWS_REGION_MAXMIND_LICENSE", "NEWS_REGION_MAXMIND_HOST"))
    elif provider != "IPWHOIS":
        raise NewsConfigurationError("NEWS_CONFIG_REGION_INVALID", ("NEWS_REGION_PROVIDER",))
