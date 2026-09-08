"""Test the local multi-character quality-gate attestation issuer."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).parents[1] / "issue_quality_gate_attestation.py"
SPEC = importlib.util.spec_from_file_location("issue_quality_gate_attestation", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class IssueQualityGateAttestationTest(unittest.TestCase):
    def test_issues_private_integrity_bound_attestation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_records(root)
            output = root / "attestation.json"

            MODULE.write_attestation(output, MODULE.build_attestation(
                MODULE.read_record(root / "evaluation.json", "evaluation report"),
                MODULE.read_record(root / "ner.json", "NER verification"),
                MODULE.read_record(root / "review.json", "cross-chapter review")))

            result = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("AUDIOBOOK_MULTI_CHARACTER_QUALITY_GATE_V1", result["schemaVersion"])
            self.assertEqual(64, len(result["attestationSha256"]))
            self.assertEqual(0o600, output.stat().st_mode & 0o777)
            with self.assertRaisesRegex(ValueError, "already exists"):
                MODULE.write_attestation(output, result)

    def test_rejects_failed_or_tampered_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_records(root)
            evaluation = MODULE.read_record(root / "evaluation.json", "evaluation report")
            evaluation["gates"]["traitF1"] = False
            with self.assertRaisesRegex(ValueError, "not approved"):
                MODULE.build_attestation(evaluation,
                                         MODULE.read_record(root / "ner.json", "NER verification"),
                                         MODULE.read_record(root / "review.json", "cross-chapter review"))
            link = root / "linked-review.json"
            link.symlink_to(root / "review.json")
            with self.assertRaisesRegex(ValueError, "invalid"):
                MODULE.read_record(link, "cross-chapter review")

    @staticmethod
    def _write_records(root: Path) -> None:
        gates = {key: True for key in (
            "characterRecall", "characterPrecision", "aliasF1", "relationshipF1",
            "explicitSpeakerAccuracy", "speakerAccuracy", "presentationAccuracy",
            "characterTypeAccuracy", "traitF1")}
        (root / "evaluation.json").write_text(json.dumps({"ready": True, "gates": gates,
            "metrics": {"bookCount": 10}}), encoding="utf-8")
        (root / "ner.json").write_text(json.dumps({
            "schemaVersion": "AUDIOBOOK_NER_VERIFICATION_V1", "deploymentId": "ner-prod-v1",
            "validated": True, "personExampleCount": 100, "precision": 0.90, "recall": 0.90}),
            encoding="utf-8")
        (root / "review.json").write_text(json.dumps({
            "schemaVersion": "AUDIOBOOK_CROSS_CHAPTER_REVIEW_V1", "approved": True,
            "reviewedBookCount": 10, "reviewedCharacterCount": 20,
            "reviewedRelationshipCount": 20, "reviewedSpeechSegmentCount": 100}), encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
