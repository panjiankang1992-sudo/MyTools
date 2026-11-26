import os
from unittest.mock import patch

from mytools_qq_connector.reference_store import MessageReferenceStore


def message(key, content="original"):
    return {"id": "message-" + key, "content": content,
            "message_scene": {"ext": [f"msg_idx={key}", "ref_msg_idx=other"]}}


def test_references_survive_restart_and_never_cross_scope(tmp_path):
    path = tmp_path / "references.sqlite3"
    store = MessageReferenceStore(path)
    store.remember("app:account:c2c:user", message("REFIDX_1"))
    restarted = MessageReferenceStore(path)
    assert restarted.get("app:account:c2c:user", "REFIDX_1")["content"] == "original"
    assert restarted.get("app:account:c2c:user", "other") is None
    assert restarted.get("app:other:c2c:user", "REFIDX_1") is None
    assert restarted.get("app:account:group:group", "REFIDX_1") is None
    assert os.stat(path).st_mode & 0o777 == 0o600


def test_replayed_events_do_not_replace_or_extend_cached_original(tmp_path):
    store = MessageReferenceStore(tmp_path / "refs.db", ttl_seconds=10)
    with patch("mytools_qq_connector.reference_store.time.time", return_value=100):
        store.remember("scope", message("a"))
    with patch("mytools_qq_connector.reference_store.time.time", return_value=108):
        store.remember("scope", message("a", "different"))
        assert store.get("scope", "a")["content"] == "original"
    with patch("mytools_qq_connector.reference_store.time.time", return_value=111):
        assert store.get("scope", "a") is None


def test_cache_eviction_and_oversize_messages_are_bounded(tmp_path):
    store = MessageReferenceStore(tmp_path / "refs.db", max_entries=2, max_message_bytes=300)
    for key in ("a", "b", "c"):
        store.remember("scope", message(key))
    assert store.get("scope", "a") is None
    assert store.get("scope", "b") is not None
    store.remember("scope", message("large", "x" * 1000))
    assert store.get("scope", "large") is None


def test_byte_budget_applies_across_conversations(tmp_path):
    store = MessageReferenceStore(tmp_path / "refs.db", max_bytes=250)
    for i in range(10):
        store.remember(str(i), message(str(i)))
    assert sum(store.get(str(i), str(i)) is not None for i in range(10)) <= 2


def test_parallel_cache_writes_keep_independent_entries(tmp_path):
    from concurrent.futures import ThreadPoolExecutor

    store = MessageReferenceStore(tmp_path / "refs.db")
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(lambda i: store.remember("scope", message(str(i))), range(30)))
    assert all(store.get("scope", str(i)) is not None for i in range(30))
