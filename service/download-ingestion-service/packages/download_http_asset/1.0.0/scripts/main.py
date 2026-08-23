#!/usr/bin/env python3
"""Download one HTTP asset with bounded streaming and atomic publication."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import urlparse
from urllib.request import Request, urlopen

CHUNK_BYTES = 1024 * 1024
DEFAULT_MAX_BYTES = 2 * 1024 * 1024 * 1024
MAX_CONFIGURED_BYTES = 20 * 1024 * 1024 * 1024
SAFE_NAME = re.compile(r"^[^/\\\x00]{1,255}$")


def validated_name(value: object) -> str:
    """Reject path traversal and invalid destination file names."""
    name = str(value or "").strip()
    if not SAFE_NAME.fullmatch(name) or name in {".", ".."}:
        raise ValueError("fileName is invalid")
    return name


def validated_url(value: object) -> str:
    """Allow only absolute HTTP URLs."""
    url = str(value or "").strip()
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("url must be absolute HTTP or HTTPS")
    return url


def byte_limit(parameters: dict) -> int:
    """Resolve a positive bounded download size limit."""
    limit = int(parameters.get("maxBytes", DEFAULT_MAX_BYTES))
    if limit <= 0 or limit > MAX_CONFIGURED_BYTES:
        raise ValueError("maxBytes is outside the supported range")
    return limit


def stream_download(parameters: dict, destination_root: Path, opener=urlopen) -> dict:
    """Stream an asset to staging, validate it, and atomically publish it."""
    request_id = str(parameters["downloadRequestId"])
    item_id = str(parameters["itemId"])
    file_name = validated_name(parameters["fileName"])
    url = validated_url(parameters["url"])
    limit = byte_limit(parameters)
    target_dir = destination_root / request_id
    target_dir.mkdir(parents=True, exist_ok=True)
    target = target_dir / file_name
    digest = hashlib.sha256()
    size = 0
    temporary_path: Path | None = None
    request = Request(url, headers={"User-Agent": "MyTools-Download-Executor/1.0"})
    try:
        with opener(request, timeout=30) as response:
            declared = response.headers.get("Content-Length")
            if declared is not None and int(declared) > limit:
                raise ValueError("declared content length exceeds maxBytes")
            with tempfile.NamedTemporaryFile("wb", dir=target_dir, delete=False) as handle:
                temporary_path = Path(handle.name)
                while chunk := response.read(CHUNK_BYTES):
                    size += len(chunk)
                    if size > limit:
                        raise ValueError("download exceeds maxBytes")
                    digest.update(chunk)
                    handle.write(chunk)
        content_sha256 = digest.hexdigest()
        expected = str(parameters.get("expectedSha256") or "").lower()
        if expected and expected != content_sha256:
            raise ValueError("download checksum mismatch")
        temporary_path.replace(target)
        temporary_path = None
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
    return {
        "requestId": request_id,
        "itemId": item_id,
        "fileName": file_name,
        "relativePath": f"{request_id}/{file_name}",
        "sizeBytes": size,
        "contentSha256": content_sha256,
    }


def write_result(result: dict) -> None:
    """Atomically write the executor result document."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one HTTP asset download task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    destination_root = Path(os.environ["DOWNLOAD_DESTINATION_ROOT"]).resolve()
    destination_root.mkdir(parents=True, exist_ok=True)
    write_result(stream_download(context["parameters"], destination_root))


if __name__ == "__main__":
    main()
