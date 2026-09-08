import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
DASHBOARD = ROOT / "service/deploy/monitoring/task-reliability-dashboard.json"


class TaskReliabilityDashboardTest(unittest.TestCase):
    def test_dashboard_is_portable_and_covers_core_operational_signals(self):
        dashboard = json.loads(DASHBOARD.read_text(encoding="utf-8"))
        self.assertEqual("mytools-task-reliability", dashboard["uid"])
        self.assertGreaterEqual(dashboard["schemaVersion"], 39)
        self.assertEqual("30s", dashboard["refresh"])
        self.assertIn(
            "DS_PROMETHEUS",
            {item["name"] for item in dashboard["__inputs"] if item["type"] == "datasource"},
        )
        panels = dashboard["panels"]
        self.assertEqual(len(panels), len({panel["id"] for panel in panels}))
        self.assertGreaterEqual(len(panels), 8)
        expressions = [
            target["expr"]
            for panel in panels
            for target in panel.get("targets", [])
        ]
        joined = "\n".join(expressions)
        for metric in (
            "task_queue_depth",
            "task_queue_wait_seconds",
            "task_execution_running",
            "task_execution_seconds_count",
            "task_execution_seconds_sum",
            "task_lease_lost_total",
            "task_outbox_backlog",
            "task_outbox_dead",
            "task_executor_clusters_unavailable",
            "task_executor_tasks_blocked",
            "task_executor_journal_readable",
            "task_executor_journal_pending",
            "task_executor_journal_diagnostic",
            "task_executor_journal_retry_delay_seconds",
        ):
            self.assertIn(metric, joined)
        for expression in expressions:
            self.assertRegex(expression, r'application="task-(scheduler|executor)-service"')
        self.assertNotRegex(joined, r"taskInstanceId|executionId|nodeId|businessId")
        self.assertFalse(any(re.search(r"\[[0-9]+[smhdwy]\]", expression) for expression in expressions
                             if "rate(" in expression))

    def test_every_panel_uses_the_imported_prometheus_data_source(self):
        dashboard = json.loads(DASHBOARD.read_text(encoding="utf-8"))

        for panel in dashboard["panels"]:
            self.assertEqual("prometheus", panel["datasource"]["type"])
            self.assertEqual("${DS_PROMETHEUS}", panel["datasource"]["uid"])


if __name__ == "__main__":
    unittest.main()
