#!/usr/bin/env python3
"""Scan one allow-listed directory and submit bounded media ingestion children."""

from __future__ import annotations

import hashlib
import json
import mimetypes
import os
from pathlib import Path
import tempfile
import urllib.request

from mytools_task_sdk.context import TaskContext

MAX_FILES = 1000
READ_SIZE = 1024 * 1024
MEDIA_EXTENSIONS = {".avi", ".flv", ".m2ts", ".m4v", ".mkv", ".mov", ".mp4", ".mpeg",
                    ".mpg", ".mts", ".rm", ".rmvb", ".ts", ".webm", ".wmv"}


class MediaLibraryClient:
    """Minimal authenticated Media Library scan client."""

    def __init__(self, base_url: str, token: str, requester=urllib.request.urlopen) -> None:
        if not token:
            raise ValueError("Media Library internal token is missing")
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.requester = requester

    def request(self, method: str, path: str, payload: dict | None) -> dict:
        """Send one bounded internal JSON request."""
        data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = urllib.request.Request(self.base_url + path, data=data, method=method, headers={
            "Authorization": f"Bearer {self.token}", "Content-Type": "application/json",
            "Accept": "application/json"})
        with self.requester(request, timeout=60) as response:
            body = response.read()
        if len(body) > 1024 * 1024:
            raise ValueError("Media Library response is too large")
        return {} if not body else json.loads(body.decode())

    def begin(self, payload: dict) -> dict:
        """Begin or resume one scan."""
        return self.request("POST", "/internal/v1/media/scans", payload)

    def stage(self, scan_id: str, entries: list[dict]) -> None:
        """Stage the complete scan manifest."""
        self.request("PUT", f"/internal/v1/media/scans/{scan_id}/entries", {"entries": entries})

    def finish(self, scan_id: str, manifest_sha256: str) -> dict:
        """Atomically publish one completely imported scan."""
        return self.request("POST", f"/internal/v1/media/scans/{scan_id}/complete",
                            {"manifestSha256": manifest_sha256})


def allowed_root(source: Path, configured_roots: list[str]) -> Path:
    """Resolve a source beneath an explicitly configured scan root."""
    resolved = source.resolve(strict=True)
    if not resolved.is_dir():
        raise ValueError("Media scan source is not a directory")
    roots = [Path(value).resolve(strict=True) for value in configured_roots]
    if not roots or not any(resolved == root or root in resolved.parents for root in roots):
        raise ValueError("Media scan source is outside configured roots")
    return resolved


def file_hash(path: Path) -> tuple[str, int, int]:
    """Hash one stable regular file and reject concurrent mutation."""
    before = path.stat(follow_symlinks=False)
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(READ_SIZE):
            digest.update(chunk)
    after = path.stat(follow_symlinks=False)
    signature = (before.st_ino, before.st_size, before.st_mtime_ns)
    if signature != (after.st_ino, after.st_size, after.st_mtime_ns):
        raise RuntimeError("Media file changed while scanning")
    return digest.hexdigest(), after.st_size, after.st_mtime_ns


def discover(root: Path, directory_key: str) -> list[dict]:
    """Build one deterministic and bounded media manifest."""
    paths = sorted((path for path in root.rglob("*") if not path.is_symlink()
                    and path.is_file() and path.suffix.lower() in MEDIA_EXTENSIONS),
                   key=lambda value: value.relative_to(root).as_posix())
    if len(paths) > MAX_FILES:
        raise ValueError("Media scan exceeds direct child task limit")
    entries = []
    for path in paths:
        relative = path.relative_to(root).as_posix()
        content_sha256, size_bytes, modified_ns = file_hash(path)
        if size_bytes <= 0:
            raise ValueError("Media scan contains an empty file")
        identity = hashlib.sha256(f"{directory_key}\0{relative}\0{content_sha256}".encode()).hexdigest()
        source_id = f"scan:{identity}"
        entries.append({"sourceBusinessId": source_id, "displayName": path.name,
                        "mimeType": mimetypes.guess_type(path.name)[0] or "application/octet-stream",
                        "sizeBytes": size_bytes, "contentSha256": content_sha256,
                        "sourcePath": str(path), "providerVersion": str(modified_ns)})
    return entries


