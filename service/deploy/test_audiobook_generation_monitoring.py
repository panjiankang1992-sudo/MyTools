import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
RULES = ROOT / "service/deploy/monitoring/audiobook-generation-alerts.yml"
DASHBOARD = ROOT / "service/deploy/monitoring/audiobook-generation-dashboard.json"
RUNBOOK = ROOT / "docs/runbooks/audiobook-generation-alerts.md"


class AudiobookGenerationMonitoringTest(unittest.TestCase):
    def test_alerts_only_use_exported_low_cardinality_metrics_and_runbooks(self):
        rules = RULES.read_text(encoding="utf-8")
        runbook = RUNBOOK.read_text(encoding="utf-8")
        alerts = re.findall(r"^\s+- alert: ([A-Za-z][A-Za-z0-9]+)$", rules, re.MULTILINE)

        self.assertEqual(3, len(alerts))
        self.assertEqual(len(alerts), len(set(alerts)))
        for alert in alerts:
            self.assertIn(f"## {alert}", runbook)
            self.assertRegex(rules, rf"(?m)runbook_url: .*#{alert.lower()}$")
        for metric in (
            "reader_audiobook_generation_failed_total",
            "reader_audiobook_generation_rejected_total",
            "reader_audiobook_stage_duration_seconds_bucket",
        ):
            self.assertIn(metric, rules)
        self.assertNotRegex(rules, r"(?:owner|book|generation|voice)[Ii]d\s*=")
        self.assertRegex(rules, r'application="reader-service"')

    def test_dashboard_is_portable_and_excludes_sensitive_labels(self):
        dashboard = json.loads(DASHBOARD.read_text(encoding="utf-8"))

        self.assertEqual("mytools-audiobook-generation", dashboard["uid"])
        self.assertEqual("30s", dashboard["refresh"])
        self.assertIn("DS_PROMETHEUS", {value["name"] for value in dashboard["__inputs"]})
        panels = dashboard["panels"]
        self.assertEqual(4, len(panels))
        self.assertEqual(len(panels), len({panel["id"] for panel in panels}))
        expressions = [target["expr"] for panel in panels for target in panel.get("targets", [])]
        joined = "\n".join(expressions)
        for metric in (
            "reader_audiobook_generation_accepted_total",
            "reader_audiobook_generation_completed_total",
            "reader_audiobook_generation_failed_total",
            "reader_audiobook_generation_rejected_total",
            "reader_audiobook_stage_duration_seconds_bucket",
            "reader_audiobook_text_characters_bucket",
        ):
            self.assertIn(metric, joined)
        self.assertNotRegex(joined, r"(?:owner|book|generation|voice)[Ii]d\s*=")
        for panel in panels:
            self.assertEqual("prometheus", panel["datasource"]["type"])
            self.assertEqual("${DS_PROMETHEUS}", panel["datasource"]["uid"])


if __name__ == "__main__":
    unittest.main()
