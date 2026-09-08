#!/usr/bin/env python3
"""Analyze frozen audiobook chapters into auditable characters, relationships, and speakers."""

from __future__ import annotations

import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import re
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import time

from mytools_task_sdk.errors import write_task_error
from mytools_task_sdk.storage import StorageGatewayClient

MAX_BOOK_BYTES = 50 * 1024 * 1024
MAX_CHAPTER_BYTES = 10 * 1024 * 1024
MAX_CHAPTERS = 20_000
MAX_PROMPT_CHARACTERS = 4_000
MAX_CHARACTERS = 5_000
MAX_RELATIONSHIPS = 20_000
MAX_SPEECH_SEGMENTS = 200_000
MAX_CANDIDATE_NAMES = 200
MAX_KNOWN_BOOK_CHARACTER_HINTS = 200
MAX_KNOWN_ALIASES_PER_CHARACTER = 16
MAX_KNOWN_BOOK_CHARACTER_HINT_CODEPOINTS = 4_096
MAX_QUOTE_RANGES = 500
MAX_MODEL_RESPONSE_BYTES = 8 * 1024 * 1024
MAX_MODEL_RETRIES = 5
MAX_NER_RESPONSE_BYTES = 2 * 1024 * 1024
MAX_NER_RETRIES = 3
MAX_NER_ENTITIES = 200
MAX_CANDIDATE_NAME_LENGTH = 64
LOW_CONFIDENCE_EVIDENCE_MAX_CODEPOINTS = 320
LOW_CONFIDENCE_EVIDENCE_CONTEXT_CODEPOINTS = 72
ANALYSIS_RULE_VERSION = "BOOK_ANALYSIS_RULES_V4"
RETRYABLE_MODEL_HTTP_STATUS = frozenset({408, 429, 500, 502, 503, 504})
SENTENCE_ENDINGS = re.compile(r"[。！？!?；;]\s*")
TRACE_VERSION = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}\Z")
PRESENTATIONS = {"FEMININE", "MASCULINE", "NON_BINARY", "NEUTRAL", "UNKNOWN"}
SPEAKER_KINDS = {"CHARACTER", "NARRATOR", "UNKNOWN"}
QUOTED_SPEECH = re.compile(r"[“\"]([^”\"\n]{1,5000})[”\"]")
SPEAKER_NAME = re.compile(
    r"(?<![一-龥])([一-龥]{2,4}?)(?=[，,、\s]{0,3}(?:说道|问道|答道|回答|喊道|叫道|笑道|低声说|轻声说|高声说|说|问|答|喊|叫|笑))")
SPEAKER_NAME_AFTER_CONNECTOR = re.compile(
    r"(?:看向|面对|对|向|跟|同|和|给|朝|冲)([一-龥]{2,4}?)(?=[，,、\s]{0,3}(?:说道|问道|答道|回答|喊道|叫道|笑道|低声说|轻声说|高声说|说|问|答|喊|叫|笑))")
NON_CHARACTER_CANDIDATES = frozenset({"一个人", "众人", "大家", "自己", "我们", "他们", "她们", "你们", "有人"})
TENCENT_HUNYUAN_ENDPOINT = "https://hunyuan.ai.tencentcloudapi.com"
TENCENT_HUNYUAN_HOST = "hunyuan.ai.tencentcloudapi.com"
TENCENT_HUNYUAN_ACTION = "ChatCompletions"
TENCENT_HUNYUAN_VERSION = "2023-09-01"
TENCENT_HUNYUAN_SERVICE = "hunyuan"
TENCENT_HUNYUAN_REGION = re.compile(r"[a-z0-9-]{1,64}")
MARKDOWN_JSON_FENCE = re.compile(r"\A```(?:json)?\s*(\{.*\})\s*```\Z", re.DOTALL | re.IGNORECASE)


class ReaderServiceClient:
    """Call the Reader Service's authenticated book-analysis contract."""

    def __init__(self, base_url: str, token: str):
        if not token:
            raise ValueError("Reader Service internal token is missing")
        self._base_url = base_url.rstrip("/")
        self._token = token

    def input(self, generation_id: str) -> dict:
        """Read the frozen chapter text locations for one generation."""
        return self._request("GET", f"/{generation_id}/book-analysis-input")

    def save(self, generation_id: str, result: dict) -> dict:
        """Atomically persist the complete normalized book-analysis result."""
        return self._request("POST", f"/{generation_id}/book-analysis", result)

    def complete(self, generation_id: str) -> dict:
        """Complete analysis and trigger the next asynchronous stage."""
        return self._request("POST", f"/{generation_id}/complete-book-analysis", {})

    def _request(self, method: str, path: str, body: dict | None = None) -> dict:
        payload = None if body is None else json.dumps(body, ensure_ascii=False,
                                                       separators=(",", ":")).encode("utf-8")
        headers = {"Authorization": f"Bearer {self._token}", "Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(
            f"{self._base_url}/api/internal/v1/audiobook-generations{path}", data=payload,
            method=method, headers=headers)
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read()
        try:
            result = json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError as exception:
            if path.endswith("/book-analysis-input"):
                operation = "book analysis input"
            elif path.endswith("/book-analysis"):
                operation = "book analysis save"
            elif path.endswith("/complete-book-analysis"):
                operation = "book analysis completion"
            else:
                operation = "audiobook analysis request"
            raise RuntimeError("Reader Service returned invalid JSON for " + operation) from exception
        if not isinstance(result, dict):
            raise RuntimeError("Reader Service returned an invalid audiobook analysis response")
        return result


