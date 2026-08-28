"""Expose download ingestion through the MCP stdio transport."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path
from dataclasses import dataclass
from hashlib import sha256
from typing import Any, BinaryIO
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen
from uuid import uuid4

PROTOCOL_VERSION = "2025-06-18"
TOOL_NAME = "analyze_download"
BTIH = re.compile(r"(?:^|[?&])xt=urn:btih:(?:[0-9a-fA-F]{40}|[A-Z2-7]{32})(?:&|$)")
X_PATH = re.compile(r"^/(?:[^/]+/status|i/(?:web/)?status)/[0-9]{1,24}(?:/.*)?$", re.I)
X_USER_PATH = re.compile(r"^/([A-Za-z0-9_]{1,15})(?:/media)?/?$", re.I)
X_RESERVED_PATHS = {"home", "explore", "search", "notifications", "messages", "settings", "compose", "i"}


@dataclass(frozen=True, slots=True)
class IngestionClient:
    """Submit one normalized request to Download Ingestion."""

    base_url: str
    token: str
    owner_id: int
    timeout_seconds: float = 15.0

    def submit(self, link: str, mode: str) -> dict[str, Any]:
        """Classify and submit a link without performing the expensive download."""
        kind, parameters, strategy = classify(link, mode)
        digest = sha256(link.encode("utf-8")).hexdigest()
        if kind == "MAGNET":
            account_id = os.getenv("DOWNLOAD_MCP_PIKPAK_ACCOUNT_ID", "").strip()
            if strategy == "PIKPAK" and not account_id:
                raise ValueError("DOWNLOAD_MCP_PIKPAK_ACCOUNT_ID is required for PikPak mode")
            if account_id:
                parameters["accountId"] = account_id
        payload = {
            "idempotencyKey": f"mcp:{digest}",
            "sourceType": "MCP",
            "sourceKey": f"mcp:{uuid4()}",
            "requestKind": "LOCAL_MAGNET" if kind == "MAGNET" and strategy == "LOCAL" else kind,
            "ownerId": self.owner_id,
            "parameters": parameters,
        }
        request = Request(
            f"{self.base_url.rstrip('/')}/api/v1/download-requests",
            data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
            method="POST",
            headers={"Authorization": f"Bearer {self.token}",
                     "Content-Type": "application/json", "Accept": "application/json"},
        )
        try:
            with urlopen(request, timeout=self.timeout_seconds) as response:
                result = json.loads(response.read().decode("utf-8"))
        except HTTPError as exception:
            detail = exception.read(2048).decode("utf-8", "replace")
            raise RuntimeError(f"download ingestion rejected the request: {detail}") from exception
        except URLError as exception:
            raise RuntimeError("download ingestion is unavailable") from exception
        if not isinstance(result, dict) or not result.get("id"):
            raise RuntimeError("download ingestion returned an invalid response")
        return {
            "job_id": str(result["id"]), "created": True,
            "kind": kind.lower(), "strategy": strategy.lower(),
            "status": str(result.get("status") or "ACCEPTED"), "link": link,
            "message": "Download request accepted",
        }


def classify(link: str, mode: str) -> tuple[str, dict[str, Any], str]:
    """Map a supported link to one existing orchestration task type."""
    value = link.strip()
    selected = mode.strip().lower() or "auto"
    if selected not in {"auto", "pikpak", "local"}:
        raise ValueError("mode must be auto, pikpak, or local")
    if value.lower().startswith("magnet:"):
        if len(value.encode("utf-8")) > 8192 or not BTIH.search(value):
            raise ValueError("link must contain one valid magnet BTIH")
        default_mode = os.getenv("DOWNLOAD_MCP_MAGNET_MODE", "pikpak").strip().lower()
        strategy = default_mode if selected == "auto" else selected
        if strategy not in {"pikpak", "local"}:
            raise ValueError("DOWNLOAD_MCP_MAGNET_MODE must be pikpak or local")
        return "MAGNET", {"magnetUri": value}, strategy.upper()
    parsed = urlparse(value)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname \
            or parsed.username or parsed.password or len(value.encode("utf-8")) > 8192:
        raise ValueError("link must be an absolute HTTP(S) URL or magnet URI")
    host = parsed.hostname.lower().removeprefix("www.").removeprefix("mobile.")
    if host in {"x.com", "twitter.com"} and X_PATH.fullmatch(parsed.path):
        return "X_POST", {"url": value}, "LOCAL"
    user_match = X_USER_PATH.fullmatch(parsed.path)
    if host in {"x.com", "twitter.com"} and user_match \
            and user_match.group(1).lower() not in X_RESERVED_PATHS:
        return "X_USER", {"url": value}, "LOCAL"
    return "WEB_ARCHIVE", {"url": value}, "LOCAL"


class McpServer:
    """Implement the bounded JSON-RPC subset required by MCP tools."""

    def __init__(self, client: IngestionClient):
        self.client = client

    def handle(self, request: Any) -> dict[str, Any] | None:
        """Handle one JSON-RPC request."""
        if not isinstance(request, dict) or request.get("jsonrpc") != "2.0":
            return error(request.get("id") if isinstance(request, dict) else None,
                         -32600, "invalid request")
        request_id = request.get("id")
        if request_id is None:
            return None
        method = str(request.get("method") or "")
        if method == "initialize":
            requested = str((request.get("params") or {}).get("protocolVersion") or "")
            supported = {"2024-11-05", "2025-03-26", PROTOCOL_VERSION}
            return result(request_id, {"protocolVersion": requested if requested in supported
                                      else PROTOCOL_VERSION,
                                       "capabilities": {"tools": {"listChanged": False}},
                                       "serverInfo": {"name": "mytools-download", "version": "0.1.0"}})
        if method == "ping":
            return result(request_id, {})
        if method == "tools/list":
            return result(request_id, {"tools": [tool_definition()]})
        if method != "tools/call":
            return error(request_id, -32601, f"method not found: {method}")
        params = request.get("params") or {}
        arguments = params.get("arguments") or {}
        if params.get("name") != TOOL_NAME:
            return error(request_id, -32602, f"unknown tool: {params.get('name')}")
        if not isinstance(arguments, dict) or not isinstance(arguments.get("link"), str):
            return error(request_id, -32602, "link must be a string")
        try:
            document = self.client.submit(arguments["link"], str(arguments.get("mode") or "auto"))
            return result(request_id, {"content": [{"type": "text", "text": json.dumps(document)}],
                                       "structuredContent": document, "isError": False})
        except (ValueError, RuntimeError) as exception:
            return result(request_id, {"content": [{"type": "text", "text": str(exception)}],
                                       "isError": True})

    def run(self, source: BinaryIO, destination: BinaryIO) -> None:
        """Serve newline-delimited JSON-RPC over stdio."""
        for line in source:
            try:
                response = self.handle(json.loads(line))
            except (UnicodeDecodeError, json.JSONDecodeError) as exception:
                response = error(None, -32700, str(exception))
            if response is not None:
                destination.write((json.dumps(response, separators=(",", ":")) + "\n").encode("utf-8"))
                destination.flush()


def tool_definition() -> dict[str, Any]:
    """Return the stable analyze_download tool schema."""
    return {"name": TOOL_NAME,
            "description": "Analyze and enqueue one HTTP(S), X, or magnet download.",
            "inputSchema": {"type": "object", "properties": {
                "link": {"type": "string"},
                "mode": {"type": "string", "enum": ["auto", "pikpak", "local"], "default": "auto"}},
                "required": ["link"], "additionalProperties": False}}


def result(request_id: Any, value: Any) -> dict[str, Any]:
    """Build one successful JSON-RPC response."""
    return {"jsonrpc": "2.0", "id": request_id, "result": value}


def error(request_id: Any, code: int, message: str) -> dict[str, Any]:
    """Build one JSON-RPC error response."""
    return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


def main() -> None:
    """Start the MCP stdio adapter using environment-only configuration."""
    parser = argparse.ArgumentParser(prog="mytools-download-mcp")
    parser.add_argument("--env-file", default="")
    arguments = parser.parse_args()
    load_env_file(arguments.env_file)
    token = os.getenv("DOWNLOAD_INTERNAL_TOKEN", "")
    if not token:
        raise RuntimeError("DOWNLOAD_INTERNAL_TOKEN is required")
    owner_id = int(os.getenv("DOWNLOAD_MCP_OWNER_ID", "0"))
    client = IngestionClient(os.getenv("DOWNLOAD_INGESTION_URL", "http://127.0.0.1:23220"),
                             token, owner_id)
    McpServer(client).run(sys.stdin.buffer, sys.stdout.buffer)


def load_env_file(path: str) -> None:
    """Load a systemd-style key/value file without evaluating shell syntax."""
    if not path:
        return
    for raw_line in Path(path).read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise RuntimeError("invalid environment file line")
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip()
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            raise RuntimeError("invalid environment variable name")
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
            value = value[1:-1]
        os.environ.setdefault(key, value)


if __name__ == "__main__":
    main()
