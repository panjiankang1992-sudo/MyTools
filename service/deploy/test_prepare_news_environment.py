"""增量环境更新的幂等、权限与竞争保护。"""
import contextlib
import fcntl
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import prepare_news_environment as prepare
import news_deployment_config as config


class PrepareNewsEnvironmentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "services.env"
        self.original = b"# Existing settings must remain exact\nSMTP_PASSWORD='fixture-original-secret'\nNEWS_ADMIN_TOKEN=existing-admin-token\nNEWS_JOB_TOKEN=\n"
        self.path.write_bytes(self.original);self.path.chmod(0o600)

    def test_dry_run_generates_no_secrets_and_changes_no_files(self):
        stream = io.StringIO()
        with patch.object(prepare.secrets, "token_urlsafe") as generator, contextlib.redirect_stdout(stream):
            self.assertEqual(0, prepare.main(["--env-file", str(self.path)]))
        generator.assert_not_called()
        self.assertEqual(self.original, self.path.read_bytes())
        self.assertEqual([self.path], list(self.path.parent.iterdir()))
        self.assertNotIn("fixture-original-secret", stream.getvalue())
        self.assertNotIn("existing-admin-token", stream.getvalue())
        self.assertIn("NEWS_JOB_TOKEN", stream.getvalue())

    def test_apply_preserves_values_mode_owner_backup_and_second_run(self):
        before = self.path.stat()
        result = prepare.apply(self.path)
        self.assertTrue(result["changed"])
        self.assertTrue(self.path.read_bytes().startswith(self.original))
        saved = config.read_private(self.path)
        self.assertEqual("existing-admin-token", saved.values["NEWS_ADMIN_TOKEN"])
        self.assertEqual("", saved.values["NEWS_JOB_TOKEN"])
        self.assertEqual((before.st_uid, before.st_gid), (saved.uid, saved.gid))
        backup = list(self.path.parent.glob("*.news-backup-*"))
        self.assertEqual(1, len(backup));self.assertEqual(self.original, config.read_private(backup[0]).raw)
        generated = [saved.values[key] for key in config.TOKENS if key not in {"NEWS_ADMIN_TOKEN", "NEWS_JOB_TOKEN"}]
        self.assertEqual(len(generated), len(set(generated)))
        self.assertTrue(all(len(value) >= 32 for value in generated))
        for key in config.MAIN_FLAGS + config.TIMER_FLAGS:
            self.assertEqual("false", saved.values[key])
        original_bytes = self.path.read_bytes()
        with patch.object(prepare.secrets, "token_urlsafe") as generator:
            again = prepare.apply(self.path)
        generator.assert_not_called();self.assertFalse(again["changed"])
        self.assertEqual(original_bytes, self.path.read_bytes())
        self.assertEqual(1, len(list(self.path.parent.glob("*.news-backup-*"))))

    def test_existing_enabled_service_is_not_silently_reconfigured(self):
        self.path.write_bytes(self.original + b"NEWS_DELIVERY_ENABLED=true\n")
        original = self.path.read_bytes()
        with self.assertRaises(config.NewsConfigurationError) as caught:
            prepare.apply(self.path)
        self.assertEqual("NEWS_CONFIG_UPGRADE_REQUIRES_DISABLED", caught.exception.code)
        self.assertEqual(original, self.path.read_bytes())

    def test_append_limit_keeps_original_readable_without_creating_backup(self):
        original = b"#" + b"x" * (config.MAX_ENV_BYTES - 2) + b"\n"
        self.path.write_bytes(original)
        with self.assertRaises(config.NewsConfigurationError) as caught:
            prepare.apply(self.path)
        self.assertEqual("NEWS_CONFIG_FILE_TOO_LARGE", caught.exception.code)
        self.assertEqual(original, config.read_private(self.path).raw)
        self.assertFalse(list(self.path.parent.glob("*.news-backup-*")))
        self.assertFalse(list(self.path.parent.glob(".news-env-*")))

    def test_competing_writer_is_not_overwritten(self):
        real = prepare.private_write
        updated = self.original + b"OTHER_SETTING=concurrent-change\n"
        def interleave(descriptor, data, original):
            real(descriptor, data, original)
            self.path.write_bytes(updated)
        with patch.object(prepare, "private_write", side_effect=interleave), self.assertRaises(config.NewsConfigurationError) as caught:
            prepare.apply(self.path)
        self.assertEqual("NEWS_CONFIG_CHANGED", caught.exception.code)
        self.assertEqual(updated, self.path.read_bytes())
        self.assertFalse(list(self.path.parent.glob(".news-env-*")))

    def test_failure_before_replace_keeps_original_and_private_backup(self):
        with patch.object(prepare.os, "replace", side_effect=OSError("fixture-secret-error")), self.assertRaises(config.NewsConfigurationError) as caught:
            prepare.apply(self.path)
        self.assertNotIn("fixture-secret", str(caught.exception))
        self.assertEqual(self.original, self.path.read_bytes())
        backup = next(self.path.parent.glob("*.news-backup-*"))
        self.assertEqual(self.original, config.read_private(backup).raw)
        self.assertFalse(list(self.path.parent.glob(".news-env-*")))

    def test_live_lock_and_symlink_lock_fail_without_wait_or_write(self):
        lock = self.path.with_name(self.path.name + ".news-upgrade.lock")
        descriptor = os.open(lock, os.O_CREAT | os.O_RDWR, 0o600)
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaises(config.NewsConfigurationError) as caught:
                prepare.apply(self.path)
            self.assertEqual("NEWS_CONFIG_UPGRADE_BUSY", caught.exception.code)
        finally:
            os.close(descriptor)
        lock.unlink();lock.symlink_to(self.path)
        with self.assertRaises(config.NewsConfigurationError):
            prepare.apply(self.path)
        self.assertEqual(self.original, self.path.read_bytes())
