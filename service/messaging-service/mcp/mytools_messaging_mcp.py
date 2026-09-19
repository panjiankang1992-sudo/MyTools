#!/usr/bin/env python3
"""Expose MyTools Messaging through a bounded MCP stdio adapter."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass
from hashlib import sha256
from pathlib import Path
from typing import Any, BinaryIO
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from uuid import UUID

PROTOCOL_VERSION = "2025-06-18"
SERVER_INSTRUCTIONS = (
    "Use read tools to inspect MyTools mail. Sending, replying, cancelling, and mailbox polling "
    "change external or durable state and require user intent. send_email is asynchronous; use "
    "get_delivery_status when the user needs confirmation. Never claim delivery from ACCEPTED alone."
)


@dataclass(frozen=True, slots=True)
class MessagingClient:
    """Call the deployed Messaging Service without exposing its internal token."""

    base_url: str
    token: str
    owner_id: int
    account_key: str
    timeout_seconds: float = 30.0

    def send_email(self, arguments: dict[str, Any]) -> dict[str, Any]:
        """Create one asynchronous email delivery."""
        recipient = required_text(arguments, "recipient", 1024)
        subject = optional_text(arguments, "subject", 998)
        body = required_text(arguments, "body", 10_485_760)
        if not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", recipient):
            raise ValueError("recipient must be one email address")
        idempotency_key = optional_text(arguments, "idempotency_key", 255)
        if not idempotency_key:
            material = "\0".join((str(self.owner_id), recipient, subject, body))
            idempotency_key = "mcp:email:" + sha256(material.encode("utf-8")).hexdigest()
        return self.request("POST", "/internal/v1/deliveries", {
            "ownerId": self.owner_id,
            "idempotencyKey": idempotency_key,
            "channelType": "EMAIL",
            "recipient": recipient,
            "subject": subject or None,
            "body": body,
        })

    def get_delivery_status(self, arguments: dict[str, Any]) -> dict[str, Any]:
        """Read one owner-scoped delivery status."""
        delivery_id = required_uuid(arguments, "delivery_id")
        query = urlencode({"ownerId": self.owner_id})
        return self.request("GET", f"/internal/v1/deliveries/{delivery_id}?{query}")

    def cancel_delivery(self, arguments: dict[str, Any]) -> dict[str, Any]:
        """Cancel one owner-scoped pending delivery."""
        delivery_id = required_uuid(arguments, "delivery_id")
        query = urlencode({"ownerId": self.owner_id})
        return self.request("POST", f"/internal/v1/deliveries/{delivery_id}/cancel?{query}")

    def list_inbound_messages(self, arguments: dict[str, Any]) -> dict[str, Any]:
        """List email-only inbound messages across bounded Messaging pages."""
        limit = arguments.get("limit", 20)
        if isinstance(limit, bool) or not isinstance(limit, int) or limit < 1 or limit > 100:
            raise ValueError("limit must be an integer from 1 to 100")
        after_id = optional_text(arguments, "after_id", 36)
        if after_id:
            after_id = validate_uuid(after_id, "after_id")
        messages: list[dict[str, Any]] = []
        cursor = after_id
        # 通用 Messaging API 暂无渠道过滤，最多扫描十页并仅暴露邮件。
        for _page in range(10):
            query: dict[str, Any] = {"ownerId": self.owner_id, "limit": limit - len(messages)}
            if cursor:
                query["afterId"] = cursor
            page = self.request("GET", "/internal/v1/inbound-messages?" + urlencode(query))
            items = page.get("items")
            if not isinstance(items, list):
                raise RuntimeError("Messaging Service returned an invalid inbound page")
            messages.extend(item for item in items
                            if isinstance(item, dict) and item.get("channelType") == "EMAIL")
            cursor = page.get("nextAfterId")
            if len(messages) >= limit or not cursor:
                break
        return {"items": messages[:limit], "nextAfterId": cursor}

    def get_inbound_message(self, arguments: dict[str, Any]) -> dict[str, Any]:
        """Read one owner-scoped inbound message."""
        message_id = required_uuid(arguments, "message_id")
        return self.require_email_message(message_id)

    def reply_to_inbound_message(self, arguments: dict[str, Any]) -> dict[str, Any]:
        """Create an asynchronous reply using the stored inbound route."""
        message_id = required_uuid(arguments, "message_id")
        body = required_text(arguments, "body", 10_485_760)
        self.require_email_message(message_id)
        idempotency_key = optional_text(arguments, "idempotency_key", 255)
        if not idempotency_key:
            material = "\0".join((str(self.owner_id), message_id, body))
            idempotency_key = "mcp:reply:" + sha256(material.encode("utf-8")).hexdigest()
        return self.request("POST", f"/internal/v1/inbound-messages/{message_id}/replies", {
            "idempotencyKey": idempotency_key, "body": body,
        })

    def require_email_message(self, message_id: str) -> dict[str, Any]:
        """Read one owner-scoped message and reject non-email channels."""
        query = urlencode({"ownerId": self.owner_id})
        document = self.request("GET", f"/internal/v1/inbound-messages/{message_id}?{query}")
        if document.get("channelType") != "EMAIL":
            raise ValueError("message_id does not identify an email message")
        return document

    def check_mailbox(self, _arguments: dict[str, Any]) -> dict[str, Any]:
        """Poll the single server-configured IMAP account."""
        if not self.account_key:
            raise RuntimeError("MESSAGING_MCP_EMAIL_ACCOUNT_KEY is not configured")
        return self.request("POST", "/internal/v1/adapters/email/poll", {"accountKey": self.account_key})

    def request(self, method: str, path: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        """Perform one authenticated JSON request with bounded error details."""
        data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode("utf-8")
        request = Request(self.base_url.rstrip("/") + path, data=data, method=method, headers={
            "Authorization": "Bearer " + self.token,
            "Accept": "application/json",
            "Content-Type": "application/json",
        })
        try:
            with urlopen(request, timeout=self.timeout_seconds) as response:
                document = json.loads(response.read().decode("utf-8"))
        except HTTPError as exception:
            detail = exception.read(2048).decode("utf-8", "replace")
            raise RuntimeError(f"Messaging Service rejected the request ({exception.code}): {detail}") from exception
        except URLError as exception:
            raise RuntimeError("Messaging Service is unavailable") from exception
        except json.JSONDecodeError as exception:
            raise RuntimeError("Messaging Service returned invalid JSON") from exception
        if not isinstance(document, dict):
            raise RuntimeError("Messaging Service returned an invalid response")
        return document


class McpServer:
    """Implement the bounded JSON-RPC subset required for MCP tools."""

    def __init__(self, client: MessagingClient):
        self.client = client

    def handle(self, request: Any) -> dict[str, Any] | None:
        """Handle one JSON-RPC request."""
        if not isinstance(request, dict) or request.get("jsonrpc") != "2.0":
            return error(request.get("id") if isinstance(request, dict) else None, -32600, "invalid request")
        request_id = request.get("id")
        if request_id is None:
            return None
        method = str(request.get("method") or "")
        if method == "initialize":
            requested = str((request.get("params") or {}).get("protocolVersion") or "")
            supported = {"2024-11-05", "2025-03-26", PROTOCOL_VERSION}
            return result(request_id, {
                "protocolVersion": requested if requested in supported else PROTOCOL_VERSION,
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "mytools-messaging", "version": "1.0.0"},
                "instructions": SERVER_INSTRUCTIONS,
            })
        if method == "ping":
            return result(request_id, {})
        if method == "tools/list":
            return result(request_id, {"tools": tool_definitions()})
        if method != "tools/call":
            return error(request_id, -32601, f"method not found: {method}")
        params = request.get("params") or {}
        arguments = params.get("arguments") or {}
        if not isinstance(arguments, dict):
            return error(request_id, -32602, "arguments must be an object")
        handlers = {
            "send_email": self.client.send_email,
            "get_delivery_status": self.client.get_delivery_status,
            "cancel_delivery": self.client.cancel_delivery,
            "list_inbound_messages": self.client.list_inbound_messages,
            "get_inbound_message": self.client.get_inbound_message,
            "reply_to_inbound_message": self.client.reply_to_inbound_message,
            "check_mailbox": self.client.check_mailbox,
        }
        handler = handlers.get(str(params.get("name") or ""))
        if handler is None:
            return error(request_id, -32602, f"unknown tool: {params.get('name')}")
        try:
            document = handler(arguments)
            return result(request_id, tool_result(document, False))
        except (ValueError, RuntimeError) as exception:
            return result(request_id, tool_result({"error": str(exception)}, True))

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


def tool_definitions() -> list[dict[str, Any]]:
    """Return stable tool schemas and behavioral annotations."""
    read_only = {"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True,
                 "openWorldHint": False}
    write = {"readOnlyHint": False, "destructiveHint": False, "idempotentHint": True,
             "openWorldHint": True}
    destructive = {"readOnlyHint": False, "destructiveHint": True, "idempotentHint": True,
                   "openWorldHint": False}
    return [
        tool("send_email", "Queue one email through MyTools Messaging. Returns acceptance, not final delivery.", {
            "recipient": string_schema(1024, "Single recipient email address"),
            "subject": string_schema(998, "Email subject"),
            "body": string_schema(10_485_760, "Plain-text email body"),
            "idempotency_key": string_schema(255, "Optional stable retry key"),
        }, ["recipient", "body"], write),
        tool("get_delivery_status", "Get the current status of one owner-scoped delivery.", {
            "delivery_id": uuid_schema("Delivery UUID"),
        }, ["delivery_id"], read_only),
        tool("cancel_delivery", "Cancel one owner-scoped pending delivery.", {
            "delivery_id": uuid_schema("Delivery UUID"),
        }, ["delivery_id"], destructive),
        tool("list_inbound_messages", "List a bounded page of inbound messages for the configured owner.", {
            "limit": {"type": "integer", "minimum": 1, "maximum": 100, "default": 20},
            "after_id": uuid_schema("Optional pagination cursor UUID"),
        }, [], read_only),
        tool("get_inbound_message", "Get one owner-scoped inbound message including body and parts.", {
            "message_id": uuid_schema("Inbound message UUID"),
        }, ["message_id"], read_only),
        tool("reply_to_inbound_message", "Queue a reply using the inbound message's stored channel route.", {
            "message_id": uuid_schema("Inbound message UUID"),
            "body": string_schema(10_485_760, "Reply body"),
            "idempotency_key": string_schema(255, "Optional stable retry key"),
        }, ["message_id", "body"], write),
        tool("check_mailbox", "Poll the single server-configured IMAP mailbox and ingest new mail.", {}, [], write),
    ]


def tool(name: str, description: str, properties: dict[str, Any], required: list[str],
         annotations: dict[str, bool]) -> dict[str, Any]:
    """Build one MCP tool definition."""
    return {"name": name, "description": description,
            "inputSchema": {"type": "object", "properties": properties, "required": required,
                            "additionalProperties": False},
            "annotations": annotations}


def string_schema(maximum: int, description: str) -> dict[str, Any]:
    """Build one bounded string schema."""
    return {"type": "string", "minLength": 1, "maxLength": maximum, "description": description}


def uuid_schema(description: str) -> dict[str, Any]:
    """Build one UUID string schema."""
    return {"type": "string", "format": "uuid", "description": description}


def required_text(arguments: dict[str, Any], name: str, maximum: int) -> str:
    """Read one required bounded string."""
    value = arguments.get(name)
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        raise ValueError(f"{name} must be a non-empty string up to {maximum} characters")
    return value.strip() if name == "recipient" else value


def optional_text(arguments: dict[str, Any], name: str, maximum: int) -> str:
    """Read one optional bounded string."""
    value = arguments.get(name)
    if value is None:
        return ""
    if not isinstance(value, str) or len(value) > maximum:
        raise ValueError(f"{name} must be a string up to {maximum} characters")
    return value


def required_uuid(arguments: dict[str, Any], name: str) -> str:
    """Read one required UUID string."""
    value = required_text(arguments, name, 36)
    return validate_uuid(value, name)


def validate_uuid(value: str, name: str) -> str:
    """Normalize one UUID string."""
    try:
        return str(UUID(value))
    except ValueError as exception:
        raise ValueError(f"{name} must be a UUID") from exception


def tool_result(document: dict[str, Any], is_error: bool) -> dict[str, Any]:
    """Build one text and structured MCP tool result."""
    return {"content": [{"type": "text", "text": json.dumps(document, ensure_ascii=False)}],
            "structuredContent": document, "isError": is_error}


def result(request_id: Any, value: Any) -> dict[str, Any]:
    """Build one successful JSON-RPC response."""
    return {"jsonrpc": "2.0", "id": request_id, "result": value}


def error(request_id: Any, code: int, message: str) -> dict[str, Any]:
    """Build one JSON-RPC error response."""
    return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


def load_env_file(path: str) -> None:
    """Load a systemd-style environment file without evaluating shell syntax."""
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


def main() -> None:
    """Start the MCP stdio adapter using environment-only configuration."""
    parser = argparse.ArgumentParser(prog="mytools-messaging-mcp")
    parser.add_argument("--env-file", default="")
    arguments = parser.parse_args()
    load_env_file(arguments.env_file)
    token = os.getenv("MESSAGING_INTERNAL_TOKEN", "").strip()
    if not token:
        raise RuntimeError("MESSAGING_INTERNAL_TOKEN is required")
    owner_id = int(os.getenv("MESSAGING_MCP_OWNER_ID", "0"))
    if owner_id <= 0:
        raise RuntimeError("MESSAGING_MCP_OWNER_ID must be positive")
    account_key = os.getenv("MESSAGING_MCP_EMAIL_ACCOUNT_KEY",
                            os.getenv("MESSAGING_EMAIL_ACCOUNT_KEY", "")).strip()
    client = MessagingClient(os.getenv("MESSAGING_URL", "http://127.0.0.1:23250"), token,
                             owner_id, account_key)
    McpServer(client).run(sys.stdin.buffer, sys.stdout.buffer)


if __name__ == "__main__":
    main()
