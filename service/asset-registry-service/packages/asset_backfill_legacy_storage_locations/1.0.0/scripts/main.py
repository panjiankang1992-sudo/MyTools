#!/usr/bin/env python3
"""Backfill Storage Gateway locations for sealed legacy media assets."""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
from urllib.parse import quote, unquote, urlencode, urlsplit
from urllib.request import Request, urlopen

PAGE_SIZE = 200
MEDIA_PREFIXES = ("image/", "video/", "audio/")


class Client:
    """Read the sealed snapshot and update mapped assets through protected APIs."""

    def __init__(self, legacy_url: str, legacy_token: str, asset_url: str,
                 asset_token: str, opener=urlopen):
        if not legacy_token or not asset_token:
            raise ValueError("legacy storage backfill tokens are missing")
        self.legacy_url = legacy_url.rstrip("/")
        self.asset_url = asset_url.rstrip("/")
        self.legacy_token = legacy_token
        self.asset_token = asset_token
        self.opener = opener

    def page(self, snapshot_id: str, after_id: str | None) -> dict:
        """Read one bounded sealed snapshot page."""
        query = {"snapshotId": snapshot_id, "limit": PAGE_SIZE}
        if after_id:
            query["afterId"] = after_id
        return self.request(self.legacy_url + "/internal/v1/migration/assets?" + urlencode(query),
                            "GET", None, self.legacy_token)

    def resolve(self, identities: list[dict]) -> dict:
        """Resolve immutable legacy identities to Asset Registry IDs."""
        return self.request(self.asset_url + "/internal/v1/assets/migrations/legacy-mappings/resolve",
                            "POST", {"identities": identities}, self.asset_token)

    def asset(self, asset_id: str) -> dict:
        """Read one asset and its current optimistic version."""
        return self.request(self.asset_url + "/internal/v1/assets/" + quote(asset_id),
                            "GET", None, self.asset_token)

    def register_location(self, asset_id: str, payload: dict) -> dict:
        """Register one idempotent Storage Gateway location."""
        return self.request(self.asset_url + "/internal/v1/assets/" + quote(asset_id) + "/locations",
                            "POST", payload, self.asset_token)

    def request(self, url: str, method: str, payload: dict | None, token: str) -> dict:
        """Execute one authenticated bounded JSON request."""
        data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = Request(url, data=data, method=method, headers={
            "Authorization": f"Bearer {token}", "Accept": "application/json",
            "Content-Type": "application/json"})
        with self.opener(request, timeout=30) as response:
            result = json.loads(response.read().decode("utf-8"))
        if not isinstance(result, dict):
            raise RuntimeError("legacy storage backfill response is invalid")
        return result


def execute(client: Client, snapshot_id: str, legacy_root_path: str,
            storage_root: str, dry_run: bool) -> dict:
    """Backfill every eligible media location and return closing counters."""
    root = Path(legacy_root_path).as_posix().rstrip("/")
    if not root.startswith("/") or not storage_root or "/" in storage_root:
        raise ValueError("legacy storage backfill root is invalid")
    totals = {"scanned": 0, "eligible": 0, "registered": 0,
              "skipped": 0, "missing": 0, "rejected": 0}
    after_id = None
    while True:
        page = client.page(snapshot_id, after_id)
        if page.get("snapshotId") != snapshot_id or not isinstance(page.get("items"), list):
            raise RuntimeError("legacy asset snapshot changed during storage backfill")
        totals["scanned"] += len(page["items"])
        eligible = [candidate(item, root, storage_root) for item in page["items"]]
        eligible = [value for value in eligible if value is not None]
        totals["eligible"] += len(eligible)
        if eligible:
            resolved = client.resolve([value["identity"] for value in eligible])
            mappings = {(value["sourceSystem"], value["legacyAssetId"]): str(value["assetId"])
                        for value in resolved.get("mappings") or []}
            for value in eligible:
                identity = value["identity"]
                asset_id = mappings.get((identity["sourceSystem"], identity["legacyAssetId"]))
                if asset_id is None:
                    totals["missing"] += 1
                    continue
                asset = client.asset(asset_id)
                if has_location(asset, value["storageUri"]):
                    totals["skipped"] += 1
                    continue
                if dry_run:
                    totals["registered"] += 1
                    continue
                try:
                    client.register_location(asset_id, {
                        "expectedAssetVersion": int(asset["version"]),
                        "idempotencyKey": "legacy-storage:" + identity["sourceSystem"] + ":"
                                          + identity["legacyAssetId"],
                        "providerType": "STORAGE_GATEWAY", "storageUri": value["storageUri"],
                        "providerVersion": "legacy-v1"})
                    totals["registered"] += 1
                except Exception:
                    # 单条异常必须计入闭合报告，任务结束后由 rejected 门禁决定是否接受。
                    totals["rejected"] += 1
        next_after = page.get("nextAfterId")
        if next_after is None:
            break
        if str(next_after) == str(after_id):
            raise RuntimeError("legacy asset snapshot cursor did not advance")
        after_id = str(next_after)
    if totals["eligible"] != sum(totals[name] for name in
                                  ("registered", "skipped", "missing", "rejected")):
        raise RuntimeError("legacy storage backfill counters do not close")
    if totals["rejected"]:
        raise RuntimeError("legacy storage backfill rejected one or more assets")
    return {"sourceSnapshotId": snapshot_id, "dryRun": dry_run, **totals}


def candidate(item: object, legacy_root: str, storage_root: str) -> dict | None:
    """Return one safe media backfill candidate from the normalized snapshot item."""
    if not isinstance(item, dict) or not isinstance(item.get("asset"), dict):
        return None
    asset = item["asset"]
    if not str(asset.get("mimeType") or "").startswith(MEDIA_PREFIXES):
        return None
    location = asset.get("location")
    if not isinstance(location, dict):
        return None
    parsed = urlsplit(str(location.get("storageUri") or ""))
    source_path = unquote(parsed.path)
    prefix = legacy_root + "/"
    if parsed.scheme != "file" or parsed.netloc or not source_path.startswith(prefix):
        return None
    relative = source_path[len(prefix):]
    if not relative or ".." in Path(relative).parts:
        return None
    return {"identity": {"sourceSystem": str(item["sourceSystem"]),
                          "legacyAssetId": str(item["legacyAssetId"])},
            "storageUri": "storage://" + storage_root + "/" + quote(relative, safe="/")}


def has_location(asset: dict, storage_uri: str) -> bool:
    """Check whether the exact available Storage Gateway location already exists."""
    return any(isinstance(value, dict) and value.get("providerType") == "STORAGE_GATEWAY"
               and value.get("availability") == "AVAILABLE"
               and value.get("storageUri") == storage_uri for value in asset.get("locations") or [])


def write_result(result: dict) -> None:
    """Atomically write the non-sensitive task report."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent,
                                     delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Run the scheduler-owned legacy media storage backfill."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = context["parameters"]
    client = Client(os.getenv("LEGACY_ASSET_ADAPTER_URL", "http://127.0.0.1:23330"),
                    os.getenv("LEGACY_ASSET_ADAPTER_TOKEN", ""),
                    os.getenv("ASSET_REGISTRY_URL", "http://127.0.0.1:23270"),
                    os.getenv("ASSET_REGISTRY_INTERNAL_TOKEN", ""))
    write_result(execute(client, str(parameters["sourceSnapshotId"]),
                         str(parameters["legacyRootPath"]), str(parameters["storageRoot"]),
                         bool(parameters["dryRun"])))


if __name__ == "__main__":
    main()
