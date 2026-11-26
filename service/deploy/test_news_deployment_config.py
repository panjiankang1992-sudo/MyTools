"""新闻配置门禁使用离线值，不读取本机生产环境。"""
import base64
import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import news_deployment_config as config
import verify_news_deployment as verify


def staged():
    result = dict(config.DEFAULTS)
    result.update({key: f"fixture-{index:02d}-" + "x" * 40 for index, key in enumerate(config.TOKENS)})
    result["NEWS_DB_PASSWORD"] = "fixture-database-password"
    result["MESSAGING_DESTINATION_ENCRYPTION_KEY"] = base64.b64encode(bytes(range(32))).decode()
    return result


def shadow():
    result = staged()
    for key in ("NEWS_COLLECTION_ENABLED", "NEWS_PIPELINE_ENABLED", "NEWS_SCHEDULING_ENABLED", "NEWS_RETENTION_ENABLED",
                "NEWS_PIPELINE_TIMER_ENABLED", "NEWS_SCHEDULING_TIMER_ENABLED", "NEWS_RETENTION_TIMER_ENABLED"):
        result[key] = "true"
    result.update({"NEWS_REGION_NODE_ID": "fixture-node", "TASK_NEWS_NODE_VALUE": "fixture-node",
                   "TASK_EXECUTOR_NODE_NAME": "fixture-node", "TASK_EXECUTOR_REQUIRE_PACKAGE_INDEX": "true",
                   "NEWS_REGION_MMDB_PATH": "/fixture/city.mmdb"})
    return result


