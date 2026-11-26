"""验证标签模型复用不把其他工作负载显存误记为可用。"""
import importlib.util
from pathlib import Path
from unittest.mock import Mock, patch
import pytest

SPEC = importlib.util.spec_from_file_location("tag_gpu_lease_141", Path(__file__).parents[1] / "scripts/gpu_lease.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def enter_lease(models, kind="tag"):
    """模拟本机协调协议并返回显存检查阈值。"""
    handle = Mock()
    with patch.dict(MODULE.os.environ, {"IMAGE_GPU_COORDINATION_VALIDATED": "true",
            "IMAGE_GPU_LOCK_FILE": "/tmp/test-tag-gpu.lock", "TAGGING_MODEL": "expected:8b"}), \
            patch.object(MODULE.Path, "is_symlink", return_value=False), \
            patch.object(MODULE.Path, "open", return_value=handle), \
            patch.object(MODULE, "acquire"), patch.object(MODULE, "wait_idle"), \
            patch.object(MODULE, "request", side_effect=lambda base, path, body=None:
                         {"models": models} if path == "/api/ps" else {}), \
            patch.object(MODULE, "wait_free") as wait:
        try:
            with MODULE.GpuLease(kind, model="expected:8b"):
                pass
            return wait.call_args.args[0]
        finally:
            handle.close.assert_called_once()


def test_cold_model_requires_full_free_memory():
    assert enter_lease([]) == 10 * 1024


def test_same_resident_model_reuses_allocated_memory():
    assert enter_lease([{"name": "expected:8b", "size_vram": 5525 * 1024 * 1024}]) == 4715


def test_reuse_still_reserves_headroom():
    assert enter_lease([{"name": "expected:8b", "size_vram": 12 * 1024 ** 3}]) == 1024


def test_other_model_cannot_be_counted_as_reusable():
    with pytest.raises(RuntimeError, match="GPU_OTHER_MODEL_RESIDENT"):
        enter_lease([{"name": "unrelated:8b", "size_vram": 8 * 1024 ** 3}])


@pytest.mark.parametrize("value", [-1, True, "5525"])
def test_invalid_resident_memory_fails_closed(value):
    with pytest.raises(ValueError, match="GPU_RESOURCE_UNVERIFIED"):
        enter_lease([{"name": "expected:8b", "size_vram": value}])


def test_duplicate_resident_entries_cannot_inflate_capacity():
    with pytest.raises(ValueError, match="GPU_RESOURCE_UNVERIFIED"):
        enter_lease([{"name": "expected:8b", "size_vram": 4 * 1024 ** 3}] * 2)


def test_image_cold_start_threshold_is_unchanged():
    assert enter_lease([], kind="image") == 12 * 1024
