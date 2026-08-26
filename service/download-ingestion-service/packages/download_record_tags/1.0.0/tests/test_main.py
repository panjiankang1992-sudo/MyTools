"""Tests for the download tag result callback package."""
import importlib.util
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("download_record_tags", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def test_maps_generated_tags_to_terminal_result():
    context = {"parameters": {"itemId": "item-1"}, "stepOutputs": {
        "generate_tags": {"tags": [{"name": "cosplay", "type": "topic", "confidence": 0.98}]}}}
    assert MODULE.build_result(context) == {"itemId": "item-1", "tagStatus": "TAGGED",
                                             "tags": [{"name": "cosplay", "type": "topic",
                                                       "confidence": 0.98}]}


def test_records_failed_tagging_without_losing_download():
    assert MODULE.build_result({"parameters": {"itemId": "item-2"}, "stepOutputs": {}}) == {
        "itemId": "item-2", "tagStatus": "FAILED", "tags": []}
