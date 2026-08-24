#!/usr/bin/env python3
"""Import migrated MyTools media assets into Media Library without reading old tables again."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import unquote, urlencode, urlparse
from urllib.request import Request, urlopen
from uuid import UUID

PAGE_SIZE = 200
SNAPSHOT_ID = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
SOURCE_SYSTEM = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
SHA256 = re.compile(r"^[a-fA-F0-9]{64}$")
MEDIA_PREFIXES = ("image/", "video/", "audio/")


class Client:
    """Use separate credentials for the frozen source, mapping registry and media target."""

    def __init__(self, legacy_url: str, legacy_token: str, asset_url: str,
                 asset_token: str, media_url: str, media_token: str, opener=urlopen):
        """Create a migration client and reject missing credentials before reading data."""
        if not legacy_token or not asset_token or not media_token:
            raise ValueError("Media migration tokens are missing")
        self.legacy_url = legacy_url.rstrip("/")
        self.legacy_token = legacy_token
        self.asset_url = asset_url.rstrip("/")
        self.asset_token = asset_token
        self.media_url = media_url.rstrip("/")
        self.media_token = media_token
        self.opener = opener

    def page(self, snapshot_id: str, after_id: str | None) -> dict:
        """Read one bounded page from the sealed legacy asset snapshot."""
        query = {"snapshotId": snapshot_id, "limit": PAGE_SIZE}
        if after_id:
            query["afterId"] = after_id
        return self._request(self.legacy_url + "/internal/v1/migration/assets?" + urlencode(query),
                             "GET", None, self.legacy_token)

    def resolve(self, identities: list[dict]) -> dict:
        """Resolve immutable legacy identities to already migrated Asset Registry IDs."""
        return self._request(self.asset_url
                             + "/internal/v1/assets/migrations/legacy-mappings/resolve",
                             "POST", {"identities": identities}, self.asset_token)

    def import_media(self, event: dict) -> dict:
        """Idempotently project one migrated asset into Media Library."""
        return self._request(self.media_url + "/internal/v1/media/asset-events",
                             "POST", event, self.media_token)

    def _request(self, url: str, method: str, payload: dict | None, token: str) -> dict:
        data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = Request(url, data=data, method=method, headers={
            "Authorization": f"Bearer {token}", "Accept": "application/json",
            "Content-Type": "application/json"})
        with self.opener(request, timeout=30) as response:
            raw = response.read(16 * 1024 * 1024 + 1)
        if len(raw) > 16 * 1024 * 1024:
            raise RuntimeError("Media migration response is too large")
        value = json.loads(raw.decode("utf-8"))
        if not isinstance(value, dict):
            raise RuntimeError("Media migration endpoint returned invalid data")
        return value


def execute(client: Client, snapshot_id: str, dry_run: bool) -> dict:
    """Preflight every mapping, then idempotently import the same frozen snapshot."""
    if not SNAPSHOT_ID.fullmatch(snapshot_id):
        raise ValueError("Media source snapshot id is invalid")
    first = scan(client, snapshot_id, False)
    imported = 0
    if not dry_run:
        applied = scan(client, snapshot_id, True)
        if applied[:3] != first[:3] or applied[3] != first[3]:
            raise RuntimeError("Media source snapshot changed between preflight and apply")
        imported = applied[1]
    return {"sourceSnapshotId": snapshot_id, "dryRun": dry_run,
            "exported": first[0], "mediaItems": first[1], "skippedNonMedia": first[2],
            "imported": imported, "digestSha256": first[3]}


def scan(client: Client, snapshot_id: str, apply: bool) -> tuple[int, int, int, str]:
    """Walk the frozen snapshot once, validating mappings before any page is applied."""
    exported = media_items = skipped = 0
    digest = hashlib.sha256()
    after_id = None
    while True:
        page = client.page(snapshot_id, after_id)
        if page.get("snapshotId") != snapshot_id:
            raise RuntimeError("Legacy asset snapshot changed during media migration")
        items = page.get("items")
        if not isinstance(items, list) or len(items) > PAGE_SIZE:
            raise RuntimeError("Legacy asset media page is invalid")
        events = prepare_events(client, snapshot_id, items)
        exported += len(items)
        media_items += len(events)
        skipped += len(items) - len(events)
        for event in events:
            canonical = json.dumps(event, sort_keys=True, separators=(",", ":")).encode()
            digest.update(hashlib.sha256(canonical).digest())
            if apply:
                result = client.import_media(event)
                if str(result.get("assetId")) != event["assetId"] or int(result.get("ownerId", -1)) != event["ownerId"]:
                    raise RuntimeError("Media Library returned a conflicting migrated item")
        next_after_id = page.get("nextAfterId")
        if next_after_id is None:
            break
        next_after_id = str(next_after_id)
        if not next_after_id or next_after_id == after_id:
            raise RuntimeError("Legacy asset media cursor did not advance")
        after_id = next_after_id
    return exported, media_items, skipped, digest.hexdigest()


def prepare_events(client: Client, snapshot_id: str, items: list[dict]) -> list[dict]:
    """Validate one page and join it to immutable Asset Registry mappings."""
    prepared = []
    identities = []
    for item in items:
        event = normalize(snapshot_id, item)
        if event is not None:
            key = (str(item["sourceSystem"]), str(item["legacyAssetId"]))
            prepared.append((key, event))
            identities.append({"sourceSystem": key[0], "legacyAssetId": key[1]})
    if not prepared:
        return []
    resolved = client.resolve(identities)
    mappings = resolved.get("mappings")
    missing = resolved.get("missing")
    if not isinstance(mappings, list) or not isinstance(missing, list) or missing:
        raise RuntimeError("Legacy media assets are missing Asset Registry mappings")
    by_identity = {}
    for mapping in mappings:
        if not isinstance(mapping, dict):
            raise RuntimeError("Legacy media asset mapping is invalid")
        key = (str(mapping.get("sourceSystem")), str(mapping.get("legacyAssetId")))
        try:
            asset_id = str(UUID(str(mapping.get("assetId"))))
        except ValueError as exception:
            raise RuntimeError("Legacy media asset mapping is invalid") from exception
        if key in by_identity:
            raise RuntimeError("Legacy media asset mapping is duplicated")
        by_identity[key] = asset_id
    if set(by_identity) != {key for key, _ in prepared}:
        raise RuntimeError("Legacy media asset mapping set does not close")
    events = []
    for key, event in prepared:
        events.append({**event, "assetId": by_identity[key]})
    return events


def normalize(snapshot_id: str, item: dict) -> dict | None:
    """Build the safe Media Library event without exposing legacy storage locations."""
    if not isinstance(item, dict):
        raise RuntimeError("Legacy media item is invalid")
    source_system = str(item.get("sourceSystem") or "")
    legacy_id = str(item.get("legacyAssetId") or "")
    asset = item.get("asset")
    if not SOURCE_SYSTEM.fullmatch(source_system) or not legacy_id or len(legacy_id) > 255 \
            or not isinstance(asset, dict):
        raise RuntimeError("Legacy media identity is invalid")
    mime_type = str(asset.get("mimeType") or "").lower()
    if not mime_type.startswith(MEDIA_PREFIXES):
        return None
    owner_id = asset.get("ownerId")
    size = asset.get("sizeBytes")
    sha256 = str(asset.get("contentSha256") or "").lower()
    source_type = str(asset.get("sourceType") or "")
    business_id = str(asset.get("sourceBusinessId") or "")
    if isinstance(owner_id, bool) or not isinstance(owner_id, int) or owner_id <= 0 \
            or isinstance(size, bool) or not isinstance(size, int) or size <= 0 \
            or not SHA256.fullmatch(sha256) or not re.fullmatch(r"^[A-Z][A-Z0-9_]{0,63}$", source_type) \
            or not business_id or len(business_id) > 255:
        raise RuntimeError("Legacy media asset payload is invalid")
    location = asset.get("location")
    uri = str(location.get("storageUri") or "") if isinstance(location, dict) else ""
    display_name = Path(unquote(urlparse(uri).path)).name
    if not display_name or len(display_name) > 512:
        display_name = f"legacy-{legacy_id}"[:512]
    identity_digest = hashlib.sha256(
        f"{snapshot_id}\0{source_system}\0{legacy_id}".encode()).hexdigest()
    return {"eventId": "legacy-media:" + identity_digest, "ownerId": owner_id,
            "sourceType": source_type, "sourceBusinessId": business_id,
            "displayName": display_name, "mimeType": mime_type, "sizeBytes": size,
            "contentSha256": sha256}


def write_result(result: dict) -> None:
    """Atomically publish the task report."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Run an explicit dry-run or apply migration task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = context["parameters"]
    client = Client(os.getenv("LEGACY_ASSET_ADAPTER_URL", "http://127.0.0.1:23330"),
                    os.getenv("LEGACY_ASSET_ADAPTER_TOKEN", ""),
                    os.getenv("ASSET_REGISTRY_URL", "http://127.0.0.1:23270"),
                    os.getenv("ASSET_REGISTRY_INTERNAL_TOKEN", ""),
                    os.getenv("MEDIA_LIBRARY_URL", "http://127.0.0.1:23280"),
                    os.getenv("MEDIA_LIBRARY_INTERNAL_TOKEN", ""))
    write_result(execute(client, str(parameters["sourceSnapshotId"]), bool(parameters["dryRun"])))


if __name__ == "__main__":
    main()
