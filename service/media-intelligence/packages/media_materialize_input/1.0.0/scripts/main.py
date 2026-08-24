#!/usr/bin/env python3
"""Materialize an immutable Asset Registry object into the executor work directory."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import quote, unquote, urlsplit
from urllib.request import Request, urlopen

CHUNK_BYTES = 1024 * 1024
DEFAULT_MAXIMUM_BYTES = 100 * 1024 * 1024 * 1024
ROOT_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9._-]{0,127}$")


def request_json(url: str, token: str, opener=urlopen) -> dict:
    """Read one authenticated JSON document."""
    request = Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/json"})
    with opener(request, timeout=30) as response:
        document = json.loads(response.read().decode("utf-8"))
    if not isinstance(document, dict):
        raise ValueError("service response must be an object")
    return document


def select_location(asset: dict) -> tuple[str, str, str]:
    """Select one readable Storage Gateway URI and return root, path and URI."""
    for location in asset.get("locations") or []:
        if not isinstance(location, dict) or location.get("availability") != "AVAILABLE":
            continue
        if location.get("providerType") != "STORAGE_GATEWAY":
            continue
        uri = str(location.get("storageUri") or "")
        parsed = urlsplit(uri)
        path = unquote(parsed.path.lstrip("/"))
        if (parsed.scheme == "storage" and ROOT_PATTERN.fullmatch(parsed.netloc or "")
                and path and not parsed.query and not parsed.fragment and ".." not in Path(path).parts):
            return parsed.netloc, path, uri
    raise ValueError("asset has no available Storage Gateway location")


def safe_filename(value: object) -> str:
    """Return a bounded filename without directory components."""
    name = Path(str(value or "input.bin")).name.strip()
    if not name or name in {".", ".."}:
        return "input.bin"
    return name[:255]


def stream_content(url: str, token: str, target: Path, expected_size: int,
                   expected_sha256: str, maximum_bytes: int, opener=urlopen) -> None:
    """Stream one object with exact size and digest verification before atomic publication."""
    if expected_size < 0 or expected_size > maximum_bytes:
        raise ValueError("asset size exceeds materialization limit")
    request = Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/octet-stream"})
    target.parent.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256()
    size = 0
    with tempfile.NamedTemporaryFile("wb", dir=target.parent, delete=False) as handle:
        temporary = Path(handle.name)
        try:
            with opener(request, timeout=300) as response:
                while chunk := response.read(CHUNK_BYTES):
                    size += len(chunk)
                    if size > expected_size or size > maximum_bytes:
                        raise ValueError("materialized input exceeds expected size")
                    digest.update(chunk)
                    handle.write(chunk)
            if size != expected_size or digest.hexdigest() != expected_sha256:
                raise ValueError("materialized input integrity check failed")
            handle.flush()
            os.fsync(handle.fileno())
        except BaseException:
            temporary.unlink(missing_ok=True)
            raise
    temporary.replace(target)


def execute(context: dict, asset_url: str, asset_token: str, storage_url: str,
            storage_token: str, opener=urlopen) -> dict:
    """Resolve, validate and materialize one registered media asset."""
    parameters = context.get("parameters") or {}
    asset_id = str(parameters.get("assetRegistryId") or "")
    expected_sha256 = str(parameters.get("contentSha256") or "").lower()
    asset = request_json(asset_url.rstrip("/") + "/internal/v1/assets/" + quote(asset_id),
                         asset_token, opener)
    if str(asset.get("id")) != asset_id or str(asset.get("status")) != "ACTIVE":
        raise ValueError("asset identity or status is invalid")
    if str(asset.get("contentSha256") or "").lower() != expected_sha256:
        raise ValueError("asset digest does not match task parameters")
    expected_size = int(asset.get("sizeBytes"))
    root, path, storage_uri = select_location(asset)
    maximum_bytes = int(os.getenv("MEDIA_INPUT_MAXIMUM_BYTES", str(DEFAULT_MAXIMUM_BYTES)))
    query = "?rootName=" + quote(root) + "&path=" + quote(path)
    target = Path(os.environ["TASK_WORK_DIR"]) / "input" / safe_filename(parameters.get("filename"))
    stream_content(storage_url.rstrip("/") + "/api/internal/v1/storage/objects/content" + query,
                   storage_token, target, expected_size, expected_sha256, maximum_bytes, opener)
    return {"assetRegistryId": asset_id, "sourcePath": str(target), "storageUri": storage_uri,
            "contentSha256": expected_sha256, "sizeBytes": expected_size}


def write_result(result: dict) -> None:
    """Atomically write the task result."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Materialize one task input."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    result = execute(context, os.getenv("ASSET_REGISTRY_URL", "http://127.0.0.1:23270"),
                     os.getenv("ASSET_REGISTRY_INTERNAL_TOKEN", ""),
                     os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                     os.getenv("STORAGE_INTERNAL_TOKEN", ""))
    write_result(result)


if __name__ == "__main__":
    main()
