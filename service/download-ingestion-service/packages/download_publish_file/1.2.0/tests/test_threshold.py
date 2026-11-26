"""小视频归档边界回归。"""
import importlib.util
from pathlib import Path
import pytest
SPEC = importlib.util.spec_from_file_location("threshold_module", Path(__file__).parents[1]/"scripts/main.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
@pytest.mark.parametrize("size", [50*1024*1024,100*1024*1024-1,100*1024*1024,100*1024*1024+1])
def test_threshold(size):
    parameters={"resourceUsername":"acceptance","receivedAt":"2026-09-26T00:00:00Z","assetMimeType":"video/mp4"}
    path=MODULE.managed_directory(parameters,{"fileName":"clip.mp4","sizeBytes":size})
    assert ("/big_media/" in path) == (size >= 100*1024*1024)
