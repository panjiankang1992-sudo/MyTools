import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import urllib.error


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SDK = Path(__file__).parents[5] / "task-executor-service" / "sdk" / "python"
sys.path.insert(0, str(SDK))
SPEC = importlib.util.spec_from_file_location("reader_synthesize_audiobook", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Storage:
    def __init__(self, sources):
        self.sources = sources
        self.published = []

    def download(self, uri, target, _maximum):
        target.write_bytes(self.sources[uri])
        return len(self.sources[uri])

    def publish(self, path, _root, relative, key, size, digest):
        self.published.append((path.read_bytes(), relative, key, size, digest))
        return "storage://managed/" + relative


class Reader:
    def __init__(self, chapters):
        self.chapters = chapters
        self.saved = []
        self.completed = None

    def input(self, generation_id):
        return {"generationId": generation_id, "narratorProvider": "VOLCENGINE",
                "narratorVoiceType": "narrator-voice", "chapters": self.chapters}

    def save(self, _generation_id, chapters):
        self.saved.extend(chapters)

    def complete(self, generation_id):
        self.completed = generation_id
        return {"generationId": generation_id, "synthesizedChapterCount": len(self.saved)}


class Tts:
    def __init__(self):
        self.texts = []

    def synthesize(self, text, _request_id, voice_type=None, provider=None, text_type="plain"):
        self.texts.append((text, voice_type, provider, text_type))
        return ("ID3" + text).encode("utf-8")


class Assets:
    def __init__(self):
        self.payloads = []

    def register(self, payload):
        self.payloads.append(payload)
        return {"id": "00000000-0000-4000-8000-000000000888", "version": 1}


def assemble(parts, target):
    target.write_bytes(b"".join(part.read_bytes() for part in parts))


def duration(_path):
    return 1234


class Response:
    def __init__(self, value):
        self._body = json.dumps(value, separators=(",", ":")).encode("utf-8")
        self.headers = {"Content-Length": str(len(self._body))}

    def __enter__(self):
        return self

    def __exit__(self, _type, _value, _traceback):
        return False

    def read(self, maximum=-1):
        return self._body if maximum < 0 else self._body[:maximum]


class StreamResponse:
    def __init__(self, parts, content_length=None):
        self._body = b"".join(parts)
        self._position = 0
        self.headers = {} if content_length is None else {"Content-Length": str(content_length)}

    def __enter__(self):
        return self

    def __exit__(self, _type, _value, _traceback):
        return False

    def read(self, maximum=-1):
        if maximum < 0:
            maximum = len(self._body) - self._position
        result = self._body[self._position:self._position + maximum]
        self._position += len(result)
        return result


class ReaderSynthesizeAudiobookTest(unittest.TestCase):

    def test_synthesizes_and_publishes_frozen_chapters(self):
        source = "\u7b2c\u4e00\u53e5\u3002\n\n\u7b2c\u4e8c\u53e5\u3002".encode("utf-8")
        digest = hashlib.sha256(source).hexdigest()
        chapters = [{"index": 0, "title": "chapter one", "contentSha256": digest,
                     "textStorageUri": "storage://managed/text.txt", "textSizeBytes": len(source)}]
        storage = Storage({"storage://managed/text.txt": source})
        reader = Reader(chapters)
        tts = Tts()
        assets = Assets()
        with tempfile.TemporaryDirectory() as directory:
            result = MODULE.execute({"generationId": "00000000-0000-4000-8000-000000000001",
                                     "ownerId": 1001, "storageRoot": "managed"}, storage, reader, tts, assets,
                                    Path(directory), assemble, duration)

        self.assertEqual(1, result["synthesizedChapterCount"])
        self.assertEqual([("\u7b2c\u4e00\u53e5\u3002\n\u7b2c\u4e8c\u53e5\u3002", "narrator-voice", "VOLCENGINE", "plain")], tts.texts)
        self.assertEqual(1, len(reader.saved))
        self.assertEqual(1234, reader.saved[0]["durationMs"])
        self.assertEqual("mp3", reader.saved[0]["format"])
        self.assertEqual("00000000-0000-4000-8000-000000000888", reader.saved[0]["assetId"])
        self.assertEqual("AUDIOBOOK_CHAPTER", assets.payloads[0]["sourceType"])
        self.assertTrue(storage.published[0][1].endswith(".mp3"))

    def test_completes_after_interruption_when_all_chapters_were_already_saved(self):
        reader = Reader([])
        storage = Storage({})
        tts = Tts()
        assets = Assets()
        with tempfile.TemporaryDirectory() as directory:
            result = MODULE.execute({"generationId": "00000000-0000-4000-8000-000000000001",
                                     "ownerId": 1001, "storageRoot": "managed"}, storage, reader, tts, assets,
                                    Path(directory), assemble, duration)

        self.assertEqual(0, result["synthesizedChapterCount"])
        self.assertEqual("00000000-0000-4000-8000-000000000001", reader.completed)
        self.assertEqual([], tts.texts)
        self.assertEqual([], storage.published)
        self.assertEqual([], assets.payloads)

    def test_does_not_complete_when_audio_publication_fails(self):
        source = "第一句。".encode("utf-8")
        digest = hashlib.sha256(source).hexdigest()
        chapters = [{"index": 0, "contentSha256": digest, "textStorageUri": "storage://managed/text.txt",
                     "textSizeBytes": len(source)}]

        class FailingPublicationStorage(Storage):
            def publish(self, *_args):
                raise RuntimeError("storage publication is unavailable")

        reader = Reader(chapters)
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "publication"):
                MODULE.execute({"generationId": "00000000-0000-4000-8000-000000000001",
                                "ownerId": 1001, "storageRoot": "managed"},
                               FailingPublicationStorage({"storage://managed/text.txt": source}), reader, Tts(), Assets(),
                               Path(directory), assemble, duration)

        self.assertEqual([], reader.saved)
        self.assertIsNone(reader.completed)

    def test_rejects_chapter_text_with_wrong_frozen_checksum(self):
        source = b"chapter"
        storage = Storage({"storage://managed/text.txt": source})
        chapter = {"index": 0, "contentSha256": "a" * 64,
                   "textStorageUri": "storage://managed/text.txt", "textSizeBytes": len(source)}
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "checksum"):
                MODULE.synthesize_chapter(storage, Tts(), Path(directory), Assets(), 1001, "managed", "generation",
                                          chapter, assemble, duration)

    def test_splits_long_text_at_sentence_boundaries(self):
        text = "A\u3002" * 20
        chunks = MODULE.split_text(text, 32)
        self.assertEqual(text, "".join(chunks))
        self.assertEqual(3, len(chunks))
        self.assertTrue(all(len(chunk.encode("utf-8")) <= 32 for chunk in chunks))

    def test_splits_chinese_text_by_utf8_byte_limit_without_breaking_codepoints(self):
        text = "甲。" * 200

        chunks = MODULE.split_text(text, 900)

        self.assertEqual(text, "".join(chunks))
        self.assertGreater(len(chunks), 1)
        self.assertTrue(all(len(chunk.encode("utf-8")) <= 900 for chunk in chunks))

    def test_renders_only_validated_longest_pronunciation_terms_as_ssml(self):
        entries = MODULE.pronunciation_entries([
            {"term": "张三", "pinyin": "zhang1 san1"},
            {"term": "张三丰", "pinyin": "zhang1 san1 feng1"}])

        rendered, text_type = MODULE.pronunciation_render("张三丰对张三说<&。", entries)

        self.assertEqual("ssml", text_type)
        self.assertEqual('<speak><phoneme alphabet="py" ph="zhang1 san1 feng1">张三丰</phoneme>对'
                         '<phoneme alphabet="py" ph="zhang1 san1">张三</phoneme>说&lt;&amp;。</speak>', rendered)
        MODULE.validate_pronunciation_ssml(rendered)

    def test_rejects_untrusted_or_invalid_pronunciation_ssml(self):
        with self.assertRaisesRegex(ValueError, "SSML"):
            MODULE.validate_pronunciation_ssml('<speak><break time="1s" /></speak>')

    def test_requires_frozen_ssml_capability_for_narrator_and_effective_character_voice(self):
        chapters = [{"segments": [{"speakerKind": "CHARACTER", "confidence": 0.9,
                                     "ssmlSupported": False}]}]

        with self.assertRaisesRegex(ValueError, "capability"):
            MODULE.validate_pronunciation_voice_capabilities(False, chapters, 0.8)
        with self.assertRaisesRegex(ValueError, "capability"):
            MODULE.validate_pronunciation_voice_capabilities(True, chapters, 0.8)

        chapters[0]["segments"][0]["ssmlSupported"] = True
        MODULE.validate_pronunciation_voice_capabilities(True, chapters, 0.8)
        with self.assertRaisesRegex(ValueError, "dictionary"):
            MODULE.pronunciation_entries([{"term": "张三", "pinyin": "zhang san"}])
        with self.assertRaisesRegex(ValueError, "dictionary"):
            MODULE.pronunciation_entries([{"term": "<张三>", "pinyin": "zhang1 san1"}])

    def test_splits_rendered_ssml_before_the_provider_byte_limit(self):
        entries = MODULE.pronunciation_entries([{"term": "甲", "pinyin": "jia3"}])
        payloads = MODULE.pronunciation_payloads("甲" * 300, entries)

        self.assertGreater(len(payloads), 1)
        self.assertTrue(all(len(payload.encode("utf-8")) <= 900 and text_type == "ssml"
                            for payload, text_type in payloads))

    def test_uses_frozen_character_voice_for_attributed_text_only(self):
        text = "旁白。角色。结尾。"
        segments = [{"startCodepoint": 3, "endCodepoint": 6, "provider": "VOLCENGINE",
                     "voiceType": "character-voice",
                     "speakerKind": "CHARACTER", "confidence": 0.9}]
        rendered = MODULE.voice_render_segments(text, "VOLCENGINE", "narrator-voice", segments)

        self.assertEqual([("VOLCENGINE", "narrator-voice", "旁白。"),
                          ("VOLCENGINE", "character-voice", "角色。"),
                          ("VOLCENGINE", "narrator-voice", "结尾。")], rendered)

    def test_uses_narrator_when_character_speaker_confidence_is_low(self):
        segments = [{"startCodepoint": 0, "endCodepoint": 3, "provider": "VOLCENGINE",
                     "voiceType": "character-voice", "speakerKind": "CHARACTER", "confidence": 0.4}]

        rendered = MODULE.voice_render_segments("角色。", "VOLCENGINE", "narrator-voice", segments, 0.8)

        self.assertEqual([("VOLCENGINE", "narrator-voice", "角色。")], rendered)

    def test_splits_tencent_text_using_the_documented_safe_utf8_boundary(self):
        chunks = MODULE.pronunciation_payloads("甲" * 151, [], MODULE.TENCENT_TTS_MAX_SEGMENT_UTF8_BYTES)

        self.assertEqual("甲" * 151, "".join(value for value, _kind in chunks))
        self.assertEqual(2, len(chunks))
        self.assertTrue(all(len(value.encode("utf-8")) <= MODULE.TENCENT_TTS_MAX_SEGMENT_UTF8_BYTES
                            for value, _kind in chunks))

    def test_tencent_client_signs_the_fixed_endpoint_and_decodes_mp3(self):
        requests = []

        def urlopen(request, timeout):
            requests.append((request, timeout))
            return Response({"Response": {
                "Audio": base64.b64encode(b"ID3audio").decode("ascii"),
                "SessionId": "server-session",
                "RequestId": "server-request",
            }})

        client = MODULE.TencentTtsClient("test-secret-id", "test-secret-key", "1001", 30,
                                         region="ap-guangzhou", urlopen=urlopen,
                                         clock=lambda: 1_700_000_000)
        audio = client.synthesize("测试文本。", "123e4567-e89b-12d3-a456-426614174000", "1001", "TENCENT")

        self.assertEqual(b"ID3audio", audio)
        self.assertEqual(1, len(requests))
        self.assertEqual(30, requests[0][1])
        headers = {key.lower(): value for key, value in requests[0][0].header_items()}
        self.assertEqual("TextToVoice", headers["x-tc-action"])
        self.assertEqual("2019-08-23", headers["x-tc-version"])
        self.assertEqual("ap-guangzhou", headers["x-tc-region"])
        self.assertIn("Credential=test-secret-id/", headers["authorization"])
        self.assertIn("SignedHeaders=content-type;host;x-tc-action", headers["authorization"])
        self.assertEqual("TC3-HMAC-SHA256 Credential=test-secret-id/2023-11-14/tts/tc3_request, "
                         "SignedHeaders=content-type;host;x-tc-action, "
                         "Signature=6988b45ed0990d6e00ebe2395c5e3ab4bc7914783e0b801f08db63ab2eeec8f7",
                         headers["authorization"])
        self.assertNotIn("test-secret-key", headers["authorization"])
        payload = json.loads(requests[0][0].data.decode("utf-8"))
        self.assertEqual({"Text": "测试文本。", "VoiceType": 1001, "PrimaryLanguage": 1,
                          "SampleRate": 16000, "Codec": "mp3"},
                         {key: payload[key] for key in ("Text", "VoiceType", "PrimaryLanguage", "SampleRate", "Codec")})
        self.assertTrue(payload["SessionId"].startswith("mytools-"))
        self.assertNotEqual("123e4567-e89b-12d3-a456-426614174000", payload["SessionId"])

    def test_tencent_client_rejects_unverified_audio_and_other_providers(self):
        client = MODULE.TencentTtsClient("test-secret-id", "test-secret-key", "1001", 30,
                                         urlopen=lambda _request, timeout: Response({"Response": {
                                             "Audio": base64.b64encode(b"not-audio").decode("ascii")}}))

        with self.assertRaisesRegex(RuntimeError, "audio"):
            client.synthesize("测试文本。", "123e4567-e89b-12d3-a456-426614174000")
        with self.assertRaisesRegex(ValueError, "provider"):
            client.synthesize("测试文本。", "123e4567-e89b-12d3-a456-426614174000", provider="VOLCENGINE")
        with self.assertRaisesRegex(ValueError, "voice"):
            client.synthesize("甲" * 151, "123e4567-e89b-12d3-a456-426614174000")

    def test_volcengine_client_retries_rate_limit_with_same_request_contract(self):
        requests = []
        sleeps = []
        responses = [urllib.error.HTTPError("https://tts.example/api/v1/tts", 429, "busy", {}, None),
                     Response({"code": 3000, "data": base64.b64encode(b"ID3audio").decode("ascii")})]

        def urlopen(request, timeout):
            requests.append((request, timeout))
            current = responses.pop(0)
            if isinstance(current, Exception):
                raise current
            return current

        client = MODULE.VolcengineTtsClient("https://tts.example/api/v1/tts", "app-id", "test-token", "volcano_tts",
                                            "default-voice", 30, max_retries=2, urlopen=urlopen,
                                            sleeper=sleeps.append)
        audio = client.synthesize("测试文本。", "123e4567-e89b-12d3-a456-426614174000", "role-voice", "VOLCENGINE")

        self.assertEqual(b"ID3audio", audio)
        self.assertEqual([0.5], sleeps)
        self.assertEqual(2, len(requests))
        first_payload = json.loads(requests[0][0].data.decode("utf-8"))
        second_payload = json.loads(requests[1][0].data.decode("utf-8"))
        self.assertEqual("Bearer;test-token", requests[0][0].get_header("Authorization"))
        self.assertEqual(first_payload["request"], second_payload["request"])
        self.assertEqual("plain", first_payload["request"]["text_type"])
        self.assertEqual({"voice_type": "role-voice", "encoding": "mp3", "speed_ratio": 1.0,
                          "loudness_ratio": 1.0}, first_payload["audio"])
        self.assertNotEqual("mytools-audiobook", first_payload["user"]["uid"])

    def test_volcengine_client_sends_only_validated_ssml(self):
        requests = []

        def urlopen(request, timeout):
            requests.append((request, timeout))
            return Response({"code": 3000, "data": base64.b64encode(b"ID3audio").decode("ascii")})

        client = MODULE.VolcengineTtsClient("https://tts.example/api/v1/tts", "app-id", "test-token", "volcano_tts",
                                            "default-voice", 30, urlopen=urlopen)
        ssml = '<speak><phoneme alphabet="py" ph="xie4 le4">解乐</phoneme></speak>'
        self.assertEqual(b"ID3audio", client.synthesize(ssml, "123e4567-e89b-12d3-a456-426614174000",
                                                          text_type="ssml"))
        self.assertEqual("ssml", json.loads(requests[0][0].data.decode("utf-8"))["request"]["text_type"])
        with self.assertRaisesRegex(ValueError, "SSML"):
            client.synthesize('<speak><break /></speak>', "123e4567-e89b-12d3-a456-426614174000",
                              text_type="ssml")

    def test_volcengine_client_rejects_non_mp3_response_and_unsupported_provider(self):
        client = MODULE.VolcengineTtsClient("https://tts.example/api/v1/tts", "app-id", "test-token", "volcano_tts",
                                            "default-voice", 30,
                                            urlopen=lambda _request, timeout: Response({"code": 3000,
                                                "data": base64.b64encode(b"not-audio").decode("ascii")}))

        with self.assertRaisesRegex(RuntimeError, "audio"):
            client.synthesize("测试文本。", "123e4567-e89b-12d3-a456-426614174000")
        with self.assertRaisesRegex(ValueError, "provider"):
            client.synthesize("测试文本。", "123e4567-e89b-12d3-a456-426614174000",
                              provider="UNSUPPORTED")
        with self.assertRaisesRegex(ValueError, "provider"):
            client.synthesize("测试文本。", "123e4567-e89b-12d3-a456-426614174000", provider=17)
        with self.assertRaisesRegex(ValueError, "voice"):
            client.synthesize("字" * 301, "123e4567-e89b-12d3-a456-426614174000")

    def test_volcengine_client_rejects_a_v3_endpoint_when_configured_for_v1(self):
        with self.assertRaisesRegex(ValueError, "implemented V1 HTTP route"):
            MODULE.VolcengineTtsClient("https://tts.example/api/v3/tts/unidirectional", "app-id", "test-token",
                                       "volcano_tts", "default-voice", 30)

    def test_volcengine_v3_client_decodes_json_objects_split_across_network_reads(self):
        requests = []
        first = json.dumps({"code": 0, "data": base64.b64encode(b"ID3first-").decode("ascii")},
                           separators=(",", ":")).encode("utf-8")
        second = json.dumps({"code": "0", "data": base64.b64encode(b"second").decode("ascii")},
                            separators=(",", ":")).encode("utf-8")
        complete = json.dumps({"code": 20000000}, separators=(",", ":")).encode("utf-8")
        response = StreamResponse([first[:9], first[9:] + second + complete])

        def urlopen(request, timeout):
            requests.append((request, timeout))
            return response

        client = MODULE.VolcengineTtsClient("https://tts.example/api/v3/tts/unidirectional", "", "", "",
                                            "v3-voice", 30, api_version="v3", api_key="test-api-key",
                                            resource_id="seed-tts-2.0", urlopen=urlopen)
        audio = client.synthesize("测试文本。", "123e4567-e89b-12d3-a456-426614174000", provider="VOLCENGINE")

        self.assertEqual(b"ID3first-second", audio)
        self.assertEqual(1, len(requests))
        headers = {key.lower(): value for key, value in requests[0][0].header_items()}
        self.assertEqual("test-api-key", headers["x-api-key"])
        self.assertEqual("seed-tts-2.0", headers["x-api-resource-id"])
        self.assertEqual("123e4567-e89b-12d3-a456-426614174000", headers["x-api-request-id"])
        self.assertNotIn("authorization", headers)
        payload = json.loads(requests[0][0].data.decode("utf-8"))
        self.assertEqual("v3-voice", payload["req_params"]["speaker"])
        self.assertEqual(24000, payload["req_params"]["sample_rate"])
        self.assertEqual({"format": "mp3", "speech_rate": 0, "loudness_rate": 0},
                         payload["req_params"]["audio_params"])
        self.assertNotEqual("mytools-audiobook", payload["user"]["uid"])

    def test_volcengine_v3_rejects_incomplete_stream_and_unverified_ssml(self):
        client = MODULE.VolcengineTtsClient("https://tts.example/api/v3/tts/unidirectional", "", "", "",
                                            "v3-voice", 30, api_version="v3", api_key="test-api-key",
                                            resource_id="seed-tts-2.0",
                                            urlopen=lambda _request, timeout: StreamResponse([
                                                json.dumps({"code": 0, "data": base64.b64encode(b"ID3audio").decode("ascii")}
                                                           ).encode("utf-8")]))

        with self.assertRaisesRegex(RuntimeError, "did not complete"):
            client.synthesize("测试文本。", "123e4567-e89b-12d3-a456-426614174000")
        with self.assertRaisesRegex(ValueError, "SSML capability"):
            client.synthesize('<speak><phoneme alphabet="py" ph="xie4 le4">解乐</phoneme></speak>',
                              "123e4567-e89b-12d3-a456-426614174000", text_type="ssml")
        with self.assertRaisesRegex(ValueError, "V3 TTS runtime configuration"):
            MODULE.VolcengineTtsClient("https://tts.example/api/v3/tts/unidirectional", "", "", "", "v3-voice",
                                       30, api_version="v3", api_key="test-api-key", resource_id="bad resource")


if __name__ == "__main__":
    unittest.main()
