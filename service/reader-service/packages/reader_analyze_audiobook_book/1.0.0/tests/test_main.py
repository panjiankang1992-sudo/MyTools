import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import urllib.error


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SDK = Path(__file__).parents[5] / "task-executor-service" / "sdk" / "python"
sys.path.insert(0, str(SDK))
SPEC = importlib.util.spec_from_file_location("reader_analyze_audiobook_book", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Storage:
    def __init__(self, sources):
        self.sources = sources

    def download(self, uri, target, _maximum):
        target.write_bytes(self.sources[uri])
        return len(self.sources[uri])


class Reader:
    def __init__(self, chapters, inherited_analysis=None):
        self.chapters = chapters
        self.inherited_analysis = inherited_analysis
        self.saved = None

    def input(self, generation_id):
        result = {"generationId": generation_id, "chapters": self.chapters}
        if self.inherited_analysis is not None:
            result["inheritedAnalysis"] = self.inherited_analysis
        return result

    def save(self, _generation_id, result):
        self.saved = result
        return {"characterCount": len(result["characters"])}

    def complete(self, generation_id):
        return {"generationId": generation_id, "characterCount": len(self.saved["characters"]),
                "relationshipCount": len(self.saved["relationships"]),
                "speechSegmentCount": len(self.saved["speechSegments"])}


class Analyzer:
    model_version = "TEST_ANALYSIS_MODEL_V1"

    def analyze_chunk(self, chapter_index, _title, _offset, _text, _known_characters):
        return {
            "characters": [{"canonicalName": "Lin", "displayName": "Lin", "presentation": "NEUTRAL",
                            "characterType": "HUMAN", "traits": ["calm"], "occurrenceCount": 1,
                            "confidence": 0.9, "aliases": [{"alias": "L", "aliasType": "SHORT_NAME",
                            "evidenceStartCodepoint": 0, "evidenceEndCodepoint": 1, "confidence": 0.8}]}],
            "relationships": [],
            "speechSegments": [{"sequenceNumber": 3, "textStartCodepoint": 0, "textEndCodepoint": 1,
                                "speakerKind": "CHARACTER", "speakerCanonicalName": "Lin",
                                "deliveryTags": ["calm"], "confidence": 0.8}],
        }


class ModelResponse:
    def __init__(self, payload):
        self.payload = payload

    def read(self, _maximum=None):
        return self.payload

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class ReaderAnalyzeAudiobookBookTest(unittest.TestCase):
    def test_chunks_preserve_codepoint_offsets_and_content(self):
        text = "第一句。第二句。第三句。"
        chunks = MODULE.chunks(text, 5)
        self.assertEqual(text, "".join(value for _, value in chunks))
        self.assertEqual(0, chunks[0][0])
        self.assertEqual(len(chunks[0][1]), chunks[1][0])

    def test_default_chunk_limit_bounds_one_model_response_workload(self):
        chunks = MODULE.chunks("甲" * (MODULE.MAX_PROMPT_CHARACTERS + 1))

        self.assertEqual(MODULE.MAX_PROMPT_CHARACTERS, len(chunks[0][1]))
        self.assertEqual(MODULE.MAX_PROMPT_CHARACTERS, chunks[1][0])

    def test_extracts_non_authoritative_chinese_speaker_and_quote_hints(self):
        text = "“请进。”林舟说道。“稍等。”苏晚回答。众人说不出话。"

        self.assertEqual(["林舟", "苏晚"], MODULE.candidate_names(text))
        self.assertEqual([{"startCodepoint": 1, "endCodepoint": 4},
                          {"startCodepoint": 11, "endCodepoint": 14}], MODULE.quoted_speech_ranges(text))

    def test_combines_independent_ner_and_speech_verb_hints_without_promoting_them_to_facts(self):
        class Ner:
            def extract(self, _text):
                return [{"name": "顾宁", "startCodepoint": 0, "endCodepoint": 2,
                         "source": "NER", "confidence": 0.97}]

        hints = MODULE.candidate_entities("顾宁看向林舟说道。", Ner())

        self.assertEqual(["顾宁", "林舟"], [value["name"] for value in hints])
        self.assertEqual("NER", hints[0]["source"])
        self.assertEqual("SPEECH_VERB", hints[1]["source"])

    def test_sends_validated_ner_hints_to_model_as_non_authoritative_context(self):
        class Ner:
            def extract(self, _text):
                return [{"name": "顾宁", "startCodepoint": 0, "endCodepoint": 2,
                         "source": "NER", "confidence": 0.97}]

        client = MODULE.OpenAiCompatibleAnalysisClient("https://model.example/v1/chat", "key", "model", 30,
                                                        ner_client=Ner())
        with patch.object(client, "_chat", return_value={}) as chat:
            client.analyze_chunk(1, "Chapter", 0, "顾宁对林舟说道。")

        instructions, user = chat.call_args.args
        payload = __import__("json").loads(user)
        self.assertIn("non-authoritative", instructions)
        self.assertEqual("顾宁", payload["candidateEntityHints"][0]["name"])
        self.assertIn("林舟", payload["candidateCharacterNames"])

    def test_sends_bounded_known_book_characters_as_non_authoritative_context(self):
        client = MODULE.OpenAiCompatibleAnalysisClient("https://model.example/v1/chat", "key", "model", 30)
        known = [{"canonicalName": "LinZhou", "aliases": ["YoungLin"]}]
        with patch.object(client, "_chat", return_value={}) as chat:
            client.analyze_chunk(1, "Chapter", 0, "林舟看向窗外。", known)

        instructions, user = chat.call_args.args
        payload = __import__("json").loads(user)
        self.assertIn("Known book identities are also non-authoritative", instructions)
        self.assertEqual(known, payload["knownBookCharacterHints"])

    def test_rejects_ambiguous_or_oversized_known_book_character_hints(self):
        with self.assertRaisesRegex(ValueError, "Known audiobook character hints"):
            MODULE.known_character_hints([
                {"canonicalName": "Lin", "aliases": []},
                {"canonicalName": "Lin", "aliases": []},
            ])
        with self.assertRaisesRegex(ValueError, "Known audiobook character hints"):
            MODULE.known_character_hints([{"canonicalName": "Lin", "aliases": ["Lin"]}])
        with self.assertRaisesRegex(ValueError, "Known audiobook character hints"):
            MODULE.known_character_hints([{"canonicalName": f"Character{index}", "aliases": []}
                                          for index in range(MODULE.MAX_CHARACTERS + 1)])

    def test_bounds_known_book_character_context_without_failing_large_books(self):
        hints = MODULE.known_character_hints([
            {"canonicalName": f"Character{index:04d}", "aliases": ["Alias" * 30]}
            for index in range(MODULE.MAX_KNOWN_BOOK_CHARACTER_HINTS + 20)
        ])

        self.assertEqual(MODULE.MAX_KNOWN_BOOK_CHARACTER_HINTS, len(hints))
        self.assertLessEqual(sum(len(value["canonicalName"]) + sum(len(alias) for alias in value["aliases"])
                                 for value in hints), MODULE.MAX_KNOWN_BOOK_CHARACTER_HINT_CODEPOINTS)

    def test_carries_validated_character_hints_to_later_chapters(self):
        first = "Lin says.".encode("utf-8")
        second = "YoungLin replies.".encode("utf-8")
        chapters = [
            {"index": 0, "title": "One", "contentSha256": hashlib.sha256(first).hexdigest(),
             "textStorageUri": "storage://managed/one.txt", "textSizeBytes": len(first)},
            {"index": 1, "title": "Two", "contentSha256": hashlib.sha256(second).hexdigest(),
             "textStorageUri": "storage://managed/two.txt", "textSizeBytes": len(second)},
        ]

        class ContextAnalyzer:
            model_version = "TEST_ANALYSIS_MODEL_V1"

            def __init__(self):
                self.hints = []

            def analyze_chunk(self, chapter_index, _title, _offset, _text, known_characters):
                self.hints.append(known_characters)
                if chapter_index == 0:
                    return {"characters": [{"canonicalName": "Lin", "displayName": "Lin",
                            "presentation": "NEUTRAL", "characterType": "HUMAN", "traits": [],
                            "occurrenceCount": 1, "confidence": 0.9, "aliases": [
                                {"alias": "YoungLin", "aliasType": "TITLE", "evidenceStartCodepoint": 0,
                                 "evidenceEndCodepoint": 3, "confidence": 0.9}]}],
                            "relationships": [], "speechSegments": []}
                return {"characters": [], "relationships": [], "speechSegments": []}

        analyzer = ContextAnalyzer()
        reader = Reader(chapters)
        with tempfile.TemporaryDirectory() as directory:
            MODULE.execute({"generationId": "00000000-0000-4000-8000-000000000011"},
                           Storage({"storage://managed/one.txt": first, "storage://managed/two.txt": second}),
                           reader, analyzer, Path(directory))

        self.assertEqual([], analyzer.hints[0])
        self.assertEqual([{"canonicalName": "Lin", "aliases": ["YoungLin"]}], analyzer.hints[1])

    def test_reuses_completed_analysis_without_recalling_the_model_for_unchanged_chapters(self):
        inherited = {"characters": [{"canonicalName": "Lin", "displayName": "Lin",
                      "presentation": "NEUTRAL", "characterType": "HUMAN", "traits": ["calm"],
                      "firstChapterIndex": 3, "occurrenceCount": 2, "confidence": 0.9,
                      "aliases": [{"alias": "YoungLin", "aliasType": "TITLE",
                                   "evidenceChapterIndex": 3, "evidenceStartCodepoint": 1,
                                   "evidenceEndCodepoint": 9, "confidence": 0.9}]}],
                     "relationships": [], "speechSegments": []}

        class FailingAnalyzer:
            model_version = "TEST_ANALYSIS_MODEL_V1"

            def analyze_chunk(self, *_args):
                raise AssertionError("unchanged chapter must not be sent to the model")

        reader = Reader([], inherited)
        with tempfile.TemporaryDirectory() as directory:
            result = MODULE.execute({"generationId": "00000000-0000-4000-8000-000000000012"},
                                    Storage({}), reader, FailingAnalyzer(), Path(directory))

        self.assertEqual(1, result["characterCount"])
        self.assertEqual("Lin", reader.saved["characters"][0]["canonicalName"])

    def test_seeds_new_chapter_model_hints_from_reused_completed_analysis(self):
        source = "YoungLin arrives.".encode("utf-8")
        inherited = {"characters": [{"canonicalName": "Lin", "displayName": "Lin",
                      "presentation": "NEUTRAL", "characterType": "HUMAN", "traits": [],
                      "firstChapterIndex": 0, "occurrenceCount": 1, "confidence": 0.9,
                      "aliases": [{"alias": "YoungLin", "aliasType": "TITLE",
                                   "evidenceChapterIndex": 0, "evidenceStartCodepoint": 0,
                                   "evidenceEndCodepoint": 8, "confidence": 0.9}]}],
                     "relationships": [], "speechSegments": []}
        chapter = {"index": 1, "title": "New", "contentSha256": hashlib.sha256(source).hexdigest(),
                   "textStorageUri": "storage://managed/new.txt", "textSizeBytes": len(source)}

        class ContextAnalyzer:
            model_version = "TEST_ANALYSIS_MODEL_V1"

            def __init__(self):
                self.hints = []

            def analyze_chunk(self, _chapter_index, _title, _offset, _text, known_characters):
                self.hints.append(known_characters)
                return {"characters": [], "relationships": [], "speechSegments": []}

        analyzer = ContextAnalyzer()
        with tempfile.TemporaryDirectory() as directory:
            MODULE.execute({"generationId": "00000000-0000-4000-8000-000000000013"},
                           Storage({"storage://managed/new.txt": source}), Reader([chapter], inherited), analyzer,
                           Path(directory))

        self.assertEqual([[{"canonicalName": "Lin", "aliases": ["YoungLin"]}]], analyzer.hints)

    def test_validates_ner_response_person_spans_and_ignores_non_person_types(self):
        payload = json_bytes({"entities": [
            {"type": "PERSON", "text": "林舟", "startCodepoint": 0, "endCodepoint": 2, "confidence": 0.93},
            {"type": "LOCATION", "text": "北京", "startCodepoint": 2, "endCodepoint": 4, "confidence": 0.99},
        ]})
        client = MODULE.NerCandidateClient("https://ner.example/v1/entities", "ner-key", 30, 0)
        with patch.object(MODULE.urllib.request, "urlopen", return_value=ModelResponse(payload)):
            result = client.extract("林舟北京")

        self.assertEqual([{"name": "林舟", "startCodepoint": 0, "endCodepoint": 2,
                           "source": "NER", "confidence": 0.93}], result)

    def test_rejects_insecure_or_invalid_person_ner_responses(self):
        with self.assertRaisesRegex(ValueError, "configuration"):
            MODULE.NerCandidateClient("http://ner.example/v1/entities", "", 30)
        payload = json_bytes({"entities": [
            {"type": "PERSON", "text": "林舟", "startCodepoint": 1, "endCodepoint": 3, "confidence": 0.9},
        ]})
        client = MODULE.NerCandidateClient("https://ner.example/v1/entities", "", 30, 0)
        with patch.object(MODULE.urllib.request, "urlopen", return_value=ModelResponse(payload)):
            with self.assertRaisesRegex(ValueError, "person entity"):
                client.extract("林舟来了")

    def test_execute_persists_fingerprint_and_global_speaker_offsets(self):
        source = "林说。".encode("utf-8")
        digest = hashlib.sha256(source).hexdigest()
        chapters = [{"index": 0, "title": "Chapter", "contentSha256": digest,
                     "textStorageUri": "storage://managed/chapter.txt", "textSizeBytes": len(source)}]
        reader = Reader(chapters)
        with tempfile.TemporaryDirectory() as directory:
            result = MODULE.execute({"generationId": "00000000-0000-4000-8000-000000000001"},
                                    Storage({"storage://managed/chapter.txt": source}), reader, Analyzer(),
                                    Path(directory))

        self.assertEqual(1, result["characterCount"])
        self.assertEqual(1, result["speechSegmentCount"])
        self.assertEqual(64, len(reader.saved["analysisFingerprintSha256"]))
        self.assertEqual("TEST_ANALYSIS_MODEL_V1", reader.saved["analysisModelVersion"])
        self.assertEqual(MODULE.ANALYSIS_RULE_VERSION, reader.saved["analysisRuleVersion"])
        self.assertEqual(0, reader.saved["speechSegments"][0]["sequenceNumber"])
        self.assertEqual("Lin", reader.saved["speechSegments"][0]["speakerCanonicalName"])

    def test_merge_drops_overlapping_speech_segments(self):
        observation = {"characters": [], "relationships": [], "speechSegments": [
            {"chapterIndex": 1, "sequenceNumber": -1, "textStartCodepoint": 0, "textEndCodepoint": 3,
             "speakerKind": "NARRATOR", "speakerCanonicalName": None, "deliveryTags": [], "confidence": 0.7},
            {"chapterIndex": 1, "sequenceNumber": -1, "textStartCodepoint": 2, "textEndCodepoint": 4,
             "speakerKind": "UNKNOWN", "speakerCanonicalName": None, "deliveryTags": [], "confidence": 0.6},
        ]}
        result = MODULE.merge_observations([observation])
        self.assertEqual(1, len(result["speechSegments"]))

    def test_rejects_unsafe_analysis_provenance(self):
        with self.assertRaisesRegex(ValueError, "provenance"):
            MODULE.merge_observations([], "model\nname")

    def test_merges_unique_alias_references_without_guessing_ambiguous_aliases(self):
        observation = {"characters": [
            {"canonicalName": "Lin", "displayName": "Lin", "presentation": "NEUTRAL",
             "characterType": "HUMAN", "traits": [], "firstChapterIndex": 0, "occurrenceCount": 1,
             "confidence": 0.9, "aliases": [{"alias": "L", "aliasType": "SHORT_NAME",
             "evidenceChapterIndex": 0, "evidenceStartCodepoint": 0, "evidenceEndCodepoint": 1,
             "confidence": 0.9}]},
            {"canonicalName": "Chen", "displayName": "Chen", "presentation": "NEUTRAL",
             "characterType": "HUMAN", "traits": [], "firstChapterIndex": 0, "occurrenceCount": 1,
             "confidence": 0.9, "aliases": []},
        ], "relationships": [{"sourceCanonicalName": "L", "targetCanonicalName": "Chen",
                                "relationshipType": "FRIEND", "direction": "BIDIRECTIONAL",
                                "evidenceChapterIndex": 0, "evidenceStartCodepoint": 0,
                                "evidenceEndCodepoint": 1, "confidence": 0.9}], "speechSegments": [
            {"chapterIndex": 0, "sequenceNumber": -1, "textStartCodepoint": 0, "textEndCodepoint": 1,
             "speakerKind": "CHARACTER", "speakerCanonicalName": "L", "deliveryTags": [], "confidence": 0.9},
        ]}

        result = MODULE.merge_observations([observation])

        self.assertEqual("Lin", result["relationships"][0]["sourceCanonicalName"])
        self.assertEqual("Lin", result["speechSegments"][0]["speakerCanonicalName"])

    def test_coalesces_cross_chapter_profiles_only_through_unique_alias_evidence(self):
        first = {"characters": [
            {"canonicalName": "LinZhou", "displayName": "Lin Zhou", "presentation": "MASCULINE",
             "characterType": "HUMAN", "traits": ["calm"], "firstChapterIndex": 0,
             "occurrenceCount": 2, "confidence": 0.82, "aliases": [
                 {"alias": "YoungLin", "aliasType": "TITLE", "evidenceChapterIndex": 0,
                  "evidenceStartCodepoint": 0, "evidenceEndCodepoint": 8, "confidence": 0.91}]},
            {"canonicalName": "Chen", "displayName": "Chen", "presentation": "NEUTRAL",
             "characterType": "HUMAN", "traits": [], "firstChapterIndex": 0,
             "occurrenceCount": 1, "confidence": 0.9, "aliases": []},
        ], "relationships": [
            {"sourceCanonicalName": "YoungLin", "targetCanonicalName": "Chen",
             "relationshipType": "FRIEND", "direction": "BIDIRECTIONAL", "evidenceChapterIndex": 0,
             "evidenceStartCodepoint": 0, "evidenceEndCodepoint": 8, "confidence": 0.81},
        ], "speechSegments": [
            {"chapterIndex": 0, "sequenceNumber": -1, "textStartCodepoint": 0, "textEndCodepoint": 8,
             "speakerKind": "CHARACTER", "speakerCanonicalName": "YoungLin", "deliveryTags": [],
             "confidence": 0.81},
        ]}
        second = {"characters": [
            {"canonicalName": "YoungLin", "displayName": "Young Lin", "presentation": "MASCULINE",
             "characterType": "HUMAN", "traits": ["brave"], "firstChapterIndex": 1,
             "occurrenceCount": 3, "confidence": 0.96, "aliases": []},
        ], "relationships": [], "speechSegments": [
            {"chapterIndex": 1, "sequenceNumber": -1, "textStartCodepoint": 2, "textEndCodepoint": 5,
             "speakerKind": "CHARACTER", "speakerCanonicalName": "YoungLin", "deliveryTags": [],
             "confidence": 0.96},
        ]}

        result = MODULE.merge_observations([first, second])
        reversed_result = MODULE.merge_observations([second, first])

        self.assertEqual(result["analysisFingerprintSha256"], reversed_result["analysisFingerprintSha256"])
        self.assertEqual(["Chen", "LinZhou"], [value["canonicalName"] for value in result["characters"]])
        lin = result["characters"][1]
        self.assertEqual(5, lin["occurrenceCount"])
        self.assertEqual(["brave", "calm"], lin["traits"])
        self.assertEqual(["YoungLin"], [value["alias"] for value in lin["aliases"]])
        self.assertEqual("LinZhou", result["relationships"][0]["sourceCanonicalName"])
        self.assertEqual(["LinZhou", "LinZhou"],
                         [value["speakerCanonicalName"] for value in result["speechSegments"]])

    def test_keeps_shared_alias_titles_as_separate_profiles(self):
        observation = {"characters": [
            {"canonicalName": "A", "displayName": "A", "presentation": "NEUTRAL",
             "characterType": "HUMAN", "traits": [], "firstChapterIndex": 0,
             "occurrenceCount": 1, "confidence": 0.9, "aliases": [
                 {"alias": "Master", "aliasType": "TITLE", "evidenceChapterIndex": 0,
                  "evidenceStartCodepoint": 0, "evidenceEndCodepoint": 1, "confidence": 0.9}]},
            {"canonicalName": "B", "displayName": "B", "presentation": "NEUTRAL",
             "characterType": "HUMAN", "traits": [], "firstChapterIndex": 0,
             "occurrenceCount": 1, "confidence": 0.9, "aliases": [
                 {"alias": "Master", "aliasType": "TITLE", "evidenceChapterIndex": 0,
                  "evidenceStartCodepoint": 2, "evidenceEndCodepoint": 3, "confidence": 0.9}]},
            {"canonicalName": "Master", "displayName": "Master", "presentation": "NEUTRAL",
             "characterType": "HUMAN", "traits": [], "firstChapterIndex": 1,
             "occurrenceCount": 1, "confidence": 0.9, "aliases": []},
        ], "relationships": [], "speechSegments": []}

        result = MODULE.merge_observations([observation])

        self.assertEqual(["A", "B", "Master"], [value["canonicalName"] for value in result["characters"]])

    def test_rejects_model_offsets_outside_the_frozen_prompt_chunk(self):
        observation = {"characters": [], "relationships": [], "speechSegments": [
            {"textStartCodepoint": 0, "textEndCodepoint": 5, "speakerKind": "NARRATOR",
             "speakerCanonicalName": None, "deliveryTags": [], "confidence": 0.8},
        ]}

        with self.assertRaisesRegex(ValueError, "range"):
            MODULE.normalize_observation(observation, 0, 20, "正文片段")

    def test_builds_low_confidence_evidence_from_frozen_chunk_only(self):
        observation = {"characters": [], "relationships": [], "speechSegments": [
            {"textStartCodepoint": 4, "textEndCodepoint": 8, "speakerKind": "UNKNOWN",
             "speakerCanonicalName": None, "deliveryTags": [], "confidence": 0.45},
            {"textStartCodepoint": 0, "textEndCodepoint": 2, "speakerKind": "NARRATOR",
             "speakerCanonicalName": None, "deliveryTags": [], "confidence": 0.80},
        ]}

        result = MODULE.normalize_observation(observation, 1, 10, "开头。\n\t“谁来了？”\n结尾。")

        self.assertEqual("开头。 “谁来了？” 结尾。", result["speechSegments"][0]["evidenceExcerpt"])
        self.assertNotIn("evidenceExcerpt", result["speechSegments"][1])

    def test_limits_low_confidence_evidence_to_the_documented_codepoint_budget(self):
        chunk = "甲" * 400 + "“谁来了？”" + "乙" * 400
        excerpt = MODULE.low_confidence_evidence_excerpt(chunk, 400, 406)

        self.assertLessEqual(len(excerpt), MODULE.LOW_CONFIDENCE_EVIDENCE_MAX_CODEPOINTS)
        self.assertIn("谁来了", excerpt)

    def test_normalizes_decimal_string_confidence_without_accepting_non_numeric_values(self):
        self.assertEqual(0.85, MODULE.confidence("0.85"))
        with self.assertRaisesRegex(ValueError, "confidence"):
            MODULE.confidence("high")
        with self.assertRaisesRegex(ValueError, "confidence"):
            MODULE.confidence("NaN")

    def test_rejects_insecure_or_credential_bearing_model_endpoints(self):
        with self.assertRaisesRegex(ValueError, "configuration"):
            MODULE.OpenAiCompatibleAnalysisClient("http://model.example/v1/chat", "key", "model", 30)
        with self.assertRaisesRegex(ValueError, "configuration"):
            MODULE.OpenAiCompatibleAnalysisClient("https://key@model.example/v1/chat", "key", "model", 30)

    def test_tencent_hunyuan_client_signs_and_normalizes_json_choice(self):
        payload = json_bytes({"Response": {"Choices": [{"Message": {"Content": "{}"}}]}})
        client = MODULE.TencentHunyuanAnalysisClient("test-secret-id", "test-secret-key", "hunyuan-a13b", 30)
        with patch.object(MODULE.time, "time", return_value=1_700_000_000), \
                patch.object(MODULE.urllib.request, "urlopen", return_value=ModelResponse(payload)) as urlopen:
            result = client._chat("instructions", "input")

        self.assertEqual({}, result)
        request = urlopen.call_args.args[0]
        headers = {key.lower(): value for key, value in request.header_items()}
        self.assertEqual("ChatCompletions", headers["x-tc-action"])
        self.assertEqual("ap-guangzhou", headers["x-tc-region"])
        self.assertIn("SignedHeaders=content-type;host;x-tc-action;x-tc-region", headers["authorization"])
        self.assertNotIn("test-secret-key", headers["authorization"])
        payload = __import__("json").loads(request.data.decode("utf-8"))
        self.assertEqual("hunyuan-a13b", payload["Model"])
        self.assertFalse(payload["Stream"])
        self.assertNotIn("ResponseFormat", payload)

    def test_tencent_hunyuan_client_accepts_top_level_non_streaming_response(self):
        payload = json_bytes({"Choices": [{"Message": {"Content": "{}"}}]})
        client = MODULE.TencentHunyuanAnalysisClient("test-secret-id", "test-secret-key", "hunyuan-a13b", 30)
        with patch.object(MODULE.urllib.request, "urlopen", return_value=ModelResponse(payload)):
            self.assertEqual({}, client._chat("instructions", "input"))

    def test_tencent_hunyuan_client_rejects_invalid_region(self):
        with self.assertRaisesRegex(ValueError, "region"):
            MODULE.TencentHunyuanAnalysisClient("id", "key", "hunyuan-a13b", 30, region="invalid region")

    def test_tencent_hunyuan_client_accepts_one_markdown_json_fence(self):
        payload = json_bytes({"Response": {"Choices": [{"Message": {"Content": "```json\n{}\n```"}}]}})
        client = MODULE.TencentHunyuanAnalysisClient("test-secret-id", "test-secret-key", "hunyuan-a13b", 30)
        with patch.object(MODULE.urllib.request, "urlopen", return_value=ModelResponse(payload)):
            self.assertEqual({}, client._chat("instructions", "input"))

    def test_reports_stable_contract_for_unclassified_runtime_failure(self):
        with patch.dict(MODULE.os.environ, {"TASK_ERROR_FILE": "/tmp/task-error.json"}, clear=True), \
                patch.object(MODULE, "write_task_error") as write_error:
            MODULE.report_task_failure(RuntimeError("unexpected"))

        write_error.assert_called_once_with("AUDIOBOOK_ANALYSIS_RUNTIME_FAILURE", "TRANSIENT",
                                            "AUDIOBOOK_ANALYSIS_RUNTIME_FAILURE")

    def test_reports_provider_unavailable_contract(self):
        with patch.dict(MODULE.os.environ, {"TASK_ERROR_FILE": "/tmp/task-error.json"}, clear=True), \
                patch.object(MODULE, "write_task_error") as write_error:
            MODULE.report_task_failure(RuntimeError("Audiobook analysis model endpoint is unavailable"))

        write_error.assert_called_once_with("AUDIOBOOK_ANALYSIS_PROVIDER_UNAVAILABLE", "TRANSIENT",
                                            "AUDIOBOOK_ANALYSIS_PROVIDER_UNAVAILABLE")

    def test_reader_service_client_identifies_invalid_analysis_response_operation(self):
        client = MODULE.ReaderServiceClient("http://reader.example", "reader-token")
        with patch.object(MODULE.urllib.request, "urlopen", return_value=ModelResponse(b"")):
            with self.assertRaisesRegex(RuntimeError, "book analysis input"):
                client.input("generation-id")

    def test_ner_client_identifies_invalid_json_response(self):
        client = MODULE.NerCandidateClient("https://ner.example/v1/entities", "", 30, 0)
        with patch.object(MODULE.urllib.request, "urlopen", return_value=ModelResponse(b"")):
            with self.assertRaisesRegex(RuntimeError, "NER response is invalid JSON"):
                client.extract("林舟")

    def test_main_identifies_invalid_task_context(self):
        with tempfile.TemporaryDirectory() as directory:
            context_path = Path(directory) / "context.json"
            context_path.write_text("not-json", encoding="utf-8")
            with patch.dict(MODULE.os.environ, {"TASK_CONTEXT_FILE": str(context_path)}, clear=True):
                with self.assertRaisesRegex(RuntimeError, "task context is invalid"):
                    MODULE.main()

    def test_hunyuan_empty_dedicated_credentials_fall_back_to_tts_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            context_path = Path(directory) / "context.json"
            context_path.write_text('{"parameters":{}}', encoding="utf-8")
            captured = {}

            def create_analyzer(secret_id, secret_key, *_args):
                captured["secretId"] = secret_id
                captured["secretKey"] = secret_key
                return object()

            with patch.dict(MODULE.os.environ, {
                    "TASK_CONTEXT_FILE": str(context_path),
                    "AUDIOBOOK_ANALYSIS_PROVIDER": "TENCENT_HUNYUAN",
                    "AUDIOBOOK_ANALYSIS_MODEL": "hunyuan-a13b",
                    "TENCENT_HUNYUAN_SECRET_ID": "",
                    "TENCENT_HUNYUAN_SECRET_KEY": "",
                    "TENCENT_TTS_SECRET_ID": "tts-secret-id",
                    "TENCENT_TTS_SECRET_KEY": "tts-secret-key",
                }, clear=True), \
                    patch.object(MODULE, "StorageGatewayClient"), \
                    patch.object(MODULE, "ReaderServiceClient"), \
                    patch.object(MODULE, "TencentHunyuanAnalysisClient", side_effect=create_analyzer), \
                    patch.object(MODULE, "execute", return_value={}), \
                    patch.object(MODULE, "write_result"):
                MODULE.main()

        self.assertEqual("tts-secret-id", captured["secretId"])
        self.assertEqual("tts-secret-key", captured["secretKey"])

    def test_retries_transport_failure_without_replaying_invalid_model_response(self):
        payload = json_bytes({"choices": [{"message": {"content": "{}"}}]})
        client = MODULE.OpenAiCompatibleAnalysisClient("https://model.example/v1/chat", "key", "model", 30, 1)
        with patch.object(MODULE.urllib.request, "urlopen", side_effect=[
                urllib.error.URLError("temporary"), ModelResponse(payload)]) as urlopen, \
                patch.object(MODULE.time, "sleep") as sleep:
            result = client._chat("instructions", "input")

        self.assertEqual({}, result)
        self.assertEqual(2, urlopen.call_count)
        sleep.assert_called_once_with(0.5)

    def test_rejects_oversized_model_response_before_json_parsing(self):
        client = MODULE.OpenAiCompatibleAnalysisClient("https://model.example/v1/chat", "key", "model", 30, 0)
        with patch.object(MODULE.urllib.request, "urlopen", return_value=ModelResponse(
                b"x" * (MODULE.MAX_MODEL_RESPONSE_BYTES + 1))):
            with self.assertRaisesRegex(RuntimeError, "exceeds limit"):
                client._chat("instructions", "input")


def json_bytes(value):
    """Encode one mocked model envelope without introducing a provider dependency."""
    import json
    return json.dumps(value, separators=(",", ":")).encode("utf-8")


if __name__ == "__main__":
    unittest.main()
