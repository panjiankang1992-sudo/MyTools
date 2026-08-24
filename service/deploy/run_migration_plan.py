#!/usr/bin/env python3
"""Run an approved remote migration plan through Scheduler and persist safe evidence."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import tempfile
import time
import urllib.error
import urllib.request
from collections.abc import Sequence
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

TERMINAL = {"SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED"}
RUN_ID = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
TASK_NAME = re.compile(r"^[a-z][a-z0-9_]{0,127}$")
PLACEHOLDER = re.compile(r"(?i)(replace|placeholder|example|change[-_ ]?me|todo)")


class SchedulerClient:
    """Minimal Scheduler client used only from the deployment host."""

    def __init__(self, base_url: str, timeout: float = 5) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def request(self, path: str, method: str = "GET",
                payload: dict[str, Any] | None = None) -> Any:
        """Send one JSON request and require a successful response."""
        data = None if payload is None else canonical(payload)
        request = urllib.request.Request(
            self.base_url + path, data=data, method=method,
            headers={"Accept": "application/json", "Content-Type": "application/json"})
        try:
            response = urllib.request.urlopen(request, timeout=self.timeout)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"Scheduler returned HTTP {error.code}") from error
        with response:
            body = response.read()
            if response.status not in range(200, 300):
                raise RuntimeError(f"Scheduler returned HTTP {response.status}")
        return json.loads(body.decode()) if body else None


def canonical(value: Any) -> bytes:
    """Serialize JSON deterministically for request bodies and evidence digests."""
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=True).encode()


def sha256_file(path: Path) -> str:
    """Hash one backup manifest without loading it entirely into memory."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def require_text(value: Any, name: str, pattern: re.Pattern[str]) -> str:
    """Validate one required operator-controlled identifier."""
    if not isinstance(value, str) or not pattern.fullmatch(value) or PLACEHOLDER.search(value):
        raise ValueError(f"{name} is invalid")
    return value


def validate_plan(plan: Any) -> dict[str, Any]:
    """Validate plan safety gates before any Scheduler mutation is allowed."""
    if not isinstance(plan, dict) or plan.get("approved") is not True:
        raise ValueError("plan must be explicitly approved")
    run_id = require_text(plan.get("runId"), "runId", RUN_ID)
    backup = plan.get("backup")
    if not isinstance(backup, dict):
        raise TypeError("backup is required")
    manifest = Path(str(backup.get("manifestPath", "")))
    expected_hash = str(backup.get("sha256", "")).lower()
    if not manifest.is_absolute() or not manifest.is_file():
        raise ValueError("backup manifest must be an existing absolute file")
    if not re.fullmatch(r"[0-9a-f]{64}", expected_hash):
        raise ValueError("backup manifest sha256 is invalid")
    actual_hash = sha256_file(manifest)
    if actual_hash != expected_hash:
        raise ValueError("backup manifest sha256 does not match")
    phases = plan.get("phases")
    if not isinstance(phases, list) or not phases:
        raise ValueError("at least one migration phase is required")
    task_count = 0
    for phase in phases:
        if not isinstance(phase, dict):
            raise TypeError("migration phase is invalid")
        require_text(phase.get("name"), "phase name", TASK_NAME)
        tasks = phase.get("tasks")
        if not isinstance(tasks, list) or not tasks:
            raise ValueError("migration phase must contain tasks")
        for task in tasks:
            validate_task(task)
            task_count += 1
    if task_count > 100:
        raise ValueError("migration plan exceeds the task limit")
    return {"runId": run_id, "backupSha256": actual_hash, "phases": phases}


def validate_task(task: Any) -> None:
    """Validate one bounded migration task declaration."""
    if not isinstance(task, dict):
        raise TypeError("migration task is invalid")
    require_text(task.get("taskName"), "taskName", TASK_NAME)
    if not isinstance(task.get("parameters"), dict):
        raise TypeError("task parameters must be an object")
    assertions = task.get("assertions", [])
    if not isinstance(assertions, list):
        raise TypeError("task assertions must be an array")
    for assertion in assertions:
        if (not isinstance(assertion, dict) or
                not isinstance(assertion.get("path"), str) or
                not re.fullmatch(r"[A-Za-z][A-Za-z0-9.]{0,255}", assertion["path"]) or
                "equals" not in assertion):
            raise ValueError("task assertion is invalid")


def wait_for_terminal(client: Any, task_id: str, deadline: float) -> dict[str, Any]:
    """Wait for one task instance terminal state."""
    while time.monotonic() < deadline:
        task = client.request(f"/api/v1/task-instances/{task_id}")
        if isinstance(task, dict) and task.get("status") in TERMINAL:
            return task
        time.sleep(1)
    raise RuntimeError(f"Task {task_id} did not reach a terminal state")


def result_value(result: dict[str, Any], path: str) -> Any:
    """Resolve one safe dotted assertion path from a step result."""
    value: Any = result
    for component in path.split("."):
        if not isinstance(value, dict) or component not in value:
            raise RuntimeError(f"result assertion path {path} is missing")
        value = value[component]
    return value


