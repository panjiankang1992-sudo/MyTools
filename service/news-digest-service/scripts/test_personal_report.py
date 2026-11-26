"""验证模型输出边界、分类图文正文和离线 MIME 校验。"""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import personal_report as report


class PersonalReportTest(unittest.TestCase):
    def test_rss_only_report_resume_has_no_email_jobs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); source = root / "input.json"; output = root / "report"
            source.write_text(json.dumps({"cutoff": "2026-11-01T00:00:00Z", "zone": "Asia/Shanghai",
                                         "region": {"regionCode": "CN-JS", "nodeId": "private-node"},
                                         "sections": [dict(self.section, category=c) for c in report.CATEGORIES]}))
            clusters = {"groups": [{"members": [c + ":1"], "representative": c + ":1", "mergeReason": ""} for c in report.CATEGORIES]}
            with patch.object(report, "create_infographic", side_effect=lambda item, row, dest: dest.write_bytes(b'\x89PNG\r\n\x1a\nfixture')), \
                    patch.object(report, "analyze_material", return_value=clusters), \
                    patch.object(report, "analyze", return_value=self.selection), \
                    patch.object(report, "validate_mail_job", side_effect=AssertionError("No mail validation")):
                report.generate_report(source, output, [], allow_partial=True)
                report.generate_report(source, output, [], resume=True, allow_partial=True)
            self.assertFalse(list(output.rglob("job.json")))
            region = json.loads((output / "daily-report.json").read_text())["region"]
            self.assertEqual(region["regionCode"], "CN-JS")
            self.assertNotIn("nodeId", region)

    def setUp(self):
        self.item = {"rank": 1, "eventId": "event-1", "title": "Source headline", "url": "https://example.com/story",
                     "publishedAt": "2026-10-19T00:00:00Z", "sources": ["source"], "evidence": "Source evidence.",
                     "evidenceUrl": "https://example.com/story", "detailAvailable": True}
        self.section = {"category": "TECH", "items": [self.item]}
        self.row = {"eventId": "event-1", "title": "Translated title", "summary": "A sourced summary.",
                    "points": ["Verified point"], "limitation": "", "rank": 1, "memberRanks": [1],
                    "representativeRank": 1, "mergeReason": "", "rankingReason": "Recent and relevant"}
        self.selection = {"items": [self.row], "excludedRanks": []}

    def test_model_cannot_drop_replace_or_duplicate_events(self):
        self.assertEqual(report.validate_analysis(self.selection, self.section), self.selection)
        for rows in ([], [self.row, self.row], [dict(self.row, memberRanks=[99])]):
            with self.assertRaises(ValueError):
                report.validate_analysis({"items": rows, "excludedRanks": []}, self.section)

    def test_rank_output_is_bound_to_original_event_and_order(self):
        row = dict(self.row, rank=1, eventId="model-supplied-id")
        result = report.validate_analysis({"items": [row], "excludedRanks": []}, self.section)
        self.assertEqual(result["items"][0]["eventId"], self.item["eventId"])
        with self.assertRaises(ValueError):
            report.validate_analysis({"items": [dict(row, rank=2)], "excludedRanks": []}, self.section)

    def test_invalid_dsh_output_retries_once_with_shared_deadline(self):
        good = json.dumps(self.selection)
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(report, "invoke_dsh", side_effect=["invalid-json", good]) as invoke, \
                patch.object(report.time, "monotonic", side_effect=[100, 101, 107]):
            rows = report.analyze(self.section, "dsh", Path("patch.yml"), 10, directory)
            self.assertEqual(rows["items"][0]["eventId"], "event-1")
            self.assertEqual([call.args[3] for call in invoke.call_args_list], [9, 3])
            self.assertTrue((Path(directory) / "tech-response-attempt-1.txt").exists())
            self.assertIn("return_one_complete_json_object", invoke.call_args_list[1].args[0])
            self.assertIn("Untrusted previous draft", invoke.call_args_list[1].args[0])

    def test_historical_date_category_and_retry_are_in_session_titles(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(report, 'invoke_dsh', side_effect=['invalid', json.dumps(self.selection)]) as invoke:
            report.analyze(self.section, 'dsh', Path('patch.yml'), 30, directory, '2026-11-01')
            self.assertEqual(invoke.call_args_list[0].args[-1], '2026-11-01 \u6280\u672f Top10')
            self.assertEqual(invoke.call_args_list[1].args[-1], '2026-11-01 \u6280\u672f Top10 \u00b7 \u4fee\u590d2')
        self.assertEqual(report.report_day({'cutoff': '2026-11-01T23:30:00Z', 'zone': 'Asia/Shanghai'}), '2026-11-02')

    def test_grouping_failure_prevents_headless_session_creation(self):
        with patch.object(report.socket, 'socket', side_effect=OSError('unavailable')), \
                patch.object(report.subprocess, 'Popen') as process:
            with self.assertRaisesRegex(ValueError, 'DSH_NEWS_GROUP_UNAVAILABLE'):
                report.invoke_dsh('prompt', 'dsh', Path('patch.yml'), 30, Path('.'), '2026-11-14 test')
            process.assert_not_called()

    def test_repair_feedback_identifies_empty_members_and_preserves_old_attempts(self):
        invalid = {"items": [dict(self.row, memberRanks=[])], "excludedRanks": []}
        with tempfile.TemporaryDirectory() as directory:
            saved = Path(directory) / "tech-response-attempt-1.txt"
            original = json.dumps(invalid)
            saved.write_text(original)
            with patch.object(report, "invoke_dsh", return_value=json.dumps(self.selection)) as invoke:
                report.analyze(self.section, "dsh", Path("patch.yml"), 30, directory)
            prompt = invoke.call_args.args[0]
            self.assertIn("memberRanks_must_be_nonempty_array", prompt)
            self.assertIn('"unassignedCandidateRanks": [1]', prompt)
            self.assertEqual(saved.read_text(), original)
            self.assertTrue((Path(directory) / "tech-response-attempt-2.txt").is_file())

    def test_partial_report_continues_after_one_failed_category_and_resumes_only_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); source = root / "input.json"; output = root / "report"
            source.write_text(json.dumps({"cutoff": "2026-10-24T00:00:00Z", "zone": "Asia/Shanghai",
                                         "sections": [dict(self.section, category=category) for category in list(report.CATEGORIES)[:4]]}))
            clusters = {"groups": [{"members": [category + ":1"], "representative": category + ":1", "mergeReason": ""}
                                   for category in list(report.CATEGORIES)[:4]]}
            image = lambda item, row, destination: destination.write_bytes(b'\x89PNG\r\n\x1a\nfixture')
            with patch.object(report, "create_infographic", side_effect=image), \
                    patch.object(report, "analyze_material", return_value=clusters), \
                    patch.object(report, "analyze", side_effect=[self.selection, ValueError("DSH_EVENT_SET_INVALID"), self.selection, self.selection]):
                summary = report.generate_report(source, output, ["test@example.com"], allow_partial=True)
            self.assertEqual([row["category"] for row in summary["sections"]], ["TECH", "DOMESTIC", "LOCAL"])
            self.assertEqual(summary["failedSections"][0]["category"], "WORLD")
            self.assertFalse(summary["complete"])
            self.assertFalse((output / "world/job.json").exists())
            before = (output / "tech/job.json").read_bytes()
            with patch.object(report, "create_infographic", side_effect=image), \
                    patch.object(report, "analyze", return_value=self.selection) as analyze:
                summary = report.generate_report(source, output, ["test@example.com"], resume=True, allow_partial=True)
            self.assertTrue(summary["complete"])
            analyze.assert_called_once()
            self.assertEqual(analyze.call_args.args[0]["category"], "WORLD")
            self.assertEqual(before, (output / "tech/job.json").read_bytes())

    def test_format_retries_stop_and_operational_failures_do_not_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(report, "invoke_dsh", return_value="invalid") as invoke:
                with self.assertRaisesRegex(ValueError, "DSH_JSON_INVALID"):
                    report.analyze(self.section, "dsh", Path("patch.yml"), 30, directory)
                self.assertEqual(invoke.call_count, 2)
                self.assertFalse((Path(directory) / "tech-response.txt").exists())
            with patch.object(report, "invoke_dsh", side_effect=ValueError("DSH_CREDENTIAL_MISSING")) as invoke:
                with self.assertRaisesRegex(ValueError, "DSH_CREDENTIAL_MISSING"):
                    report.analyze(self.section, "dsh", Path("patch.yml"), 30, directory)
                invoke.assert_called_once()

    def test_saved_response_with_known_typo_recovers_without_model_call(self):
        candidate = dict(self.row)
        candidate["memberManks"] = candidate.pop("memberRanks")
        raw = json.dumps({"items": [candidate], "excludedRanks": []})
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tech-response-attempt-1.txt"
            path.write_text(raw)
            with patch.object(report, "invoke_dsh", side_effect=AssertionError("No model call expected")):
                result = report.analyze(self.section, "dsh", Path("patch.yml"), 30, directory)
            self.assertEqual(result["items"][0]["memberRanks"], [1])
            self.assertEqual(path.read_text(), raw)
            self.assertEqual((Path(directory) / "tech-response.txt").read_text(), raw)

    def test_saved_response_with_missing_candidates_is_regenerated(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "tech-response-attempt-1.txt").write_text(json.dumps({"items": [], "excludedRanks": []}))
            with patch.object(report, "invoke_dsh", return_value=json.dumps(self.selection)) as invoke:
                result = report.analyze(self.section, "dsh", Path("patch.yml"), 30, directory)
            invoke.assert_called_once()
            self.assertEqual(result, self.selection)

    def test_resume_keeps_completed_analysis_and_rejects_changed_recipients(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); source = root / "input.json"; output = root / "report"
            source.write_text(json.dumps({"cutoff": "2026-10-19T00:00:00Z", "zone": "Asia/Shanghai",
                                         "sections": [dict(self.section, category=category) for category in list(report.CATEGORIES)[:4]]}))
            clusters = {"groups": [{"members": [category + ":1"], "representative": category + ":1", "mergeReason": ""}
                                   for category in list(report.CATEGORIES)[:4]]}
            recipients = ["test@example.com"]
            image = lambda item, row, destination: destination.write_bytes(b'\x89PNG\r\n\x1a\nfixture')
            with patch.object(report, "create_infographic", side_effect=image), \
                    patch.object(report, "analyze_material", return_value=clusters), \
                    patch.object(report, "analyze", side_effect=[self.selection, ValueError("DSH_FAILED")]):
                with self.assertRaisesRegex(ValueError, "DSH_FAILED"):
                    report.generate_report(source, output, recipients)
            job_before = (output / "tech" / "job.json").read_bytes()
            with patch.object(report, "create_infographic", side_effect=image), \
                    patch.object(report, "analyze", return_value=self.selection) as analyze:
                summary = report.generate_report(source, output, recipients, resume=True)
                self.assertEqual(analyze.call_count, 3)
                self.assertEqual(len(summary["sections"]), 4)
            self.assertEqual(job_before, (output / "tech" / "job.json").read_bytes())
            with patch.object(report, "analyze", side_effect=AssertionError("No model call expected")):
                report.generate_report(source, output, recipients, resume=True)
                with self.assertRaisesRegex(ValueError, "REPORT_RESUME_CONTEXT_MISMATCH"):
                    report.generate_report(source, output, ["different@example.com"], resume=True)
                with patch.object(report, "skill_hash", return_value="changed-skill"):
                    with self.assertRaisesRegex(ValueError, "REPORT_RESUME_CONTEXT_MISMATCH"):
                        report.generate_report(source, output, recipients, resume=True)

    def test_legacy_top_twenty_renders_only_top_ten_with_merged_links(self):
        items = [dict(self.item, rank=rank, eventId="event-" + str(rank), image={"caption": "Source"})
                 for rank in range(1, 21)]
        items[0]["sourceLinks"] = [self.item["url"], "https://second.example.com/story"]
        rows = [dict(self.row, rank=rank, eventId="event-" + str(rank), summary="Brief " + str(rank))
                for rank in range(1, 21)]
        paths = {item["eventId"]: "cid:" + item["eventId"] for item in items}
        _, plain, rendered = report.render(dict(self.section, items=items), rows, "2026-10-19", paths)
        self.assertEqual(rendered.count("<img "), 10)
        self.assertIn("Brief 10", plain)
        self.assertNotIn("Brief 11", plain)
        self.assertNotIn("Brief 20", plain)
        self.assertIn("https://second.example.com/story", rendered)
        self.assertNotIn("src='cid:event-11'", rendered)

    def test_html_escapes_model_content_and_keeps_original_image_and_links(self):
        self.item["image"] = {"caption": "A source photo"}
        row = dict(self.row, summary="<script>alert(1)</script>")
        _, _, rendered = report.render(self.section, [row], "2026-10-19", {"event-1": "cid:tech-1@news.mytools"})
        self.assertIn("src='cid:tech-1@news.mytools'", rendered)
        self.assertIn("href='https://example.com/story'", rendered)
        self.assertNotIn("<script>", rendered)
        self.assertIn("&lt;script&gt;", rendered)

    def test_source_failures_are_visible_in_the_report(self):
        coverage = [{"source": "example", "name": "Source <failed>", "status": "FAILED"}]
        _, plain, rendered = report.render(self.section, [self.row], "2026-10-19", {}, collection=coverage)
        self.assertIn("Source <failed>", plain)
        self.assertIn("Source &lt;failed&gt;", rendered)

    def test_missing_body_does_not_expose_internal_flags_or_deny_existing_link(self):
        self.item["detailAvailable"] = False
        self.item["evidence"] = ""
        row = dict(self.row, summary="detailAvailable=false; no source links exist", limitation="internal flag detailAvailable")
        _, plain, rendered = report.render(self.section, [row], "2026-10-19", {})
        self.assertNotIn("detailAvailable", plain + rendered)
        self.assertNotIn("no source links exist", plain + rendered)
        self.assertIn("https://example.com/story", rendered)
        self.assertEqual(report.public_row(self.item, row)["summary"], report.public_row(self.item, self.row)["summary"])

    def test_short_evidence_is_retained_without_claiming_only_a_title_was_collected(self):
        self.item.update(detailAvailable=False, evidence="A short confirmed bulletin & context.")
        row = dict(self.row, summary="Model expansion that was not supplied.")
        _, plain, rendered = report.render(self.section, [row], "2026-10-19", {})
        self.assertIn(self.item["evidence"], plain)
        self.assertIn("A short confirmed bulletin &amp; context.", rendered)
        self.assertNotIn(row["summary"], plain + rendered)
        self.assertEqual(report.public_row(self.item, row)["summary"], self.item["evidence"])

    def test_mail_validator_checks_html_and_inline_image_hash_without_smtp(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = b'\x89PNG\r\n\x1a\n' + b'fixture'
            (root / "photo.png").write_bytes(data)
            (root / "body.txt").write_text("Daily report")
            (root / "body.html").write_text('<img src="cid:photo@news">')
            job = {"job_id": "news-validation-fixture", "to": ["test@example.com"], "subject": "Report", "body_file": "body.txt",
                   "html_file": "body.html", "inline_images": [{"path": "photo.png", "cid": "photo@news", "mime": "image/png", "sha256": report.digest(data)}]}
            (root / "job.json").write_text(json.dumps(job))
            command = [sys.executable, str(Path(__file__).with_name("send_personal_mail.py")), str(root / "job.json")]
            result = subprocess.run(command, capture_output=True, text=True, check=True)
            self.assertEqual(json.loads(result.stdout)["inline_images"], 1)
            fingerprint = json.loads(result.stdout)["fingerprint"]
            receipt = root / (job["job_id"] + ".receipt.json")
            for status in ("accepted", "unknown", "inflight"):
                receipt.write_text(json.dumps({"job_id": job["job_id"], "fingerprint": fingerprint, "status": status}))
                current = subprocess.run([*command, "--status"], capture_output=True, text=True, check=True)
                self.assertEqual(json.loads(current.stdout)["status"], status)
                attempt = subprocess.run([*command, "--send"], capture_output=True, text=True)
                self.assertEqual(attempt.returncode, 0 if status == "accepted" else 1)
                if status == "accepted":
                    self.assertEqual(json.loads(attempt.stdout)["status"], "skipped_already_accepted")
                self.assertEqual(json.loads(receipt.read_text())["status"], status)
            (root / "photo.png").write_bytes(data + b'changed')
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)


if __name__ == "__main__":
    unittest.main()
