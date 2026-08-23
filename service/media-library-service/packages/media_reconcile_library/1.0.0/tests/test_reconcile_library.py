import importlib.util
from pathlib import Path

import pytest

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("media_reconcile_library", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def page(revision=7, next_after=None, staging=0, running=0):
    result = {"nextAfterId": next_after, "libraryRevision": revision, "directoryCount": 2,
              "completedScanCount": 3, "stagingScanCount": staging,
              "pageDigestSha256": "a" * 64}
    result.update({name: 1 if name == "itemCount" else 0 for name in MODULE.COUNT_FIELDS})
    result["runningAnalysisCount"] = running
    return result


class Client:
    def __init__(self, pages):
        self.pages = iter(pages)

    def page(self, after_id):
        return next(self.pages)


def test_aggregates_stable_pages():
    result = MODULE.execute(Client([page(next_after="cursor"), page()]), True)
    assert result["libraryRevision"] == 7
    assert result["itemCount"] == 2
    assert result["directoryCount"] == 2
    assert len(result["digestSha256"]) == 64


def test_rejects_revision_drift():
    with pytest.raises(RuntimeError, match="changed during reconciliation"):
        MODULE.execute(Client([page(next_after="cursor"), page(revision=8)]), False)


def test_requires_quiescent_library_by_default():
    with pytest.raises(RuntimeError, match="not quiescent"):
        MODULE.execute(Client([page(staging=1)]), True)
