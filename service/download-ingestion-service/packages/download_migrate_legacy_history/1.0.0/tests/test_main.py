from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
from uuid import uuid4

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = spec_from_file_location("download_migrate_legacy_history", MODULE_PATH)
MODULE = module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeClient:
    def __init__(self, items, digest):
        self.items = items
        self.digest = digest

    def page(self, snapshot_id, after_id):
        return {"snapshotId": snapshot_id, "collectionSha256": self.digest,
                "itemCount": len(self.items), "items": self.items, "nextAfterId": None}

    def import_batch(self, _migration_key, dry_run, items):
        return {"dryRun": dry_run, "accepted": len(items), "skipped": 0,
                "rejected": 0, "digestSha256": "0" * 64}


def test_execute_verifies_and_migrates_sealed_collection():
    item = {"itemType": "ASSET", "legacyId": "1", "payloadSha256": "a" * 64}
    import hashlib
    digest = hashlib.sha256()
    for value in ("ASSET", "1", "a" * 64):
        encoded = value.encode()
        digest.update(len(encoded).to_bytes(4, "big"))
        digest.update(encoded)
    snapshot_id = str(uuid4())
    result = MODULE.execute(FakeClient([item], digest.hexdigest()),
                            "download-v1", snapshot_id, True)
    assert result["exported"] == 1
    assert result["accepted"] == 1
    assert result["digestSha256"] == digest.hexdigest()
