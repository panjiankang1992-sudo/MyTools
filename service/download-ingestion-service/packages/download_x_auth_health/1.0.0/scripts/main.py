#!/usr/bin/env python3
"""定时验证 X 登录会话并通过统一消息服务发送失效告警。"""

from __future__ import annotations

from datetime import datetime, timezone
import http.cookiejar
import json
import os
from pathlib import Path
import subprocess
import tempfile
from urllib.parse import urlencode
from urllib.request import Request, urlopen


SUPPORTED_ALERT_CHANNELS = {"TELEGRAM", "QQ", "ONEBOT"}
MAX_RESPONSE_BYTES = 1024 * 1024


def validate_cookie_file(path: Path) -> None:
    """验证 Cookie 文件存在并包含 X 登录所需的关键项。"""
    if not path.is_file():
        raise ValueError("configured X cookie file is missing")
    jar = http.cookiejar.MozillaCookieJar(str(path))
    try:
        jar.load(ignore_discard=True, ignore_expires=True)
    except (OSError, http.cookiejar.LoadError) as exception:
        raise ValueError("configured X cookie file is invalid") from exception
    names = {cookie.name for cookie in jar if cookie.domain.endswith("x.com")}
    if not {"auth_token", "ct0"}.issubset(names):
        raise ValueError("configured X cookie file lacks authentication cookies")


def gallery_command(cookie_path: Path, probe_url: str) -> list[str]:
    """构造最小化 X 时间线探测命令并允许更新会话 Cookie。"""
    command = [os.getenv("GALLERY_DL_BINARY", "gallery-dl"), "-J", "--no-download",
               "--no-input", "--no-colors", "--range", "1",
               "--cookies", str(cookie_path), "-o", "extractor.cookies-update=true"]
    proxy = os.getenv("X_PROXY_URL", "").strip()
    if proxy:
        command.extend(("--proxy", proxy))
    command.append(probe_url)
    return command


def probe(cookie_path: Path, probe_url: str, runner=subprocess.run) -> None:
    """探测一条时间线元数据并识别登录失效响应。"""
    validate_cookie_file(cookie_path)
    completed = runner(gallery_command(cookie_path, probe_url), capture_output=True,
                       timeout=120, check=False)
    output = completed.stdout[:MAX_RESPONSE_BYTES]
    if b'"error": "AuthRequired"' in output or b'"error":"AuthRequired"' in output:
        raise ValueError("X authentication is required")
    if completed.returncode != 0:
        raise ValueError("X authentication probe failed")


def request_json(request: Request, opener=urlopen) -> object:
    """调用统一消息服务并限制响应体大小。"""
    with opener(request, timeout=30) as response:
        payload = response.read(MAX_RESPONSE_BYTES + 1)
    if len(payload) > MAX_RESPONSE_BYTES:
        raise ValueError("Messaging response exceeds limit")
    return json.loads(payload)


def alert(message: str, checked_at: datetime, opener=urlopen) -> bool:
    """向所有者最近使用的消息入口发送每日一次的会话失效提醒。"""
    base_url = os.getenv("MESSAGING_SERVICE_URL", "").rstrip("/")
    token = os.getenv("MESSAGING_INTERNAL_TOKEN", "").strip()
    owner_value = os.getenv("X_HEALTH_ALERT_OWNER_ID", "").strip()
    if not base_url or not token or not owner_value:
        return False
    owner_id = int(owner_value)
    query = urlencode({"ownerId": owner_id, "limit": 100})
    list_request = Request(f"{base_url}/internal/v1/inbound-messages?{query}",
                           headers={"Authorization": f"Bearer {token}"})
    page = request_json(list_request, opener)
    items = page.get("items") if isinstance(page, dict) else None
    candidates = [item for item in (items or []) if isinstance(item, dict)
                  and item.get("channelType") in SUPPORTED_ALERT_CHANNELS]
    if not candidates:
        return False
    latest = max(candidates, key=lambda item: str(item.get("receivedAt") or ""))
    message_id = str(latest.get("id") or "")
    if not message_id:
        return False
    body = {"idempotencyKey": f"x-auth-health-{checked_at:%Y%m%d}", "body": message}
    reply_request = Request(f"{base_url}/internal/v1/inbound-messages/{message_id}/replies",
                            data=json.dumps(body, separators=(",", ":")).encode(), method="POST",
                            headers={"Authorization": f"Bearer {token}",
                                     "Content-Type": "application/json"})
    request_json(reply_request, opener)
    return True


def write_result(result: dict) -> None:
    """原子写入 X 会话健康检查结果。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent,
                                     delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """执行 X 会话健康检查并在失效时发送统一消息告警。"""
    checked_at = datetime.now(timezone.utc)
    cookie_path = Path(os.getenv("X_COOKIES_PATH", "").strip())
    probe_url = os.getenv("X_HEALTH_PROBE_URL", "https://x.com/X").strip()
    try:
        probe(cookie_path, probe_url)
    except (OSError, ValueError, subprocess.SubprocessError) as exception:
        try:
            alert("X authentication expired. Sign in with Chrome and refresh the server cookie file.",
                  checked_at)
        except (OSError, ValueError, json.JSONDecodeError):
            # 告警链路异常不能覆盖原始认证失败，调度任务仍需保留明确失败状态。
            pass
        raise RuntimeError("X authentication health check failed") from exception
    write_result({"healthy": True, "checkedAt": checked_at.isoformat(), "probeUrl": probe_url})


if __name__ == "__main__":
    main()
