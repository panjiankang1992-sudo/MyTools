import importlib.util
import json
from pathlib import Path
import stat
import tempfile
import unittest


SCRIPT = Path(__file__).parents[1] / "evaluate.py"
SPEC = importlib.util.spec_from_file_location("audiobook_analysis_evaluate", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def analysis(character_name="Lin", speaker_name="Lin"):
    return {
        "characters": [{"canonicalName": character_name, "presentation": "NEUTRAL", "characterType": "HUMAN",
                        "traits": ["CALM"], "aliases": [{"alias": "L"}]}],
        "relationships": [{"sourceCanonicalName": character_name, "targetCanonicalName": character_name,
                            "relationshipType": "SELF", "direction": "BIDIRECTIONAL"}],
        "speechSegments": [{"chapterIndex": 0, "textStartCodepoint": 2, "textEndCodepoint": 5,
                            "speakerKind": "CHARACTER", "speakerCanonicalName": speaker_name,
                            "isExplicit": True}],
    }


class AudiobookAnalysisEvaluateTest(unittest.TestCase):

    def test_scores_exact_character_alias_relationship_and_speaker_matches(self):
        metrics = MODULE.score_record(analysis(), analysis())
        gates = MODULE.gate(MODULE.aggregate([metrics]), 0.90, 0.90, 0.80, minimum_attribute_examples=1)

        self.assertEqual(1.0, metrics["characters"]["recall"])
        self.assertEqual(1.0, metrics["aliases"]["f1"])
        self.assertEqual(1.0, metrics["relationships"]["precision"])
        self.assertEqual(1.0, metrics["speakers"]["explicit"]["accuracy"])
        self.assertEqual(1.0, metrics["attributes"]["presentations"]["accuracy"])
        self.assertEqual(1.0, metrics["attributes"]["characterTypes"]["accuracy"])
        self.assertEqual(1.0, metrics["attributes"]["traits"]["f1"])
        self.assertEqual(1, metrics["attributes"]["traits"]["labeledCharacters"])
        self.assertTrue(all(gates.values()))

    def test_fails_speaker_gate_when_the_gold_quote_has_the_wrong_character(self):
        metrics = MODULE.aggregate([MODULE.score_record(analysis(), analysis(speaker_name="Chen"))])
        gates = MODULE.gate(metrics, 0.90, 0.90, 0.80)

        self.assertEqual(0.0, metrics["speakers"]["explicit"]["accuracy"])
        self.assertFalse(gates["explicitSpeakerAccuracy"])

    def test_rejects_duplicate_speech_ranges(self):
        invalid = analysis()
        invalid["speechSegments"].append(dict(invalid["speechSegments"][0]))

        with self.assertRaisesRegex(ValueError, "duplicated"):
            MODULE.score_record(invalid, analysis())

    def test_empty_goldens_and_invalid_thresholds_do_not_pass_quality_gates(self):
        empty = {"characters": [], "relationships": [], "speechSegments": []}
        metrics = MODULE.aggregate([MODULE.score_record(empty, empty)])

        self.assertFalse(any(MODULE.gate(metrics, 0.90, 0.90, 0.80).values()))
        with self.assertRaisesRegex(ValueError, "threshold"):
            MODULE.gate(metrics, 1.1, 0.90, 0.80)

    def test_fails_attribute_gate_when_character_traits_are_wrong_or_under_labeled(self):
        expected = analysis()
        actual = analysis()
        actual["characters"][0]["traits"] = ["IMPULSIVE"]
        metrics = MODULE.aggregate([MODULE.score_record(expected, actual)])

        strict = MODULE.gate(metrics, 0.90, 0.90, 0.80, minimum_attribute_examples=1)
        under_labeled = MODULE.gate(metrics, 0.90, 0.90, 0.80, minimum_attribute_examples=2)

        self.assertFalse(strict["traitF1"])
        self.assertFalse(under_labeled["presentationAccuracy"])
        self.assertFalse(under_labeled["characterTypeAccuracy"])

    def test_rejects_duplicate_character_names_instead_of_hiding_them_in_a_set(self):
        invalid = analysis()
        invalid["characters"].append(dict(invalid["characters"][0]))

        with self.assertRaisesRegex(ValueError, "character is duplicated"):
            MODULE.score_record(invalid, analysis())

    def test_cli_writes_private_ready_evaluation_report_for_attestation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            golden = root / "golden.jsonl"
            actual = root / "actual.jsonl"
            output = root / "evaluation-report.json"
            golden.write_text("\n".join(json.dumps({"bookId": f"book-{index}", "expected": analysis()})
                                        for index in range(20)) + "\n", encoding="utf-8")
            actual.write_text("\n".join(json.dumps({"bookId": f"book-{index}", "actual": analysis()})
                                        for index in range(20)) + "\n", encoding="utf-8")

            result = MODULE.main(["--golden", str(golden), "--actual", str(actual), "--output", str(output)])

            self.assertEqual(0, result)
            self.assertEqual(stat.S_IRUSR | stat.S_IWUSR, stat.S_IMODE(output.stat().st_mode))
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertTrue(report["ready"])
            self.assertEqual(20, report["metrics"]["bookCount"])

    def test_cli_writes_failed_report_but_never_overwrites_audit_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            golden = root / "golden.jsonl"
            actual = root / "actual.jsonl"
            output = root / "evaluation-report.json"
            golden.write_text(json.dumps({"bookId": "book-1", "expected": analysis()}) + "\n", encoding="utf-8")
            actual.write_text(json.dumps({"bookId": "book-1", "actual": analysis(speaker_name="Chen")}) + "\n",
                              encoding="utf-8")

            result = MODULE.main(["--golden", str(golden), "--actual", str(actual), "--output", str(output),
                                  "--minimum-attribute-examples", "1"])
            second_result = MODULE.main(["--golden", str(golden), "--actual", str(actual), "--output", str(output),
                                         "--minimum-attribute-examples", "1"])

            self.assertEqual(2, result)
            self.assertFalse(json.loads(output.read_text(encoding="utf-8"))["ready"])
            self.assertEqual(1, second_result)

    def test_rejects_symbolic_link_input_to_avoid_reading_an_untrusted_target(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_file = root / "input.jsonl"
            link = root / "link.jsonl"
            input_file.write_text(json.dumps({"bookId": "book-1", "expected": analysis()}) + "\n", encoding="utf-8")
            link.symlink_to(input_file)

            with self.assertRaisesRegex(ValueError, "input is invalid"):
                MODULE.read_jsonl(link, expected=True)


if __name__ == "__main__":
    unittest.main()