class OpenAiCompatibleAnalysisClient:
    """Use a configured JSON-only analysis model without embedding any provider credential."""

    def __init__(self, endpoint: str, api_key: str, model: str, timeout_seconds: int, max_retries: int = 3,
                 ner_client: NerCandidateClient | None = None):
        parsed_endpoint = urllib.parse.urlsplit(endpoint)
        if (not endpoint or not api_key or not model or not TRACE_VERSION.fullmatch(model) or
                parsed_endpoint.scheme != "https" or
                not parsed_endpoint.hostname or parsed_endpoint.username is not None or
                parsed_endpoint.password is not None):
            raise ValueError("Audiobook analysis runtime configuration is incomplete")
        if not isinstance(timeout_seconds, int) or timeout_seconds < 1 or timeout_seconds > 180:
            raise ValueError("Audiobook analysis timeout is invalid")
        if not isinstance(max_retries, int) or max_retries < 0 or max_retries > MAX_MODEL_RETRIES:
            raise ValueError("Audiobook analysis retry count is invalid")
        self._endpoint = endpoint
        self._api_key = api_key
        self._model = model
        self._timeout_seconds = timeout_seconds
        self._max_retries = max_retries
        self._ner_client = ner_client

    @property
    def model_version(self) -> str:
        """Return the configured model identifier that is safe to persist as execution provenance."""
        return self._model

    def analyze_chunk(self, chapter_index: int, title: str, offset: int, text: str,
                      known_characters: list[dict] | None = None) -> dict:
        """Return one bounded, JSON-only observation for a frozen chapter span."""
        schema = {
            "characters": [{"canonicalName": "string", "displayName": "string",
                            "presentation": "FEMININE|MASCULINE|NON_BINARY|NEUTRAL|UNKNOWN",
                            "characterType": "string", "traits": ["string"],
                            "firstChapterIndex": chapter_index, "occurrenceCount": 1,
                            "confidence": 0.0, "aliases": [{"alias": "string", "aliasType": "string",
                            "evidenceStartCodepoint": 0, "evidenceEndCodepoint": 1, "confidence": 0.0}]}],
            "relationships": [{"sourceCanonicalName": "string", "targetCanonicalName": "string",
                                "relationshipType": "string", "direction": "string",
                                "evidenceStartCodepoint": 0, "evidenceEndCodepoint": 1, "confidence": 0.0}],
            "speechSegments": [{"sequenceNumber": 0, "textStartCodepoint": 0, "textEndCodepoint": 1,
                                "speakerKind": "CHARACTER|NARRATOR|UNKNOWN",
                                "speakerCanonicalName": "string or null", "deliveryTags": ["string"],
                                "confidence": 0.0}]
        }
        instructions = (
            "You extract facts from one frozen book span for audiobook production. Return JSON only. "
            "Never invent facts. Identify recurring named characters, aliases, voice presentation, traits, "
            "relationships supported by the span, and contiguous spoken/narrated segments. Offset values must be "
            "Unicode code-point offsets within the supplied text. SpeakerKind CHARACTER requires canonicalName; "
            "NARRATOR and UNKNOWN must use null. Candidate names and quoted ranges are non-authoritative hints; "
            "Known book identities are also non-authoritative hints: reuse their canonicalName only when this "
            "span supplies evidence, and never merge two identities only because their names are similar. "
            "Verify every fact against the supplied text. Omit uncertain facts instead of guessing. "
            "Candidate entity hints are non-authoritative and include a local evidence offset only. Schema: "
            + json.dumps(schema, ensure_ascii=False, separators=(",", ":")))
        entity_hints = candidate_entities(text, self._ner_client)
        names = candidate_names(text)
        for entity in entity_hints:
            if entity["name"] not in names:
                names.append(entity["name"])
        user = json.dumps({"chapterIndex": chapter_index, "chapterTitle": title, "chunkStartCodepoint": offset,
                           "candidateCharacterNames": names[:MAX_CANDIDATE_NAMES],
                           "candidateEntityHints": entity_hints,
                           "knownBookCharacterHints": known_character_hints(known_characters),
                           "quotedSpeechRanges": quoted_speech_ranges(text), "text": text},
                          ensure_ascii=False, separators=(",", ":"))
        return self._chat(instructions, user)

    def _chat(self, instructions: str, user: str) -> dict:
        payload = {
            "model": self._model,
            "temperature": 0,
            "response_format": {"type": "json_object"},
            "messages": [{"role": "system", "content": instructions}, {"role": "user", "content": user}],
        }
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(self._endpoint, data=body, method="POST", headers={
            "Authorization": f"Bearer {self._api_key}", "Content-Type": "application/json",
            "Accept": "application/json",
        })
        response = self._request_with_retry(request)
        choices = response.get("choices")
        if not isinstance(choices, list) or not choices:
            raise RuntimeError("Audiobook analysis model response has no choices")
        content = choices[0].get("message", {}).get("content")
        if not isinstance(content, str) or not content:
            raise RuntimeError("Audiobook analysis model response has no JSON content")
        try:
            result = json.loads(content)
        except json.JSONDecodeError as exception:
            raise RuntimeError("Audiobook analysis model returned invalid JSON") from exception
        if not isinstance(result, dict):
            raise RuntimeError("Audiobook analysis model result must be an object")
        return result

    def _request_with_retry(self, request: urllib.request.Request) -> dict:
        """Call the model with bounded retries only for retryable transport and provider failures."""
        for attempt in range(self._max_retries + 1):
            try:
                with urllib.request.urlopen(request, timeout=self._timeout_seconds) as response:
                    raw = response.read(MAX_MODEL_RESPONSE_BYTES + 1)
                if len(raw) > MAX_MODEL_RESPONSE_BYTES:
                    raise RuntimeError("Audiobook analysis model response exceeds limit")
                try:
                    return json.loads(raw.decode("utf-8"))
                except json.JSONDecodeError as exception:
                    raise RuntimeError("Audiobook analysis model response is invalid JSON") from exception
            except urllib.error.HTTPError as exception:
                if exception.code not in RETRYABLE_MODEL_HTTP_STATUS or attempt >= self._max_retries:
                    raise RuntimeError(f"Audiobook analysis model request failed with HTTP {exception.code}") from exception
            except (urllib.error.URLError, TimeoutError, OSError) as exception:
                if attempt >= self._max_retries:
                    raise RuntimeError("Audiobook analysis model endpoint is unavailable") from exception
            time.sleep(0.5 * (2 ** attempt))
        raise RuntimeError("Audiobook analysis model request did not complete")


