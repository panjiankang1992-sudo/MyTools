#!/usr/bin/env python3
"""Project one completed media download into Media Library."""

from __future__ import annotations

import hashlib
import json
import mimetypes
import os
from pathlib import PurePosixPath, Path
import re
import tempfile
import urllib.request
from urllib.parse import unquote, urlparse


DATE = re.compile(r"^20[0-9]{6}$")
MONTH = re.compile(r"^20[0-9]{4}$")


def media_directory(storage_uri: str) -> tuple[str, str] | None:
    """Return the month and day encoded in one managed media URI."""
    parsed = urlparse(storage_uri)
    if parsed.scheme != "storage":
        return None
    parts = PurePosixPath(unquote(parsed.path)).parts
    for index, value in enumerate(parts):
        if value == "media" and index + 2 < len(parts):
            month, day = parts[index + 1:index + 3]
            if MONTH.fullmatch(month) and DATE.fullmatch(day) and day.startswith(month):
                return month, day
    return None


def execute(context: dict, base_url: str, token: str,
            requester=urllib.request.urlopen) -> dict:
    """Register media output while allowing non-media downloads to pass through."""
    parameters = dict(context.get("parameters") or {})
    outputs = dict(context.get("stepOutputs") or {})
    published = dict(outputs.get("publish_asset") or outputs.get("download_asset") or {})
    registered = dict(outputs.get("register_asset") or {})
    display_name = str(published.get("fileName") or parameters.get("fileName") or "")
    mime_type = str(parameters.get("assetMimeType") or published.get("mimeType") or
                    mimetypes.guess_type(display_name)[0] or "")
    if not mime_type.startswith(("image/", "video/", "audio/")):
        return {"registered": False, "reason": "not-media"}
    directory = media_directory(str(published.get("storageUri") or ""))
    if directory is None:
        return {"registered": False, "reason": "not-managed-media-directory"}
    asset_id = str(registered.get("assetId") or "")
    content_sha256 = str(published.get("contentSha256") or published.get("sha256") or "").lower()
    size_bytes = published.get("sizeBytes", published.get("size"))
    if not asset_id or not re.fullmatch(r"[a-fA-F0-9-]{36}", asset_id):
        raise ValueError("registered download asset is missing")
    if not re.fullmatch(r"[a-f0-9]{64}", content_sha256) or not display_name or int(size_bytes or 0) <= 0:
        raise ValueError("published download metadata is incomplete")
    owner_id = int(parameters.get("ownerId") or 0)
    if owner_id <= 0:
        raise ValueError("download owner is missing")
    month, day = directory
    directory_key = hashlib.sha256(f"{owner_id}\0/media/{month}/{day}".encode()).hexdigest()[:24]
    parent_key = hashlib.sha256(f"{owner_id}\0/media/{month}".encode()).hexdigest()[:24]
    source_id = str(parameters.get("assetSourceBusinessId") or parameters.get("requestId") or
                    parameters.get("downloadRequestId") or context["taskInstanceId"])
    payload = {
        "eventId": f"download-media:{context['taskInstanceId']}",
        "assetId": asset_id,
        "ownerId": owner_id,
        "sourceBusinessId": source_id,
        "displayName": display_name,
        "mimeType": mime_type,
        "sizeBytes": int(size_bytes),
        "contentSha256": content_sha256,
        "directoryKey": directory_key,
        "directoryName": day,
        "parentDirectoryKey": parent_key,
        "parentDirectoryName": month,
    }
    if not token:
        raise ValueError("Media Library internal token is missing")
    request = urllib.request.Request(
        base_url.rstrip("/") + "/internal/v1/media/downloaded-asset-events",
        data=json.dumps(payload, separators=(",", ":")).encode(), method="POST",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json",
                 "Accept": "application/json"})
    with requester(request, timeout=30) as response:
        result = json.loads(response.read().decode())
    return {"registered": True, "mediaItemId": str(result["id"]),
            "directoryName": day, "version": int(result["version"])}


def main() -> None:
    """Run one downloaded media projection step."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    result = execute(context, os.getenv("MEDIA_LIBRARY_URL", "http://127.0.0.1:23300"),
                     os.environ.get("MEDIA_LIBRARY_INTERNAL_TOKEN", ""))
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent,
                                     delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


if __name__ == "__main__":
    main()
