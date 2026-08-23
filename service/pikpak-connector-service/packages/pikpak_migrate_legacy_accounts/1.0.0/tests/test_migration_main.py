from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
from uuid import uuid4
import pytest
MODULE_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = spec_from_file_location("pikpak_migrate_legacy_accounts", MODULE_PATH)
MODULE = module_from_spec(SPEC); SPEC.loader.exec_module(MODULE)
ITEM = {"externalKey": "pikpak-main", "remoteKey": "pikpak", "offlineRoot": "DownloadBot/offline",
        "readyRoot": "DownloadBot/inbox", "legacyEnabled": True, "stableSeconds": 60}
class FakeClient:
    def __init__(self): self.registered = []
    def page(self, _after):
        return {"items": [ITEM], "nextAfterId": None, "totalCount": 1,
                "collectionSha256": MODULE.collection_digest([ITEM])}
    def register(self, payload): self.registered.append(payload); return {"id": str(uuid4())}
def parameters(dry_run):
    return {"dryRun": dry_run, "accountMappings": [{"externalKey": "pikpak-main",
        "storageProviderId": str(uuid4()), "secretRef": "secret://pikpak/main"}]}
def test_dry_run_validates_without_registering():
    client = FakeClient(); result = MODULE.execute(parameters(True), client)
    assert result["sourceCount"] == 1 and result["acceptedCount"] == 0 and not client.registered
def test_formal_migration_forces_new_account_disabled():
    client = FakeClient(); result = MODULE.execute(parameters(False), client)
    assert result["acceptedCount"] == 1 and client.registered[0]["enabled"] is False
    assert client.registered[0]["stableSeconds"] == 60
def test_mapping_must_cover_exact_source_accounts():
    client = FakeClient()
    with pytest.raises(ValueError, match="exactly cover"):
        MODULE.execute({"dryRun": True, "accountMappings": []}, client)

def test_dry_run_rejects_unrepresentable_stable_window():
    client = FakeClient()
    invalid = dict(ITEM, stableSeconds=86401)
    client.page = lambda _after: {"items": [invalid], "nextAfterId": None, "totalCount": 1,
        "collectionSha256": MODULE.collection_digest([invalid])}
    with pytest.raises(ValueError, match="stable window"):
        MODULE.execute(parameters(True), client)