class TencentHunyuanAnalysisClient(OpenAiCompatibleAnalysisClient):
    """Use Tencent Hunyuan with TC3 signing while preserving the normalized analysis contract."""

    def __init__(self, secret_id: str, secret_key: str, model: str, timeout_seconds: int,
                 max_retries: int = 3, region: str = "ap-guangzhou",
                 ner_client: NerCandidateClient | None = None):
        if (not isinstance(secret_id, str) or not secret_id.strip() or len(secret_id.strip()) > 128
                or not isinstance(secret_key, str) or not secret_key.strip() or len(secret_key.strip()) > 4096
                or not isinstance(model, str) or TRACE_VERSION.fullmatch(model.strip()) is None):
            raise ValueError("Tencent Hunyuan analysis runtime configuration is incomplete")
        if not isinstance(timeout_seconds, int) or timeout_seconds < 1 or timeout_seconds > 180:
            raise ValueError("Tencent Hunyuan analysis timeout is invalid")
        if not isinstance(max_retries, int) or max_retries < 0 or max_retries > MAX_MODEL_RETRIES:
            raise ValueError("Tencent Hunyuan analysis retry count is invalid")
        if (not isinstance(region, str)
                or TENCENT_HUNYUAN_REGION.fullmatch(region.strip()) is None):
            raise ValueError("Tencent Hunyuan analysis region is invalid")
        self._secret_id = secret_id.strip()
        self._secret_key = secret_key.strip()
        self._model = model.strip()
        self._timeout_seconds = timeout_seconds
        self._max_retries = max_retries
        self._region = region.strip()
        self._ner_client = ner_client

    def _chat(self, instructions: str, user: str) -> dict:
        """Call the non-streaming Hunyuan contract and accept only one JSON object response."""
        payload = {
            "Model": self._model,
            "Stream": False,
            "Temperature": 0,
            "Messages": [{"Role": "system", "Content": instructions},
                         {"Role": "user", "Content": user}],
        }
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(TENCENT_HUNYUAN_ENDPOINT, data=body, method="POST",
                                         headers=self._headers(body))
        result = self._request_with_retry(request)
        # 腾讯云 API 在失败时包装 Response，而非流式成功响应可直接返回 Choices。
        response = result.get("Response") if isinstance(result.get("Response"), dict) else result
        if not isinstance(response, dict):
            raise RuntimeError("Tencent Hunyuan response is invalid")
        error = response.get("Error")
        if error is not None:
            code = error.get("Code") if isinstance(error, dict) else None
            if not isinstance(code, str) or not TRACE_VERSION.fullmatch(code):
                code = "unknown"
            raise RuntimeError(f"Tencent Hunyuan rejected analysis with code {code}")
        choices = response.get("Choices")
        if not isinstance(choices, list) or not choices:
            raise RuntimeError("Tencent Hunyuan response has no choices")
        message = choices[0].get("Message") if isinstance(choices[0], dict) else None
        content = message.get("Content") if isinstance(message, dict) else None
        if not isinstance(content, str) or not content:
            raise RuntimeError("Tencent Hunyuan response has no JSON content")
        try:
            result = json.loads(unwrap_json_content(content))
        except json.JSONDecodeError as exception:
            raise RuntimeError("Tencent Hunyuan returned invalid JSON") from exception
        if not isinstance(result, dict):
            raise RuntimeError("Tencent Hunyuan result must be an object")
        return result

    def _headers(self, body: bytes) -> dict[str, str]:
        """Create a TC3-signed Hunyuan request including all Tencent common headers in scope."""
        timestamp = int(time.time())
        if timestamp < 1:
            raise RuntimeError("Tencent Hunyuan clock is invalid")
        date = time.strftime("%Y-%m-%d", time.gmtime(timestamp))
        content_type = "application/json; charset=utf-8"
        canonical_headers = ("content-type:" + content_type + "\nhost:" + TENCENT_HUNYUAN_HOST
                             + "\nx-tc-action:" + TENCENT_HUNYUAN_ACTION.lower()
                             + "\nx-tc-region:" + self._region + "\n")
        signed_headers = "content-type;host;x-tc-action;x-tc-region"
        canonical_request = ("POST\n/\n\n" + canonical_headers + "\n" + signed_headers + "\n"
                             + hashlib.sha256(body).hexdigest())
        credential_scope = f"{date}/{TENCENT_HUNYUAN_SERVICE}/tc3_request"
        string_to_sign = ("TC3-HMAC-SHA256\n" + str(timestamp) + "\n" + credential_scope + "\n"
                          + hashlib.sha256(canonical_request.encode("utf-8")).hexdigest())
        signing_key = self._hmac(("TC3" + self._secret_key).encode("utf-8"), date)
        signing_key = self._hmac(signing_key, TENCENT_HUNYUAN_SERVICE)
        signing_key = self._hmac(signing_key, "tc3_request")
        signature = hmac.new(signing_key, string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
        authorization = ("TC3-HMAC-SHA256 Credential=" + self._secret_id + "/" + credential_scope
                         + ", SignedHeaders=" + signed_headers + ", Signature=" + signature)
        return {
            "Authorization": authorization,
            "Content-Type": content_type,
            "Host": TENCENT_HUNYUAN_HOST,
            "X-TC-Action": TENCENT_HUNYUAN_ACTION,
            "X-TC-Region": self._region,
            "X-TC-Timestamp": str(timestamp),
            "X-TC-Version": TENCENT_HUNYUAN_VERSION,
        }

    @staticmethod
    def _hmac(key: bytes, value: str) -> bytes:
        """Calculate one TC3 HMAC stage without retaining derived credentials."""
        return hmac.new(key, value.encode("utf-8"), hashlib.sha256).digest()


def unwrap_json_content(content: str) -> str:
    """Accept a JSON object wrapped in one provider-generated Markdown fence only."""
    normalized = content.strip()
    fenced = MARKDOWN_JSON_FENCE.fullmatch(normalized)
    return fenced.group(1).strip() if fenced is not None else normalized


class NerCandidateClient:
    """Read non-authoritative PERSON candidates from a deployment-configured independent NER endpoint."""

    def __init__(self, endpoint: str, api_key: str, timeout_seconds: int, max_retries: int = 2):
        parsed_endpoint = urllib.parse.urlsplit(endpoint)
        if (not endpoint or parsed_endpoint.scheme != "https" or not parsed_endpoint.hostname or
                parsed_endpoint.username is not None or parsed_endpoint.password is not None):
            raise ValueError("Audiobook NER runtime configuration is incomplete")
        if not isinstance(timeout_seconds, int) or timeout_seconds < 1 or timeout_seconds > 180:
            raise ValueError("Audiobook NER timeout is invalid")
        if not isinstance(max_retries, int) or max_retries < 0 or max_retries > MAX_NER_RETRIES:
            raise ValueError("Audiobook NER retry count is invalid")
        self._endpoint = endpoint
        self._api_key = api_key
        self._timeout_seconds = timeout_seconds
        self._max_retries = max_retries

    def extract(self, text: str) -> list[dict]:
        """Return validated local PERSON spans using the documented NER response contract."""
        if not isinstance(text, str) or not text:
            raise ValueError("Frozen chapter text is invalid")
        payload = json.dumps({"text": text}, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if self._api_key:
            headers["Authorization"] = f"Bearer {self._api_key}"
        request = urllib.request.Request(self._endpoint, data=payload, method="POST", headers=headers)
        response = self._request_with_retry(request)
        entities = response.get("entities")
        if not isinstance(entities, list) or len(entities) > MAX_NER_ENTITIES:
            raise ValueError("Audiobook NER response entities are invalid")
        result: list[dict] = []
        for entity in entities:
            if not isinstance(entity, dict) or entity.get("type") != "PERSON":
                continue
            start = entity.get("startCodepoint")
            end = entity.get("endCodepoint")
            value = candidate_name(entity.get("text"))
            if (not isinstance(start, int) or not isinstance(end, int) or start < 0 or end <= start or
                    end > len(text) or text[start:end] != value or value in NON_CHARACTER_CANDIDATES):
                raise ValueError("Audiobook NER person entity is invalid")
            result.append({"name": value, "startCodepoint": start, "endCodepoint": end,
                           "source": "NER", "confidence": confidence(entity.get("confidence", 1.0))})
        return result

    def _request_with_retry(self, request: urllib.request.Request) -> dict:
        """Use bounded retries only for retryable NER transport and provider failures."""
        for attempt in range(self._max_retries + 1):
            try:
                with urllib.request.urlopen(request, timeout=self._timeout_seconds) as response:
                    raw = response.read(MAX_NER_RESPONSE_BYTES + 1)
                if len(raw) > MAX_NER_RESPONSE_BYTES:
                    raise RuntimeError("Audiobook NER response exceeds limit")
                try:
                    decoded = json.loads(raw.decode("utf-8"))
                except json.JSONDecodeError as exception:
                    raise RuntimeError("Audiobook NER response is invalid JSON") from exception
                if not isinstance(decoded, dict):
                    raise RuntimeError("Audiobook NER response is invalid")
                return decoded
            except urllib.error.HTTPError as exception:
                if exception.code not in RETRYABLE_MODEL_HTTP_STATUS or attempt >= self._max_retries:
                    raise RuntimeError(f"Audiobook NER request failed with HTTP {exception.code}") from exception
            except json.JSONDecodeError as exception:
                raise RuntimeError("Audiobook NER response is invalid JSON") from exception
            except (urllib.error.URLError, TimeoutError, OSError) as exception:
                if attempt >= self._max_retries:
                    raise RuntimeError("Audiobook NER endpoint is unavailable") from exception
            time.sleep(0.5 * (2 ** attempt))
        raise RuntimeError("Audiobook NER request did not complete")


def chunks(text: str, limit: int = MAX_PROMPT_CHARACTERS) -> list[tuple[int, str]]:
    """Split a chapter on sentence boundaries while preserving code-point offsets."""
    if not text:
        raise ValueError("Frozen chapter text is empty")
    result: list[tuple[int, str]] = []
    start = 0
    while start < len(text):
        end = min(start + limit, len(text))
        if end < len(text):
            boundaries = [match.end() for match in SENTENCE_ENDINGS.finditer(text, start, end)]
            if boundaries:
                end = boundaries[-1]
        if end <= start:
            end = min(start + limit, len(text))
        result.append((start, text[start:end]))
        start = end
    return result


def candidate_names(text: str) -> list[str]:
    """从说话动词附近提取受限的中文人名候选，仅作为模型提示而非事实。"""
    if not isinstance(text, str):
        raise ValueError("Frozen chapter text is invalid")
    result: list[str] = []
    for value, _start, _end in speaker_name_hints(text):
        if value in NON_CHARACTER_CANDIDATES or value in result:
            continue
        result.append(value)
        if len(result) == MAX_CANDIDATE_NAMES:
            break
    return result


def speaker_name_hints(text: str) -> list[tuple[str, int, int]]:
    """Return bounded name spans from direct speech verbs and common recipient connectors."""
    values: list[tuple[str, int, int]] = []
    for pattern in (SPEAKER_NAME, SPEAKER_NAME_AFTER_CONNECTOR):
        for match in pattern.finditer(text):
            values.append((match.group(1), match.start(1), match.end(1)))
    return sorted(set(values), key=lambda value: (value[1], value[2], value[0]))


def candidate_name(value: object) -> str:
    """Validate one bounded candidate name without promoting it to a persisted character fact."""
    if (not isinstance(value, str) or not (normalized := value.strip()) or
            len(normalized) > MAX_CANDIDATE_NAME_LENGTH or normalized != value or
            any(character.isspace() or ord(character) < 32 for character in normalized)):
        raise ValueError("Audiobook NER candidate name is invalid")
    return normalized


def candidate_entities(text: str, ner_client: NerCandidateClient | None = None) -> list[dict]:
    """Combine validated independent NER spans with deterministic speech-verb hints for model review."""
    if not isinstance(text, str):
        raise ValueError("Frozen chapter text is invalid")
    result: list[dict] = []
    seen: set[tuple[str, int, int]] = set()

    def add(value: dict) -> None:
        """Keep each exact local entity span once while preserving the stronger independent NER evidence."""
        key = (value["name"], value["startCodepoint"], value["endCodepoint"])
        if key not in seen and len(result) < MAX_CANDIDATE_NAMES:
            seen.add(key)
            result.append(value)

    if ner_client is not None:
        for entity in ner_client.extract(text):
            add(entity)
    for value, start, end in speaker_name_hints(text):
        if value not in NON_CHARACTER_CANDIDATES:
            add({"name": value, "startCodepoint": start, "endCodepoint": end,
                 "source": "SPEECH_VERB", "confidence": 0.6})
    return sorted(result, key=lambda value: (value["startCodepoint"], value["endCodepoint"],
                                              value["name"], value["source"]))


def quoted_speech_ranges(text: str) -> list[dict[str, int]]:
    """返回分块内直引语正文范围，供模型优先识别可能的说话人片段。"""
    if not isinstance(text, str):
        raise ValueError("Frozen chapter text is invalid")
    return [{"startCodepoint": match.start(1), "endCodepoint": match.end(1)}
            for match in QUOTED_SPEECH.finditer(text)][:MAX_QUOTE_RANGES]


def confidence(value: object) -> float:
    """Return a bounded confidence score and accept only decimal-string model serialization."""
    if isinstance(value, str):
        normalized = value.strip()
        if re.fullmatch(r"(?:0(?:\.\d+)?|1(?:\.0+)?)", normalized) is None:
            raise ValueError("Analysis confidence is invalid")
        number = float(normalized)
    elif isinstance(value, (int, float)) and not isinstance(value, bool):
        number = float(value)
    else:
        raise ValueError("Analysis confidence is invalid")
    if not math.isfinite(number):
        raise ValueError("Analysis confidence is invalid")
    return max(0.0, min(1.0, number))


def name(value: object) -> str:
    """Normalize a model-supplied stable character name."""
    if not isinstance(value, str) or not (normalized := value.strip()) or len(normalized) > 256:
        raise ValueError("Analysis character name is invalid")
    return normalized


def tag_list(value: object, maximum: int, width: int) -> list[str]:
    """Normalize a bounded, unique list of nonempty tags."""
    if not isinstance(value, list) or len(value) > maximum:
        raise ValueError("Analysis tag list is invalid")
    tags: list[str] = []
    for item in value:
        if not isinstance(item, str) or not (tag := item.strip()) or len(tag) > width or tag in tags:
            raise ValueError("Analysis tag is invalid")
        tags.append(tag)
    return tags


def known_character_hints(values: list[dict] | None) -> list[dict]:
    """Normalize a bounded, non-authoritative book-level identity hint list for one model call."""
    if values is None:
        return []
    if not isinstance(values, list) or len(values) > MAX_CHARACTERS:
        raise ValueError("Known audiobook character hints are invalid")
    result: list[dict] = []
    canonical_names: set[str] = set()
    for value in values:
        if not isinstance(value, dict):
            raise ValueError("Known audiobook character hints are invalid")
        canonical = name(value.get("canonicalName"))
        if canonical in canonical_names:
            raise ValueError("Known audiobook character hints are invalid")
        canonical_names.add(canonical)
        raw_aliases = value.get("aliases", [])
        if not isinstance(raw_aliases, list) or len(raw_aliases) > MAX_KNOWN_ALIASES_PER_CHARACTER:
            raise ValueError("Known audiobook character hints are invalid")
        aliases: list[str] = []
        for raw_alias in raw_aliases:
            alias = name(raw_alias)
            if alias == canonical or alias in aliases:
                raise ValueError("Known audiobook character hints are invalid")
            aliases.append(alias)
        result.append({"canonicalName": canonical, "aliases": aliases})
    limited: list[dict] = []
    selected: list[tuple[dict, dict]] = []
    used = 0
    for value in sorted(result, key=lambda item: (item["canonicalName"].casefold(), item["canonicalName"])):
        if len(limited) == MAX_KNOWN_BOOK_CHARACTER_HINTS:
            break
        canonical = value["canonicalName"]
        if used + len(canonical) > MAX_KNOWN_BOOK_CHARACTER_HINT_CODEPOINTS:
            continue
        used += len(canonical)
        limited_value = {"canonicalName": canonical, "aliases": []}
        limited.append(limited_value)
        selected.append((value, limited_value))
    # 先保留尽可能多的规范名，再使用剩余预算补充别名，避免少数长别名挤掉主要角色候选。
    for source, target in selected:
        for alias in source["aliases"]:
            if used + len(alias) > MAX_KNOWN_BOOK_CHARACTER_HINT_CODEPOINTS:
                continue
            target["aliases"].append(alias)
            used += len(alias)
    return limited


def remember_character_hints(values: dict[str, dict], observation: dict) -> None:
    """Add validated local facts to later chunks' candidate-only book identity context."""
    for character in observation["characters"]:
        canonical = character["canonicalName"]
        remembered = values.setdefault(canonical, {"canonicalName": canonical, "aliases": []})
        aliases = remembered["aliases"]
        for alias in character["aliases"]:
            alias_name = alias["alias"]
            if alias_name != canonical and alias_name not in aliases:
                aliases.append(alias_name)
        aliases.sort(key=lambda value: (value.casefold(), value))
        del aliases[MAX_KNOWN_ALIASES_PER_CHARACTER:]


def evidence(raw: dict, chapter_index: int, chunk_offset: int, chunk_length: int) -> tuple[int, int, int]:
    """Convert a mandatory local evidence interval to global chapter code-point offsets."""
    start = raw.get("evidenceStartCodepoint")
    end = raw.get("evidenceEndCodepoint")
    if (not isinstance(start, int) or not isinstance(end, int) or start < 0 or end <= start or
            end > chunk_length):
        raise ValueError("Analysis evidence range is invalid")
    return chapter_index, start + chunk_offset, end + chunk_offset


def low_confidence_evidence_excerpt(chunk: str, start: int, end: int) -> str:
    """Build a bounded, whitespace-normalized evidence excerpt from the frozen prompt chunk."""
    if not isinstance(chunk, str) or start < 0 or end <= start or end > len(chunk):
        raise ValueError("Analysis speech segment evidence is invalid")
    if end - start >= LOW_CONFIDENCE_EVIDENCE_MAX_CODEPOINTS:
        raw = chunk[start:start + LOW_CONFIDENCE_EVIDENCE_MAX_CODEPOINTS - 1] + "…"
    else:
        marker_count = int(start > 0) + int(end < len(chunk))
        remaining = LOW_CONFIDENCE_EVIDENCE_MAX_CODEPOINTS - (end - start) - marker_count
        if remaining < 0:
            raw = chunk[start:end]
            excerpt = " ".join(raw.split())
            if not excerpt:
                raise ValueError("Analysis speech segment evidence is blank")
            return excerpt
        before = min(LOW_CONFIDENCE_EVIDENCE_CONTEXT_CODEPOINTS, remaining // 2, start)
        after = min(LOW_CONFIDENCE_EVIDENCE_CONTEXT_CODEPOINTS, remaining - before, len(chunk) - end)
        remaining -= before + after
        # 将剩余预算均分给两侧，保证摘录尽可能保留判断说话人的上下文。
        additional_before = min(remaining // 2, start - before)
        before += additional_before
        after += min(remaining - additional_before, len(chunk) - end - after)
        excerpt_start = start - before
        excerpt_end = end + after
        raw = ("…" if excerpt_start > 0 else "") + chunk[excerpt_start:excerpt_end] + (
            "…" if excerpt_end < len(chunk) else "")
    excerpt = " ".join(raw.split())
    if not excerpt:
        raise ValueError("Analysis speech segment evidence is blank")
    return excerpt


def normalize_observation(raw: dict, chapter_index: int, chunk_offset: int, chunk: str) -> dict:
    """Validate one model observation and convert all local offsets to frozen chapter offsets."""
    if not isinstance(raw, dict) or not isinstance(chunk, str) or not chunk:
        raise ValueError("Analysis observation is invalid")
    chunk_length = len(chunk)
    characters: list[dict] = []
    for value in raw.get("characters", []):
        if not isinstance(value, dict):
            raise ValueError("Analysis character is invalid")
        presentation = value.get("presentation", "UNKNOWN")
        if presentation not in PRESENTATIONS:
            raise ValueError("Analysis character presentation is invalid")
        aliases: list[dict] = []
        for alias in value.get("aliases", []):
            if not isinstance(alias, dict):
                raise ValueError("Analysis alias is invalid")
            alias_chapter, start, end = evidence(alias, chapter_index, chunk_offset, chunk_length)
            alias_name = name(alias.get("alias"))
            alias_type = name(alias.get("aliasType"))
            aliases.append({"alias": alias_name, "aliasType": alias_type, "evidenceChapterIndex": alias_chapter,
                            "evidenceStartCodepoint": start, "evidenceEndCodepoint": end,
                            "confidence": confidence(alias.get("confidence"))})
        characters.append({"canonicalName": name(value.get("canonicalName")),
                           "displayName": name(value.get("displayName")), "presentation": presentation,
                           "characterType": name(value.get("characterType")),
                           "traits": tag_list(value.get("traits", []), 32, 80),
                           "firstChapterIndex": chapter_index,
                           "occurrenceCount": max(1, int(value.get("occurrenceCount", 1))),
                           "confidence": confidence(value.get("confidence")), "aliases": aliases})
    relationships: list[dict] = []
    for value in raw.get("relationships", []):
        if not isinstance(value, dict):
            raise ValueError("Analysis relationship is invalid")
        evidence_chapter, start, end = evidence(value, chapter_index, chunk_offset, chunk_length)
        relationships.append({"sourceCanonicalName": name(value.get("sourceCanonicalName")),
                              "targetCanonicalName": name(value.get("targetCanonicalName")),
                              "relationshipType": name(value.get("relationshipType")),
                              "direction": name(value.get("direction")), "evidenceChapterIndex": evidence_chapter,
                              "evidenceStartCodepoint": start, "evidenceEndCodepoint": end,
                              "confidence": confidence(value.get("confidence"))})
    speech_segments: list[dict] = []
    for value in raw.get("speechSegments", []):
        if not isinstance(value, dict):
            raise ValueError("Analysis speech segment is invalid")
        kind = value.get("speakerKind")
        start = value.get("textStartCodepoint")
        end = value.get("textEndCodepoint")
        if (kind not in SPEAKER_KINDS or not isinstance(start, int) or not isinstance(end, int) or
                start < 0 or end <= start or end > chunk_length):
            raise ValueError("Analysis speech segment range is invalid")
        speaker = value.get("speakerCanonicalName")
        if kind == "CHARACTER":
            speaker = name(speaker)
        elif speaker is not None:
            raise ValueError("Analysis non-character segment has a speaker")
        segment_confidence = confidence(value.get("confidence"))
        segment = {"chapterIndex": chapter_index, "sequenceNumber": -1,
                                "textStartCodepoint": start + chunk_offset, "textEndCodepoint": end + chunk_offset,
                                "speakerKind": kind, "speakerCanonicalName": speaker,
                                "deliveryTags": tag_list(value.get("deliveryTags", []), 16, 64),
                                "confidence": segment_confidence}
        # 原文摘录绝不采纳模型字段，低置信度审核证据只能从校验过的冻结正文派生。
        if segment_confidence < 0.80:
            segment["evidenceExcerpt"] = low_confidence_evidence_excerpt(chunk, start, end)
        speech_segments.append(segment)
    return {"characters": characters, "relationships": relationships, "speechSegments": speech_segments}


def inherited_index(value: object, field: str, upper_bound: int) -> int:
    """Validate a nonnegative persisted chapter or code-point location before it is reused."""
    if not isinstance(value, int) or isinstance(value, bool) or value < 0 or value > upper_bound:
        raise ValueError(f"Inherited audiobook analysis {field} is invalid")
    return value


def inherited_evidence(value: dict, field: str) -> tuple[int, int, int]:
    """Validate one globally-addressed evidence interval carried from an unchanged frozen chapter."""
    chapter_index = inherited_index(value.get("evidenceChapterIndex"), field, MAX_CHAPTERS - 1)
    start = inherited_index(value.get("evidenceStartCodepoint"), field, MAX_CHAPTER_BYTES)
    end = inherited_index(value.get("evidenceEndCodepoint"), field, MAX_CHAPTER_BYTES)
    if end <= start:
        raise ValueError(f"Inherited audiobook analysis {field} is invalid")
    return chapter_index, start, end


def inherited_analysis_observation(raw: object) -> dict:
    """Revalidate completed facts from unchanged chapters as one local observation.

    The Reader Service emits this payload only for an append or chapter-order incremental run.  It is still
    treated as an internal boundary: evidence locations and bounded fields are checked before they can seed
    later model hints or become part of the next immutable book-analysis record.
    """
    empty = {"characters": [], "relationships": [], "speechSegments": []}
    if raw is None:
        return empty
    if not isinstance(raw, dict):
        raise ValueError("Inherited audiobook analysis is invalid")
    raw_characters = raw.get("characters", [])
    raw_relationships = raw.get("relationships", [])
    raw_segments = raw.get("speechSegments", [])
    if (not isinstance(raw_characters, list) or len(raw_characters) > MAX_CHARACTERS
            or not isinstance(raw_relationships, list) or len(raw_relationships) > MAX_RELATIONSHIPS
            or not isinstance(raw_segments, list) or len(raw_segments) > MAX_SPEECH_SEGMENTS):
        raise ValueError("Inherited audiobook analysis is invalid")

    characters: list[dict] = []
    canonical_names: set[str] = set()
    for value in raw_characters:
        if not isinstance(value, dict):
            raise ValueError("Inherited audiobook character is invalid")
        canonical = name(value.get("canonicalName"))
        if canonical in canonical_names:
            raise ValueError("Inherited audiobook character is duplicated")
        canonical_names.add(canonical)
        presentation = value.get("presentation")
        if presentation not in PRESENTATIONS:
            raise ValueError("Inherited audiobook character presentation is invalid")
        occurrence_count = value.get("occurrenceCount")
        if (not isinstance(occurrence_count, int) or isinstance(occurrence_count, bool)
                or occurrence_count < 1):
            raise ValueError("Inherited audiobook character occurrence count is invalid")
        raw_aliases = value.get("aliases", [])
        if not isinstance(raw_aliases, list) or len(raw_aliases) > MAX_CHAPTERS:
            raise ValueError("Inherited audiobook character aliases are invalid")
        aliases: list[dict] = []
        seen_aliases: set[tuple[str, str, int, int, int]] = set()
        for alias in raw_aliases:
            if not isinstance(alias, dict):
                raise ValueError("Inherited audiobook alias is invalid")
            chapter_index, start, end = inherited_evidence(alias, "alias evidence")
            normalized = {"alias": name(alias.get("alias")), "aliasType": name(alias.get("aliasType")),
                          "evidenceChapterIndex": chapter_index, "evidenceStartCodepoint": start,
                          "evidenceEndCodepoint": end, "confidence": confidence(alias.get("confidence"))}
            key = (normalized["alias"], normalized["aliasType"], chapter_index, start, end)
            if key in seen_aliases:
                raise ValueError("Inherited audiobook alias is duplicated")
            seen_aliases.add(key)
            aliases.append(normalized)
        characters.append({"canonicalName": canonical, "displayName": name(value.get("displayName")),
                           "presentation": presentation, "characterType": name(value.get("characterType")),
                           "traits": tag_list(value.get("traits", []), 32, 80),
                           "firstChapterIndex": inherited_index(value.get("firstChapterIndex"),
                                                                  "character first chapter", MAX_CHAPTERS - 1),
                           "occurrenceCount": occurrence_count, "confidence": confidence(value.get("confidence")),
                           "aliases": aliases})

    relationships: list[dict] = []
    for value in raw_relationships:
        if not isinstance(value, dict):
            raise ValueError("Inherited audiobook relationship is invalid")
        chapter_index, start, end = inherited_evidence(value, "relationship evidence")
        relationships.append({"sourceCanonicalName": name(value.get("sourceCanonicalName")),
                              "targetCanonicalName": name(value.get("targetCanonicalName")),
                              "relationshipType": name(value.get("relationshipType")),
                              "direction": name(value.get("direction")), "evidenceChapterIndex": chapter_index,
                              "evidenceStartCodepoint": start, "evidenceEndCodepoint": end,
                              "confidence": confidence(value.get("confidence"))})

    speech_segments: list[dict] = []
    for value in raw_segments:
        if not isinstance(value, dict):
            raise ValueError("Inherited audiobook speech segment is invalid")
        kind = value.get("speakerKind")
        start = inherited_index(value.get("textStartCodepoint"), "speech segment range", MAX_CHAPTER_BYTES)
        end = inherited_index(value.get("textEndCodepoint"), "speech segment range", MAX_CHAPTER_BYTES)
        if kind not in SPEAKER_KINDS or end <= start:
            raise ValueError("Inherited audiobook speech segment is invalid")
        speaker = value.get("speakerCanonicalName")
        if kind == "CHARACTER":
            speaker = name(speaker)
        elif speaker is not None:
            raise ValueError("Inherited audiobook non-character segment has a speaker")
        segment = {"chapterIndex": inherited_index(value.get("chapterIndex"), "speech segment chapter",
                                                      MAX_CHAPTERS - 1),
                   "sequenceNumber": -1, "textStartCodepoint": start, "textEndCodepoint": end,
                   "speakerKind": kind, "speakerCanonicalName": speaker,
                   "deliveryTags": tag_list(value.get("deliveryTags", []), 16, 64),
                   "confidence": confidence(value.get("confidence"))}
        excerpt = value.get("evidenceExcerpt")
        if excerpt is not None:
            if (not isinstance(excerpt, str) or not (normalized_excerpt := " ".join(excerpt.split()))
                    or len(normalized_excerpt) > LOW_CONFIDENCE_EVIDENCE_MAX_CODEPOINTS):
                raise ValueError("Inherited audiobook speech segment evidence is invalid")
            segment["evidenceExcerpt"] = normalized_excerpt
        speech_segments.append(segment)
    return {"characters": characters, "relationships": relationships, "speechSegments": speech_segments}


def character_priority(value: dict) -> tuple[int, float, int, str, str]:
    """Return a stable preference ordering for one observed character profile."""
    return (int(value["firstChapterIndex"]), -float(value["confidence"]),
            -int(value["occurrenceCount"]), value["canonicalName"].casefold(), value["canonicalName"])


def attribute_priority(value: dict) -> tuple[float, int, int, str, str]:
    """Prefer stronger evidence when selecting a merged profile's display attributes."""
    return (-float(value["confidence"]), -int(value["occurrenceCount"]),
            int(value["firstChapterIndex"]), value["canonicalName"].casefold(), value["canonicalName"])


def alias_priority(value: dict) -> tuple[float, int, int, int, str, str]:
    """Prefer the strongest deterministic evidence record for one duplicate alias."""
    return (-float(value["confidence"]), int(value["evidenceChapterIndex"]),
            int(value["evidenceStartCodepoint"]), int(value["evidenceEndCodepoint"]),
            value["aliasType"], value["alias"])


def merge_character_profiles(canonical: str, profiles: list[dict]) -> dict:
    """Combine observations already proven to represent the same stable canonical identity."""
    if not profiles:
        raise ValueError("Audiobook character merge is empty")
    attribute_source = min(profiles, key=attribute_priority)
    alias_by_reference: dict[str, dict] = {}
    for profile in profiles:
        for alias in profile["aliases"]:
            key = alias["alias"].casefold()
            existing = alias_by_reference.get(key)
            if existing is None or alias_priority(alias) < alias_priority(existing):
                alias_by_reference[key] = alias.copy()
    aliases = [value for value in alias_by_reference.values()
               if value["alias"].casefold() != canonical.casefold()]
    aliases.sort(key=lambda value: (value["alias"].casefold(), value["alias"], value["aliasType"],
                                    value["evidenceChapterIndex"], value["evidenceStartCodepoint"],
                                    value["evidenceEndCodepoint"]))
    traits = sorted({trait for profile in profiles for trait in profile["traits"]},
                    key=lambda value: (value.casefold(), value))[:32]
    return {"canonicalName": canonical, "displayName": attribute_source["displayName"],
            "presentation": attribute_source["presentation"], "characterType": attribute_source["characterType"],
            "traits": traits, "firstChapterIndex": min(int(value["firstChapterIndex"]) for value in profiles),
            "occurrenceCount": sum(int(value["occurrenceCount"]) for value in profiles),
            "confidence": attribute_source["confidence"], "aliases": aliases}


def coalesce_alias_linked_characters(characters: dict[str, dict]) -> tuple[dict[str, dict], dict[str, str]]:
    """Merge profiles only when a canonical name has one unambiguous alias-evidence owner.

    A shared title such as “师父” is deliberately not enough to join two profiles. This keeps automatic
    cross-chapter identity resolution conservative while still repairing a common sequence where one chapter
    uses a personal name and another uses an evidence-backed alias as its canonical label.
    """
    parent = {canonical: canonical for canonical in characters}

    def root(value: str) -> str:
        """Find one union component root with path compression."""
        current = value
        while parent[current] != current:
            parent[current] = parent[parent[current]]
            current = parent[current]
        return current

    def union(left: str, right: str) -> None:
        """Join two evidence-linked identity components deterministically."""
        left_root = root(left)
        right_root = root(right)
        if left_root == right_root:
            return
        # 固定根选择保证输入观察顺序变化时仍产生相同的聚合组件。
        if (left_root.casefold(), left_root) < (right_root.casefold(), right_root):
            parent[right_root] = left_root
        else:
            parent[left_root] = right_root

    canonical_by_reference: dict[str, set[str]] = {}
    alias_owners: dict[str, set[str]] = {}
    for canonical, character in characters.items():
        canonical_by_reference.setdefault(canonical.casefold(), set()).add(canonical)
        for alias in character["aliases"]:
            alias_owners.setdefault(alias["alias"].casefold(), set()).add(canonical)
    for reference, owners in alias_owners.items():
        canonical_owners = canonical_by_reference.get(reference, set())
        if len(owners) != 1 or len(canonical_owners) != 1:
            continue
        alias_owner = next(iter(owners))
        canonical_owner = next(iter(canonical_owners))
        if alias_owner != canonical_owner:
            union(alias_owner, canonical_owner)

    members_by_root: dict[str, list[str]] = {}
    for canonical in characters:
        members_by_root.setdefault(root(canonical), []).append(canonical)
    merged: dict[str, dict] = {}
    canonical_remap: dict[str, str] = {}
    for members in members_by_root.values():
        selected = min(members, key=lambda value: character_priority(characters[value]))
        profiles = [characters[value] for value in members]
        merged[selected] = merge_character_profiles(selected, profiles)
        for member in members:
            canonical_remap[member] = selected
    return merged, canonical_remap


def merge_observations(observations: list[dict], analysis_model_version: str = "TEST_ANALYSIS_MODEL_V1",
                       analysis_rule_version: str = ANALYSIS_RULE_VERSION) -> dict:
    """Merge chunk facts with conservative evidence-backed cross-chapter identity resolution."""
    if (not isinstance(analysis_model_version, str) or not TRACE_VERSION.fullmatch(analysis_model_version) or
            not isinstance(analysis_rule_version, str) or not TRACE_VERSION.fullmatch(analysis_rule_version) or
            len(analysis_rule_version) > 64):
        raise ValueError("Audiobook analysis provenance is invalid")
    profiles_by_canonical: dict[str, list[dict]] = {}
    relationships: list[dict] = []
    segments: list[dict] = []
    for observation in observations:
        for value in observation["characters"]:
            canonical = value["canonicalName"]
            profiles_by_canonical.setdefault(canonical, []).append(value)
        relationships.extend(observation["relationships"])
        segments.extend(observation["speechSegments"])
    characters = {canonical: merge_character_profiles(canonical, values)
                  for canonical, values in profiles_by_canonical.items()}
    if len(characters) > MAX_CHARACTERS or len(relationships) > MAX_RELATIONSHIPS or len(segments) > MAX_SPEECH_SEGMENTS:
        raise ValueError("Audiobook analysis result exceeds task limits")
    characters, canonical_remap = coalesce_alias_linked_characters(characters)
    references: dict[str, set[str]] = {}
    for canonical, merged_canonical in canonical_remap.items():
        references.setdefault(canonical.casefold(), set()).add(merged_canonical)
    for canonical, character in characters.items():
        references.setdefault(canonical.casefold(), set()).add(canonical)
        for alias in character["aliases"]:
            references.setdefault(alias["alias"].casefold(), set()).add(canonical)

    def resolve_character(reference: str) -> str | None:
        """只把唯一的规范名或别名解析到角色，歧义称谓必须保留人工审核。"""
        owners = references.get(reference.casefold(), set())
        return next(iter(owners)) if len(owners) == 1 else None

    resolved_relationships: list[dict] = []
    for value in relationships:
        source = resolve_character(value["sourceCanonicalName"])
        target = resolve_character(value["targetCanonicalName"])
        if source is None or target is None:
            continue
        resolved = value.copy()
        resolved["sourceCanonicalName"] = source
        resolved["targetCanonicalName"] = target
        resolved_relationships.append(resolved)
    relationships = resolved_relationships
    resolved_segments: list[dict] = []
    for value in segments:
        if value["speakerKind"] != "CHARACTER":
            resolved_segments.append(value)
            continue
        speaker = resolve_character(value["speakerCanonicalName"])
        if speaker is None:
            continue
        resolved = value.copy()
        resolved["speakerCanonicalName"] = speaker
        resolved_segments.append(resolved)
    segments = resolved_segments
    unique_relationships_by_key: dict[tuple[str, str, str, str], dict] = {}
    for value in relationships:
        key = (value["sourceCanonicalName"], value["targetCanonicalName"], value["relationshipType"], value["direction"])
        existing = unique_relationships_by_key.get(key)
        priority = (-float(value["confidence"]), int(value["evidenceChapterIndex"]),
                    int(value["evidenceStartCodepoint"]), int(value["evidenceEndCodepoint"]))
        if existing is None or priority < (-float(existing["confidence"]), int(existing["evidenceChapterIndex"]),
                                           int(existing["evidenceStartCodepoint"]),
                                           int(existing["evidenceEndCodepoint"])):
            unique_relationships_by_key[key] = value
    unique_relationships = [unique_relationships_by_key[key]
                            for key in sorted(unique_relationships_by_key)]
    per_chapter: dict[int, list[dict]] = {}
    for value in segments:
        per_chapter.setdefault(value["chapterIndex"], []).append(value)
    normalized_segments: list[dict] = []
    for chapter_index in sorted(per_chapter):
        values = per_chapter[chapter_index]
        values.sort(key=lambda value: (value["textStartCodepoint"], value["textEndCodepoint"]))
        previous_end = -1
        sequence = 0
        for value in values:
            if value["textStartCodepoint"] < previous_end:
                continue
            value["sequenceNumber"] = sequence
            normalized_segments.append(value)
            previous_end = value["textEndCodepoint"]
            sequence += 1
    result = {"analysisModelVersion": analysis_model_version, "analysisRuleVersion": analysis_rule_version,
              "characters": sorted(characters.values(), key=lambda value: value["canonicalName"]),
              "relationships": unique_relationships, "speechSegments": normalized_segments}
    fingerprint_source = json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    result["analysisFingerprintSha256"] = hashlib.sha256(fingerprint_source).hexdigest()
    return result


def execute(parameters: dict, storage: StorageGatewayClient, reader: ReaderServiceClient,
            analyzer: OpenAiCompatibleAnalysisClient, work_directory: Path) -> dict:
    """Analyze only pending frozen chapters and merge safe inherited facts into one immutable result."""
    generation_id = str(parameters["generationId"])
    input_data = reader.input(generation_id)
    chapters = input_data.get("chapters")
    inherited_raw = input_data.get("inheritedAnalysis")
    inherited = inherited_analysis_observation(inherited_raw)
    if (not isinstance(chapters, list) or len(chapters) > MAX_CHAPTERS
            or (not chapters and inherited_raw is None)):
        raise ValueError("Audiobook analysis chapter plan is invalid")
    observations: list[dict] = [inherited]
    remembered_characters: dict[str, dict] = {}
    # 已完成、且正文哈希不变的章节只提供经过服务端校验的事实和候选身份提示，不再重复调用模型。
    remember_character_hints(remembered_characters, inherited)
    total_size = 0
    for chapter in chapters:
        index = int(chapter["index"])
        expected_hash = str(chapter["contentSha256"])
        path = work_directory / f"chapter-{index:05d}.txt"
        storage.download(str(chapter["textStorageUri"]), path, MAX_CHAPTER_BYTES)
        payload = path.read_bytes()
        total_size += len(payload)
        if total_size > MAX_BOOK_BYTES:
            raise ValueError("Audiobook analysis book exceeds task limit")
        if hashlib.sha256(payload).hexdigest() != expected_hash:
            raise ValueError("Frozen chapter text checksum does not match")
        text = payload.decode("utf-8", errors="strict")
        for offset, chunk in chunks(text):
            observation = normalize_observation(
                analyzer.analyze_chunk(index, str(chapter["title"]), offset, chunk,
                                       known_character_hints(list(remembered_characters.values()))),
                index, offset, chunk)
            observations.append(observation)
            remember_character_hints(remembered_characters, observation)
    result = merge_observations(observations, analyzer.model_version, ANALYSIS_RULE_VERSION)
    reader.save(generation_id, result)
    completed = reader.complete(generation_id)
    return {"generationId": generation_id, "characterCount": int(completed["characterCount"]),
            "relationshipCount": int(completed["relationshipCount"]),
            "speechSegmentCount": int(completed["speechSegmentCount"])}


def write_result(result: dict) -> None:
    """Write the scheduler-visible result with an atomic file replacement."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def report_task_failure(exception: Exception) -> None:
    """Persist a bounded error contract so retries never depend on truncated process output."""
    if not os.environ.get("TASK_ERROR_FILE"):
        return
    message = str(exception)
    if "endpoint is unavailable" in message or "request did not complete" in message:
        code, category = "AUDIOBOOK_ANALYSIS_PROVIDER_UNAVAILABLE", "TRANSIENT"
    elif "Tencent Hunyuan rejected analysis" in message or "request failed with HTTP" in message:
        code, category = "AUDIOBOOK_ANALYSIS_PROVIDER_REJECTED", "PERMANENT"
    elif ("Tencent Hunyuan response" in message or "Tencent Hunyuan returned invalid JSON" in message
          or "Audiobook analysis model response" in message or "Audiobook analysis model result" in message):
        code, category = "AUDIOBOOK_ANALYSIS_PROVIDER_RESPONSE_INVALID", "PERMANENT"
    elif "Reader Service returned" in message:
        code, category = "AUDIOBOOK_ANALYSIS_READER_PROTOCOL_INVALID", "PERMANENT"
    elif isinstance(exception, ValueError):
        code, category = "AUDIOBOOK_ANALYSIS_INPUT_INVALID", "PERMANENT"
    else:
        code, category = "AUDIOBOOK_ANALYSIS_RUNTIME_FAILURE", "TRANSIENT"
    try:
        write_task_error(code, category, code)
    except (OSError, ValueError):
        # 错误文档是诊断增强项，不能遮蔽原始脚本失败。
        return


def main() -> None:
    """Build clients from deployment-only configuration and run one analysis task."""
    context_path = Path(os.environ["TASK_CONTEXT_FILE"])
    try:
        context = json.loads(context_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exception:
        raise RuntimeError("Audiobook analysis task context is invalid") from exception
    storage = StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                                   os.environ.get("STORAGE_INTERNAL_TOKEN", ""))
    reader = ReaderServiceClient(os.getenv("READER_SERVICE_URL", "http://127.0.0.1:23230"),
                                 os.environ.get("READER_INTERNAL_TOKEN", ""))
    ner_endpoint = os.environ.get("AUDIOBOOK_NER_ENDPOINT", "")
    ner_client = None if not ner_endpoint else NerCandidateClient(ner_endpoint,
        os.environ.get("AUDIOBOOK_NER_API_KEY", ""), int(os.getenv("AUDIOBOOK_NER_TIMEOUT_SECONDS", "30")),
        int(os.getenv("AUDIOBOOK_NER_MAX_RETRIES", "2")))
    provider = os.getenv("AUDIOBOOK_ANALYSIS_PROVIDER", "OPENAI_COMPATIBLE").strip().upper()
    if provider == "TENCENT_HUNYUAN":
        analyzer = TencentHunyuanAnalysisClient(
            os.environ.get("TENCENT_HUNYUAN_SECRET_ID") or os.environ.get("TENCENT_TTS_SECRET_ID", ""),
            os.environ.get("TENCENT_HUNYUAN_SECRET_KEY") or os.environ.get("TENCENT_TTS_SECRET_KEY", ""),
            os.environ.get("AUDIOBOOK_ANALYSIS_MODEL", ""),
            int(os.getenv("AUDIOBOOK_ANALYSIS_TIMEOUT_SECONDS", "90")),
            int(os.getenv("AUDIOBOOK_ANALYSIS_MAX_RETRIES", "3")),
            os.getenv("TENCENT_HUNYUAN_REGION", "ap-guangzhou"), ner_client)
    elif provider == "OPENAI_COMPATIBLE":
        analyzer = OpenAiCompatibleAnalysisClient(os.environ.get("AUDIOBOOK_ANALYSIS_ENDPOINT", ""),
                                                  os.environ.get("AUDIOBOOK_ANALYSIS_API_KEY", ""),
                                                  os.environ.get("AUDIOBOOK_ANALYSIS_MODEL", ""),
                                                  int(os.getenv("AUDIOBOOK_ANALYSIS_TIMEOUT_SECONDS", "90")),
                                                  int(os.getenv("AUDIOBOOK_ANALYSIS_MAX_RETRIES", "3")), ner_client)
    else:
        raise ValueError("Audiobook analysis provider is unsupported")
    try:
        write_result(execute(context["parameters"], storage, reader, analyzer, context_path.parent))
    except Exception as exception:
        report_task_failure(exception)
        raise


if __name__ == "__main__":
    main()