def manifest_digest(entries: list[dict]) -> str:
    """Digest the stable public portion of one manifest."""
    public = [{key: entry[key] for key in ("sourceBusinessId", "displayName", "mimeType",
                                           "sizeBytes", "contentSha256")} for entry in entries]
    encoded = json.dumps(public, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


def execute(task: TaskContext, client: MediaLibraryClient, configured_roots: list[str]) -> dict:
    """Stage a scan, create ingestion children, wait, and publish atomically."""
    parameters = task.parameters
    root = allowed_root(Path(str(parameters["rootPath"])), configured_roots)
    directory_key = str(parameters["directoryKey"])
    directory_name = str(parameters["directoryName"])
    owner_id = int(parameters["ownerId"])
    node_affinity = os.environ.get("TASK_EXECUTOR_NODE_AFFINITY", "")
    if not node_affinity:
        raise ValueError("Task executor node affinity is missing")
    entries = discover(root, directory_key)
    digest = manifest_digest(entries)
    root_fingerprint = hashlib.sha256(str(root).encode()).hexdigest()
    scan = client.begin({"ownerId": owner_id, "idempotencyKey": str(parameters.get(
        "scanKey") or task.context["taskInstanceId"]), "directoryKey": directory_key,
        "directoryName": directory_name, "rootFingerprint": root_fingerprint,
        "expectedCount": len(entries)})
    scan_id = str(scan["id"])
    if scan.get("status") == "COMPLETED":
        if scan.get("manifestSha256") != digest or int(scan.get("importedCount", -1)) != len(entries):
            raise RuntimeError("Completed Media Library scan conflicts with current manifest")
        return {"scanId": scan_id, "manifestSha256": digest, "discovered": len(entries),
                "imported": len(entries), "childTaskIds": []}
    public_entries = [{key: entry[key] for key in ("sourceBusinessId", "displayName", "mimeType",
                                                   "sizeBytes", "contentSha256")} for entry in entries]
    client.stage(scan_id, public_entries)
    children = []
    for entry in entries:
        child = task.create_child("media_ingest_scanned_file", {
            "assetId": entry["sourceBusinessId"], "contentSha256": entry["contentSha256"],
            "sourcePath": entry["sourcePath"], "ownerId": owner_id,
            "assetSourceType": "MEDIA_SCAN", "assetSourceBusinessId": entry["sourceBusinessId"],
            "assetMimeType": entry["mimeType"], "assetProviderType": "LEGACY_MEDIA",
            "assetProviderVersion": entry["providerVersion"], "directoryKey": directory_key,
            "directoryName": directory_name, "scanId": scan_id},
            f"media-scan:{scan_id}:{entry['sourceBusinessId']}", business_type="MEDIA_SCAN",
            business_id=scan_id, required_node_labels={"executor.node": node_affinity})
        children.append(child)
    timeout = float(parameters.get("childTimeoutSeconds") or 3600)
    for child in children:
        completed = task.wait_child(child.id, timeout)
        if completed.status != "SUCCEEDED":
            raise RuntimeError(f"Media ingestion child failed: {child.id}:{completed.status}")
    result = client.finish(scan_id, digest)
    if result.get("status") != "COMPLETED" or int(result.get("importedCount", -1)) != len(entries):
        raise RuntimeError("Media Library did not publish the complete scan")
    return {"scanId": scan_id, "manifestSha256": digest, "discovered": len(entries),
            "imported": len(entries), "childTaskIds": [child.id for child in children]}


def write_result(result: dict) -> None:
    """Atomically write the task result."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Run one media directory scan."""
    roots = json.loads(os.getenv("MEDIA_SCAN_ALLOWED_ROOTS", "[]"))
    if not isinstance(roots, list) or not all(isinstance(value, str) for value in roots):
        raise ValueError("MEDIA_SCAN_ALLOWED_ROOTS must be a JSON string array")
    task = TaskContext.load()
    client = MediaLibraryClient(os.getenv("MEDIA_LIBRARY_URL", "http://127.0.0.1:23300"),
                                os.environ.get("MEDIA_LIBRARY_INTERNAL_TOKEN", ""))
    write_result(execute(task, client, roots))


if __name__ == "__main__":
    main()
