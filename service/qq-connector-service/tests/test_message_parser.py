import json

import pytest

from mytools_qq_connector.message_parser import MessageParser, ParseLimits, parse_size


def test_three_nested_layers_preserve_text_order_metadata_and_duplicates():
    data = {"content": "current", "message_type": 103, "msg_elements": [
        {"content": "same", "author": {"username": "Alice"}, "timestamp": "2026-10-25T01:00:00Z",
         "msg_elements": [{"content": "same", "attachments": [
             {"url": "https://example.test/a.jpg", "content_type": "image/jpeg"}]}]},
        {"content": "last"}]}
    result = MessageParser("qq").parse(data)
    assert result.complete
    texts = [p["text"] for p in result.parts if p["type"] == "TEXT"]
    assert texts.count("same") == 2
    assert "path=0.0 relation=quote" in texts[1]
    assert '"sender":"Alice"' in texts[1]
    assert "2026-10-25T01:00:00Z" in texts[1]
    assert "path=0.0.0" in texts[3]
    assert result.parts[5]["type"] == "ATTACHMENT"
    assert texts[-1] == "last"
    assert result.body == "\n".join(texts)


def test_inline_quote_is_preferred_and_never_looked_up():
    def forbidden(_key):
        raise AssertionError("inline content must be used")

    result = MessageParser("qq", forbidden).parse({"content": "reply", "message_type": 103,
        "msg_elements": [{"msg_idx": "REFIDX_a", "content": "original"}]})
    assert result.complete
    assert "original" in result.body


def test_index_only_quote_recovers_full_nested_cached_body_and_attachment():
    cache = {"REFIDX_a": {"content": "original", "msg_elements": [{"content": "inner"}],
             "attachments": [{"url": "https://example.test/a.mp4", "content_type": "video/mp4"}]}}
    result = MessageParser("qq", cache.get).parse({"content": "reply", "message_scene": {
        "ext": ["ref_msg_idx=REFIDX_a"]}})
    assert result.complete
    assert "quote_cached" in result.body
    assert "original" in result.body and "inner" in result.body
    assert any(p["attachmentType"] == "VIDEO" for p in result.parts)


@pytest.mark.parametrize("data, reason", [
    ({"message_type": 103}, "quote_body_unavailable"),
    ({"msg_elements": [{"msg_idx": "unknown"}]}, "reference_unavailable"),
    ({"msg_elements": [{"preview": {"url": "https://example.test/avatar", "content": "preview"}}]},
     "element_body_unavailable"),
    ({"msg_elements": "broken"}, "invalid_elements"),
    ({"msg_elements": [None]}, "invalid_element"),
    ({"payload": "{broken"}, "invalid_payload"),
    ({"attachments": [{"url": "file:///secret"}]}, "attachment_unavailable"),
    ({"attachments": [{"url": "https://example.test/" + "a" * 4096}]}, "attachment_unavailable"),
])
def test_unavailable_content_is_explicit(data, reason):
    result = MessageParser("qq").parse(data)
    assert not result.complete
    assert reason in result.body
    assert "preview" not in result.body
    assert all(p["type"] == "TEXT" for p in result.parts)


def test_ref_cycle_is_bounded_and_visible():
    cache = {"a": {"content": "first", "message_scene": {"ext": ["ref_msg_idx=b"]}},
             "b": {"content": "second", "message_scene": {"ext": ["ref_msg_idx=a"]}}}
    result = MessageParser("qq", cache.get).parse({"msg_elements": [{"msg_idx": "a"}]})
    assert not result.complete
    assert "reference_cycle" in result.body
    assert result.body.count("first") == 1


def test_structural_cycle_and_repeated_noncyclic_nodes():
    shared = {"content": "repeated"}
    root = {"msg_elements": [shared, shared]}
    result = MessageParser("qq").parse(root)
    assert result.complete and result.body.count("repeated") == 2
    shared["msg_elements"] = [root]
    assert "cycle" in MessageParser("qq").parse(root).body


def test_nested_json_and_voice_metadata():
    result = MessageParser("qq").parse({"msg_elements": [{"payload": json.dumps({
        "content": "inner", "attachments": [{"url": "https://example.test/voice.silk",
        "voice_wav_url": "https://example.test/voice.wav", "content_type": "voice",
        "size": "2KB", "asr_refer_text": "hello"}]})}]})
    assert result.complete
    attachment = next(p for p in result.parts if p["type"] == "ATTACHMENT")
    assert attachment["attachmentType"] == "RECORD"
    assert attachment["mimeType"] == "audio/wav"
    assert attachment["declaredSize"] == 2048
    assert "[qq-voice-transcript] hello" in result.body


def test_deduplication_preserves_occurrences_and_cleans_text_parts():
    url = "https://example.test/a.jpg"
    attachment = {"url": url, "content_type": "image/jpeg"}
    result = MessageParser("qq").parse({"content": url, "attachments": [attachment],
        "msg_elements": [{"content": url + "x", "attachments": [attachment]}]})
    assert result.complete
    assert len([p for p in result.parts if p["type"] == "ATTACHMENT"]) == 1
    assert result.parts[0]["text"] == "[empty]"
    assert url + "x" in result.body
    assert "[qq-attachment-ref index=1]" in result.body


def test_voice_url_aliases_and_trailing_punctuation_do_not_duplicate_downloads():
    raw = "https://example.test/voice.silk"
    wav = "https://example.test/voice.wav"
    result = MessageParser("qq").parse({"content": f"listen ({raw}).",
        "attachments": [{"url": raw, "voice_wav_url": wav, "content_type": "voice"}],
        "msg_elements": [{"attachments": [{"url": raw, "content_type": "voice"}]}]})
    assert raw not in result.body and wav not in result.body
    attachments = [p for p in result.parts if p["type"] == "ATTACHMENT"]
    assert len(attachments) == 1 and attachments[0]["sourceUrl"] == wav


@pytest.mark.parametrize("limits, data, reason", [
    (ParseLimits(depth=1), {"msg_elements": [{"msg_elements": [{"content": "deep"}]}]}, "depth_limit"),
    (ParseLimits(nodes=2), {"msg_elements": [{"content": "a"}, {"content": "b"}]}, "node_limit"),
    (ParseLimits(parts=3), {"msg_elements": [{"content": "a"}, {"content": "b"}]}, "part_limit"),
    (ParseLimits(text_chars=3), {"content": "abcdef"}, "text_limit"),
    (ParseLimits(json_chars=2), {"payload": '{"content":"a"}'}, "json_limit"),
])
def test_limits_never_silently_report_success(limits, data, reason):
    result = MessageParser("qq", limits=limits).parse(data)
    assert not result.complete
    assert reason in result.body
    assert len(result.parts) <= limits.parts


def test_more_than_one_hundred_attachments_are_read_and_five_hundred_parts_are_bounded():
    attachments = [{"url": f"https://example.test/{i}.jpg", "content_type": "image/jpeg"}
                   for i in range(600)]
    result = MessageParser("qq").parse({"attachments": attachments[:120]})
    assert result.complete
    assert sum(p["type"] == "ATTACHMENT" for p in result.parts) == 120
    result = MessageParser("qq").parse({"attachments": attachments})
    assert len(result.parts) == 500
    assert "part_limit" in result.body


@pytest.mark.parametrize("value", [-1, 0, "0.1B", True, "NaN", "1e309", "999999999999999999999999TB"])
def test_invalid_sizes_do_not_reject_otherwise_readable_messages(value):
    assert parse_size(value) is None
