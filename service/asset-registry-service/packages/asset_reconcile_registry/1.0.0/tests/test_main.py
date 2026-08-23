import importlib.util
from pathlib import Path

import pytest

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("asset_reconcile_registry", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeClient:
    def __init__(self, pages):
        self.pages = iter(pages)

    def page(self, _after_id):
        return next(self.pages)


def page(next_after_id=None, asset_count=2):
    return {"nextAfterId": next_after_id, "registryRevision": 7,
            "assetCount": asset_count, "sourceCount": 3,
            "availableLocationCount": 1, "invalidLocationCount": 1, "artifactCount": 2,
            "bundleReferenceCount": 1, "digestSha256": "a" * 64}


def test_aggregates_bounded_pages():
    result = MODULE.execute(FakeClient([page("00000000-0000-4000-8000-000000000001"), page()]))
    assert result["assetCount"] == 4
    assert result["sourceCount"] == 6
    assert result["pageCount"] == 2
    assert result["registryRevision"] == 7
    assert len(result["digestSha256"]) == 64


def test_rejects_non_advancing_cursor_and_invalid_count():
    with pytest.raises(RuntimeError, match="did not advance"):
        MODULE.execute(FakeClient([page("same"), page("same")]), "same")
    invalid = page()
    invalid["assetCount"] = True
    with pytest.raises(RuntimeError, match="count is invalid"):
        MODULE.execute(FakeClient([invalid]))


def test_rejects_revision_change_during_scan():
    changed = page()
    changed["registryRevision"] = 8
    with pytest.raises(RuntimeError, match="changed during reconciliation"):
        MODULE.execute(FakeClient([page("00000000-0000-4000-8000-000000000001"), changed]))
