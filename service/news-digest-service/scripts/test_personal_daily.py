"""验证分类隔离、有限恢复及未知回执不重发。"""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import personal_daily as daily


class PersonalDailyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.config_path = self.root / "config.json"
        self.config = {"enabled": True, "ownerId": "123", "stateDirectory": str(self.root / "state"),
                       "nodeId": "personal-node", "zone": "Asia/Shanghai", "addressFamily": "IPV6",
                       "recipients": ["test@example.com"]}
        self.config_path.write_text(json.dumps(self.config))
        (self.root / "sources.json").write_text("[]")
        (self.root / "news-digest-service.jar").write_bytes(b"test-runtime")
        self.directory = daily.current_directory(self.config)
        self.summary = {"sections": [{"category": category.upper(), "job": str(self.directory / "report" / category / "job.json")}
                                      for category in ("tech", "world", "domestic", "local")], "failedSections": []}
        for section in self.summary["sections"]:
            job = Path(section["job"])
            job.parent.mkdir(parents=True)
            job.write_text(json.dumps({"job_id": "test-" + job.parent.name}))
        self.receipts = {}
        self.sends = []
        self.failures = {}

    def collect(self, runtime, config, directory):
        source = directory / "input"
        source.mkdir()
        (source / "report-input.json").write_text(json.dumps({"sections": [{"items": [{"rank": 1}]}]}))

    def mail(self, runtime, job, action):
        category = job.parent.name
        if action == "--send":
            self.sends.append(category)
            failure = self.failures.get(category)
            self.receipts[category] = {"job_id": "test-" + category, "status": "failed" if failure else "accepted",
                                       "smtp_code": failure or 250}
            if failure:
                raise ValueError("DAILY_MAIL_ACTION_FAILED")
        return self.receipts.get(category, {"job_id": "test-" + category, "status": "prepared"})

    def test_disabled_send_stops_before_collection(self):
        self.config["enabled"] = False
        self.config_path.write_text(json.dumps(self.config))
        with patch.object(daily, "collect") as collect:
            with self.assertRaisesRegex(ValueError, "DAILY_SENDING_DISABLED"):
                daily.run(self.config_path, self.root, True)
            collect.assert_not_called()

    def test_partial_analysis_sends_other_categories_before_recovery(self):
        partial = {"sections": [s for s in self.summary["sections"] if s["category"] != "WORLD"],
                   "failedSections": [{"category": "WORLD", "code": "DSH_EVENT_SET_INVALID", "retryable": True}]}
        with patch.object(daily, "collect", side_effect=self.collect) as collect, \
                patch.object(daily, "generate_report", side_effect=[partial, self.summary]) as generate, \
                patch.object(daily, "mail_action", side_effect=self.mail):
            result = daily.run(self.config_path, self.root, True)
        self.assertEqual(self.sends, ["tech", "domestic", "local", "world"])
        self.assertTrue(result["complete"])
        self.assertEqual(generate.call_count, 2)
        collect.assert_called_once()

    def test_transient_send_failure_does_not_block_later_categories_or_repeat_accepted(self):
        self.failures["world"] = 451

        def mail(runtime, job, action):
            try:
                return self.mail(runtime, job, action)
            finally:
                if job.parent.name == "world" and action == "--send":
                    self.failures.pop("world", None)

        with patch.object(daily, "collect", side_effect=self.collect), \
                patch.object(daily, "generate_report", return_value=self.summary), \
                patch.object(daily, "mail_action", side_effect=mail):
            result = daily.run(self.config_path, self.root, True)
        self.assertTrue(result["complete"])
        self.assertEqual(self.sends, ["tech", "world", "domestic", "local", "world"])

    def test_failed_recovery_is_bounded_and_later_run_reuses_input_and_jobs(self):
        self.failures["world"] = 451
        with patch.object(daily, "collect", side_effect=self.collect) as collect, \
                patch.object(daily, "generate_report", return_value=self.summary) as generate, \
                patch.object(daily, "mail_action", side_effect=self.mail):
            with self.assertRaisesRegex(ValueError, "DAILY_INCOMPLETE"):
                daily.run(self.config_path, self.root, True)
            collect.assert_called_once()
            self.assertEqual(generate.call_count, 2)
        saved = json.loads((self.directory / "delivery-summary.json").read_text())
        self.assertFalse(saved["complete"])
        self.assertEqual(len(saved["receipts"]), 4)
        self.assertEqual(self.sends.count("world"), 2)
        self.failures.clear()
        self.sends.clear()
        with patch.object(daily, "collect", side_effect=AssertionError("Do not recollect")), \
                patch.object(daily, "generate_report", return_value=self.summary), \
                patch.object(daily, "mail_action", side_effect=self.mail):
            daily.run(self.config_path, self.root, True)
        self.assertEqual(self.sends, ["world"])
        self.config["recipients"] = ["changed@example.com"]
        self.config_path.write_text(json.dumps(self.config))
        with self.assertRaisesRegex(ValueError, "DAILY_CONFIGURATION_CHANGED"):
            daily.run(self.config_path, self.root)

    def test_unknown_and_inflight_are_not_resent_and_do_not_block_other_categories(self):
        for status in ("unknown", "inflight"):
            with self.subTest(status=status):
                self.receipts = {"world": {"job_id": "test-world", "status": status}}
                self.sends.clear()
                with patch.object(daily, "collect", side_effect=self.collect), \
                        patch.object(daily, "generate_report", return_value=self.summary) as generate, \
                        patch.object(daily, "mail_action", side_effect=self.mail):
                    with self.assertRaisesRegex(ValueError, "DAILY_INCOMPLETE"):
                        daily.run(self.config_path, self.root, True)
                    generate.assert_called_once()
                self.assertEqual(self.sends, ["tech", "domestic", "local"])

    def test_permanent_smtp_rejection_is_not_automatically_retried(self):
        self.failures["world"] = 550
        with patch.object(daily, "collect", side_effect=self.collect), \
                patch.object(daily, "generate_report", return_value=self.summary) as generate, \
                patch.object(daily, "mail_action", side_effect=self.mail):
            with self.assertRaisesRegex(ValueError, "DAILY_INCOMPLETE"):
                daily.run(self.config_path, self.root, True)
            generate.assert_called_once()
        self.assertEqual(self.sends, ["tech", "world", "domestic", "local"])

    def test_disabling_during_send_stops_next_category(self):
        def mail(runtime, job, action):
            result = self.mail(runtime, job, action)
            if action == "--send":
                self.config["enabled"] = False
                self.config_path.write_text(json.dumps(self.config))
            return result

        with patch.object(daily, "collect", side_effect=self.collect), \
                patch.object(daily, "generate_report", return_value=self.summary), \
                patch.object(daily, "mail_action", side_effect=mail):
            with self.assertRaisesRegex(ValueError, "DAILY_SENDING_DISABLED"):
                daily.run(self.config_path, self.root, True)
        self.assertEqual(self.sends, ["tech"])

    def test_preview_never_sends_or_retries_partial_report(self):
        partial = {"sections": [], "failedSections": [{"category": "WORLD", "retryable": True}]}
        with patch.object(daily, "collect", side_effect=self.collect), \
                patch.object(daily, "generate_report", return_value=partial) as generate, \
                patch.object(daily, "mail_action") as mail:
            result = daily.run(self.config_path, self.root)
        self.assertEqual(result["status"], "prepared_partial")
        generate.assert_called_once()
        mail.assert_not_called()

    def test_rss_failure_does_not_block_email(self):
        def publish(*args):
            self.assertEqual(len(self.sends), 4)
            return {"status": "failed"}

        with patch.object(daily, "collect", side_effect=self.collect), \
                patch.object(daily, "generate_report", return_value=self.summary), \
                patch.object(daily, "publish_report", side_effect=publish), \
                patch.object(daily, "mail_action", side_effect=self.mail):
            result = daily.run(self.config_path, self.root, True, True)
        self.assertEqual(len(self.sends), 4)
        self.assertEqual(result["rss"]["status"], "failed")
        self.assertTrue(result["complete"])

    def test_rss_only_needs_no_recipients_and_never_sends(self):
        self.config.update(mailEnabled=False, recipients=[])
        self.config_path.write_text(json.dumps(self.config))
        with patch.object(daily, "collect", side_effect=self.collect), \
                patch.object(daily, "generate_report", return_value=self.summary) as generate, \
                patch.object(daily, "publish_report", return_value={"status": "published"}), \
                patch.object(daily, "mail_action") as mail:
            result = daily.run(self.config_path, self.root, False, True)
        self.assertEqual(generate.call_args.args[2], [])
        self.assertEqual(result["rss"]["status"], "published")
        mail.assert_not_called()

    def test_rss_export_does_not_regenerate_daily_report_or_send(self):
        self.config["rssConfig"] = str(self.root / "rss.json")
        response = type("Result", (), {"returncode": 0, "stdout": '{"status":"published"}'})()
        with patch.object(daily.subprocess, "run", return_value=response) as command, \
                patch.object(daily, "collect") as collect, patch.object(daily, "generate_report") as analyze, \
                patch.object(daily, "mail_action") as mail:
            self.assertEqual(daily.publish_report(self.config, self.root, self.directory)["status"], "published")
        self.assertIn("publish_rss.py", " ".join(command.call_args.args[0]))
        collect.assert_not_called(); analyze.assert_not_called(); mail.assert_not_called()

    def test_preview_does_not_publish_implicitly(self):
        with patch.object(daily, "collect", side_effect=self.collect), \
                patch.object(daily, "generate_report", return_value=self.summary), \
                patch.object(daily, "publish_report") as publish:
            daily.run(self.config_path, self.root)
        publish.assert_not_called()


if __name__ == "__main__":
    unittest.main()
