from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
RULES = ROOT / "service/deploy/monitoring/task-reliability-alerts.yml"
RUNBOOK = ROOT / "docs/runbooks/task-reliability-alerts.md"
SCHEDULER_POM = ROOT / "service/task-scheduler-service/pom.xml"
EXECUTOR_POM = ROOT / "service/task-executor-service/pom.xml"
SCHEDULER_CONFIG = ROOT / "service/task-scheduler-service/src/main/resources/application.yml"
EXECUTOR_CONFIG = ROOT / "service/task-executor-service/src/main/resources/application.yml"


class TaskReliabilityAlertsTest(unittest.TestCase):
    def test_rules_reference_exported_low_cardinality_metrics_and_runbooks(self):
        rules = RULES.read_text(encoding="utf-8")
        runbook = RUNBOOK.read_text(encoding="utf-8")
        alerts = re.findall(r"^\s+- alert: ([A-Za-z][A-Za-z0-9]+)$", rules, re.MULTILINE)
        self.assertEqual(len(alerts), len(set(alerts)))
        self.assertGreaterEqual(len(alerts), 9)
        for alert in alerts:
            self.assertIn(f"## {alert}", runbook)
            self.assertRegex(rules, rf"(?m)runbook_url: .*#{alert.lower()}$")
        self.assertNotRegex(rules, r"taskInstanceId|executionId|nodeId|businessId")
        for metric in (
            "task_outbox_oldest_age_seconds",
            "task_outbox_dead",
            "task_executor_clusters_unavailable",
            "task_executor_tasks_blocked",
            "task_queue_wait_seconds",
            "task_lease_lost_total",
            "task_executor_journal_readable",
            "task_executor_journal_pending",
            "task_executor_journal_diagnostic",
            "task_report_retry_total",
        ):
            self.assertIn(metric, rules)

    def test_scheduler_and_executor_expose_prometheus_registry_on_loopback(self):
        for pom in (SCHEDULER_POM, EXECUTOR_POM):
            self.assertIn("micrometer-registry-prometheus", pom.read_text(encoding="utf-8"))
        for config in (SCHEDULER_CONFIG, EXECUTOR_CONFIG):
            text = config.read_text(encoding="utf-8")
            self.assertIn("include: health,info,metrics,prometheus", text)
            self.assertRegex(text, r"server:\n\s+address: \$\{[^:]+:127\.0\.0\.1\}")
            self.assertIn("application: ${spring.application.name}", text)
        assembler = (ROOT / "service/deploy/assemble_release.py").read_text(encoding="utf-8")
        self.assertIn('source / "monitoring"', assembler)
        self.assertIn('"task-reliability-alerts.md"', assembler)
        self.assertIn('"audiobook-generation-alerts.md"', assembler)


if __name__ == "__main__":
    unittest.main()
