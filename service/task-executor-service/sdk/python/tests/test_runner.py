import json
from pathlib import Path
import subprocess
import sys


def run_script(tmp_path: Path, monkeypatch, body: str):
    script = tmp_path / "script.py"
    error = tmp_path / "task-error.json"
    script.write_text(body, encoding="utf-8")
    monkeypatch.setenv("TASK_ERROR_FILE", str(error))
    completed = subprocess.run(
        [sys.executable, "-m", "mytools_task_sdk.runner", str(script)],
        check=False,
        capture_output=True,
        text=True,
    )
    return completed, json.loads(error.read_text(encoding="utf-8")) if error.exists() else None


def test_classifies_http_conflict_as_permanent(tmp_path: Path, monkeypatch):
    completed, error = run_script(tmp_path, monkeypatch, """
import urllib.error
raise urllib.error.HTTPError('http://service', 409, 'conflict', {}, None)
""")

    assert completed.returncode != 0
    assert error["code"] == "REMOTE_STATE_CONFLICT"
    assert error["category"] == "CONFLICT"
    assert error["retryable"] is False


def test_classifies_network_failure_as_retryable(tmp_path: Path, monkeypatch):
    completed, error = run_script(tmp_path, monkeypatch, """
import urllib.error
raise urllib.error.URLError('offline')
""")

    assert completed.returncode != 0
    assert error["code"] == "REMOTE_NETWORK_FAILURE"
    assert error["category"] == "TRANSIENT"
    assert error["retryable"] is True


def test_leaves_unknown_exception_unclassified(tmp_path: Path, monkeypatch):
    completed, error = run_script(tmp_path, monkeypatch, "raise RuntimeError('unknown')\n")

    assert completed.returncode != 0
    assert error is None
