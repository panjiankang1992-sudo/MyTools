import importlib.util
from pathlib import Path
from types import SimpleNamespace

import pytest

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("media_scan_directory", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Task:
    def __init__(self, root):
        self.parameters = {"rootPath": str(root), "directoryKey": "movies",
                           "directoryName": "Movies", "ownerId": 7}
        self.context = {"taskInstanceId": "task-1"}
        self.created = []

    def create_child(self, name, parameters, key, **metadata):
        self.created.append((name, parameters, key, metadata))
        return SimpleNamespace(id=f"child-{len(self.created)}")

    def wait_child(self, child_id, timeout):
        return SimpleNamespace(id=child_id, status="SUCCEEDED")


class Client:
    def __init__(self):
        self.entries = []
        self.finished = None

    def begin(self, payload):
        self.begin_payload = payload
        return {"id": "00000000-0000-4000-8000-000000000001"}

    def stage(self, scan_id, entries):
        self.entries = entries

    def finish(self, scan_id, digest):
        self.finished = (scan_id, digest)
        return {"status": "COMPLETED", "importedCount": len(self.entries)}


def test_scans_stages_and_waits_for_ingestion_children(tmp_path):
    source = tmp_path / "library"
    source.mkdir()
    (source / "b.txt").write_text("ignored", encoding="utf-8")
    (source / "a.mp4").write_bytes(b"video")
    task = Task(source)
    client = Client()
    with pytest.MonkeyPatch.context() as patch:
        patch.setenv("TASK_EXECUTOR_NODE_AFFINITY", "media-node-a")
        result = MODULE.execute(task, client, [str(tmp_path)])
    assert result["discovered"] == 1
    assert result["imported"] == 1
    assert client.entries[0]["sourceBusinessId"].startswith("scan:")
    assert task.created[0][0] == "media_ingest_scanned_file"
    assert task.created[0][1]["scanId"] == result["scanId"]
    assert task.created[0][1]["assetSourceType"] == "MEDIA_SCAN"
    assert task.created[0][1]["assetProviderType"] == "STORAGE_GATEWAY"
    assert task.created[0][1]["assetProviderVersion"] == "v1"
    assert task.created[0][1]["sizeBytes"] == 5
    assert task.created[0][1]["analyze"] is False
    assert task.created[0][3]["required_node_labels"] == {"executor.node": "media-node-a"}


def test_scans_image_audio_and_preserves_directory_hierarchy(tmp_path):
    source = tmp_path / "202608" / "20260825"
    source.mkdir(parents=True)
    (source / "cover.jpg").write_bytes(b"image")
    (source / "voice.mp3").write_bytes(b"audio")
    task = Task(source)
    task.parameters.update({"directoryKey": "day-key", "directoryName": "20260825",
                            "parentDirectoryKey": "month-key",
                            "parentDirectoryName": "202608"})
    client = Client()
    with pytest.MonkeyPatch.context() as patch:
        patch.setenv("TASK_EXECUTOR_NODE_AFFINITY", "media-node-a")
        result = MODULE.execute(task, client, [str(tmp_path)])
    assert result["discovered"] == 2
    assert client.begin_payload["directoryName"] == "20260825"
    assert client.begin_payload["parentDirectoryName"] == "202608"
    assert {entry["mimeType"] for entry in client.entries} == {"audio/mpeg", "image/jpeg"}


def test_rejects_source_outside_allow_list(tmp_path):
    source = tmp_path / "library"
    source.mkdir()
    other = tmp_path / "other"
    other.mkdir()
    with pytest.raises(ValueError, match="outside configured roots"):
        MODULE.allowed_root(source, [str(other)])


def test_rejects_more_than_direct_child_limit(tmp_path, monkeypatch):
    for index in range(2):
        (tmp_path / f"{index}.mp4").write_bytes(b"video")
    monkeypatch.setattr(MODULE, "MAX_FILES", 1)
    with pytest.raises(ValueError, match="direct child task limit"):
        MODULE.discover(tmp_path, "movies")


def test_propagates_explicit_analysis_policy(tmp_path, monkeypatch):
    source = tmp_path / "library"
    source.mkdir()
    (source / "a.mp4").write_bytes(b"video")
    task = Task(source)
    task.parameters.update({"analyze": True, "analysisVersion": "analysis-v3", "frameCount": 6})
    monkeypatch.setenv("TASK_EXECUTOR_NODE_AFFINITY", "media-node-a")
    MODULE.execute(task, Client(), [str(tmp_path)])
    parameters = task.created[0][1]
    assert parameters["analyze"] is True
    assert parameters["analysisVersion"] == "analysis-v3"
    assert parameters["frameCount"] == 6
