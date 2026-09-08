#!/usr/bin/env python3
"""Verify health, executor registration, and default-off Gateway routing."""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Sequence


RELIABILITY_METRICS = {
    "task-scheduler-service": (
        "task_outbox_backlog",
        "task_outbox_oldest_age_seconds",
        "task_outbox_dead",
        "task_executor_clusters_unavailable",
        "task_executor_tasks_blocked",
        "task_queue_depth",
        "task_queue_wait_seconds",
        "task_execution_running",
        "task_lease_lost_total",
        "task_execution_seconds_count",
        "task_execution_seconds_sum",
    ),
    "task-executor-service": (
        "task_executor_journal_readable",
        "task_executor_journal_pending",
        "task_executor_journal_diagnostic",
        "task_executor_journal_retry_delay_seconds",
    ),
}


def request_json(url: str, timeout: float) -> tuple[int, Any]:
    """Request JSON while preserving expected HTTP error responses."""

    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    try:
        response = urllib.request.urlopen(request, timeout=timeout)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        payload = response.read().decode("utf-8")
        return response.status, json.loads(payload) if payload else None


def request_text(url: str, timeout: float) -> tuple[int, str]:
    """Request plain text while preserving expected HTTP error responses."""

    request = urllib.request.Request(url, headers={"Accept": "text/plain"})
    try:
        response = urllib.request.urlopen(request, timeout=timeout)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.read().decode("utf-8")


def service_health_url(host: str, service: dict[str, Any]) -> str:
    """Build the runtime-specific health endpoint URL."""

    path = "/health" if service["runtime"] == "python" else "/actuator/health"
    return f"http://{host}:{service['port']}{path}"


def health_is_up(payload: Any) -> bool:
    """Accept only an explicit UP health response."""

    return isinstance(payload, dict) and str(payload.get("status", "")).upper() == "UP"


def wait_for_services(
    host: str,
    services: list[dict[str, Any]],
    deadline_seconds: float,
    request_timeout: float,
) -> list[str]:
    """Wait until every declared service reports UP or the deadline expires."""

    pending = {service["name"]: service for service in services}
    deadline = time.monotonic() + deadline_seconds
    while pending and time.monotonic() < deadline:
        for name, service in list(pending.items()):
            try:
                status, payload = request_json(service_health_url(host, service), request_timeout)
                if status == 200 and health_is_up(payload):
                    del pending[name]
            except (OSError, ValueError, json.JSONDecodeError):
                pass
        if pending:
            time.sleep(1)
    return sorted(pending)


def verify_executor(host: str, scheduler_port: int, request_timeout: float) -> list[dict[str, Any]]:
    """Require at least one enabled online Executor registered in Scheduler."""

    status, payload = request_json(
        f"http://{host}:{scheduler_port}/api/v1/execution-topology/nodes", request_timeout
    )
    if status != 200 or not isinstance(payload, list):
        raise ValueError("Scheduler executor node query failed")
    online = [
        node for node in payload
        if isinstance(node, dict) and node.get("enabled") is True
        and node.get("status") in {"ONLINE", "BUSY"}
    ]
    if not online:
        raise ValueError("no enabled online Executor is registered")
    return online


def verify_gateway_default_off(host: str, gateway_port: int, request_timeout: float) -> None:
    """Require an unconfigured new Gateway route to remain disabled."""

    status, payload = request_json(
        f"http://{host}:{gateway_port}/api/app/v1/catalog", request_timeout
    )
    if status != 503 or not isinstance(payload, dict) or payload.get("code") != "GATEWAY_002":
        raise ValueError("Gateway default-off check failed")


def verify_reliability_metrics(
    host: str,
    service_ports: dict[str, int],
    request_timeout: float,
) -> int:
    """Require Scheduler and Executor operational metrics with stable service labels."""

    verified = 0
    for service_name, required_metrics in RELIABILITY_METRICS.items():
        port = service_ports.get(service_name)
        if port is None:
            raise ValueError(f"reliability metric service is missing: {service_name}")
        status, payload = request_text(
            f"http://{host}:{port}/actuator/prometheus", request_timeout
        )
        if status != 200:
            raise ValueError(f"Prometheus endpoint failed: {service_name}")
        lines = tuple(line for line in payload.splitlines() if line and not line.startswith("#"))
        missing = [
            metric for metric in required_metrics
            if not any(
                line.startswith(metric + "{")
                and f'application="{service_name}"' in line.split("}", 1)[0]
                for line in lines
            )
        ]
        if missing:
            raise ValueError(
                f"reliability metrics missing for {service_name}: " + ", ".join(missing)
            )
        verified += len(required_metrics)
    return verified


def main(argv: Sequence[str] | None = None) -> int:
    """Run deployment acceptance checks against one host."""

    directory = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=directory / "services.json")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--deadline-seconds", type=float, default=120)
    parser.add_argument("--request-timeout", type=float, default=3)
    parser.add_argument("--skip-default-disabled", action="store_true")
    parser.add_argument("--skip-gateway-default-off", action="store_true")
    arguments = parser.parse_args(argv)
    try:
        manifest = json.loads(arguments.manifest.read_text(encoding="utf-8"))
        services = manifest["services"] + manifest["statelessServices"]
        if arguments.skip_default_disabled:
            services = [service for service in services if service.get("defaultEnabled", True)]
        pending = wait_for_services(
            arguments.host, services, arguments.deadline_seconds, arguments.request_timeout
        )
        if pending:
            raise ValueError("services not healthy: " + ", ".join(pending))
        scheduler = next(service for service in services if service["name"] == "task-scheduler-service")
        online = verify_executor(arguments.host, scheduler["port"], arguments.request_timeout)
        service_ports = {service["name"]: service["port"] for service in services}
        verified_metrics = verify_reliability_metrics(
            arguments.host, service_ports, arguments.request_timeout
        )
        if not arguments.skip_gateway_default_off:
            gateway = next(service for service in services if service["name"] == "mytools-gateway")
            verify_gateway_default_off(arguments.host, gateway["port"], arguments.request_timeout)
    except (KeyError, OSError, StopIteration, ValueError, json.JSONDecodeError) as error:
        print(str(error), file=sys.stderr)
        return 2
    print(
        f"Deployment verified: {len(services)} services healthy, "
        f"{len(online)} Executor node(s) online, {verified_metrics} reliability metrics exported"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
