import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).parents[5]
sys.path.insert(0, str(ROOT / "task-executor-service" / "sdk" / "python"))
SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("app_catalog_migrate_files", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Catalog:
    def __init__(self, value):
        self.value = value
        self.bound = []

    def page(self, after_id):
        return {"items": [self.value] if after_id is None else [], "nextAfterId": None}

    def bind(self, file_id, payload):
        self.bound.append((file_id, payload))
        return {"skipped": False}


class Storage:
    def __init__(self):
        self.values = []

    def publish(self, path, root, relative, key, size, digest):
        self.values.append((path, root, relative, key, size, digest))
        return "storage://managed/" + relative


class Assets:
    def __init__(self):
        self.payload = None

    def register(self, payload):
        self.payload = payload
        return {"id": "00000000-0000-4000-8000-000000000001", "version": 1}


def test_migrates_verified_file_and_binds_asset():
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        source = root / "sample.zip"
        source.write_bytes(b"zip")
        value = {"id": "00000000-0000-4000-8000-000000000002", "legacyId": "120",
                 "ownerId": 7, "fileName": "sample.zip", "fileType": "binary",
                 "legacyStoragePath": str(source), "fileSize": 3}
        catalog, storage, assets = Catalog(value), Storage(), Assets()
        result = MODULE.execute({"migrationKey": "catalog-files-v1", "storageRoot": "managed"},
                                catalog, storage, assets, [root.resolve()])
        digest = hashlib.sha256(b"zip").hexdigest()
        assert result["migrated"] == 1
        assert storage.values[0][5] == digest
        assert assets.payload["sourceType"] == "APP_CATALOG_FILE"
        assert catalog.bound[0][1]["contentSha256"] == digest


def test_collect_rejects_limits_before_publishing():
    value = {"fileSize": 11}
    try:
        MODULE.collect(Catalog(value), 1, 10)
    except ValueError as exception:
        assert "limits" in str(exception)
    else:
        raise AssertionError("limit must be enforced")
