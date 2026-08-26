#!/usr/bin/env python3
"""Publish one verified executor download into durable managed storage."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from datetime import UTC, datetime
from urllib.parse import quote

from mytools_task_sdk.storage import StorageGatewayClient

ROOT = re.compile(r"^[A-Za-z][A-Za-z0-9._-]{0,127}$")
SAFE_DIRECTORY = re.compile(r"[^A-Za-z0-9._-]+")
BIG_VIDEO_THRESHOLD = 50 * 1024 * 1024


def managed_directory(parameters: dict, output: dict) -> str:
    """依据实际 MIME、大小和消息时间生成兼容 DownloadBot 的业务目录。"""
    received = str(parameters.get("receivedAt") or "")
    try:
        received_at = datetime.fromisoformat(received.replace("Z", "+00:00"))
    except ValueError:
        received_at = datetime.now(UTC)
    mime = str(parameters.get("assetMimeType") or "application/octet-stream").lower()
    size = int(output["sizeBytes"])
    file_name = str(output["fileName"])
    if mime.startswith("video/") and size > int(parameters.get(
            "bigVideoThresholdBytes", BIG_VIDEO_THRESHOLD)):
        stem = SAFE_DIRECTORY.sub("_", Path(file_name).stem).strip("._-") or "video"
        package = f"{received_at.strftime('%Y%m%d_%H%M%S')}_{stem[:72]}"
        return f"big_media/{package}"
    if mime.startswith(("image/", "video/")):
        directory = f"media/{received_at.strftime('%Y%m')}/{received_at.strftime('%Y%m%d')}"
        album = SAFE_DIRECTORY.sub("_", str(parameters.get("albumFolder") or "")).strip("._-")
        return f"{directory}/{album[:96]}" if album else directory
    suffix = Path(file_name).suffix.lower()
    if mime in {"application/epub+zip", "application/pdf"} or suffix in {".epub", ".mobi", ".azw3", ".txt"}:
        return "ebook"
    return "other"


def execute(context: dict, destination_root: Path, storage: StorageGatewayClient) -> dict:
    """Reverify the downloaded file and publish it using a stable content identity."""
    output = dict((context.get("stepOutputs") or {}).get("download_asset") or {})
    required = ("requestId", "itemId", "fileName", "relativePath", "sizeBytes", "contentSha256")
    if any(output.get(name) in (None, "") for name in required):
        raise ValueError("download output is incomplete")
    relative = Path(str(output["relativePath"]))
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError("download relative path is invalid")
    root = destination_root.resolve()
    unresolved_source = root / relative
    if unresolved_source.is_symlink():
        raise ValueError("download output must not be a symbolic link")
    source = unresolved_source.resolve(strict=True)
    if not source.is_file() or not source.is_relative_to(root):
        raise ValueError("download output is outside the destination root")
    expected_size = int(output["sizeBytes"])
    expected_sha256 = str(output["contentSha256"]).lower()
    digest = hashlib.sha256()
    size = 0
    with source.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            size += len(chunk)
            if size > expected_size:
                raise ValueError("download output size changed")
            digest.update(chunk)
    if size != expected_size or digest.hexdigest() != expected_sha256:
        raise ValueError("download output integrity check failed")
    root_name = str(context.get("parameters", {}).get("storageRoot") or
                    os.getenv("DOWNLOAD_STORAGE_ROOT", "downloads"))
    if not ROOT.fullmatch(root_name):
        raise ValueError("download storage root is invalid")
    parameters = dict(context.get("parameters") or {})
    managed_path = "/".join((managed_directory(parameters, output),
                             quote(str(output["fileName"]), safe="")))
    storage_uri = storage.publish(source, root_name, managed_path,
        f"download:{output['requestId']}:{output['itemId']}:{expected_sha256}",
        expected_size, expected_sha256)
    return {"requestId": str(output["requestId"]), "itemId": str(output["itemId"]),
            "fileName": str(output["fileName"]), "storageUri": storage_uri,
            "sizeBytes": expected_size, "contentSha256": expected_sha256}


def write_result(result: dict) -> None:
    """Atomically write the task result."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Publish one downloaded file."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    storage = StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                                   os.getenv("STORAGE_INTERNAL_TOKEN", ""))
    write_result(execute(context, Path(os.environ["DOWNLOAD_DESTINATION_ROOT"]), storage))


if __name__ == "__main__":
    main()
