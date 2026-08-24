import importlib.util
from pathlib import Path

import pytest

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("legacy_asset_capture_snapshot", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def row(**changes):
    value = {"id": 7, "file_path": "/opt/resource/a file.mp4", "file_size": 128,
             "mime_type": "video/mp4", "file_hash": "a" * 64,
             "update_time": "2026-08-23 20:00:00"}
    value.update(changes)
    return value


def test_normalizes_local_file_without_reading_file_content():
    result = MODULE.normalize(row(), 0)
    assert result["sourceSystem"] == "MyTools"
    assert result["legacyAssetId"] == "7"
    assert result["asset"]["contentSha256"] == "a" * 64
    assert result["asset"]["location"]["storageUri"] == "file:///opt/resource/a%20file.mp4"
    assert len(MODULE.payload_digest(result)) == 64


@pytest.mark.parametrize(("changes", "reason"), [
    ({"file_hash": None}, "HASH_MISSING_OR_INVALID"),
    ({"file_size": 0}, "SIZE_INVALID"),
    ({"file_path": "relative/file"}, "PATH_INVALID"),
])
def test_rejects_unverifiable_legacy_rows(changes, reason):
    with pytest.raises(ValueError, match=reason):
        MODULE.normalize(row(**changes), 0)


@pytest.mark.parametrize("owner_id", [0, -1])
def test_rejects_owner_that_cannot_be_read_by_target_services(owner_id):
    with pytest.raises(ValueError, match="owner id is invalid"):
        MODULE.capture(Connection(), Connection(), "snapshot-invalid-owner", owner_id)


class Cursor:
    def __init__(self, connection):
        self.connection = connection
        self.result = None

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def execute(self, sql, parameters=None):
        self.connection.commands.append((" ".join(sql.split()), parameters))
        if "SELECT * FROM legacy_asset_snapshot" in sql:
            self.result = None
        elif "MAX(id)" in sql:
            self.result = {"high_water_id": 2}
        elif "FROM local_file" in sql:
            self.result = [row(id=1), row(id=2, file_hash=None)]

    def fetchone(self):
        return self.result

    def fetchall(self):
        return self.result


class Connection:
    def __init__(self):
        self.commands = []
        self.commits = 0
        self.rollbacks = 0

    def cursor(self):
        return Cursor(self)

    def commit(self):
        self.commits += 1

    def rollback(self):
        self.rollbacks += 1


def test_capture_uses_consistent_source_transaction_and_atomic_target_commit():
    source = Connection()
    target = Connection()
    result = MODULE.capture(source, target, "snapshot-1", 1)
    assert result["captured"] == 1
    assert result["ownerId"] == 1
    assert result["rejected"] == 1
    assert target.commits == 1
    assert source.rollbacks == 1
    assert any("START TRANSACTION WITH CONSISTENT SNAPSHOT" in sql for sql, _ in source.commands)
    assert any("status='SEALED'" in sql for sql, _ in target.commands)
