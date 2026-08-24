"""Tests for frozen Drive account migration and reconciliation."""

import importlib.util
from pathlib import Path

import pytest


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("drive_migrate_legacy_accounts_v11", SCRIPT)
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def account(legacy_id: int) -> dict:
    """Build one sanitized source account."""

    return {"legacyId": legacy_id, "ownerId": 7, "externalAccountId": f"drive:{legacy_id}",
            "displayName": "Primary", "providerType": "RCLONE",
            "providerSecretRef": f"secret://mytools/rclone/{legacy_id}",
            "remoteKey": f"drive_{legacy_id}", "readOnly": True, "enabled": True}


class Client:
    """Provide deterministic frozen source and target evidence."""

    def __init__(self):
        self.items = []

    def page(self, source, after_id, high_water):
        values = [account(11)] if source == "DRIVE" else [account(12)]
        frozen = 11 if source == "DRIVE" else 12
        return {"accounts": values, "nextAfterId": frozen, "complete": True,
                "snapshotHighWater": frozen}

    def import_batch(self, migration_key, dry_run, items):
        self.items.extend(items)
        digest = module.hashlib.sha256()
        for item in items:
            module.update_digest(digest, item["sourceSystem"], item["legacyAccountId"],
                                 module.account_digest(item["account"]))
        return {"migrationKey": migration_key, "dryRun": dry_run, "exported": len(items),
                "accepted": len(items), "skipped": 0, "rejected": 0,
                "digestSha256": digest.hexdigest()}

    def evidence(self, migration_key):
        digest = module.hashlib.sha256()
        for item in self.items:
            module.update_digest(digest, item["sourceSystem"], item["legacyAccountId"],
                                 module.account_digest(item["account"]))
        return {"migrationKey": migration_key, "itemCount": len(self.items),
                "digestSha256": digest.hexdigest()}


def test_freezes_both_sources_and_reconciles_target():
    """Formal migration must close counts and match target collection evidence."""

    result = module.execute(Client(), "drive-20260824", False)

    assert result["exported"] == 2
    assert result["accepted"] == 2
    assert result["sourceHighWater"] == {"DRIVE": 11, "WEBDAV": 12}


def test_rejects_changed_source_high_water():
    """A caller-provided high water cannot change between pages."""

    with pytest.raises(RuntimeError, match="high water changed"):
        module.execute(Client(), "drive-20260824", True, {"DRIVE": 10, "WEBDAV": 12})


def test_rejects_secret_shaped_extra_source_fields():
    """Unexpected source fields must fail instead of leaking into target requests."""

    value = account(13)
    value["password"] = "secret"
    with pytest.raises(RuntimeError, match="invalid fields"):
        module.migration_item("DRIVE", value)
