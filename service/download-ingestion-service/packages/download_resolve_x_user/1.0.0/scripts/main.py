#!/usr/bin/env python3
"""读取 X 用户媒体帖子并分批创建现有单帖下载任务。"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import urlparse
from urllib.request import Request, urlopen
from uuid import UUID

from mytools_task_sdk.context import TaskContext
from mytools_task_sdk.orchestration import wait_all_or_cancel

X_USER_PATH = re.compile(r"^/([A-Za-z0-9_]{1,15})(?:/media)?/?$", re.IGNORECASE)
RESERVED = {"home", "explore", "search", "notifications", "messages", "settings", "compose", "i"}
POST_ID = re.compile(r"^[0-9]{1,24}$")
PAGE_SIZE = 100
CHILD_BATCH_SIZE = 20
MAX_POSTS = 10000
MAX_METADATA_BYTES = 16 * 1024 * 1024


def canonical_user_url(value: object) -> tuple[str, str]:
    """验证 X 用户主页并统一为用户时间线地址。"""
    parsed = urlparse(str(value or "").strip())
    host = (parsed.hostname or "").lower().removeprefix("www.").removeprefix("mobile.")
    match = X_USER_PATH.fullmatch(parsed.path)
    if parsed.scheme not in {"http", "https"} or host not in {"x.com", "twitter.com"} or not match:
        raise ValueError("url must identify one X user")
    username = match.group(1)
    if username.lower() in RESERVED:
        raise ValueError("url must identify one X user")
    return f"https://x.com/{username}", username


def parse_post_ids(raw: bytes) -> list[str]:
    """从单页 gallery-dl 元数据中提取有媒体的帖子标识。"""
    if len(raw) > MAX_METADATA_BYTES:
        raise ValueError("X user metadata exceeds limit")
    try:
        messages = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise ValueError("gallery-dl returned invalid user metadata") from exception
    if not isinstance(messages, list):
        raise ValueError("gallery-dl user metadata must be a list")
    for message in messages:
        if isinstance(message, list) and len(message) > 1 and message[0] == -1 \
                and isinstance(message[1], dict) and message[1].get("error") == "AuthRequired":
            raise ValueError("X user timeline requires configured authenticated cookies")
    identifiers = []
    seen = set()
    for message in messages:
        if not isinstance(message, list) or len(message) < 3 or message[0] != 3:
            continue
        metadata = message[2]
        if not isinstance(metadata, dict):
            continue
        identifier = str(metadata.get("tweet_id") or "")
        if POST_ID.fullmatch(identifier) and identifier not in seen:
            seen.add(identifier)
            identifiers.append(identifier)
    return identifiers


def gallery_command(url: str, start: int, end: int) -> list[str]:
    """构造只读取一页元数据的受控 gallery-dl 命令。"""
    command = [os.getenv("GALLERY_DL_BINARY", "gallery-dl"), "-J", "--no-download",
               "--no-input", "--no-colors", "--range", f"{start}-{end}",
               "-o", "extractor.twitter.videos=true",
               "-o", "extractor.twitter.quoted=false",
               "-o", "extractor.twitter.retweets=false"]
    cookie_path = os.getenv("X_COOKIES_PATH", "").strip()
    if cookie_path:
        if not Path(cookie_path).is_file():
            raise ValueError("configured X cookie file is missing")
        command.extend(("--cookies", cookie_path))
    proxy = os.getenv("X_PROXY_URL", "").strip()
    if proxy:
        command.extend(("--proxy", proxy))
    command.append(url)
    return command


def enumerate_post_ids(parameters: dict, runner=subprocess.run) -> tuple[str, list[str]]:
    """分页读取用户媒体时间线，最多接受全局消息动作上限数量。"""
    url, username = canonical_user_url(parameters.get("url"))
    maximum = int(parameters.get("maxPosts", MAX_POSTS))
    if maximum < 1 or maximum > MAX_POSTS:
        raise ValueError("maxPosts is outside the supported range")
    identifiers = []
    seen = set()
    start = 1
    while start <= maximum:
        end = min(maximum, start + PAGE_SIZE - 1)
        completed = runner(gallery_command(url, start, end), capture_output=True,
                           timeout=180, check=False)
        if completed.returncode != 0:
            raise ValueError("gallery-dl could not read X user posts")
        page = parse_post_ids(completed.stdout)
        new_values = [identifier for identifier in page if identifier not in seen]
        if not new_values:
            break
        identifiers.extend(new_values)
        seen.update(new_values)
        if len(page) < PAGE_SIZE:
            break
        start += PAGE_SIZE
    if not identifiers:
        raise ValueError("X user contains no downloadable media posts")
    return username, identifiers[:maximum]


def claim_post_ids(parameters: dict, post_ids: list[str], opener=urlopen) -> list[str]:
    """在消息自动化服务登记每个帖子链接并只返回首次处理的帖子。"""
    message_id = str(parameters.get("messageBatchId") or "")
    try:
        UUID(message_id)
    except ValueError:
        return post_ids
    base_url = os.getenv("MESSAGE_AUTOMATION_URL", "").rstrip("/")
    token = os.getenv("MESSAGE_AUTOMATION_INTERNAL_TOKEN", "")
    if not base_url or not token:
        raise ValueError("message link registry configuration is missing")
    claimed = []
    for offset in range(0, len(post_ids), PAGE_SIZE):
        urls = [f"https://x.com/i/web/status/{identifier}"
                for identifier in post_ids[offset:offset + PAGE_SIZE]]
        payload = {"ownerId": int(parameters.get("ownerId") or 0), "messageId": message_id,
                   "processedAt": str(parameters.get("receivedAt") or ""), "urls": urls}
        request = Request(f"{base_url}/internal/v1/processed-links/claims",
                          data=json.dumps(payload, separators=(",", ":")).encode(), method="POST",
                          headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
        with opener(request, timeout=30) as response:
            result = json.loads(response.read(MAX_METADATA_BYTES + 1))
        values = result.get("claimedUrls") if isinstance(result, dict) else None
        if not isinstance(values, list):
            raise ValueError("message link registry returned an invalid response")
        for value in values:
            match = re.fullmatch(r"https://x\.com/i/web/status/([0-9]{1,24})", str(value))
            if match:
                claimed.append(match.group(1))
    return claimed


def execute(context: TaskContext, username: str, post_ids: list[str]) -> dict:
    """每二十条帖子创建一批单帖下载任务并等待批次完成。"""
    parameters = context.parameters
    request_id = str(parameters.get("downloadRequestId") or "")
    UUID(request_id)
    batches = 0
    for offset in range(0, len(post_ids), CHILD_BATCH_SIZE):
        children = []
        for identifier in post_ids[offset:offset + CHILD_BATCH_SIZE]:
            url = f"https://x.com/i/web/status/{identifier}"
            fingerprint = hashlib.sha256(url.encode()).hexdigest()
            child = context.create_child(
                "download_x_post",
                {"downloadRequestId": request_id, "url": url, "ownerId": int(parameters.get("ownerId") or 0),
                 "resourceUsername": str(parameters.get("resourceUsername") or ""),
                 "receivedAt": str(parameters.get("receivedAt") or ""),
                 "messageBatchId": str(parameters.get("messageBatchId") or request_id),
                 "albumFolder": username},
                f"x-user-post:{request_id}:{identifier}:{fingerprint}",
                business_type="DOWNLOAD_REQUEST", business_id=request_id)
            children.append(child)
        wait_all_or_cancel(context, children, 1800)
        batches += 1
    return {"requestId": request_id, "username": username,
            "postCount": len(post_ids), "batchCount": batches}


def write_result(result: dict) -> None:
    """原子写入用户帖子编排结果。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent,
                                     delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """执行 X 用户媒体帖子批量编排。"""
    context = TaskContext.load()
    username, post_ids = enumerate_post_ids(context.parameters)
    post_ids = claim_post_ids(context.parameters, post_ids)
    write_result(execute(context, username, post_ids))


if __name__ == "__main__":
    main()
