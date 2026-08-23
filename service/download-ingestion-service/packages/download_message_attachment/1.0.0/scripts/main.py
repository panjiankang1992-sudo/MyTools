#!/usr/bin/env python3
"""Stream one provider attachment through the Messaging trust boundary."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.request import Request, urlopen

CHUNK_BYTES = 1024 * 1024
MAX_CONFIGURED_BYTES = 20 * 1024 * 1024 * 1024
SAFE_NAME = re.compile(r"^[^/\\\x00]{1,255}$")


def stream_download(parameters: dict, destination_root: Path, base_url: str, token: str,
                    opener=urlopen) -> dict:
    """Fetch by opaque job id, enforce bounds, and atomically publish the content."""
    if not token:
        raise ValueError("Messaging Service internal token is missing")
    request_id = str(parameters["downloadRequestId"])
    item_id = str(parameters["itemId"])
    job_id = str(parameters["attachmentJobId"])
    file_name = str(parameters["fileName"] or "").strip()
    limit = int(parameters["maxBytes"])
    if not SAFE_NAME.fullmatch(file_name) or file_name in {".", ".."}:
        raise ValueError("fileName is invalid")
    if limit < 1 or limit > MAX_CONFIGURED_BYTES:
        raise ValueError("maxBytes is outside the supported range")
    target_dir = destination_root / request_id
    target_dir.mkdir(parents=True, exist_ok=True)
    target = target_dir / file_name
    request = Request(f"{base_url.rstrip('/')}/internal/v1/attachment-downloads/{job_id}/content",
                      data=b"", method="POST", headers={"Authorization": f"Bearer {token}",
                                                         "Accept": "application/octet-stream"})
    digest, size, temporary_path = hashlib.sha256(), 0, None
    try:
        with opener(request, timeout=60) as response:
            declared = response.headers.get("Content-Length")
            if declared is not None and int(declared) > limit:
                raise ValueError("declared content length exceeds maxBytes")
            with tempfile.NamedTemporaryFile("wb", dir=target_dir, delete=False) as handle:
                temporary_path = Path(handle.name)
                while chunk := response.read(CHUNK_BYTES):
                    size += len(chunk)
                    if size > limit:
                        raise ValueError("attachment exceeds maxBytes")
                    digest.update(chunk)
                    handle.write(chunk)
        temporary_path.replace(target)
        temporary_path = None
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
    return {"requestId": request_id, "itemId": item_id, "fileName": file_name,
            "relativePath": f"{request_id}/{file_name}", "sizeBytes": size,
            "contentSha256": digest.hexdigest()}


def write_result(result: dict) -> None:
    """Atomically write the executor result file."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one controlled attachment stream task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    root = Path(os.environ["DOWNLOAD_DESTINATION_ROOT"]).resolve()
    root.mkdir(parents=True, exist_ok=True)
    write_result(stream_download(context["parameters"], root,
                                 os.getenv("MESSAGING_SERVICE_URL", "http://127.0.0.1:23250"),
                                 os.environ.get("MESSAGING_INTERNAL_TOKEN", "")))


if __name__ == "__main__":
    main()
