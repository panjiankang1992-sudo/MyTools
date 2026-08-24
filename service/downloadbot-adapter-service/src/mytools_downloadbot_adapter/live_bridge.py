"""不修改旧 DownloadBot 的只读链接任务旁路桥接。"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
import hashlib
from typing import Protocol
from uuid import UUID, uuid4

from .models import AcceptLegacyEvent, AdapterMode, EventStatus
from .service import AdapterService

BRIDGE_NAME = "downloadbot-link-jobs-v1"


@dataclass(frozen=True, slots=True)
class LegacyLinkJob:
    """旧库中创建后不可变的链接任务字段。"""

    legacy_id: int
    input_uri: str
    uri_sha256: str
    link_kind: str
    strategy: str
    source_type: str
    source_key: str
    account_id: str


class LegacyLinkSource(Protocol):
    """旧 DownloadBot 只读查询边界。"""

    def page_after(self, legacy_id: int, limit: int) -> list[LegacyLinkJob]:
        """按旧自增主键读取稳定页。"""

    def high_water(self) -> int:
        """返回初始化时旧表的最大主键。"""


class BridgeCheckpoint(Protocol):
    """适配器自有游标和拒绝证据边界。"""

    def current(self, bridge_name: str) -> int | None:
        """返回最近已安全处理的旧主键，未初始化时返回空。"""

    def initialize(self, bridge_name: str, legacy_id: int) -> int:
        """幂等创建初始高水位并返回实际值。"""

    def advance(self, bridge_name: str, expected: int, legacy_id: int) -> None:
        """以比较并更新方式单调推进游标。"""

    def reject(self, bridge_name: str, legacy_id: int, reason_code: str, detail: str) -> None:
        """幂等保存无法投递的记录证据。"""


class LiveBridge:
    """把旧链接作业幂等转换为适配器事件。"""

    def __init__(self, source: LegacyLinkSource, checkpoint: BridgeCheckpoint,
                 adapter: AdapterService, mode: AdapterMode,
                 account_mapping: dict[str, UUID] | None = None,
                 start_from_latest: bool = True):
        self._source = source
        self._checkpoint = checkpoint
        self._adapter = adapter
        self._mode = mode
        self._account_mapping = dict(account_mapping or {})
        self._start_from_latest = start_from_latest

    def poll_once(self, limit: int = 100) -> int:
        """处理一个有界页；影子转发失败时保留游标等待重试。"""
        if limit < 1 or limit > 500:
            raise ValueError("live bridge page limit is invalid")
        current = self._checkpoint.current(BRIDGE_NAME)
        if current is None:
            initial = self._source.high_water() if self._start_from_latest else 0
            current = self._checkpoint.initialize(BRIDGE_NAME, initial)
        cursor = current
        handled = 0
        for row in self._source.page_after(cursor, limit):
            try:
                command = self._command(row)
            except ValueError as exception:
                self._checkpoint.reject(BRIDGE_NAME, row.legacy_id,
                                        "UNSUPPORTED_LEGACY_LINK", str(exception))
                self._checkpoint.advance(BRIDGE_NAME, cursor, row.legacy_id)
                cursor = row.legacy_id
                handled += 1
                continue
            event = self._adapter.accept(command)
            if self._mode is AdapterMode.SHADOW and event.status is not EventStatus.FORWARDED:
                break
            self._checkpoint.advance(BRIDGE_NAME, cursor, row.legacy_id)
            cursor = row.legacy_id
            handled += 1
        return handled

    def _command(self, row: LegacyLinkJob) -> AcceptLegacyEvent:
        if row.legacy_id < 1:
            raise ValueError("legacy link identity is invalid")
        uri = row.input_uri.strip()
        digest = hashlib.sha256(uri.encode("utf-8", "replace")).hexdigest()
        if not uri or digest != row.uri_sha256.lower():
            raise ValueError("legacy link digest does not match input")
        kind = row.link_kind.upper()
        if kind == "HTTP":
            request_kind = "WEB_ARCHIVE"
            parameters = {"url": uri}
        elif kind == "X_POST":
            request_kind = "X_POST"
            parameters = {"url": uri}
        elif kind == "MAGNET":
            account_id = self._account_mapping.get(row.account_id)
            if account_id is None:
                raise ValueError("legacy PikPak account has no new account mapping")
            request_kind = "MAGNET"
            parameters = {"magnetUri": uri, "accountId": str(account_id)}
        else:
            raise ValueError("legacy link kind is unsupported")
        return AcceptLegacyEvent(
            event_id=f"downloadbot-link:{row.legacy_id}",
            source_type=(row.source_type or "DOWNLOADBOT_LINK")[:64],
            source_key=(row.source_key or f"link:{row.legacy_id}")[:255],
            request_kind=request_kind,
            parameters={**parameters, "legacyLinkJobId": row.legacy_id,
                        "legacyStrategy": row.strategy[:32]},
        )


class MySqlLegacyLinkSource:
    """通过专用只读连接读取旧 DownloadBot 链接任务。"""

    def __init__(self, connection_factory):
        self._connection_factory = connection_factory

    def page_after(self, legacy_id: int, limit: int) -> list[LegacyLinkJob]:
        """按主键读取不包含结果正文和反馈目标的安全字段。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT id,input_uri,uri_sha256,link_kind,strategy,source_type,"
                    "source_key,account_id FROM link_jobs WHERE id>%s ORDER BY id LIMIT %s",
                    (legacy_id, limit))
                rows = cursor.fetchall()
            return [LegacyLinkJob(
                int(row["id"]), str(row["input_uri"]), str(row["uri_sha256"]),
                str(row["link_kind"]), str(row["strategy"]), str(row["source_type"]),
                str(row["source_key"]), str(row["account_id"] or "")) for row in rows]
        finally:
            connection.close()

    def high_water(self) -> int:
        """读取当前链接任务高水位，不锁定旧表。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT COALESCE(MAX(id),0) AS high_water FROM link_jobs")
                return int(cursor.fetchone()["high_water"])
        finally:
            connection.close()


class MySqlBridgeCheckpoint:
    """使用适配器独立 schema 持久化单调游标和拒绝记录。"""

    def __init__(self, connection_factory):
        self._connection_factory = connection_factory

    def current(self, bridge_name: str) -> int | None:
        """读取游标；首次运行返回空。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT last_legacy_id FROM legacy_live_bridge_cursor "
                               "WHERE bridge_name=%s", (bridge_name,))
                row = cursor.fetchone()
            return None if row is None else int(row["last_legacy_id"])
        finally:
            connection.close()

    def initialize(self, bridge_name: str, legacy_id: int) -> int:
        """用冻结高水位幂等初始化游标。"""
        if legacy_id < 0:
            raise ValueError("live bridge initial cursor is invalid")
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "INSERT IGNORE INTO legacy_live_bridge_cursor "
                    "(bridge_name,last_legacy_id,updated_at) VALUES (%s,%s,%s)",
                    (bridge_name, legacy_id, datetime.now(UTC)))
                cursor.execute("SELECT last_legacy_id FROM legacy_live_bridge_cursor "
                               "WHERE bridge_name=%s", (bridge_name,))
                actual = int(cursor.fetchone()["last_legacy_id"])
            connection.commit()
            return actual
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def advance(self, bridge_name: str, expected: int, legacy_id: int) -> None:
        """创建或比较并更新游标，拒绝并发越序推进。"""
        if legacy_id <= expected:
            raise ValueError("live bridge cursor must advance")
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT last_legacy_id FROM legacy_live_bridge_cursor "
                               "WHERE bridge_name=%s FOR UPDATE", (bridge_name,))
                row = cursor.fetchone()
                actual = 0 if row is None else int(row["last_legacy_id"])
                if actual != expected:
                    raise RuntimeError("live bridge cursor changed concurrently")
                if row is None:
                    cursor.execute(
                        "INSERT INTO legacy_live_bridge_cursor "
                        "(bridge_name,last_legacy_id,updated_at) VALUES (%s,%s,%s)",
                        (bridge_name, legacy_id, datetime.now(UTC)))
                else:
                    cursor.execute(
                        "UPDATE legacy_live_bridge_cursor SET last_legacy_id=%s,updated_at=%s "
                        "WHERE bridge_name=%s", (legacy_id, datetime.now(UTC), bridge_name))
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def reject(self, bridge_name: str, legacy_id: int, reason_code: str, detail: str) -> None:
        """幂等记录无法安全映射的旧任务。"""
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "INSERT IGNORE INTO legacy_live_bridge_rejection "
                    "(id,bridge_name,legacy_id,reason_code,detail,created_at) "
                    "VALUES (%s,%s,%s,%s,%s,%s)",
                    (str(uuid4()), bridge_name, legacy_id,
                     reason_code, detail[:1024], datetime.now(UTC)))
            connection.commit()
        finally:
            connection.close()
