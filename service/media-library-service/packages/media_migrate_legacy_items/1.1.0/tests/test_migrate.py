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
        "location": {"storageUri": f"file:///opt/extend/resource/yuyutian/media/202608/20260824/file-{legacy_id}.mp4"}},
        "mediaMetadata": {"tags": [{"name": "Legacy", "confidence": 0.8}]}}


class Client:
    def __init__(self, missing=False, bad_target=False):
        self.missing = missing
        self.bad_target = bad_target
        self.imported = []
        self.directories = []

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

    def import_media(self, request):
        self.imported.append(request)
        event = request["event"]
        return {"assetId": event["assetId"], "ownerId": event["ownerId"]}

    def backfill_directories(self, bindings):
        self.directories.extend(bindings)

    def evidence(self, migration_key):
        digest = MODULE.scan(self, migration_key, "snapshot-1", False)[4]
        return {"migrationKey": migration_key, "sourceSnapshotId": "snapshot-1",
                "itemCount": 1 if self.bad_target else 2, "tagCount": 2,
                "collectionSha256": digest}


def test_dry_run_preflights_all_mappings_without_writing_media():
    client = Client()
    result = MODULE.execute(client, "media-v1", "snapshot-1", True)
    assert result["exported"] == 3
    assert result["mediaItems"] == 2
    assert result["legacyTags"] == 2
    assert result["skippedNonMedia"] == 1
    assert result["imported"] == 0
    assert len(result["digestSha256"]) == 64
    assert client.imported == []
    assert result["targetVerified"] is False


def test_apply_is_two_pass_and_uses_stable_private_free_events():
    client = Client()
    result = MODULE.execute(client, "media-v1", "snapshot-1", False)
    first_events = list(client.imported)
    replay = MODULE.execute(client, "media-v1", "snapshot-1", False)
    assert result["imported"] == 2
    assert replay["digestSha256"] == result["digestSha256"]
    assert client.imported[2:] == first_events
    assert [request["event"]["displayName"] for request in first_events] == ["file-7.mp4", "file-9.mp4"]
    assert all(request["event"]["eventId"].startswith("legacy-media:") for request in first_events)
    assert all(request["tags"] == [{"name": "Legacy", "confidence": 0.8}]
               for request in first_events)
    assert all("location" not in request["event"] for request in first_events)
    assert [binding["directoryName"] for binding in client.directories[:2]] == ["20260824", "20260824"]
    assert [binding["parentDirectoryName"] for binding in client.directories[:2]] == ["202608", "202608"]
    assert all(len(binding["directoryKey"]) == 24 for binding in client.directories)
    assert all(request["migrationKey"] == "media-v1" for request in first_events)
    assert result["targetVerified"] is True


def test_missing_mapping_fails_before_any_media_write():
    client = Client(missing=True)
    with pytest.raises(RuntimeError, match="missing Asset Registry mappings"):
        MODULE.execute(client, "media-v1", "snapshot-1", False)
    assert client.imported == []


def test_target_evidence_must_exactly_match_frozen_source():
    with pytest.raises(RuntimeError, match="target migration evidence"):
        MODULE.execute(Client(bad_target=True), "media-v1", "snapshot-1", False)


def test_payload_digest_matches_java_protocol_fixture():
    request = {"event": {"eventId": "legacy-media-event",
                         "assetId": "00000000-0000-4000-8000-000000000010",
                         "ownerId": 10, "sourceType": "LEGACY_ASSET",
                         "sourceBusinessId": "local_file:10", "displayName": "legacy.mp4",
                         "mimeType": "video/mp4", "sizeBytes": 20,
                         "contentSha256": "d" * 64},
               "tags": [{"name": "Travel", "confidence": 0.8},
                        {"name": "R18-否", "confidence": None}]}
    assert MODULE.migration_payload_digest(request) == \
        "eb8e0d754703394181a4e0b091201b01981610a9095822bfa90ef380d2cb5b85"


def test_directory_binding_uses_daily_media_directory():
    binding = MODULE.directory_binding_for(
        7, "42", "file:///opt/extend/resource/yuyutian/media/202608/20260827/image.jpg")
    assert binding["directoryName"] == "20260827"
    assert binding["parentDirectoryName"] == "202608"
    assert len(binding["directoryKey"]) == 24


def test_directory_binding_rejects_missing_media_level():
    with pytest.raises(RuntimeError, match="hierarchy"):
        MODULE.directory_binding_for(
            7, "43", "file:///opt/extend/resource/yuyutian/202608/20260825/image.jpg")


def test_directory_binding_rejects_user_media_outside_media_level():
    with pytest.raises(RuntimeError, match="hierarchy"):
        MODULE.directory_binding_for(
            7, "44", "file:///opt/extend/resource/yuyutian/20260825_120000_video/storyboard/1.jpg")


def test_directory_binding_groups_legacy_root_by_owner_and_date():
    standard = MODULE.directory_binding_for(
        7, "45", "file:///opt/extend/resource/yuyutian/media/202608/20260825/image.jpg")
    legacy = MODULE.directory_binding_for(
        7, "46", "file:///opt/extend/resource/big_media/20260825_120000_video.jpg")
    assert legacy["parentDirectoryKey"] == standard["parentDirectoryKey"]
    assert legacy["directoryKey"] == standard["directoryKey"]


def test_directory_binding_uses_snapshot_date_when_path_has_no_date():
    binding = MODULE.directory_binding_for(
        7, "47", "file:///opt/extend/resource/other/song.mp3", "legacy_asset_20260824_02")
    assert binding["parentDirectoryName"] == "202608"
    assert binding["directoryName"] == "20260824"
