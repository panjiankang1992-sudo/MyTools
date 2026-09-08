import json
from pathlib import Path

import pytest

from mytools_task_sdk.errors import write_task_error


def test_writes_retryable_error_atomically(tmp_path: Path, monkeypatch):
    target = tmp_path / "task-error.json"
    monkeypatch.setenv("TASK_ERROR_FILE", str(target))

    write_task_error("REMOTE_TEMPORARY_FAILURE", "TRANSIENT", "retry later")

    assert json.loads(target.read_text(encoding="utf-8")) == {
        "code": "REMOTE_TEMPORARY_FAILURE",
        "category": "TRANSIENT",
        "retryable": True,
        "message": "retry later",
    }


def test_rejects_unknown_category_and_invalid_code(tmp_path: Path, monkeypatch):
    monkeypatch.setenv("TASK_ERROR_FILE", str(tmp_path / "task-error.json"))

    with pytest.raises(ValueError):
        write_task_error("bad-code", "PERMANENT")
    with pytest.raises(ValueError):
        write_task_error("VALID_CODE", "UNKNOWN")
