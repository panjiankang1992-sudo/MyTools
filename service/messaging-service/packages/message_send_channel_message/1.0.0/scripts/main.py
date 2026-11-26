#!/usr/bin/env python3
"""使用当前执行租约调用新版消息投递入口，结果仅含投递状态。"""
from __future__ import annotations

import json
import os
from pathlib import Path
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import uuid


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """禁止凭据随跳转离开固定服务目标。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        """直接拒绝跳转。"""
        raise ValueError("Redirect rejected")


def canonical_uuid(value):
    """任务标识必须采用规范 UUID 表示。"""
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise ValueError("Invalid task identifier")
    return value


def execute(context, base_url, token, execution_id, lease_token):
    """发送固定运行标识和当前租约，不接受用户参数中的身份或网络配置。"""
    parsed = urllib.parse.urlsplit(base_url)
    secure = parsed.scheme == "https" or (parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "::1", "localhost"})
    if not secure or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment \
            or parsed.path not in {"", "/"} or any(c.isspace() for c in base_url) or len(token) < 32:
        raise ValueError("Invalid outbound worker configuration")
    if context.get("taskName") != "message_send_channel_message" or context.get("stepName") != "send_channel" \
            or context.get("executionId") != execution_id or set(context.get("parameters", {})) != {"deliveryId", "generation"}:
        raise ValueError("Invalid outbound task context")
    delivery_id = canonical_uuid(context["parameters"]["deliveryId"])
    generation = context["parameters"]["generation"]
    if type(generation) is not int or not 1 <= generation <= 100:
        raise ValueError("Invalid delivery generation")
    execution_id, lease_token = canonical_uuid(execution_id), canonical_uuid(lease_token)
    request = urllib.request.Request(base_url.rstrip("/") + "/internal/v2/messaging/jobs/deliveries/" + delivery_id + "/execute?generation=" + str(generation), data=b"{}",
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json", "Accept": "application/json",
                 "X-Task-Execution-Id": execution_id, "X-Task-Lease-Token": lease_token}, method="POST")
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    deadline = time.monotonic() + 85
    with client.open(request, timeout=80) as response:
        if response.status != 200:
            raise ValueError("Unexpected response status")
        payload = bytearray()
        while True:
            chunk = response.read(min(4096, 16385 - len(payload)))
            if not chunk:
                break
            payload.extend(chunk)
            if len(payload) > 16384 or time.monotonic() > deadline:
                raise ValueError("Response budget exceeded")
    result = json.loads(payload)
    if not isinstance(result, dict) or result.get("id") != delivery_id or result.get("generation") != generation \
            or result.get("status") not in {"PROVIDER_ACCEPTED", "FAILED", "UNKNOWN", "CANCELLED", "EXPIRED"}:
        raise ValueError("Invalid delivery receipt")
    return {"deliveryId": delivery_id, "status": result["status"]}


def write_result(result, target):
    """原子保存小型结果文件，避免中断后被读到半份 JSON。"""
    target = Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main():
    """环境由 Executor 的包级配置和保留执行变量提供。"""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    lease = Path(os.environ["TASK_LEASE_TOKEN_FILE"]).read_text(encoding="utf-8").strip()
    result = execute(context, os.environ.get("MESSAGING_SERVICE_URL", "http://127.0.0.1:23250"),
                     os.environ.get("MESSAGING_OUTBOUND_WORKER_TOKEN", ""), os.environ["TASK_EXECUTION_ID"], lease)
    write_result(result, os.environ["TASK_RESULT_FILE"])


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("OUTBOUND_EXECUTION_FAILED", file=sys.stderr)
        sys.exit(1)
