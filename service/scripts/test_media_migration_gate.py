import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).with_name("media_migration_gate.py")
SPEC = importlib.util.spec_from_file_location("media_migration_gate", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def snapshot():
    return {"snapshotId": "media-snapshot-1", "ownerId": 1, "captured": 3,
            "rejected": 0, "digestSha256": "a" * 64}


def asset(dry_run):
    return {"sourceSnapshotId": "media-snapshot-1", "dryRun": dry_run, "exported": 3,
            "accepted": 3, "skipped": 0, "rejected": 0, "digestSha256": "b" * 64}


def media(dry_run):
    return {"sourceSnapshotId": "media-snapshot-1", "dryRun": dry_run, "exported": 3,
            "mediaItems": 2, "legacyTags": 4, "skippedNonMedia": 1,
            "imported": 0 if dry_run else 2, "digestSha256": "c" * 64}


def asset_reconciliation():
    return {"legacyMappingCount": 3, "assetCount": 2, "digestSha256": "d" * 64}


def media_reconciliation():
    return {"itemCount": 1, "sourceRelationCount": 2, "sourceTagRelationCount": 4,
            "tagRelationCount": 3, "stagingScanCount": 0,
            "analyzingCount": 0, "runningAnalysisCount": 0, "digestSha256": "e" * 64}


class MediaMigrationGateTest(unittest.TestCase):
    def test_accepts_complete_quiescent_migration_evidence(self):
        result = MODULE.evaluate(snapshot(), asset(True), asset(False), media(True), media(False),
                                 asset_reconciliation(), media_reconciliation())
        self.assertTrue(result["ready"])
        self.assertEqual([], result["errors"])
        self.assertNotIn("a" * 64, str(result))

    def test_rejects_snapshot_rejections_and_missing_tags(self):
        source = snapshot()
        source["rejected"] = 1
        reconciliation = media_reconciliation()
        reconciliation["sourceTagRelationCount"] = 3
        result = MODULE.evaluate(source, asset(True), asset(False), media(True), media(False),
                                 asset_reconciliation(), reconciliation)
        self.assertFalse(result["ready"])
        self.assertIn("SNAPSHOT_HAS_REJECTIONS", result["errors"])
        self.assertIn("MEDIA_RECONCILIATION_INCOMPLETE", result["errors"])

    def test_rejects_changed_source_and_partial_media_import(self):
        applied_asset = asset(False)
        applied_asset["digestSha256"] = "f" * 64
        applied_media = media(False)
        applied_media["imported"] = 1
        result = MODULE.evaluate(snapshot(), asset(True), applied_asset, media(True), applied_media,
                                 asset_reconciliation(), media_reconciliation())
        self.assertFalse(result["ready"])
        self.assertIn("ASSET_DIGEST_MISMATCH", result["errors"])
        self.assertIn("MEDIA_IMPORT_INCOMPLETE", result["errors"])

    def test_rejects_non_quiescent_reconciliation(self):
        reconciliation = media_reconciliation()
        reconciliation["analyzingCount"] = 1
        result = MODULE.evaluate(snapshot(), asset(True), asset(False), media(True), media(False),
                                 asset_reconciliation(), reconciliation)
        self.assertFalse(result["ready"])
        self.assertIn("MEDIA_RECONCILIATION_NOT_QUIESCENT", result["errors"])


if __name__ == "__main__":
    unittest.main()
