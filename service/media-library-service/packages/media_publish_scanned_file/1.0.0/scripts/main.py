#!/usr/bin/env python3
"""Reverify and publish one scanned media file into managed storage."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import quote

from mytools_task_sdk.storage import StorageGatewayClient

READ_SIZE = 1024 * 1024
ROOT_NAME = re.compile(r"^[A-Za-z][A-Za-z0-9._-]{0,127}$")


def resolve_source(source_value: str, configured_roots: list[str]) -> Path:
    """Resolve one regular non-symlink file under an explicitly allowed media root."""
    unresolved = Path(source_value)
    if unresolved.is_symlink():
        raise ValueError("Media source must not be a symbolic link")
    source = unresolved.resolve(strict=True)
    roots = [Path(value).resolve(strict=True) for value in configured_roots]
    if any(not root.is_dir() for root in roots):
        raise ValueError("Media scan root is not a directory")
    if not source.is_file() or not roots or not any(
            source == root or root in source.parents for root in roots):
        raise ValueError("Media source is outside configured roots")
    return source


def execute(context: dict, configured_roots: list[str], storage: StorageGatewayClient) -> dict:
    """Reverify an immutable scan entry and publish it with a stable content identity."""
    parameters = context["parameters"]
    source = resolve_source(str(parameters["sourcePath"]), configured_roots)
    expected_size = int(parameters["sizeBytes"])
    expected_sha256 = str(parameters["contentSha256"]).lower()
    before = source.stat(follow_symlinks=False)
    digest = hashlib.sha256()
    size = 0
    with source.open("rb") as handle:
        while chunk := handle.read(READ_SIZE):
            size += len(chunk)
            if size > expected_size:
                raise ValueError("Media source size changed")
            digest.update(chunk)
    after = source.stat(follow_symlinks=False)
    if (before.st_ino, before.st_size, before.st_mtime_ns) != (
            after.st_ino, after.st_size, after.st_mtime_ns):
        raise ValueError("Media source changed while publishing")
    if size != expected_size or digest.hexdigest() != expected_sha256:
        raise ValueError("Media source integrity check failed")
    root_name = str(parameters.get("storageRoot") or os.getenv("MEDIA_STORAGE_ROOT", "media"))
    if ROOT_NAME.fullmatch(root_name) is None:
        raise ValueError("Media storage root is invalid")
    owner_id = int(parameters.get("ownerId") or 0)
    file_name = quote(source.name, safe="")
    managed_path = f"scans/{owner_id}/{expected_sha256}/{file_name}"
    source_id = str(parameters["sourceBusinessId"])
    storage_uri = storage.publish(source, root_name, managed_path,
                                  f"media-scan:{source_id}:{expected_sha256}",
                                  expected_size, expected_sha256)
    return {"storageUri": storage_uri, "contentSha256": expected_sha256,
            "sizeBytes": expected_size}


def write_result(result: dict) -> None:
    """Atomically write the task result."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent,
                                     delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Publish one scanned media file."""
    configured_roots = json.loads(os.getenv("MEDIA_SCAN_ALLOWED_ROOTS", "[]"))
    if not isinstance(configured_roots, list) or not all(
            isinstance(value, str) for value in configured_roots):
        raise ValueError("MEDIA_SCAN_ALLOWED_ROOTS must be a JSON string array")
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    storage = StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                                   os.getenv("STORAGE_INTERNAL_TOKEN", ""))
    write_result(execute(context, configured_roots, storage))


if __name__ == "__main__":
    main()
