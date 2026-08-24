#!/usr/bin/env python3
"""Migrate verified legacy application files into durable managed assets."""

from __future__ import annotations

import hashlib
import json
import mimetypes
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

from mytools_task_sdk.asset import AssetRegistryClient
from mytools_task_sdk.storage import StorageGatewayClient

KEY = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
ROOT = re.compile(r"^[A-Za-z][A-Za-z0-9._-]{0,127}$")


class CatalogClient:
    """Access only the bounded application file migration API."""

    def __init__(self, base_url: str, token: str, opener=urlopen):
        if not token:
            raise ValueError("App Catalog token is missing")
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.opener = opener

    def page(self, after_id: str | None) -> dict:
        """Read one bounded unresolved file page."""
        query = {"limit": 200}
        if after_id:
            query["afterId"] = after_id
        return self._request("GET", "/internal/v1/catalog/migrations/unresolved-files?" + urlencode(query))

    def bind(self, file_id: str, payload: dict) -> dict:
        """Bind one verified asset to its catalog file."""
        return self._request("POST", f"/internal/v1/catalog/migrations/files/{quote(file_id)}/asset", payload)

    def _request(self, method: str, path: str, payload: dict | None = None) -> dict:
        body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        headers = {"Authorization": f"Bearer {self.token}", "Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = Request(self.base_url + path, data=body, method=method, headers=headers)
        with self.opener(request, timeout=30) as response:
            document = json.loads(response.read().decode("utf-8"))
        if not isinstance(document, dict):
            raise RuntimeError("App Catalog response is invalid")
        return document


def allowed_path(value: str, roots: list[Path]) -> Path:
    """Resolve a regular legacy file below an explicitly configured root."""
    source = Path(value)
    if not source.is_absolute() or source.is_symlink():
        raise ValueError("Legacy catalog path is invalid")
    resolved = source.resolve(strict=True)
    if not resolved.is_file() or not any(resolved.is_relative_to(root) for root in roots):
        raise ValueError("Legacy catalog path is outside configured roots")
    return resolved


def collect(client: CatalogClient, maximum_files: int, maximum_bytes: int) -> tuple[list[dict], int]:
    """Freeze the unresolved set in memory before publishing any content."""
    files: list[dict] = []
    total = 0
    after_id = None
    while True:
        page = client.page(after_id)
        values = page.get("items")
        if not isinstance(values, list):
            raise RuntimeError("App Catalog file page is invalid")
        files.extend(values)
        total += sum(int(value["fileSize"]) for value in values)
        if len(files) > maximum_files or total > maximum_bytes:
            raise ValueError("Catalog file migration exceeds configured limits")
        after_id = page.get("nextAfterId")
        if not after_id:
            return files, total


def sha256(path: Path) -> str:
    """Hash one file without loading it into memory."""
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def execute(parameters: dict, catalog: CatalogClient, storage: StorageGatewayClient,
            assets: AssetRegistryClient, roots: list[Path]) -> dict:
    """Publish, register and bind every currently unresolved catalog file."""
    migration_key = str(parameters.get("migrationKey") or "")
    root_name = str(parameters.get("storageRoot") or "")
    maximum_files = int(parameters.get("maximumFiles", 10_000))
    maximum_bytes = int(parameters.get("maximumBytes", 100 * 1024 * 1024 * 1024))
    if not KEY.fullmatch(migration_key) or not ROOT.fullmatch(root_name) or not roots:
        raise ValueError("Catalog file migration parameters are invalid")
    files, total_bytes = collect(catalog, maximum_files, maximum_bytes)
    migrated = skipped = 0
    result_digest = hashlib.sha256()
    for value in files:
        source = allowed_path(str(value["legacyStoragePath"]), roots)
        declared_size = int(value["fileSize"])
        if source.stat().st_size != declared_size:
            raise ValueError("Legacy catalog file size changed")
        content_sha256 = sha256(source)
        legacy_id = str(value["legacyId"])
        safe_name = re.sub(r"[^A-Za-z0-9._-]", "_", str(value["fileName"]))[:180] or "file.bin"
        relative_path = f"app-catalog/{legacy_id}/{content_sha256}-{safe_name}"
        idempotency_key = f"app-catalog-file:{legacy_id}:{content_sha256}"
        storage_uri = storage.publish(source, root_name, relative_path, idempotency_key,
                                      declared_size, content_sha256)
        mime_type = mimetypes.guess_type(str(value["fileName"]))[0] or "application/octet-stream"
        asset = assets.register({"ownerId": int(value["ownerId"]),
            "idempotencyKey": idempotency_key, "sourceType": "APP_CATALOG_FILE",
            "sourceBusinessId": legacy_id, "contentSha256": content_sha256,
            "sizeBytes": declared_size, "mimeType": mime_type,
            "location": {"idempotencyKey": idempotency_key + ":location",
                         "providerType": "STORAGE_GATEWAY", "storageUri": storage_uri,
                         "providerVersion": "v1"}})
        bound = catalog.bind(str(value["id"]), {"legacyId": legacy_id, "assetId": str(asset["id"]),
            "contentSha256": content_sha256, "storageUri": storage_uri, "fileSize": declared_size})
        skipped += 1 if bound.get("skipped") is True else 0
        migrated += 0 if bound.get("skipped") is True else 1
        for part in (legacy_id, content_sha256, str(asset["id"]), storage_uri):
            encoded = part.encode()
            result_digest.update(len(encoded).to_bytes(4, "big"))
            result_digest.update(encoded)
    return {"migrationKey": migration_key, "files": len(files), "bytes": total_bytes,
            "migrated": migrated, "skipped": skipped,
            "digestSha256": result_digest.hexdigest()}


def write_result(result: dict) -> None:
    """Atomically write the task result."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Run one bounded application file migration."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    roots = [Path(value).resolve() for value in os.getenv("APP_CATALOG_LEGACY_ROOTS", "").split(os.pathsep)
             if value]
    result = execute(context["parameters"],
        CatalogClient(os.getenv("APP_CATALOG_URL", "http://127.0.0.1:23310"),
                      os.getenv("APP_CATALOG_INTERNAL_TOKEN", "")),
        StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                             os.getenv("STORAGE_INTERNAL_TOKEN", "")),
        AssetRegistryClient(os.getenv("ASSET_REGISTRY_URL", "http://127.0.0.1:23270"),
                            os.getenv("ASSET_REGISTRY_INTERNAL_TOKEN", "")), roots)
    write_result(result)


if __name__ == "__main__":
    main()
