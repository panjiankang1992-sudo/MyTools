#!/usr/bin/env python3
"""解析一个 X 帖子并为每个媒体创建原子下载子任务。"""

from __future__ import annotations

import hashlib
import json
import mimetypes
import os
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import urlparse
from uuid import UUID

from mytools_task_sdk.context import TaskContext
from mytools_task_sdk.orchestration import wait_all_or_cancel

X_PATH = re.compile(r"^/(?:[^/]+/status|i/(?:web/)?status)/(\d{1,24})(?:/.*)?$", re.I)
SAFE_PART = re.compile(r"[^A-Za-z0-9_-]+")
MAX_METADATA_BYTES = 8 * 1024 * 1024


def canonical_x_url(value: object) -> tuple[str, str]:
    """只接受单个公开 X 帖子 URL 并生成稳定地址。"""
    parsed = urlparse(str(value or "").strip())
    host = (parsed.hostname or "").lower().removeprefix("www.").removeprefix("mobile.")
    match = X_PATH.match(parsed.path)
    if parsed.scheme not in {"http", "https"} or host not in {"x.com", "twitter.com"} or not match:
        raise ValueError("url must identify one X status")
    tweet_id = match.group(1)
    return f"https://x.com/i/web/status/{tweet_id}", tweet_id


def parse_resources(raw: bytes, tweet_id: str, maximum: int) -> list[dict]:
    """从 gallery-dl 有界元数据中提取唯一的 twimg 媒体。"""
    if len(raw) > MAX_METADATA_BYTES:
        raise ValueError("X metadata exceeds limit")
    try:
        messages = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise ValueError("gallery-dl returned invalid metadata") from exception
    if not isinstance(messages, list):
        raise ValueError("gallery-dl metadata must be a list")
    resources = []
    seen = set()
    for message in messages:
        if not isinstance(message, list) or len(message) < 3 or message[0] != 3:
            continue
        url, metadata = message[1], message[2]
        if not isinstance(url, str) or not isinstance(metadata, dict):
            continue
        parsed = urlparse(url)
        if parsed.scheme != "https" or not (parsed.hostname or "").lower().endswith(".twimg.com"):
            continue
        resource_tweet = str(metadata.get("tweet_id") or tweet_id)
        if not re.fullmatch(r"[0-9]{1,24}", resource_tweet):
            continue
        try:
            index = int(metadata.get("num") or metadata.get("count") or len(resources) + 1)
        except (TypeError, ValueError):
            index = len(resources) + 1
        extension = str(metadata.get("extension") or "").strip(". ").lower()
        if not extension or not re.fullmatch(r"[a-z0-9]{1,10}", extension):
            extension = Path(parsed.path).suffix.lstrip(".").lower() or "bin"
        base = SAFE_PART.sub("_", str(metadata.get("filename") or "media")).strip("_") or "media"
        file_name = f"x-{resource_tweet}-{index:02d}-{base[:120]}.{extension}"
        key = resource_tweet, index, url
        if key in seen:
            continue
        seen.add(key)
        resources.append({"tweetId": resource_tweet, "index": index, "url": url,
                          "fileName": file_name,
                          "mimeType": mimetypes.guess_type(file_name)[0]
                          or "application/octet-stream"})
        if len(resources) > maximum:
            raise ValueError("X media count exceeds limit")
    if not resources:
        raise ValueError("X post contains no downloadable media")
    return resources


def resolve(parameters: dict, runner=subprocess.run) -> tuple[str, list[dict]]:
    """运行无下载模式的 gallery-dl 并返回标准化媒体。"""
    canonical, tweet_id = canonical_x_url(parameters["url"])
    maximum = int(parameters.get("maxMedia", 20))
    if maximum < 1 or maximum > 40:
        raise ValueError("maxMedia is outside the supported range")
    command = [os.getenv("GALLERY_DL_BINARY", "gallery-dl"), "-J", "--no-download",
               "--no-input", "--no-colors", "-o", "extractor.twitter.videos=true",
               "-o", "extractor.twitter.quoted=true", "-o", "extractor.twitter.cards=false"]
    cookie_path = os.getenv("X_COOKIES_PATH", "")
    if cookie_path:
        if not Path(cookie_path).is_file():
            raise ValueError("configured X cookie file is missing")
        command.extend(("--cookies", cookie_path))
    proxy = os.getenv("X_PROXY_URL", "")
    if proxy:
        command.extend(("--proxy", proxy))
    command.append(canonical)
    completed = runner(command, capture_output=True, timeout=90, check=False)
    if completed.returncode != 0:
        raise RuntimeError("gallery-dl could not resolve X media")
    return tweet_id, parse_resources(completed.stdout, tweet_id, maximum)


def execute(context: TaskContext, parameters: dict, resources: list[dict], tweet_id: str) -> dict:
    """并行创建媒体子任务，并在失败时取消尚未完成的同批任务。"""
    request_id = str(parameters["downloadRequestId"])
    UUID(request_id)
    maximum_bytes = int(parameters.get("maxBytesPerItem", 2 * 1024 * 1024 * 1024))
    children = []
    for resource in resources:
        item_id = f"x:{resource['tweetId']}:{resource['index']}"
        fingerprint = hashlib.sha256(resource["url"].encode()).hexdigest()
        child = context.create_child(
            "download_http_asset",
            {"downloadRequestId": request_id, "itemId": item_id, "url": resource["url"],
             "fileName": resource["fileName"], "maxBytes": maximum_bytes,
             "ownerId": int(parameters.get("ownerId") or 0),
             "assetMimeType": resource["mimeType"],
             "assetSourceBusinessId": f"{request_id}:{item_id}"},
            f"x-media:{request_id}:{resource['tweetId']}:{resource['index']}:{fingerprint}",
            business_type="DOWNLOAD_REQUEST", business_id=request_id)
        children.append(child)
    wait_all_or_cancel(context, children, 1500)
    return {"requestId": request_id, "tweetId": tweet_id,
            "mediaCount": len(children), "childTaskIds": [child.id for child in children]}


def write_result(result: dict) -> None:
    """原子写入任务结果。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent,
                                     delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """执行一个 X 帖子解析父任务。"""
    context = TaskContext.load()
    parameters = context.parameters
    tweet_id, resources = resolve(parameters)
    write_result(execute(context, parameters, resources, tweet_id))


if __name__ == "__main__":
    main()
