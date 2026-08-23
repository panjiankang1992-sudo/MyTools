import importlib.util
from pathlib import Path
from types import SimpleNamespace

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("media_submit_analysis", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Task:
    def __init__(self, analyze):
        self.parameters = {"assetId": "scan:item", "contentSha256": "a" * 64,
                           "sourcePath": "/media/video.mp4", "ownerId": 7,
                           "assetMimeType": "video/mp4", "analyze": analyze,
                           "analysisVersion": "analysis-v2"}
        self.context = {"stepOutputs": {
            "register_asset": {"assetId": "00000000-0000-4000-8000-000000000001"},
            "register_media_item": {"mediaItemId": "00000000-0000-4000-8000-000000000002"}}}
        self.created = []

    def create_child(self, name, parameters, key, **metadata):
        self.created.append((name, parameters, key, metadata))
        return SimpleNamespace(id="00000000-0000-4000-8000-000000000003")


def test_skips_analysis_by_default():
    task = Task(False)
    result = MODULE.execute(task, "")
    assert result["status"] == "SKIPPED"
    assert task.created == []


def test_submits_same_node_versioned_analysis():
    task = Task(True)
    result = MODULE.execute(task, "media-node-a")
    assert result["status"] == "SUBMITTED"
    assert task.created[0][0] == "media_analyze_video"
    assert task.created[0][1]["assetRegistryId"].endswith("0001")
    assert task.created[0][1]["mediaItemId"].endswith("0002")
    assert task.created[0][3]["required_node_labels"] == {"executor.node": "media-node-a"}
