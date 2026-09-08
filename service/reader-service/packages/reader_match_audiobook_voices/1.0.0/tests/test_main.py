import importlib.util
import json
from pathlib import Path
import sys
import unittest


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SDK = Path(__file__).parents[5] / "task-executor-service" / "sdk" / "python"
sys.path.insert(0, str(SDK))
from mytools_task_sdk.audiobook_quality_gate import build_attestation

SPEC = importlib.util.spec_from_file_location("reader_match_audiobook_voices", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


CATALOG = """[
  {"provider":"VOLCENGINE","voiceType":"narrator","language":"zh-CN","presentation":"NEUTRAL","ageGroup":"ADULT","styleTags":["calm"],"narratorEligible":true,"catalogVersion":"v1","ssmlSupported":true},
  {"provider":"VOLCENGINE","voiceType":"female","language":"zh-CN","presentation":"FEMININE","ageGroup":"ADULT","styleTags":["warm"],"narratorEligible":false,"catalogVersion":"v1","ssmlSupported":true}
]"""


class Reader:
    def __init__(self):
        self.saved = None

    def input(self, generation_id):
        return {"generationId": generation_id, "characters": [
            {"canonicalName": "Lin", "presentation": "FEMININE", "characterType": "HUMAN",
             "traits": ["warm"], "confidence": 0.9}
        ], "voices": []}

    def save(self, _generation_id, plan):
        self.saved = plan
        return {"bindingCount": len(plan["bindings"])}

    def complete(self, generation_id):
        return {"generationId": generation_id, "bindingCount": len(self.saved["bindings"])}


class ReaderMatchAudiobookVoicesTest(unittest.TestCase):
    def test_locks_narrator_and_character_to_configured_voices(self):
        reader = Reader()
        _, attestation_sha256 = MODULE.multi_character_voice_policy(
            "true", "true", self.quality_attestation(), "ner-prod-v1")
        result = MODULE.execute({"generationId": "00000000-0000-4000-8000-000000000001"}, reader, CATALOG,
                                allow_character_voices=True,
                                quality_gate_attestation_sha256=attestation_sha256)

        self.assertEqual(2, result["bindingCount"])
        self.assertEqual("narrator", reader.saved["bindings"][0]["voiceType"])
        self.assertEqual("female", reader.saved["bindings"][1]["voiceType"])
        self.assertTrue(all(value["locked"] for value in reader.saved["bindings"]))
        self.assertTrue(all(value["ssmlSupported"] for value in reader.saved["voices"]))
        self.assertEqual(64, len(reader.saved["voicePlanFingerprintSha256"]))

    def test_rejects_catalog_without_explicit_ssml_capability(self):
        invalid = """[{"provider":"VOLCENGINE","voiceType":"narrator","language":"zh-CN","presentation":"NEUTRAL","ageGroup":"ADULT","styleTags":[],"narratorEligible":true,"catalogVersion":"v1"}]"""

        with self.assertRaisesRegex(ValueError, "invalid"):
            MODULE.catalog_from_configuration(invalid, frozenset({"VOLCENGINE"}))

    def test_rejects_catalog_provider_without_implemented_executor(self):
        invalid = """[{"provider":"MINIMAX","voiceType":"narrator","language":"zh-CN","presentation":"NEUTRAL","ageGroup":"ADULT","styleTags":[],"narratorEligible":true,"catalogVersion":"v1","ssmlSupported":true}]"""

        with self.assertRaisesRegex(ValueError, "provider"):
            MODULE.catalog_from_configuration(invalid, frozenset({"VOLCENGINE"}))
        with self.assertRaisesRegex(ValueError, "provider"):
            MODULE.supported_providers_from_configuration("VOLCENGINE,minimax")

    def test_rejects_catalog_without_narrator_candidate(self):
        invalid = """[{"provider":"VOLCENGINE","voiceType":"female","language":"zh-CN","presentation":"FEMININE","ageGroup":"ADULT","styleTags":[],"narratorEligible":false,"catalogVersion":"v1","ssmlSupported":true}]"""

        with self.assertRaisesRegex(ValueError, "narrator"):
            MODULE.catalog_from_configuration(invalid, frozenset({"VOLCENGINE"}))

    def test_selects_only_tencent_voices_for_the_tencent_contract(self):
        catalog = """[
          {"provider":"TENCENT","voiceType":"1001","language":"zh-CN","presentation":"NEUTRAL","ageGroup":"ADULT","styleTags":["calm"],"narratorEligible":true,"catalogVersion":"tencent-2026","ssmlSupported":false,"apiVersions":["tencent-v1"]},
          {"provider":"VOLCENGINE","voiceType":"legacy","language":"zh-CN","presentation":"NEUTRAL","ageGroup":"ADULT","styleTags":[],"narratorEligible":true,"catalogVersion":"legacy","ssmlSupported":true,"apiVersions":["v1"]}
        ]"""

        voices = MODULE.catalog_from_configuration(catalog, frozenset({"TENCENT", "VOLCENGINE"}),
                                                 "tencent-v1", None, "TENCENT")

        self.assertEqual(["TENCENT"], [voice["provider"] for voice in voices])
        self.assertEqual(["1001"], [voice["voiceType"] for voice in voices])

    def test_uses_narrator_for_a_low_confidence_character(self):
        input_data = {"characters": [{"canonicalName": "Unknown", "presentation": "FEMININE",
                                        "characterType": "HUMAN", "traits": ["warm"], "confidence": 0.4}]}
        voices = MODULE.catalog_from_configuration(CATALOG, frozenset({"VOLCENGINE"}))

        _, attestation_sha256 = MODULE.multi_character_voice_policy(
            "true", "true", self.quality_attestation(), "ner-prod-v1")
        plan = MODULE.build_plan(input_data, voices, 0.8, True, attestation_sha256)
        binding = plan["bindings"][1]

        self.assertEqual("narrator", binding["voiceType"])
        self.assertEqual("RULES_V1_NARRATOR_FALLBACK", binding["matchMethod"])
        self.assertIn("character_confidence_below_threshold", binding["rationaleTags"])

    def test_uses_narrator_until_multi_character_quality_gate_is_explicitly_enabled(self):
        input_data = {"characters": [{"canonicalName": "Lin", "presentation": "FEMININE",
                                        "characterType": "HUMAN", "traits": ["warm"], "confidence": 0.9}]}
        voices = MODULE.catalog_from_configuration(CATALOG, frozenset({"VOLCENGINE"}))

        plan = MODULE.build_plan(input_data, voices, 0.8, allow_character_voices=False)
        binding = plan["bindings"][1]

        self.assertEqual("narrator", binding["voiceType"])
        self.assertEqual("RULES_V1_NARRATOR_FALLBACK", binding["matchMethod"])
        self.assertIn("multi_character_voice_disabled", binding["rationaleTags"])
        self.assertFalse(MODULE.multi_character_enabled("false"))
        self.assertTrue(MODULE.multi_character_enabled(
            " true ", "true", self.quality_attestation(), "ner-prod-v1"))
        with self.assertRaisesRegex(ValueError, "NER deployment"):
            MODULE.multi_character_enabled("true", "true", self.quality_attestation(), "ner-unreviewed")
        with self.assertRaisesRegex(ValueError, "quality gate"):
            MODULE.multi_character_enabled("true")
        with self.assertRaisesRegex(ValueError, "multi-character"):
            MODULE.multi_character_enabled("enabled")

    def test_multi_character_plan_freezes_a_valid_quality_gate_attestation(self):
        input_data = {"characters": [{"canonicalName": "Lin", "presentation": "FEMININE",
                                        "characterType": "HUMAN", "traits": ["warm"], "confidence": 0.9}]}
        voices = MODULE.catalog_from_configuration(CATALOG, frozenset({"VOLCENGINE"}))
        enabled, attestation_sha256 = MODULE.multi_character_voice_policy(
            "true", "true", self.quality_attestation(), "ner-prod-v1")

        plan = MODULE.build_plan(input_data, voices, 0.8, enabled, attestation_sha256)

        self.assertEqual("female", plan["bindings"][1]["voiceType"])
        self.assertEqual(attestation_sha256, plan["qualityGateAttestationSha256"])
        with self.assertRaisesRegex(ValueError, "attestation"):
            MODULE.build_plan(input_data, voices, 0.8, True)

    @staticmethod
    def quality_attestation() -> str:
        gates = {key: True for key in (
            "characterRecall", "characterPrecision", "aliasF1", "relationshipF1",
            "explicitSpeakerAccuracy", "speakerAccuracy", "presentationAccuracy",
            "characterTypeAccuracy", "traitF1")}
        attestation = build_attestation(
            {"ready": True, "gates": gates, "metrics": {"bookCount": 10}},
            {"schemaVersion": "AUDIOBOOK_NER_VERIFICATION_V1", "deploymentId": "ner-prod-v1",
             "validated": True, "personExampleCount": 100, "precision": 0.90, "recall": 0.90},
            {"schemaVersion": "AUDIOBOOK_CROSS_CHAPTER_REVIEW_V1", "approved": True,
             "reviewedBookCount": 10, "reviewedCharacterCount": 20,
             "reviewedRelationshipCount": 20, "reviewedSpeechSegmentCount": 100})
        return json.dumps(attestation, separators=(",", ":"))

    def test_preserves_inherited_voice_bindings_for_appended_chapters(self):
        input_data = {"characters": [
            {"canonicalName": "Lin", "presentation": "FEMININE", "characterType": "HUMAN",
             "traits": ["warm"], "confidence": 0.9},
            {"canonicalName": "NewCharacter", "presentation": "FEMININE", "characterType": "HUMAN",
             "traits": ["warm"], "confidence": 0.9},
        ], "lockedBindings": [
            {"roleKey": "NARRATOR", "characterCanonicalName": None, "provider": "VOLCENGINE",
             "voiceType": "narrator", "matchScore": 0.9, "matchMethod": "RULES_V1",
             "rationaleTags": ["configured_catalog"]},
            {"roleKey": "CHARACTER:Lin", "characterCanonicalName": "Lin", "provider": "VOLCENGINE",
             "voiceType": "female", "matchScore": 0.88, "matchMethod": "RULES_V1",
             "rationaleTags": ["presentation_exact"]},
        ]}
        voices = MODULE.catalog_from_configuration(CATALOG, frozenset({"VOLCENGINE"}))

        plan = MODULE.build_plan(input_data, voices, 0.8, allow_character_voices=False)
        bindings = {value["roleKey"]: value for value in plan["bindings"]}

        self.assertEqual("female", bindings["CHARACTER:Lin"]["voiceType"])
        self.assertIn("incremental_voice_lock", bindings["CHARACTER:Lin"]["rationaleTags"])
        self.assertEqual("narrator", bindings["CHARACTER:NewCharacter"]["voiceType"])

    def test_rejects_inherited_voice_that_is_not_in_the_current_approved_catalog(self):
        voices = MODULE.catalog_from_configuration(CATALOG, frozenset({"VOLCENGINE"}))
        input_data = {"characters": [], "lockedBindings": [
            {"roleKey": "NARRATOR", "characterCanonicalName": None, "provider": "VOLCENGINE",
             "voiceType": "withdrawn", "matchScore": 0.9, "matchMethod": "RULES_V1",
             "rationaleTags": []},
        ]}

        with self.assertRaisesRegex(ValueError, "no longer approved"):
            MODULE.build_plan(input_data, voices, 0.8)

    def test_preserves_an_approved_voice_preview_without_exposing_it_to_matching(self):
        catalog = """[
          {"provider":"VOLCENGINE","voiceType":"narrator","language":"zh-CN","presentation":"NEUTRAL","ageGroup":"ADULT","styleTags":[],"narratorEligible":true,"catalogVersion":"v1","ssmlSupported":true,"preview":{"storageUri":"storage://managed/audiobook-voice-previews/narrator.mp3","contentSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","sizeBytes":1234,"format":"mp3","durationMs":1200}}
        ]"""

        voice = MODULE.catalog_from_configuration(catalog, frozenset({"VOLCENGINE"}))[0]

        self.assertEqual("storage://managed/audiobook-voice-previews/narrator.mp3", voice["previewStorageUri"])
        self.assertEqual("mp3", voice["previewFormat"])
        self.assertEqual(1200, voice["previewDurationMs"])

    def test_rejects_partially_configured_voice_preview(self):
        catalog = """[
          {"provider":"VOLCENGINE","voiceType":"narrator","language":"zh-CN","presentation":"NEUTRAL","ageGroup":"ADULT","styleTags":[],"narratorEligible":true,"catalogVersion":"v1","ssmlSupported":true,"preview":{"storageUri":"storage://managed/audiobook-voice-previews/narrator.mp3","format":"mp3"}}
        ]"""

        with self.assertRaisesRegex(ValueError, "preview"):
            MODULE.catalog_from_configuration(catalog, frozenset({"VOLCENGINE"}))

    def test_v3_catalog_only_returns_voices_verified_for_the_active_resource(self):
        catalog = """[
          {"provider":"VOLCENGINE","voiceType":"narrator-v3","language":"zh-CN","presentation":"NEUTRAL","ageGroup":"ADULT","styleTags":[],"narratorEligible":true,"catalogVersion":"v3","ssmlSupported":false,"apiVersions":["v3"],"resourceIds":["seed-tts-2.0"]},
          {"provider":"VOLCENGINE","voiceType":"wrong-resource","language":"zh-CN","presentation":"FEMININE","ageGroup":"ADULT","styleTags":[],"narratorEligible":false,"catalogVersion":"v3","ssmlSupported":false,"apiVersions":["v3"],"resourceIds":["seed-tts-1.0"]}
        ]"""

        voices = MODULE.catalog_from_configuration(catalog, frozenset({"VOLCENGINE"}), "v3", "seed-tts-2.0")

        self.assertEqual(["narrator-v3"], [voice["voiceType"] for voice in voices])

    def test_v3_execution_freezes_only_resource_compatible_voice(self):
        catalog = """[
          {"provider":"VOLCENGINE","voiceType":"narrator-v3","language":"zh-CN","presentation":"NEUTRAL","ageGroup":"ADULT","styleTags":[],"narratorEligible":true,"catalogVersion":"v3","ssmlSupported":false,"apiVersions":["v3"],"resourceIds":["seed-tts-2.0"]},
          {"provider":"VOLCENGINE","voiceType":"wrong-resource","language":"zh-CN","presentation":"FEMININE","ageGroup":"ADULT","styleTags":[],"narratorEligible":false,"catalogVersion":"v3","ssmlSupported":false,"apiVersions":["v3"],"resourceIds":["seed-tts-1.0"]}
        ]"""
        reader = Reader()

        result = MODULE.execute({"generationId": "00000000-0000-4000-8000-000000000001"}, reader, catalog,
                                "VOLCENGINE", 0.8, False, "v3", "seed-tts-2.0")

        self.assertEqual(2, result["bindingCount"])
        self.assertTrue(all(binding["voiceType"] == "narrator-v3" for binding in reader.saved["bindings"]))

    def test_v3_catalog_requires_explicit_resource_compatibility(self):
        catalog = """[
          {"provider":"VOLCENGINE","voiceType":"narrator-v3","language":"zh-CN","presentation":"NEUTRAL","ageGroup":"ADULT","styleTags":[],"narratorEligible":true,"catalogVersion":"v3","ssmlSupported":false,"apiVersions":["v3"]}
        ]"""

        with self.assertRaisesRegex(ValueError, "resourceIds"):
            MODULE.catalog_from_configuration(catalog, frozenset({"VOLCENGINE"}), "v3", "seed-tts-2.0")
        with self.assertRaisesRegex(ValueError, "compatible"):
            MODULE.catalog_from_configuration(CATALOG, frozenset({"VOLCENGINE"}), "v3", "seed-tts-2.0")
        with self.assertRaisesRegex(ValueError, "provider"):
            MODULE.supported_providers_from_configuration("VOLCENGINE,MINIMAX")

    def test_v3_catalog_rejects_unverified_pronunciation_ssml(self):
        catalog = """[
          {"provider":"VOLCENGINE","voiceType":"narrator-v3","language":"zh-CN","presentation":"NEUTRAL","ageGroup":"ADULT","styleTags":[],"narratorEligible":true,"catalogVersion":"v3","ssmlSupported":true,"apiVersions":["v3"],"resourceIds":["seed-tts-2.0"]}
        ]"""

        with self.assertRaisesRegex(ValueError, "SSML capability"):
            MODULE.catalog_from_configuration(catalog, frozenset({"VOLCENGINE"}), "v3", "seed-tts-2.0")


if __name__ == "__main__":
    unittest.main()
