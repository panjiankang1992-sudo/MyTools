import importlib.util
from pathlib import Path

import pytest

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("media_migrate_legacy_items", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def item(legacy_id="7", mime_type="video/mp4"):
    return {"sourceSystem": "MyTools", "legacyAssetId": legacy_id, "asset": {
        "ownerId": 1, "sourceType": "LEGACY_ASSET", "sourceBusinessId": f"local_file:{legacy_id}",
        "contentSha256": legacy_id[0] * 64, "sizeBytes": 128, "mimeType": mime_type,
        "location": {"storageUri": f"file:///media/file-{legacy_id}.mp4"}}}


class Client:
    def __init__(self, missing=False):
        self.missing = missing
        self.imported = []

    def page(self, snapshot_id, after_id):
        assert snapshot_id == "snapshot-1"
        if after_id is None:
            return {"snapshotId": snapshot_id,
                    "items": [item("7"), item("8", "application/pdf")], "nextAfterId": "next"}
        return {"snapshotId": snapshot_id, "items": [item("9", "image/jpeg")], "nextAfterId": None}

    def resolve(self, identities):
        if self.missing:
            return {"mappings": [], "missing": identities}
        return {"mappings": [{**identity,
                              "assetId": "00000000-0000-4000-8000-"
                                         + f"{int(identity['legacyAssetId']):012d}"}
                             for identity in identities], "missing": []}

    def import_media(self, event):
        self.imported.append(event)
        return {"assetId": event["assetId"], "ownerId": event["ownerId"]}


def test_dry_run_preflights_all_mappings_without_writing_media():
    client = Client()
    result = MODULE.execute(client, "snapshot-1", True)
    assert result["exported"] == 3
    assert result["mediaItems"] == 2
    assert result["skippedNonMedia"] == 1
    assert result["imported"] == 0
    assert len(result["digestSha256"]) == 64
    assert client.imported == []


def test_apply_is_two_pass_and_uses_stable_private_free_events():
    client = Client()
    result = MODULE.execute(client, "snapshot-1", False)
    first_events = list(client.imported)
    replay = MODULE.execute(client, "snapshot-1", False)
    assert result["imported"] == 2
    assert replay["digestSha256"] == result["digestSha256"]
    assert client.imported[2:] == first_events
    assert [event["displayName"] for event in first_events] == ["file-7.mp4", "file-9.mp4"]
    assert all(event["eventId"].startswith("legacy-media:") for event in first_events)
    assert all("location" not in event for event in first_events)


def test_missing_mapping_fails_before_any_media_write():
    client = Client(missing=True)
    with pytest.raises(RuntimeError, match="missing Asset Registry mappings"):
        MODULE.execute(client, "snapshot-1", False)
    assert client.imported == []
