from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
from uuid import uuid4

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = spec_from_file_location("download_reconcile_legacy_result", MODULE_PATH)
MODULE = module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeClient:
    def __init__(self, current_digest="a" * 64):
        self.request_id = str(uuid4())
        self.current_digest = current_digest

    def legacy(self, snapshot_id, event_id):
        return {"snapshotId": snapshot_id, "eventId": event_id,
                "downloadRequestId": self.request_id, "legacyJobId": "7",
                "legacyStatus": "COMPLETED", "itemCount": 2, "totalBytes": 10,
                "contentSetSha256": "a" * 64}

    def current(self, request_id):
        return {"downloadRequestId": request_id, "status": "SUCCEEDED",
                "itemCount": 2, "totalBytes": 10,
                "contentSetSha256": self.current_digest}


def test_matching_content_evidence_passes():
    result = MODULE.execute(FakeClient(), str(uuid4()), "event-1")
    assert result["matched"] is True
    assert result["mismatchReasons"] == []


def test_content_difference_is_reported():
    result = MODULE.execute(FakeClient("b" * 64), str(uuid4()), "event-1")
    assert result["matched"] is False
    assert result["mismatchReasons"] == ["CONTENT_SET_MISMATCH"]