def validate_result(task: dict[str, Any], results: Any,
                    declaration: dict[str, Any]) -> dict[str, Any]:
    """Require successful task and step results, then evaluate declared invariants."""
    task_id = str(task.get("id", ""))
    if task.get("status") != "SUCCEEDED":
        raise RuntimeError(f"Task {task_id} finished as {task.get('status')}")
    if not isinstance(results, dict) or results.get("status") != "SUCCEEDED":
        raise RuntimeError(f"Task {task_id} result envelope is invalid")
    steps = results.get("steps")
    if not isinstance(steps, list) or not steps:
        raise RuntimeError(f"Task {task_id} has no step evidence")
    failed = [step for step in steps if not isinstance(step, dict) or step.get("status") != "SUCCEEDED"]
    if failed:
        raise RuntimeError(f"Task {task_id} contains unsuccessful step evidence")
    result_step = declaration.get("resultStep")
    candidates = [step for step in steps if result_step is None or step.get("stepName") == result_step]
    if not candidates or not isinstance(candidates[-1].get("result"), dict):
        raise RuntimeError(f"Task {task_id} result step is missing")
    payload = candidates[-1]["result"]
    checked: dict[str, Any] = {}
    for assertion in declaration.get("assertions", []):
        actual = result_value(payload, assertion["path"])
        if actual != assertion["equals"]:
            raise RuntimeError(f"Task {task_id} assertion {assertion['path']} failed")
        checked[assertion["path"]] = actual
    return {
        "taskId": task_id,
        "taskName": declaration["taskName"],
        "status": "SUCCEEDED",
        "stepCount": len(steps),
        "resultSha256": hashlib.sha256(canonical(payload)).hexdigest(),
        "assertions": checked,
    }


def run(plan: dict[str, Any], client: Any, deadline_seconds: float) -> dict[str, Any]:
    """Execute approved phases serially and stop on the first failed invariant."""
    validated = validate_plan(plan)
    started = datetime.now(timezone.utc).isoformat()
    evidence: list[dict[str, Any]] = []
    sequence = 0
    for phase in validated["phases"]:
        phase_evidence = {"name": phase["name"], "tasks": []}
        for declaration in phase["tasks"]:
            sequence += 1
            payload = {
                "taskName": declaration["taskName"],
                "idempotencyKey": f"migration:{validated['runId']}:{sequence}:{declaration['taskName']}",
                "businessType": "DATA_MIGRATION",
                "businessId": validated["runId"],
                "parentTaskInstanceId": None,
                "priority": int(declaration.get("priority", 80)),
                "parameters": declaration["parameters"],
                "requiredNodeLabels": declaration.get("requiredNodeLabels", {}),
            }
            created = client.request("/api/v1/task-instances", "POST", payload)
            if not isinstance(created, dict) or not created.get("id"):
                raise RuntimeError("Scheduler create response is invalid")
            task = wait_for_terminal(client, str(created["id"]), time.monotonic() + deadline_seconds)
            results = client.request(f"/api/v1/task-instances/{created['id']}/results")
            phase_evidence["tasks"].append(validate_result(task, results, declaration))
        evidence.append(phase_evidence)
    return {
        "ready": True,
        "runId": validated["runId"],
        "backupManifestSha256": validated["backupSha256"],
        "startedAt": started,
        "finishedAt": datetime.now(timezone.utc).isoformat(),
        "phases": evidence,
    }


def write_evidence(path: Path, report: dict[str, Any]) -> None:
    """Atomically persist evidence with operator-only permissions."""
    path.parent.mkdir(mode=0o750, parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(canonical(report) + b"\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def main(argv: Sequence[str] | None = None) -> int:
    """Validate or execute one remote migration plan."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", required=True)
    parser.add_argument("--scheduler-url", default="http://127.0.0.1:23410")
    parser.add_argument("--deadline-seconds", type=float, default=7200)
    parser.add_argument("--request-timeout", type=float, default=5)
    parser.add_argument("--evidence")
    parser.add_argument("--execute", action="store_true")
    arguments = parser.parse_args(argv)
    try:
        plan = json.loads(Path(arguments.plan).read_text(encoding="utf-8"))
        validated = validate_plan(plan)
        if not arguments.execute:
            print(json.dumps({"ready": True, "validated": True,
                              "runId": validated["runId"],
                              "backupManifestSha256": validated["backupSha256"]},
                             separators=(",", ":")))
            return 0
        if not arguments.evidence:
            raise ValueError("--evidence is required with --execute")
        report = run(plan, SchedulerClient(arguments.scheduler_url, arguments.request_timeout),
                     arguments.deadline_seconds)
        write_evidence(Path(arguments.evidence), report)
    except (OSError, RuntimeError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(json.dumps({"ready": False, "error": str(error)}, separators=(",", ":")))
        return 2
    print(json.dumps(report, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
