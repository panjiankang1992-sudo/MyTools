"""OneBot 进程入口和后台桥接失败测试。"""

import os
from pathlib import Path
from unittest.mock import MagicMock, patch

from mytools_onebot_connector.main import main, read_inbound_health, run_inbound_bridge


class FailingBridge:
    """模拟无法继续安全接收入站消息的桥接。"""

    async def run(self) -> None:
        """抛出后台线程致命错误。"""
        raise RuntimeError("bridge failed")


def test_background_bridge_failure_requests_process_exit() -> None:
    """桥接线程不得在 HTTP 健康仍为成功时静默退出。"""
    exits: list[int] = []

    run_inbound_bridge(FailingBridge(), exits.append)

    assert exits == [1]


def test_main_wires_server_configured_wal_into_bridge(tmp_path) -> None:
    """启用入站时应只使用服务端固定 WAL 目录构造桥接。"""
    wal_path = tmp_path / "inbound-wal"
    environment = {
        "ONEBOT_CONNECTOR_DB_USER": "database-user",
        "ONEBOT_CONNECTOR_DB_PASSWORD": "database-password",
        "ONEBOT_CONNECTOR_INTERNAL_TOKEN": "internal-token",
        "MESSAGING_INTERNAL_TOKEN": "messaging-token",
        "MESSAGING_ONEBOT_INGRESS_ENABLED": "true",
        "ONEBOT_CONNECTOR_OWNER_ID": "7",
        "ONEBOT_CONNECTOR_INBOUND_WAL_PATH": str(wal_path),
    }
    server = MagicMock()
    wal = object()
    bridge = MagicMock()
    with patch.dict(os.environ, environment, clear=True), \
            patch("mytools_onebot_connector.main.OneBotConnectorService"), \
            patch("mytools_onebot_connector.main.create_handler") as handler_factory, \
            patch("mytools_onebot_connector.main.ThreadingHTTPServer", return_value=server), \
            patch("mytools_onebot_connector.main.threading.Thread") as thread, \
            patch("mytools_onebot_connector.main.InboundEventWal", return_value=wal) as wal_type, \
            patch("mytools_onebot_connector.main.OneBotInboundBridge",
                  return_value=bridge) as bridge_type:
        main()

    wal_type.assert_called_once_with(
        wal_path,
        max_pending_records=10000,
        max_total_payload_bytes=128 * 1024 * 1024,
        max_dead_records=1000)
    assert bridge_type.call_args.args[-1] is wal
    assert callable(bridge_type.call_args.kwargs["health_probe"])
    assert handler_factory.call_args.args[-1] is bridge.health_snapshot
    thread.assert_called_once()
    server.serve_forever.assert_called_once_with()


def test_read_inbound_health_returns_only_safe_counts(tmp_path: Path) -> None:
    """健康查询只聚合数量，不读取 WAL 内容或数据库载荷。"""
    dead_root = tmp_path / "dead"
    dead_root.mkdir()
    (dead_root / "event.DB_IMPORT_EXHAUSTED.dead").write_text(
        "secret payload", encoding="utf-8")
    (dead_root / "ignored-directory").mkdir()
    row = {
        "databasePendingInboundEvents": 2,
        "databaseDeadInboundEvents": 1,
        "pendingTextReplies": 3,
        "failedTextReplies": 4,
        "uncertainTextReplies": 5,
    }
    connection = MagicMock()
    cursor = connection.cursor.return_value.__enter__.return_value
    cursor.fetchone.return_value = row

    result = read_inbound_health(lambda: connection, tmp_path)

    assert result == {"walDeadEvents": 1, **row}
    assert "payload" not in str(result)
    cursor.execute.assert_called_once()
    connection.close.assert_called_once_with()
