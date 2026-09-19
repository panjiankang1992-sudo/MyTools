"""Tests for the MyTools Messaging MCP stdio adapter."""

from __future__ import annotations

import importlib.util
import io
import json
from pathlib import Path
import sys

import pytest

MODULE_PATH = Path(__file__).with_name("mytools_messaging_mcp.py")
SPEC = importlib.util.spec_from_file_location("mytools_messaging_mcp", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class Client:
    """Capture MCP tool dispatches."""

    def __init__(self):
        self.calls: list[tuple[str, dict]] = []

    def __getattr__(self, name):
        def call(arguments):
            self.calls.append((name, arguments))
            return {"operation": name, "status": "ACCEPTED"}
        return call


def test_lists_supported_tools_with_write_annotations() -> None:
    """Expose only implemented MyTools capabilities and classify side effects."""
    response = MODULE.McpServer(Client()).handle({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    tools = {item["name"]: item for item in response["result"]["tools"]}
    assert set(tools) == {"send_email", "get_delivery_status", "cancel_delivery",
                          "list_inbound_messages", "get_inbound_message",
                          "reply_to_inbound_message", "check_mailbox"}
    assert tools["get_inbound_message"]["annotations"]["readOnlyHint"] is True
    assert tools["send_email"]["annotations"]["readOnlyHint"] is False
    assert tools["cancel_delivery"]["annotations"]["destructiveHint"] is True


def test_dispatches_tool_and_returns_structured_content() -> None:
    """Dispatch one tool call and preserve its structured result."""
    client = Client()
    response = MODULE.McpServer(client).handle({
        "jsonrpc": "2.0", "id": 2, "method": "tools/call",
        "params": {"name": "send_email", "arguments": {
            "recipient": "person@example.test", "body": "Hello"}},
    })
    assert response["result"]["structuredContent"]["status"] == "ACCEPTED"
    assert response["result"]["isError"] is False
    assert client.calls == [("send_email", {"recipient": "person@example.test", "body": "Hello"})]


def test_send_email_uses_owner_and_stable_idempotency(monkeypatch: pytest.MonkeyPatch) -> None:
    """Keep owner authority server-side and generate a stable retry key."""
    captured = []
    client = MODULE.MessagingClient("http://messaging", "token", 42, "primary")
    monkeypatch.setattr(client.__class__, "request",
                        lambda self, method, path, payload=None: captured.append((method, path, payload)) or payload)
    first = client.send_email({"recipient": "person@example.test", "subject": "Hi", "body": "Body"})
    second = client.send_email({"recipient": "person@example.test", "subject": "Hi", "body": "Body"})
    assert first["ownerId"] == 42
    assert first["channelType"] == "EMAIL"
    assert first["idempotencyKey"] == second["idempotencyKey"]
    assert first["idempotencyKey"].startswith("mcp:email:")


def test_owner_scopes_read_requests(monkeypatch: pytest.MonkeyPatch) -> None:
    """Attach the configured owner to delivery and inbound reads."""
    captured = []
    client = MODULE.MessagingClient("http://messaging", "token", 42, "primary")
    monkeypatch.setattr(client.__class__, "request",
                        lambda self, method, path, payload=None: captured.append((method, path)) or
                        ({"items": [], "nextAfterId": None} if "inbound-messages?" in path else {}))
    identifier = "00000000-0000-4000-8000-000000000001"
    client.get_delivery_status({"delivery_id": identifier})
    client.list_inbound_messages({"limit": 10})
    assert "ownerId=42" in captured[0][1]
    assert "ownerId=42" in captured[1][1]


def test_filters_non_email_messages_across_pages(monkeypatch: pytest.MonkeyPatch) -> None:
    """Never expose QQ or other channel records through the mail MCP."""
    pages = iter([
        {"items": [{"id": "qq", "channelType": "QQ"}], "nextAfterId": "cursor"},
        {"items": [{"id": "mail", "channelType": "EMAIL"}], "nextAfterId": None},
    ])
    client = MODULE.MessagingClient("http://messaging", "token", 42, "primary")
    monkeypatch.setattr(client.__class__, "request", lambda *_args, **_kwargs: next(pages))
    result = client.list_inbound_messages({"limit": 10})
    assert result == {"items": [{"id": "mail", "channelType": "EMAIL"}], "nextAfterId": None}


def test_rejects_non_email_message_for_detail_and_reply(monkeypatch: pytest.MonkeyPatch) -> None:
    """Require both owner scope and EMAIL channel before exposing detail or replying."""
    client = MODULE.MessagingClient("http://messaging", "token", 42, "primary")
    monkeypatch.setattr(client.__class__, "request",
                        lambda *_args, **_kwargs: {"channelType": "QQ"})
    identifier = "00000000-0000-4000-8000-000000000001"
    with pytest.raises(ValueError):
        client.get_inbound_message({"message_id": identifier})
    with pytest.raises(ValueError):
        client.reply_to_inbound_message({"message_id": identifier, "body": "No"})


def test_stdio_recovers_after_parse_error() -> None:
    """Keep serving after one malformed JSON-RPC line."""
    output = io.BytesIO()
    source = io.BytesIO(b'not-json\n{"jsonrpc":"2.0","id":3,"method":"ping"}\n')
    MODULE.McpServer(Client()).run(source, output)
    responses = [json.loads(line) for line in output.getvalue().splitlines()]
    assert responses[0]["error"]["code"] == -32700
    assert responses[1]["id"] == 3


def test_rejects_invalid_recipient_before_http() -> None:
    """Reject malformed email addresses locally."""
    client = MODULE.MessagingClient("http://messaging", "token", 42, "primary")
    with pytest.raises(ValueError):
        client.send_email({"recipient": "not-an-email", "body": "Body"})
