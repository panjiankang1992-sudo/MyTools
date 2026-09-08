import importlib.util
import json
from pathlib import Path
import sys
import unittest

SDK = Path(__file__).parents[1] / "task-executor-service" / "sdk" / "python"
sys.path.insert(0, str(SDK))
from mytools_task_sdk.audiobook_quality_gate import build_attestation

SCRIPT = Path(__file__).with_name("cutover_preflight.py")
SPEC = importlib.util.spec_from_file_location("cutover_preflight", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CutoverPreflightTest(unittest.TestCase):
    def test_accepts_distinct_default_schemas_and_disabled_flags(self):
        report = MODULE.inspect({})
        self.assertTrue(report["ready"])
        self.assertEqual(0, report["greyRelease"]["readerTenantCount"])
        self.assertEqual(0, report["greyRelease"]["driveTenantCount"])
        self.assertEqual(0, report["greyRelease"]["downloadTenantCount"])
        self.assertEqual("LEGACY", report["greyRelease"]["identityValidationMode"])
        self.assertEqual([], report["errors"])

    def test_rejects_shared_schema_and_enabled_sidecar(self):
        report = MODULE.inspect({"READER_DB_NAME": "mytools_task",
                                 "READER_SEARCH_SIDECAR_ENABLED": "true"})
        self.assertFalse(report["ready"])
        self.assertEqual(2, len(report["errors"]))

    def test_rejects_enabled_media_and_messaging_routes_before_cutover(self):
        report = MODULE.inspect({"GATEWAY_MEDIA_ROUTE_ENABLED": "true",
                                 "GATEWAY_MESSAGING_ROUTE_ENABLED": "true"})
        self.assertFalse(report["ready"])
        self.assertTrue(any("GATEWAY_MEDIA_ROUTE_ENABLED" in error
                            for error in report["errors"]))
        self.assertTrue(any("GATEWAY_MESSAGING_ROUTE_ENABLED" in error
                            for error in report["errors"]))

    def test_reader_route_requires_explicit_tenant_allowlist(self):
        report = MODULE.inspect({"GATEWAY_READER_ROUTE_ENABLED": "true"}, allow_enabled=True)
        self.assertFalse(report["ready"])
        self.assertTrue(any("TENANT_ALLOWLIST is required" in error for error in report["errors"]))

    def test_reader_route_accepts_unique_positive_tenants(self):
        report = MODULE.inspect({"GATEWAY_READER_ROUTE_ENABLED": "true",
                                 "GATEWAY_READER_TENANT_ALLOWLIST": "55,56"}, allow_enabled=True)
        self.assertTrue(report["ready"])
        self.assertEqual(2, report["greyRelease"]["readerTenantCount"])

    def test_drive_route_requires_explicit_tenant_allowlist(self):
        report = MODULE.inspect({"GATEWAY_DRIVE_ROUTE_ENABLED": "true"}, allow_enabled=True)
        self.assertFalse(report["ready"])
        self.assertTrue(any("DRIVE_TENANT_ALLOWLIST is required" in error
                            for error in report["errors"]))

    def test_download_route_requires_explicit_tenant_allowlist(self):
        report = MODULE.inspect({"GATEWAY_DOWNLOAD_ROUTE_ENABLED": "true"}, allow_enabled=True)
        self.assertFalse(report["ready"])
        self.assertTrue(any("DOWNLOAD_TENANT_ALLOWLIST is required" in error
                            for error in report["errors"]))

    def test_identity_route_requires_new_token_validation_mode(self):
        invalid = MODULE.inspect({"GATEWAY_IDENTITY_ROUTE_ENABLED": "true"}, allow_enabled=True)
        valid = MODULE.inspect({"GATEWAY_IDENTITY_ROUTE_ENABLED": "true",
                                "IDENTITY_VALIDATION_MODE": "DUAL"}, allow_enabled=True)
        self.assertFalse(invalid["ready"])
        self.assertTrue(valid["ready"])

    def test_accepts_secret_free_complete_audiobook_readiness_configuration(self):
        values = self.audiobook_values()
        values.update({"AUDIOBOOK_MULTI_CHARACTER_ENABLED": "true",
                       "AUDIOBOOK_MULTI_CHARACTER_QUALITY_GATE_APPROVED": "true",
                       "AUDIOBOOK_MULTI_CHARACTER_QUALITY_GATE_ATTESTATION_JSON": self.quality_attestation(),
                       "AUDIOBOOK_NER_ENDPOINT": "https://ner.example.test/v1/entities",
                       "AUDIOBOOK_NER_DEPLOYMENT_ID": "ner-prod-v1"})

        report = MODULE.inspect(values, require_audiobook=True)

        self.assertTrue(report["ready"])
        self.assertTrue(report["audiobook"]["multiCharacterEnabled"])
        self.assertTrue(report["audiobook"]["qualityGateAttestationValid"])
        self.assertTrue(report["audiobook"]["nerDeploymentAttested"])
        self.assertEqual("v3", report["audiobook"]["ttsApiVersion"])
        self.assertEqual(11, report["audiobook"]["requiredSettingsPresent"])
        self.assertNotIn("test-api-key", json.dumps(report))
        self.assertNotIn("test-analysis-key", json.dumps(report))

    def test_rejects_multi_character_voice_without_quality_gate_attestation(self):
        values = self.audiobook_values()
        values["AUDIOBOOK_MULTI_CHARACTER_ENABLED"] = "true"

        report = MODULE.inspect(values, require_audiobook=True)

        self.assertFalse(report["ready"])
        self.assertTrue(any("QUALITY_GATE_APPROVED" in error for error in report["errors"]))

    def test_rejects_multi_character_voice_without_a_valid_quality_gate_evidence_summary(self):
        values = self.audiobook_values()
        values.update({"AUDIOBOOK_MULTI_CHARACTER_ENABLED": "true",
                       "AUDIOBOOK_MULTI_CHARACTER_QUALITY_GATE_APPROVED": "true",
                       "AUDIOBOOK_MULTI_CHARACTER_QUALITY_GATE_ATTESTATION_JSON": "{}"})

        report = MODULE.inspect(values, require_audiobook=True)

        self.assertFalse(report["ready"])
        self.assertFalse(report["audiobook"]["qualityGateAttestationValid"])
        self.assertTrue(any("ATTESTATION_JSON" in error for error in report["errors"]))

    def test_rejects_multi_character_voice_with_unattested_ner_deployment(self):
        values = self.audiobook_values()
        values.update({"AUDIOBOOK_MULTI_CHARACTER_ENABLED": "true",
                       "AUDIOBOOK_MULTI_CHARACTER_QUALITY_GATE_APPROVED": "true",
                       "AUDIOBOOK_MULTI_CHARACTER_QUALITY_GATE_ATTESTATION_JSON": self.quality_attestation(),
                       "AUDIOBOOK_NER_ENDPOINT": "https://ner.example.test/v1/entities",
                       "AUDIOBOOK_NER_DEPLOYMENT_ID": "ner-unreviewed"})

        report = MODULE.inspect(values, require_audiobook=True)

        self.assertFalse(report["ready"])
        self.assertTrue(any("NER_DEPLOYMENT_ID is not covered" in error for error in report["errors"]))

    def test_requires_executor_compatible_true_false_audiobook_controls(self):
        values = self.audiobook_values()
        values["AUDIOBOOK_MULTI_CHARACTER_ENABLED"] = "yes"

        report = MODULE.inspect(values, require_audiobook=True)

        self.assertFalse(report["ready"])
        self.assertTrue(any("MULTI_CHARACTER_ENABLED must be true or false" in error
                            for error in report["errors"]))

    def test_rejects_credential_bearing_audiobook_endpoint_without_echoing_it(self):
        values = self.audiobook_values()
        values["VOLCENGINE_TTS_ENDPOINT"] = "https://account:embedded-secret@example.test/api/v3/tts/unidirectional"

        report = MODULE.inspect(values, require_audiobook=True)

        self.assertFalse(report["ready"])
        self.assertTrue(any("VOLCENGINE_TTS_ENDPOINT" in error for error in report["errors"]))
        self.assertNotIn("embedded-secret", json.dumps(report))

    def test_rejects_a_v1_route_when_v3_is_configured(self):
        values = self.audiobook_values()
        values["VOLCENGINE_TTS_ENDPOINT"] = "https://tts.example.test/api/v1/tts"

        report = MODULE.inspect(values, require_audiobook=True)

        self.assertFalse(report["ready"])
        self.assertTrue(any("implemented /api/v3/tts/unidirectional route" in error for error in report["errors"]))

    def test_accepts_legacy_v1_only_with_legacy_credentials(self):
        values = self.audiobook_values()
        values.update({
            "VOLCENGINE_TTS_API_VERSION": "v1",
            "VOLCENGINE_TTS_ENDPOINT": "https://tts.example.test/api/v1/tts",
            "VOLCENGINE_TTS_APP_ID": "test-app-id",
            "VOLCENGINE_TTS_ACCESS_TOKEN": "test-access-token",
            "VOLCENGINE_TTS_CLUSTER": "volcano_tts",
        })
        values.pop("VOLCENGINE_TTS_API_KEY")
        values.pop("VOLCENGINE_TTS_RESOURCE_ID")
        values["AUDIOBOOK_VOICE_CATALOG_JSON"] = (
            '[{"provider":"VOLCENGINE","voiceType":"legacy-voice","narratorEligible":true,'
            '"ssmlSupported":true,"apiVersions":["v1"]}]')

        report = MODULE.inspect(values, require_audiobook=True)

        self.assertTrue(report["ready"])
        self.assertEqual("v1", report["audiobook"]["ttsApiVersion"])
        self.assertNotIn("test-access-token", json.dumps(report))

    def test_rejects_unknown_volcengine_protocol_version(self):
        values = self.audiobook_values()
        values["VOLCENGINE_TTS_API_VERSION"] = "v2"

        report = MODULE.inspect(values, require_audiobook=True)

        self.assertFalse(report["ready"])
        self.assertTrue(any("API_VERSION must be v1 or v3" in error for error in report["errors"]))

    def test_rejects_v3_catalog_voice_not_verified_for_the_active_resource(self):
        values = self.audiobook_values()
        values["AUDIOBOOK_VOICE_CATALOG_JSON"] = (
            '[{"provider":"VOLCENGINE","voiceType":"wrong-resource","narratorEligible":true,'
            '"ssmlSupported":false,"apiVersions":["v3"],"resourceIds":["seed-tts-1.0"]}]')

        report = MODULE.inspect(values, require_audiobook=True)

        self.assertFalse(report["ready"])
        self.assertTrue(any("incompatible V3 voice capability" in error for error in report["errors"]))

    def test_rejects_unverified_v3_ssml_catalog_capability(self):
        values = self.audiobook_values()
        values["AUDIOBOOK_VOICE_CATALOG_JSON"] = (
            '[{"provider":"VOLCENGINE","voiceType":"unverified-ssml","narratorEligible":true,'
            '"ssmlSupported":true,"apiVersions":["v3"],"resourceIds":["seed-tts-2.0"]}]')

        report = MODULE.inspect(values, require_audiobook=True)

        self.assertFalse(report["ready"])
        self.assertTrue(any("incompatible V3 voice capability" in error for error in report["errors"]))

    def test_accepts_tencent_base_tts_without_a_provider_endpoint_or_volcengine_credentials(self):
        values = self.audiobook_values()
        for key in ("VOLCENGINE_TTS_API_VERSION", "VOLCENGINE_TTS_ENDPOINT", "VOLCENGINE_TTS_API_KEY",
                    "VOLCENGINE_TTS_RESOURCE_ID", "VOLCENGINE_TTS_VOICE_TYPE"):
            values.pop(key)
        values.update({
            "AUDIOBOOK_TTS_DEFAULT_PROVIDER": "TENCENT",
            "AUDIOBOOK_TTS_SUPPORTED_PROVIDERS": "TENCENT",
            "TENCENT_TTS_SECRET_ID": "test-secret-id",
            "TENCENT_TTS_SECRET_KEY": "test-secret-key",
            "TENCENT_TTS_VOICE_TYPE": "1001",
            "AUDIOBOOK_VOICE_CATALOG_JSON": (
                '[{"provider":"TENCENT","voiceType":"1001","narratorEligible":true,'
                '"ssmlSupported":false,"apiVersions":["tencent-v1"]}]'),
        })

        report = MODULE.inspect(values, require_audiobook=True)

        self.assertTrue(report["ready"])
        self.assertEqual("TENCENT", report["audiobook"]["ttsProvider"])
        self.assertEqual("tencent-v1", report["audiobook"]["ttsApiVersion"])
        self.assertTrue(report["audiobook"]["ttsEndpointValid"])
        self.assertNotIn("test-secret-key", json.dumps(report))

    def test_rejects_tencent_catalog_with_a_non_numeric_or_mixed_contract_voice(self):
        values = self.audiobook_values()
        values.update({
            "AUDIOBOOK_TTS_DEFAULT_PROVIDER": "TENCENT",
            "AUDIOBOOK_TTS_SUPPORTED_PROVIDERS": "TENCENT",
            "TENCENT_TTS_SECRET_ID": "test-secret-id",
            "TENCENT_TTS_SECRET_KEY": "test-secret-key",
            "TENCENT_TTS_VOICE_TYPE": "1001",
            "AUDIOBOOK_VOICE_CATALOG_JSON": (
                '[{"provider":"TENCENT","voiceType":"voice-name","narratorEligible":true,'
                '"ssmlSupported":false,"apiVersions":["tencent-v1"]}]'),
        })

        report = MODULE.inspect(values, require_audiobook=True)

        self.assertFalse(report["ready"])
        self.assertTrue(any("incompatible Tencent" in error for error in report["errors"]))

    def test_accepts_tencent_hunyuan_analysis_with_reused_tencent_tts_credentials(self):
        values = self.audiobook_values()
        values.update({
            "AUDIOBOOK_TTS_DEFAULT_PROVIDER": "TENCENT",
            "AUDIOBOOK_TTS_SUPPORTED_PROVIDERS": "TENCENT",
            "TENCENT_TTS_SECRET_ID": "test-secret-id",
            "TENCENT_TTS_SECRET_KEY": "test-secret-key",
            "TENCENT_TTS_VOICE_TYPE": "1001",
            "AUDIOBOOK_ANALYSIS_PROVIDER": "TENCENT_HUNYUAN",
            "AUDIOBOOK_ANALYSIS_MODEL": "hunyuan-a13b",
            "AUDIOBOOK_VOICE_CATALOG_JSON": (
                '[{"provider":"TENCENT","voiceType":"1001","narratorEligible":true,'
                '"ssmlSupported":false,"apiVersions":["tencent-v1"]}]'),
        })
        values.pop("AUDIOBOOK_ANALYSIS_ENDPOINT")
        values.pop("AUDIOBOOK_ANALYSIS_API_KEY")

        report = MODULE.inspect(values, require_audiobook=True)

        self.assertTrue(report["ready"])
        self.assertEqual("TENCENT_HUNYUAN", report["audiobook"]["analysisProvider"])
        self.assertTrue(report["audiobook"]["analysisEndpointValid"])
        self.assertNotIn("test-secret-key", json.dumps(report))

    def audiobook_values(self):
        """Return a complete synthetic configuration whose sensitive values must remain unreported."""
        return {
            "AUDIOBOOK_GENERATION_ENABLED": "true",
            "AUDIOBOOK_MULTI_CHARACTER_ENABLED": "false",
            "AUDIOBOOK_MULTI_CHARACTER_QUALITY_GATE_APPROVED": "false",
            "VOLCENGINE_TTS_API_VERSION": "v3",
            "VOLCENGINE_TTS_ENDPOINT": "https://tts.example.test/api/v3/tts/unidirectional",
            "VOLCENGINE_TTS_API_KEY": "test-api-key",
            "VOLCENGINE_TTS_RESOURCE_ID": "seed-tts-2.0",
            "VOLCENGINE_TTS_VOICE_TYPE": "test-voice",
            "AUDIOBOOK_ANALYSIS_ENDPOINT": "https://analysis.example.test/v1/chat",
            "AUDIOBOOK_ANALYSIS_API_KEY": "test-analysis-key",
            "AUDIOBOOK_ANALYSIS_MODEL": "test-model",
            "AUDIOBOOK_NER_ENDPOINT": "",
            "AUDIOBOOK_NER_DEPLOYMENT_ID": "",
            "AUDIOBOOK_VOICE_CATALOG_JSON": (
                '[{"provider":"VOLCENGINE","voiceType":"test-voice","narratorEligible":true,'
                '"ssmlSupported":false,"apiVersions":["v3"],"resourceIds":["seed-tts-2.0"]}]'),
            "AUDIOBOOK_TTS_SUPPORTED_PROVIDERS": "VOLCENGINE",
            "STORAGE_INTERNAL_TOKEN": "test-storage-token",
            "READER_INTERNAL_TOKEN": "test-reader-token",
            "ASSET_REGISTRY_INTERNAL_TOKEN": "test-asset-token",
            "FFMPEG_BINARY": "ffmpeg",
            "FFPROBE_BINARY": "ffprobe",
            "AUDIOBOOK_MAXIMUM_CHARACTERS_PER_GENERATION": "1500000",
            "AUDIOBOOK_DAILY_CHARACTERS_PER_OWNER": "3000000",
            "AUDIOBOOK_VOICE_MINIMUM_CHARACTER_CONFIDENCE": "0.80",
            "AUDIOBOOK_VOICE_MINIMUM_SPEAKER_CONFIDENCE": "0.80",
        }

    @staticmethod
    def quality_attestation():
        gates = {key: True for key in (
            "characterRecall", "characterPrecision", "aliasF1", "relationshipF1",
            "explicitSpeakerAccuracy", "speakerAccuracy", "presentationAccuracy",
            "characterTypeAccuracy", "traitF1")}
        value = build_attestation(
            {"ready": True, "gates": gates, "metrics": {"bookCount": 10}},
            {"schemaVersion": "AUDIOBOOK_NER_VERIFICATION_V1", "deploymentId": "ner-prod-v1",
             "validated": True, "personExampleCount": 100, "precision": 0.90, "recall": 0.90},
            {"schemaVersion": "AUDIOBOOK_CROSS_CHAPTER_REVIEW_V1", "approved": True,
             "reviewedBookCount": 10, "reviewedCharacterCount": 20,
             "reviewedRelationshipCount": 20, "reviewedSpeechSegmentCount": 100})
        return json.dumps(value, separators=(",", ":"))


if __name__ == "__main__":
    unittest.main()
