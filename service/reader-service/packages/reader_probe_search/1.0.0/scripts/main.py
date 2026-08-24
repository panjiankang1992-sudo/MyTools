#!/usr/bin/env python3
"""Generate DSH probe terms and aggregate a sharded Reader search child task."""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
import urllib.request
from uuid import UUID

from mytools_task_sdk.context import TaskContext

MAX_SOURCES = 500
MAX_RESULTS = 500


class DshConnectorClient:
    """Bounded client for the Executor-only DSH probe endpoint."""

    def __init__(self, base_url: str, token: str, timeout: float = 125):
        if not token:
            raise ValueError("DSH Connector token is missing")
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout

    def analyze(self, owner_id: int, task_instance_id: str, clue: str) -> list[str]:
        """Generate and validate one frozen set of probe terms."""
        payload = json.dumps({"ownerId": owner_id, "taskInstanceId": task_instance_id,
                              "clue": clue}, ensure_ascii=False, separators=(",", ":")).encode()
        request = urllib.request.Request(
            self.base_url + "/internal/v1/dsh/probe-terms", data=payload, method="POST",
            headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json",
                     "Accept": "application/json"})
        with urllib.request.urlopen(request, timeout=self.timeout) as response:
            value = json.loads(response.read().decode("utf-8"))
        terms = value.get("terms") if isinstance(value, dict) else None
        if not isinstance(terms, list) or not 1 <= len(terms) <= 5:
            raise RuntimeError("DSH Connector returned invalid probe terms")
        normalized = []
        for item in terms:
            term = item.strip() if isinstance(item, str) else ""
            if len(term) < 2 or len(term) > 40 or term in normalized:
                raise RuntimeError("DSH Connector returned invalid probe terms")
            normalized.append(term)
        return normalized


def aggregate(payload: dict) -> dict:
    """Aggregate successful source-search shard results with stable title deduplication."""
    if not isinstance(payload, dict) or payload.get("status") != "SUCCEEDED":
        raise RuntimeError("Reader source search child did not succeed")
    steps = payload.get("steps")
    if not isinstance(steps, list):
        raise RuntimeError("Reader source search child returned invalid results")
    selected = [step.get("result") for step in steps if isinstance(step, dict)
                and step.get("stepName") == "search_sources" and step.get("status") == "SUCCEEDED"
                and isinstance(step.get("result"), dict)]
    if not selected:
        raise RuntimeError("Reader source search child has no successful shard results")
    deduplicated = {}
    for shard in selected:
        rows = shard.get("results")
        if not isinstance(rows, list):
            raise RuntimeError("Reader source search shard returned invalid rows")
        for row in rows:
            if not isinstance(row, dict):
                continue
            name = "".join(str(row.get("name") or "").lower().split())
            if name:
                deduplicated.setdefault(name, row)
    return {
        "totalSources": max(int(item.get("totalSources") or 0) for item in selected),
        "successfulSources": sum(int(item.get("successfulSources") or 0) for item in selected),
        "failedSources": sum(int(item.get("failedSources") or 0) for item in selected),
        "results": list(deduplicated.values())[:MAX_RESULTS],
    }


def execute(context: TaskContext, client: DshConnectorClient) -> dict:
    """Analyze the clue, run the multi-node child, and return one parent result."""
    parameters = context.parameters
    user_id = int(parameters["userId"])
    keyword = str(parameters["keyword"]).strip()
    page = max(1, int(parameters.get("page") or 1))
    sources = parameters.get("sources")
    task_instance_id = str(UUID(str(context.context["taskInstanceId"])))
    if user_id < 1 or not keyword or len(keyword) > 200 or parameters.get("mode") != "PROBE":
        raise ValueError("invalid probe search parameters")
    if not isinstance(sources, list) or len(sources) > MAX_SOURCES:
        raise ValueError("book source list is invalid or exceeds limit")
    terms = client.analyze(user_id, task_instance_id, keyword)
    child = context.create_child(
        "reader_source_search",
        {"userId": user_id, "keyword": keyword, "page": page, "mode": "PROBE",
         "searchTerms": terms, "sources": sources},
        f"reader-probe-source-search:{task_instance_id}",
        business_type="READER_SEARCH", business_id=task_instance_id)
    completed = context.wait_child(child.id, 330, 1.0)
    if completed.status != "SUCCEEDED":
        raise RuntimeError(f"Reader source search child ended with {completed.status}")
    summary = aggregate(context.get_task_results(child.id))
    return {"keyword": keyword, "mode": "PROBE", "page": page, "searchTerms": terms,
            "childTaskId": child.id, **summary}


def write_result(result: dict) -> None:
    """Atomically write the parent task result."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent,
                                     delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one probe-search orchestration task."""
    context = TaskContext.load()
    client = DshConnectorClient(
        os.getenv("DSH_CONNECTOR_URL", "http://127.0.0.1:23320"),
        os.environ.get("DSH_CONNECTOR_INTERNAL_TOKEN", ""))
    write_result(execute(context, client))


if __name__ == "__main__":
    main()