class NewsConfigurationTest(unittest.TestCase):
    def test_capacity_limits_reject_zero_overflow_and_invalid_values(self):
        for key, (low, high) in config.CAPACITY_LIMITS.items():
            for value in ("0", "-1", str(high + 1), "invalid", ""):
                with self.subTest(key=key, value=value), self.assertRaises(config.NewsConfigurationError) as caught:
                    config.validate_configuration(staged() | {key: value}, "staged")
                self.assertEqual("NEWS_CONFIG_CAPACITY_INVALID", caught.exception.code)
            for value in (str(low), str(high)):
                self.assertTrue(config.validate_configuration(staged() | {key: value}, "staged")["configurationValid"])

    def test_fresh_environment_generator_satisfies_staged_gate_without_activating_news(self):
        import create_service_env
        manifest = create_service_env.load_manifest(Path(__file__).with_name("services.json"))
        values = create_service_env.values(manifest, "/data/downloads", "/data/storage", [], "managed")
        self.assertTrue(config.validate_configuration(values, "staged")["configurationValid"])

    def test_staged_then_shadow_then_delivery_require_distinct_conditions(self):
        self.assertFalse(config.validate_configuration(staged(), "staged")["liveAcceptance"])
        value = shadow()
        self.assertEqual("shadow", config.validate_configuration(value, "shadow")["stage"])
        with self.assertRaises(config.NewsConfigurationError):
            config.validate_configuration(value, "delivery")
        value.update({key: "true" for key in ("GATEWAY_NEWS_ROUTE_ENABLED", "NEWS_DELIVERY_ENABLED", "MESSAGING_OUTBOUND_ENABLED", "NEWS_DELIVERY_TIMER_ENABLED",
                                               "MESSAGING_OUTBOUND_RETENTION_ENABLED", "MESSAGING_OUTBOUND_RETENTION_TIMER_ENABLED")})
        value["NEWS_READER_URL"] = "https://news.example.test/news"
        self.assertTrue(config.validate_configuration(value, "delivery")["configurationValid"])
        with self.assertRaises(config.NewsConfigurationError):
            config.validate_configuration(value, "shadow")

    def test_missing_invalid_reused_and_legacy_shared_credentials_are_rejected(self):
        for change in ({"NEWS_JOB_TOKEN": ""}, {"NEWS_JOB_TOKEN": "short"},
                       {"NEWS_JOB_TOKEN": staged()["NEWS_ADMIN_TOKEN"]},
                       {"TASK_EXECUTOR_INTERNAL_TOKEN": staged()["NEWS_JOB_TOKEN"]},
                       {"MESSAGING_DESTINATION_ENCRYPTION_KEY": "invalid-base64"},
                       {"MESSAGING_REGISTRATION_MAIL_DELIVERY_ENCRYPTION_KEY": staged()["MESSAGING_DESTINATION_ENCRYPTION_KEY"]}):
            with self.subTest(keys=list(change)), self.assertRaises(config.NewsConfigurationError):
                config.validate_configuration(staged() | change, "staged")

    def test_aliases_and_scheduler_identity_must_agree(self):
        for change in ({"GATEWAY_NEWS_TOKEN": "different-fixture-secret"},
                       {"GATEWAY_NEWS_TOKEN": staged()["NEWS_INTERNAL_TOKEN"] + "/"},
                       {"GATEWAY_NEWS_URL": "https://wrong.example.test"},
                       {"TASK_SCHEDULER_URL": "https://other.example.test"}):
            with self.subTest(keys=list(change)), self.assertRaises(config.NewsConfigurationError):
                config.validate_configuration(staged() | change, "staged")
        config.validate_configuration(staged() | {"GATEWAY_NEWS_TOKEN": staged()["NEWS_INTERNAL_TOKEN"],
                                                  "GATEWAY_NEWS_URL": staged()["NEWS_SERVICE_URL"] + "/"}, "staged")

    def test_node_cluster_guard_provider_and_retention_checks(self):
        for change in ({"TASK_EXECUTOR_NODE_NAME": "other"}, {"TASK_NEWS_NODE_VALUE": "absent"},
                       {"TASK_NODE_REGISTRATION_POLICY_ENFORCE": "false"}, {"TASK_EXECUTOR_REQUIRE_NON_ROOT": "false"},
                       {"TASK_ALLOWED_EXECUTOR_CLUSTERS": "media,messaging"}, {"NEWS_REGION_MMDB_PATH": "relative"},
                       {"NEWS_REGION_PROVIDER": "invented"}, {"NEWS_REGION_ADDRESS_FAMILY": "invented"},
                       {"NEWS_METADATA_RETENTION_DAYS": "7"}, {"NEWS_RETENTION_BATCH_SIZE": "10000"},
                       {"NEWS_REGION_PROVIDER": "MAXMIND_WEB", "NEWS_REGION_MAXMIND_ACCOUNT": "12"}):
            with self.subTest(keys=list(change)), self.assertRaises(config.NewsConfigurationError):
                config.validate_configuration(shadow() | change, "shadow")
        config.validate_configuration(shadow() | {"NEWS_REGION_PROVIDER": "MAXMIND_WEB", "NEWS_REGION_MAXMIND_ACCOUNT": "12",
                                                  "NEWS_REGION_MAXMIND_LICENSE": "fixture-license"}, "shadow")
        config.validate_configuration(shadow() | {"NEWS_REGION_PROVIDER": "IPWHOIS", "NEWS_REGION_MMDB_PATH": "",
                                                  "NEWS_REGION_MAXMIND_ACCOUNT": "", "NEWS_REGION_MAXMIND_LICENSE": ""}, "shadow")

    def test_staged_never_accepts_accidentally_enabled_features_or_typo(self):
        for change in ({"NEWS_COLLECTION_ENABLED": "true"}, {"NEWS_DELIVERY_ENABLED": "yes"},
                       {"NEWS_ZONE": "Invalid/Zone"}, {"NEWS_CUTOFF_TIME": "25:00"}):
            with self.assertRaises(config.NewsConfigurationError):
                config.validate_configuration(staged() | change, "staged")

    def test_endpoint_does_not_allow_credentials_redirect_shapes_or_public_http(self):
        for value in ("http://external.example.test", "https://secret@example.test", "https://@example.test", "https://example.test?",
                      "https://example.test#", "https://example.test/secret", "https://example.test\\@localhost", "https://example.test:0"):
            with self.subTest(value=value), self.assertRaises(config.NewsConfigurationError):
                config.endpoint(value, "NEWS_SERVICE_URL")
        self.assertEqual("http://[::1]:23350", config.endpoint("http://[::1]:23350", "NEWS_SERVICE_URL"))
        with self.assertRaises(config.NewsConfigurationError):
            config.endpoint("http://127.0.0.1/news", "NEWS_READER_URL", public=True)

    def test_private_file_duplicate_keys_and_symlinks_are_rejected_without_echo(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "private.env"
            for content in (b"SECRET=hidden\nSECRET=hidden\n", b"bad sensitive line", b"TOKEN='unterminated"):
                path.write_bytes(content);path.chmod(0o600)
                with self.assertRaises(config.NewsConfigurationError) as caught:
                    config.read_private(path)
                self.assertNotIn("hidden", json.dumps(caught.exception.public()))
            path.write_bytes(b"TOKEN='literal$(not-run)'\n");path.chmod(0o600)
            original = config.read_private(path)
            self.assertEqual("literal$(not-run)", original.values["TOKEN"])
            self.assertNotIn("literal", repr(original))
            link = Path(directory) / "link.env";link.symlink_to(path)
            with self.assertRaises(config.NewsConfigurationError):
                config.read_private(link)
            path.chmod(0o644)
            with self.assertRaises(config.NewsConfigurationError):
                config.read_private(path)

    def test_cli_outputs_only_stage_metadata_or_sanitized_error(self):
        stream = io.StringIO()
        with patch.dict(os.environ, staged(), clear=True), contextlib.redirect_stdout(stream):
            self.assertEqual(0, verify.main(["--from-environment"]))
        result = json.loads(stream.getvalue())
        self.assertTrue(result["configurationValid"])
        self.assertFalse(result["releaseChecked"])
        self.assertFalse(result["liveAcceptance"])
        stream = io.StringIO()
        with patch.dict(os.environ, staged() | {"NEWS_SERVICE_URL": "http://private-secret.example"}, clear=True), contextlib.redirect_stdout(stream):
            self.assertEqual(2, verify.main(["--from-environment"]))
        self.assertNotIn("private-secret", stream.getvalue())
        self.assertNotIn("fixture-database", stream.getvalue())
