"""Tests for the Download Ingestion MCP stdio adapter."""

from __future__ import annotations

import io

import pytest

from mytools_download_ingestion.mcp_server import McpServer, classify


class Client:
    """Capture normalized tool calls."""

    def __init__(self):
        self.calls = []

    def submit(self, link: str, mode: str) -> dict:
        """Return one stable accepted request."""
        self.calls.append((link, mode))
        return {"job_id": "request-1", "status": "RUNNING"}


def test_classifies_supported_link_types(monkeypatch: pytest.MonkeyPatch) -> None:
    """Classify web, X, and magnet inputs into existing task kinds."""
    monkeypatch.setenv("DOWNLOAD_MCP_MAGNET_MODE", "local")
    assert classify("https://example.org/post", "auto")[0] == "WEB_ARCHIVE"
    assert classify("https://x.com/user/status/123", "auto")[0] == "X_POST"
    magnet = "magnet:?xt=urn:btih:" + "a" * 40
    assert classify(magnet, "auto") == ("MAGNET", {"magnetUri": magnet}, "LOCAL")


def test_rejects_invalid_link() -> None:
    """Reject unsupported schemes before reaching Download Ingestion."""
    with pytest.raises(ValueError):
        classify("file:///etc/passwd", "auto")


def test_lists_and_calls_analyze_download() -> None:
    """Expose the compatible tool name and return structured task identity."""
    client = Client()
    server = McpServer(client)
    listed = server.handle({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    called = server.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                            "params": {"name": "analyze_download", "arguments": {
                                "link": "https://example.org/post", "mode": "auto"}}})
    assert listed["result"]["tools"][0]["name"] == "analyze_download"
    assert called["result"]["structuredContent"]["job_id"] == "request-1"
    assert client.calls == [("https://example.org/post", "auto")]


def test_stdio_returns_parse_error_and_continues() -> None:
    """Keep the stdio process alive after one malformed line."""
    output = io.BytesIO()
    McpServer(Client()).run(io.BytesIO(b"not-json\n{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}\n"), output)
    lines = output.getvalue().splitlines()
    assert b'"code":-32700' in lines[0]
    assert b'"id":2' in lines[1]
