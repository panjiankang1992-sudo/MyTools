#!/usr/bin/env python3
"""Import migrated MyTools media assets into Media Library without reading old tables again."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from decimal import Decimal, ROUND_HALF_UP
from urllib.parse import unquote, urlencode, urlparse
from urllib.request import Request, urlopen
from uuid import UUID

PAGE_SIZE = 200
SNAPSHOT_ID = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
MIGRATION_KEY = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
SOURCE_SYSTEM = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
SHA256 = re.compile(r"^[a-fA-F0-9]{64}$")
MEDIA_PREFIXES = ("image/", "video/", "audio/")
EBOOK_EXTENSIONS = {".txt", ".epub", ".pdf", ".mobi", ".azw3", ".cbz", ".cbr"}


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
        return self._request(self.media_url + "/internal/v1/media/migrations/legacy-items",
                             "POST", event, self.media_token)

    def backfill_directories(self, bindings: list[dict]) -> None:
        """Idempotently restore directory relations without changing legacy event digests."""
        if bindings:
            self._request(self.media_url + "/internal/v1/media/migrations/legacy-directories",
                          "POST", {"bindings": bindings}, self.media_token)

    def evidence(self, migration_key: str) -> dict:
        """Read independently recomputed evidence for this migration only."""
        return self._request(self.media_url + "/internal/v1/media/migrations/legacy-items/"
                             + migration_key + "/evidence", "GET", None, self.media_token)

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


def execute(client: Client, migration_key: str, snapshot_id: str, dry_run: bool) -> dict:
    """Preflight every mapping, then idempotently import the same frozen snapshot."""
    if not MIGRATION_KEY.fullmatch(migration_key):
        raise ValueError("Media migration key is invalid")
    if not SNAPSHOT_ID.fullmatch(snapshot_id):
        raise ValueError("Media source snapshot id is invalid")
    first = scan(client, migration_key, snapshot_id, False)
    imported = 0
    target_verified = False
    if not dry_run:
        applied = scan(client, migration_key, snapshot_id, True)
        if applied != first:
            raise RuntimeError("Media source snapshot changed between preflight and apply")
        imported = applied[1]
        target = client.evidence(migration_key)
        if target.get("migrationKey") != migration_key \
                or target.get("sourceSnapshotId") != snapshot_id \
                or target.get("itemCount") != first[1] \
                or target.get("tagCount") != first[2] \
                or target.get("collectionSha256") != first[4]:
            raise RuntimeError("Media target migration evidence does not close")
        target_verified = True
    return {"migrationKey": migration_key, "sourceSnapshotId": snapshot_id, "dryRun": dry_run,
            "exported": first[0], "mediaItems": first[1], "legacyTags": first[2],
            "skippedNonMedia": first[3], "imported": imported, "digestSha256": first[4],
            "targetVerified": target_verified}


def scan(client: Client, migration_key: str, snapshot_id: str,
         apply: bool) -> tuple[int, int, int, int, str]:
    """Walk the frozen snapshot once, validating mappings before any page is applied."""
    exported = media_items = tag_count = skipped = 0
    evidence = []
    after_id = None
    while True:
        page = client.page(snapshot_id, after_id)
        if page.get("snapshotId") != snapshot_id:
            raise RuntimeError("Legacy asset snapshot changed during media migration")
        items = page.get("items")
        if not isinstance(items, list) or len(items) > PAGE_SIZE:
            raise RuntimeError("Legacy asset media page is invalid")
        events = prepare_events(client, migration_key, snapshot_id, items)
        exported += len(items)
        media_items += len(events)
        tag_count += sum(len(request["tags"]) for request in events)
        skipped += len(items) - len(events)
        bindings = []
        for request in events:
            payload_digest = migration_payload_digest(request)
            evidence.append((request["sourceSystem"], request["legacyAssetId"],
                             payload_digest, len(request["tags"])))
            if apply:
                result = client.import_media({key: value for key, value in request.items()
                                              if key != "_directoryBinding"})
                event = request["event"]
                if str(result.get("assetId")) != event["assetId"] or int(result.get("ownerId", -1)) != event["ownerId"]:
                    raise RuntimeError("Media Library returned a conflicting migrated item")
                bindings.append(request["_directoryBinding"])
        if apply:
            client.backfill_directories(bindings)
        next_after_id = page.get("nextAfterId")
        if next_after_id is None:
            break
        next_after_id = str(next_after_id)
        if not next_after_id or next_after_id == after_id:
            raise RuntimeError("Legacy asset media cursor did not advance")
        after_id = next_after_id
    digest = hashlib.sha256()
    for source_system, legacy_id, payload_digest, tags in sorted(evidence):
        update_digest(digest, source_system, legacy_id, payload_digest, str(tags))
    return exported, media_items, tag_count, skipped, digest.hexdigest()


def prepare_events(client: Client, migration_key: str, snapshot_id: str,
                   items: list[dict]) -> list[dict]:
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
    for key, request in prepared:
        directory_binding = directory_binding_for(request["event"]["ownerId"], key[1],
                                                  request.pop("_storageUri"), snapshot_id)
        events.append({"migrationKey": migration_key, "sourceSnapshotId": snapshot_id,
                       "sourceSystem": key[0], "legacyAssetId": key[1], **request,
                       "event": {**request["event"], "assetId": by_identity[key]},
                       "_directoryBinding": directory_binding})
    return events


def migration_payload_digest(request: dict) -> str:
    """Compute the Java-compatible length-prefixed migrated payload digest."""
    event = request["event"]
    digest = hashlib.sha256()
    update_digest(digest, event.get("eventId"), event.get("assetId"), event.get("ownerId"),
                  event.get("sourceType"), event.get("sourceBusinessId"), event.get("displayName"),
                  event.get("mimeType"), event.get("sizeBytes"), str(event.get("contentSha256")).lower(),
                  event.get("directoryKey"), event.get("directoryName"), event.get("scanId"))
    for tag in sorted(request["tags"], key=lambda value: value["name"].strip().lower()):
        confidence = tag.get("confidence")
        normalized = None if confidence is None else str(
            Decimal(str(confidence)).quantize(Decimal("0.00001"), rounding=ROUND_HALF_UP))
        update_digest(digest, tag["name"].strip().lower(), normalized)
    return digest.hexdigest()


def update_digest(digest, *values: object) -> None:
    """Apply the shared four-byte length-prefix digest protocol."""
    for value in values:
        encoded = ("" if value is None else str(value)).encode("utf-8")
        digest.update(len(encoded).to_bytes(4, "big"))
        digest.update(encoded)


def directory_binding_for(owner_id: int, legacy_id: str, storage_uri: str,
                          snapshot_id: str = "") -> dict:
    """Derive a stable opaque directory key and a user-visible folder name."""
    path = Path(unquote(urlparse(storage_uri).path))
    parts = path.parts
    if len(parts) < 6 or parts[1:4] != ("opt", "extend", "resource"):
        raise RuntimeError("Legacy media directory hierarchy is invalid")

    # 当前用户媒体必须位于 resource/<username>/media/yyyyMM/yyyyMMdd 下，不能再吞掉 media 层。
    scoped_media = len(parts) >= 9 and re.fullmatch(r"[A-Za-z0-9._-]{1,128}", parts[4]) \
        and parts[4] not in {".", ".."} and parts[5] == "media"
    legacy_global_media = len(parts) >= 8 and parts[4] == "media"
    month_index = 6 if scoped_media else 5
    day_index = month_index + 1
    if (scoped_media or legacy_global_media) and len(parts) > day_index + 1 \
            and re.fullmatch(r"\d{6}", parts[month_index]) \
            and re.fullmatch(r"\d{8}", parts[day_index]):
        month_name = parts[month_index]
        day_name = parts[day_index]
    else:
        # 仅为真正的旧分类目录保留日期恢复；用户目录下缺少 media 的路径视为损坏数据。
        legacy_category_index = 5 if len(parts) > 5 and parts[5] in {"big_media", "other"} else 4
        if parts[legacy_category_index] not in {"big_media", "other"}:
            raise RuntimeError("Legacy media directory hierarchy is invalid")
        dated = next((re.match(r"^(20\d{6})", value) for value in parts[legacy_category_index + 1:]
                      if re.match(r"^(20\d{6})", value)), None)
        snapshot_date = re.search(r"(20\d{6})", snapshot_id)
        if dated is None and snapshot_date is None:
            raise RuntimeError("Legacy media directory hierarchy is invalid")
        day_name = dated.group(1) if dated is not None else snapshot_date.group(1)
        month_name = day_name[:6]
    logical_root = Path("/media")
    month = logical_root / month_name
    parent = month / day_name
    normalized = parent.as_posix().rstrip("/")
    normalized_month = month.as_posix().rstrip("/")
    directory_key = hashlib.sha256(f"{owner_id}\0{normalized}".encode()).hexdigest()[:24]
    parent_directory_key = hashlib.sha256(
        f"{owner_id}\0{normalized_month}".encode()).hexdigest()[:24]
    return {"ownerId": owner_id, "legacyAssetId": legacy_id,
            "directoryKey": directory_key, "directoryName": parent.name[:512],
            "parentDirectoryKey": parent_directory_key,
            "parentDirectoryName": month.name[:512]}


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
    location = asset.get("location")
    uri = str(location.get("storageUri") or "") if isinstance(location, dict) else ""
    extension = Path(unquote(urlparse(uri).path)).suffix.lower()
    if not mime_type.startswith(MEDIA_PREFIXES) and extension not in EBOOK_EXTENSIONS:
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
    display_name = Path(unquote(urlparse(uri).path)).name
    if not display_name or len(display_name) > 512:
        display_name = f"legacy-{legacy_id}"[:512]
    identity_digest = hashlib.sha256(
        f"{snapshot_id}\0{source_system}\0{legacy_id}".encode()).hexdigest()
    metadata = item.get("mediaMetadata")
    raw_tags = metadata.get("tags") if isinstance(metadata, dict) else []
    if not isinstance(raw_tags, list) or len(raw_tags) > 256:
        raise RuntimeError("Legacy media tags are invalid")
    tags = []
    names = set()
    for raw_tag in raw_tags:
        if not isinstance(raw_tag, dict):
            raise RuntimeError("Legacy media tag is invalid")
        name = str(raw_tag.get("name") or "").strip()
        confidence = raw_tag.get("confidence")
        if not name or len(name) > 128 or name.lower() in names \
                or confidence is not None and (isinstance(confidence, bool)
                or not isinstance(confidence, (int, float)) or confidence < 0 or confidence > 1):
            raise RuntimeError("Legacy media tag is invalid")
        names.add(name.lower())
        tags.append({"name": name, "confidence": confidence})
    event = {"eventId": "legacy-media:" + identity_digest, "ownerId": owner_id,
             "sourceType": source_type, "sourceBusinessId": business_id,
             "displayName": display_name, "mimeType": mime_type, "sizeBytes": size,
             "contentSha256": sha256}
    return {"event": event, "tags": tags, "_storageUri": uri}


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
                    os.getenv("MEDIA_LIBRARY_URL", "http://127.0.0.1:23300"),
                    os.getenv("MEDIA_LIBRARY_INTERNAL_TOKEN", ""))
    write_result(execute(client, str(parameters["migrationKey"]),
                         str(parameters["sourceSnapshotId"]), bool(parameters["dryRun"])))


if __name__ == "__main__":
    main()
