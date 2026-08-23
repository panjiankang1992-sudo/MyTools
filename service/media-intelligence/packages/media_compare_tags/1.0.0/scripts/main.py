#!/usr/bin/env python3
"""Compare legacy and sidecar tag sets without mutating either result."""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile


def normalize_tags(values: object) -> list[str]:
    """Normalize a bounded list of unique tag names."""
    if not isinstance(values, list):
        return []
    return sorted({str(value).strip() for value in values if str(value).strip()})[:64]


def compare(context: dict) -> dict:
    """Build an exact and Jaccard comparison from task context."""
    parameters = context.get("parameters", {})
    step_outputs = context.get("stepOutputs", {})
    generated = step_outputs.get("generate_tags", {}) if isinstance(step_outputs, dict) else {}
    generated_items = generated.get("tags", []) if isinstance(generated, dict) else []
    generated_names = [item.get("name") for item in generated_items if isinstance(item, dict)]
    legacy_tags = normalize_tags(parameters.get("legacyTags") if isinstance(parameters, dict) else [])
    generated_tags = normalize_tags(generated_names)
    legacy_set = set(legacy_tags)
    generated_set = set(generated_tags)
    union = legacy_set | generated_set
    matched = sorted(legacy_set & generated_set)
    return {
        "legacyTags": legacy_tags,
        "generatedTags": generated_tags,
        "matchedTags": matched,
        "exactMatch": legacy_set == generated_set,
        "jaccardSimilarity": 1.0 if not union else len(matched) / len(union),
    }


def write_result(result: dict) -> None:
    """Atomically write the comparison result."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one tag comparison step."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    write_result(compare(context))


if __name__ == "__main__":
    main()
