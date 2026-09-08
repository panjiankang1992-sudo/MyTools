#!/usr/bin/env python3
"""将已冻结的章节正文合成为可复用 MP3 音频。"""

from __future__ import annotations

import base64
import binascii
import codecs
import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid
import time
import xml.etree.ElementTree as element_tree
from xml.sax.saxutils import escape as xml_escape

from mytools_task_sdk.storage import StorageGatewayClient
from mytools_task_sdk.asset import AssetRegistryClient

MAX_CHAPTER_BYTES = 10 * 1024 * 1024
MAX_CHAPTERS = 20_000
# 火山引擎在线合成按 UTF-8 字节而非 Unicode 字符数限制输入；为协议头和服务端边界留出余量。
MAX_SEGMENT_UTF8_BYTES = 900
# 腾讯云基础合成的单次文本上限以 UTF-8 计算。450 字节可容纳最多 150 个中文或全角字符，
# 同时也低于英文 500 字符的上限；SSML 会在同一上限内重新切分。
TENCENT_TTS_MAX_SEGMENT_UTF8_BYTES = 450
BATCH_SIZE = 10
MAX_TTS_RESPONSE_BYTES = 32 * 1024 * 1024
MAX_TTS_AUDIO_BYTES = 24 * 1024 * 1024
MAX_TTS_RETRIES = 3
TTS_STREAM_READ_BYTES = 8192
VOLCENGINE_TTS_API_V1 = "v1"
VOLCENGINE_TTS_API_V3 = "v3"
VOLCENGINE_TTS_V1_ROUTE = "/api/v1/tts"
VOLCENGINE_TTS_V3_ROUTE = "/api/v3/tts/unidirectional"
VOLCENGINE_TTS_V3_COMPLETE_CODE = 20_000_000
DEFAULT_MINIMUM_SPEAKER_CONFIDENCE = 0.80
MAX_PRONUNCIATIONS = 5_000
RETRYABLE_HTTP_STATUS = frozenset({408, 429, 500, 502, 503, 504})
SENTENCE_ENDINGS = re.compile(r"[。！？!?；;]\s*")
PINYIN = re.compile(r"(?:[a-zvü]+[1-5])(?:\s+[a-zvü]+[1-5]){0,127}", re.IGNORECASE)
VOLCENGINE_RESOURCE_ID = re.compile(r"[A-Za-z0-9._-]{1,256}")
TENCENT_TTS_ENDPOINT = "https://tts.tencentcloudapi.com"
TENCENT_TTS_HOST = "tts.tencentcloudapi.com"
TENCENT_TTS_ACTION = "TextToVoice"
TENCENT_TTS_VERSION = "2019-08-23"
TENCENT_TTS_SERVICE = "tts"
TENCENT_TTS_VOICE_TYPE = re.compile(r"[1-9][0-9]{0,9}")
TENCENT_TTS_REGION = re.compile(r"[a-z0-9-]{1,64}")


class ReaderServiceClient:
    """调用 Reader Service 的有声书内部合成契约。"""

    def __init__(self, base_url: str, token: str):
        if not token:
            raise ValueError("Reader Service internal token is missing")
        self._base_url = base_url.rstrip("/")
        self._token = token

    def input(self, generation_id: str) -> dict:
        """读取已冻结的章节正文地址。"""
        return self._request("GET", f"/{generation_id}/synthesis-input")

    def save(self, generation_id: str, chapters: list[dict]) -> None:
        """持久化一批已发布的章节音频。"""
        self._request("POST", f"/{generation_id}/synthesized-chapters", {"chapters": chapters})

    def complete(self, generation_id: str) -> dict:
        """在全部章节音频就绪后完成整书运行。"""
        return self._request("POST", f"/{generation_id}/complete-synthesis", {})

    def _request(self, method: str, path: str, body: dict | None = None) -> dict:
        payload = None if body is None else json.dumps(body, ensure_ascii=False,
                                                       separators=(",", ":")).encode("utf-8")
        headers = {"Authorization": f"Bearer {self._token}", "Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(f"{self._base_url}/api/internal/v1/audiobook-generations{path}",
                                         data=payload, method=method, headers=headers)
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))


class VolcengineTtsClient:
    """封装火山引擎 V1 与 V3 在线语音合成的最小安全调用。"""

    max_segment_utf8_bytes = MAX_SEGMENT_UTF8_BYTES

    def __init__(self, endpoint: str, app_id: str, access_token: str, cluster: str, voice_type: str,
                 timeout_seconds: int, max_retries: int = MAX_TTS_RETRIES,
                 api_version: str = VOLCENGINE_TTS_API_V1, api_key: str = "", resource_id: str = "",
                 urlopen=urllib.request.urlopen, sleeper=time.sleep):
        version = self._normalize_api_version(api_version)
        try:
            parsed = urllib.parse.urlparse(endpoint)
        except ValueError as exception:
            raise ValueError("Volcengine TTS endpoint must use HTTPS without embedded credentials") from exception
        if (parsed.scheme != "https" or not parsed.hostname or parsed.username is not None or
                parsed.password is not None):
            raise ValueError("Volcengine TTS endpoint must use HTTPS without embedded credentials")
        expected_route = VOLCENGINE_TTS_V1_ROUTE if version == VOLCENGINE_TTS_API_V1 else VOLCENGINE_TTS_V3_ROUTE
        if parsed.path.rstrip("/") != expected_route:
            raise ValueError(f"Volcengine TTS endpoint must use the implemented {version.upper()} HTTP route")
        if not self._configured_text(voice_type, 256):
            raise ValueError("Volcengine TTS runtime configuration is incomplete")
        if version == VOLCENGINE_TTS_API_V1 and (not self._configured_text(app_id, 128) or
                                                 not self._configured_text(access_token, 4096) or
                                                 not self._configured_text(cluster, 128)):
            raise ValueError("Volcengine V1 TTS runtime configuration is incomplete")
        if version == VOLCENGINE_TTS_API_V3 and (not self._configured_text(api_key, 4096) or
                                                 not self._configured_text(resource_id, 256)
                                                 or VOLCENGINE_RESOURCE_ID.fullmatch(resource_id.strip()) is None):
            raise ValueError("Volcengine V3 TTS runtime configuration is incomplete")
        if (not isinstance(timeout_seconds, int) or isinstance(timeout_seconds, bool) or
                timeout_seconds < 1 or timeout_seconds > 300):
            raise ValueError("Volcengine TTS timeout is invalid")
        if (not isinstance(max_retries, int) or isinstance(max_retries, bool) or
                max_retries < 0 or max_retries > 5):
            raise ValueError("Volcengine TTS retry limit is invalid")
        self._endpoint = endpoint
        self._api_version = version
        self._app_id = app_id
        self._access_token = access_token
        self._cluster = cluster
        self._voice_type = voice_type
        self._api_key = api_key
        self._resource_id = resource_id
        self._timeout_seconds = timeout_seconds
        self._max_retries = max_retries
        self._urlopen = urlopen
        self._sleep = sleeper

    def synthesize(self, text: str, request_id: str, voice_type: str | None = None,
                   provider: str | None = None, text_type: str = "plain") -> bytes:
        """合成一段受长度限制的纯文本或受控 SSML，并返回 MP3 字节。"""
        selected_voice = voice_type or self._voice_type
        if (not isinstance(text, str) or not text.strip() or len(text.encode("utf-8")) > MAX_SEGMENT_UTF8_BYTES or
                not isinstance(request_id, str) or not re.fullmatch(r"[A-Za-z0-9._:-]{8,64}", request_id) or
                not self._configured_text(selected_voice, 256)):
            raise ValueError("Audiobook segment voice type is invalid")
        if text_type not in {"plain", "ssml"}:
            raise ValueError("Audiobook segment text type is invalid")
        if text_type == "ssml":
            validate_pronunciation_ssml(text)
        if (provider is not None and (not isinstance(provider, str) or
                                     provider.strip().upper() != "VOLCENGINE")):
            raise ValueError("Audiobook voice provider is unsupported by the Volcengine executor")
        # 仅使用请求标识生成供应商可追踪的临时 UID，避免把用户身份或书籍标识发送给供应商。
        uid = "mytools-" + hashlib.sha256(request_id.encode("utf-8")).hexdigest()[:24]
        payload, headers = self._request_contract(text, text_type, request_id, uid, selected_voice)
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(self._endpoint, data=body, method="POST", headers=headers)
        for attempt in range(self._max_retries + 1):
            try:
                with self._urlopen(request, timeout=self._timeout_seconds) as response:
                    if self._api_version == VOLCENGINE_TTS_API_V1:
                        return self._audio(self._response_json(response))
                    return self._stream_audio(response)
            except urllib.error.HTTPError as exception:
                if exception.code in RETRYABLE_HTTP_STATUS and self._retry(attempt):
                    continue
                raise RuntimeError(f"Volcengine TTS request failed with HTTP {exception.code}") from exception
            except (urllib.error.URLError, TimeoutError) as exception:
                if self._retry(attempt):
                    continue
                raise RuntimeError("Volcengine TTS endpoint is unavailable") from exception

        raise RuntimeError("Volcengine TTS retry state is invalid")

    @staticmethod
    def _normalize_api_version(value: object) -> str:
        """只接受明确版本，避免把旧凭证或新 API Key 发往错误协议。"""
        if not isinstance(value, str):
            raise ValueError("Volcengine TTS API version is invalid")
        normalized = value.strip().lower()
        if normalized not in {VOLCENGINE_TTS_API_V1, VOLCENGINE_TTS_API_V3}:
            raise ValueError("Volcengine TTS API version is invalid")
        return normalized

    def _request_contract(self, text: str, text_type: str, request_id: str, uid: str,
                          selected_voice: str) -> tuple[dict, dict[str, str]]:
        """按已选协议构建不可混用的鉴权头和最小合成请求。"""
        if self._api_version == VOLCENGINE_TTS_API_V1:
            return ({
                "app": {"appid": self._app_id, "token": self._access_token, "cluster": self._cluster},
                "user": {"uid": uid},
                "audio": {"voice_type": selected_voice, "encoding": "mp3", "speed_ratio": 1.0,
                          "loudness_ratio": 1.0},
                "request": {"reqid": request_id, "text": text, "text_type": text_type, "operation": "query"},
            }, {
                "Authorization": f"Bearer;{self._access_token}", "Content-Type": "application/json",
                "Accept": "application/json",
            })
        if text_type != "plain":
            raise ValueError("Volcengine V3 pronunciation SSML capability is not verified")
        return ({
            "user": {"uid": uid},
            "req_params": {
                "text": text,
                "speaker": selected_voice,
                "sample_rate": 24000,
                "audio_params": {"format": "mp3", "speech_rate": 0, "loudness_rate": 0},
            },
        }, {
            "Content-Type": "application/json",
            "X-Api-Key": self._api_key,
            "X-Api-Resource-Id": self._resource_id,
            "X-Api-Request-Id": request_id,
        })

    @staticmethod
    def _configured_text(value: object, maximum: int) -> bool:
        """检查不应含控制字符的受限部署配置。"""
        return (isinstance(value, str) and 0 < len(value.strip()) <= maximum and
                not any(ord(character) < 32 or ord(character) == 127 for character in value))

    def _retry(self, attempt: int) -> bool:
        """对确定可重试的传输错误执行短暂退避，且不改变供应商请求标识。"""
        if attempt >= self._max_retries:
            return False
        self._sleep(min(4.0, 0.5 * (2 ** attempt)))
        return True

    @staticmethod
    def _response_json(response) -> dict:
        """以固定上限读取 JSON 回执，阻止异常响应耗尽执行器内存。"""
        headers = getattr(response, "headers", None)
        declared = headers.get("Content-Length") if headers is not None else None
        if declared is not None:
            try:
                if int(declared) < 1 or int(declared) > MAX_TTS_RESPONSE_BYTES:
                    raise RuntimeError("Volcengine TTS response size is invalid")
            except ValueError as exception:
                raise RuntimeError("Volcengine TTS response size is invalid") from exception
        raw = response.read(MAX_TTS_RESPONSE_BYTES + 1)
        if not isinstance(raw, bytes) or not raw or len(raw) > MAX_TTS_RESPONSE_BYTES:
            raise RuntimeError("Volcengine TTS response size is invalid")
        try:
            result = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exception:
            raise RuntimeError("Volcengine TTS response is invalid") from exception
        if not isinstance(result, dict):
            raise RuntimeError("Volcengine TTS response is invalid")
        return result

    @classmethod
    def _stream_audio(cls, response) -> bytes:
        """读取 V3 Chunked JSON 流，直到供应商的完成码且不允许不完整音频发布。"""
        cls._validate_declared_response_size(response)
        text_decoder = codecs.getincrementaldecoder("utf-8")()
        json_decoder = json.JSONDecoder()
        pending = ""
        response_size = 0
        audio_parts: list[bytes] = []
        audio_size = 0
        completed = False
        while True:
            raw = response.read(TTS_STREAM_READ_BYTES)
            if not isinstance(raw, bytes):
                raise RuntimeError("Volcengine TTS stream is invalid")
            if not raw:
                break
            response_size += len(raw)
            if response_size > MAX_TTS_RESPONSE_BYTES:
                raise RuntimeError("Volcengine TTS response size is invalid")
            try:
                pending += text_decoder.decode(raw)
            except UnicodeDecodeError as exception:
                raise RuntimeError("Volcengine TTS stream is invalid") from exception
            pending, entries = cls._stream_entries(pending, json_decoder, final=False)
            audio_size, completed = cls._consume_stream_entries(entries, audio_parts, audio_size, completed)
        try:
            pending += text_decoder.decode(b"", final=True)
        except UnicodeDecodeError as exception:
            raise RuntimeError("Volcengine TTS stream is invalid") from exception
        pending, entries = cls._stream_entries(pending, json_decoder, final=True)
        audio_size, completed = cls._consume_stream_entries(entries, audio_parts, audio_size, completed)
        if pending.strip() or not completed:
            raise RuntimeError("Volcengine TTS stream did not complete")
        if not audio_parts:
            raise RuntimeError("Volcengine TTS response has no audio payload")
        audio = b"".join(audio_parts)
        cls._validate_mp3(audio)
        return audio

    @staticmethod
    def _validate_declared_response_size(response) -> None:
        """拒绝声明过大的流式响应，防止无界缓存占满执行器内存。"""
        headers = getattr(response, "headers", None)
        declared = headers.get("Content-Length") if headers is not None else None
        if declared is None:
            return
        try:
            if int(declared) < 1 or int(declared) > MAX_TTS_RESPONSE_BYTES:
                raise RuntimeError("Volcengine TTS response size is invalid")
        except ValueError as exception:
            raise RuntimeError("Volcengine TTS response size is invalid") from exception

    @staticmethod
    def _stream_entries(value: str, decoder: json.JSONDecoder, final: bool) -> tuple[str, list[dict]]:
        """从任意网络分片中逐个解出连续 JSON 对象，不假设换行恰好对齐。"""
        cursor = 0
        entries: list[dict] = []
        while True:
            while cursor < len(value) and value[cursor].isspace():
                cursor += 1
            if cursor == len(value):
                return "", entries
            try:
                entry, end = decoder.raw_decode(value, cursor)
            except json.JSONDecodeError as exception:
                if final:
                    raise RuntimeError("Volcengine TTS stream is invalid") from exception
                return value[cursor:], entries
            if not isinstance(entry, dict):
                raise RuntimeError("Volcengine TTS stream is invalid")
            entries.append(entry)
            cursor = end

    @classmethod
    def _consume_stream_entries(cls, entries: list[dict], audio_parts: list[bytes], audio_size: int,
                                completed: bool) -> tuple[int, bool]:
        """校验 V3 状态码并累积合法音频，完成标记后的任何数据都会被拒绝。"""
        for entry in entries:
            code = entry.get("code")
            if code in (VOLCENGINE_TTS_V3_COMPLETE_CODE, str(VOLCENGINE_TTS_V3_COMPLETE_CODE)):
                if completed:
                    raise RuntimeError("Volcengine TTS stream is invalid")
                completed = True
                continue
            if code not in (0, "0") or completed:
                raise RuntimeError(f"Volcengine TTS rejected stream with code {code}")
            encoded_audio = entry.get("data")
            if encoded_audio is None:
                continue
            audio = cls._decode_audio(encoded_audio)
            audio_size += len(audio)
            if audio_size > MAX_TTS_AUDIO_BYTES:
                raise RuntimeError("Volcengine TTS response audio is too large")
            audio_parts.append(audio)
        return audio_size, completed

    @staticmethod
    def _audio(result: dict) -> bytes:
        """校验供应商成功回执的 base64 MP3，不接受任意二进制伪装音频。"""
        code = result.get("code")
        if code not in (0, 3000, "0", "3000"):
            raise RuntimeError(f"Volcengine TTS rejected synthesis with code {code}")
        audio = VolcengineTtsClient._decode_audio(result.get("data"))
        VolcengineTtsClient._validate_mp3(audio)
        return audio

    @staticmethod
    def _decode_audio(encoded_audio: object) -> bytes:
        """解码供应商的单个 base64 音频分片，不接受任意数据 URI。"""
        if isinstance(encoded_audio, dict):
            encoded_audio = encoded_audio.get("audio")
        if not isinstance(encoded_audio, str) or not encoded_audio:
            raise RuntimeError("Volcengine TTS response has no audio payload")
        if encoded_audio.startswith("data:"):
            prefix, separator, encoded_audio = encoded_audio.partition(",")
            if separator != "," or prefix.lower() != "data:audio/mpeg;base64":
                raise RuntimeError("Volcengine TTS response audio is invalid")
        try:
            audio = base64.b64decode(encoded_audio, validate=True)
        except (ValueError, binascii.Error) as exception:
            raise RuntimeError("Volcengine TTS response audio is invalid") from exception
        return audio

    @staticmethod
    def _validate_mp3(audio: bytes) -> None:
        """确认合成结果以 MP3 容器或帧同步开头。"""
        if len(audio) < 4 or not (audio.startswith(b"ID3") or
                                  (audio[0] == 0xff and (audio[1] & 0xe0) == 0xe0)):
            raise RuntimeError("Volcengine TTS response audio is empty")


class TencentTtsClient:
    """封装腾讯云基础语音合成的 TC3 签名调用，并只接受 MP3 回执。"""

    max_segment_utf8_bytes = TENCENT_TTS_MAX_SEGMENT_UTF8_BYTES

    def __init__(self, secret_id: str, secret_key: str, voice_type: str, timeout_seconds: int,
                 max_retries: int = MAX_TTS_RETRIES, region: str = "", sample_rate: int = 16000,
                 urlopen=urllib.request.urlopen, sleeper=time.sleep, clock=time.time):
        if (not VolcengineTtsClient._configured_text(secret_id, 128)
                or not VolcengineTtsClient._configured_text(secret_key, 4096)
                or not self._valid_voice_type(voice_type)):
            raise ValueError("Tencent TTS runtime configuration is incomplete")
        if (not isinstance(timeout_seconds, int) or isinstance(timeout_seconds, bool)
                or timeout_seconds < 1 or timeout_seconds > 300):
            raise ValueError("Tencent TTS timeout is invalid")
        if (not isinstance(max_retries, int) or isinstance(max_retries, bool)
                or max_retries < 0 or max_retries > 5):
            raise ValueError("Tencent TTS retry limit is invalid")
        if (not isinstance(region, str) or (region.strip()
                and TENCENT_TTS_REGION.fullmatch(region.strip()) is None)):
            raise ValueError("Tencent TTS region is invalid")
        if sample_rate not in {8000, 16000, 24000}:
            raise ValueError("Tencent TTS sample rate is invalid")
        if not callable(clock):
            raise ValueError("Tencent TTS clock is invalid")
        self._secret_id = secret_id.strip()
        self._secret_key = secret_key.strip()
        self._voice_type = voice_type.strip()
        self._timeout_seconds = timeout_seconds
        self._max_retries = max_retries
        self._region = region.strip()
        self._sample_rate = sample_rate
        self._urlopen = urlopen
        self._sleep = sleeper
        self._clock = clock

    def synthesize(self, text: str, request_id: str, voice_type: str | None = None,
                   provider: str | None = None, text_type: str = "plain") -> bytes:
        """合成一段已按腾讯云上限切分的正文或受控 SSML。"""
        selected_voice = voice_type or self._voice_type
        if (not isinstance(text, str) or not text.strip()
                or len(text.encode("utf-8")) > self.max_segment_utf8_bytes
                or not isinstance(request_id, str)
                or re.fullmatch(r"[A-Za-z0-9._:-]{8,64}", request_id) is None
                or not self._valid_voice_type(selected_voice)):
            raise ValueError("Audiobook segment voice type is invalid")
        if text_type not in {"plain", "ssml"}:
            raise ValueError("Audiobook segment text type is invalid")
        if text_type == "ssml":
            validate_pronunciation_ssml(text)
        if (provider is not None and (not isinstance(provider, str)
                                     or provider.strip().upper() != "TENCENT")):
            raise ValueError("Audiobook voice provider is unsupported by the Tencent executor")

        body = json.dumps({
            "Text": text,
            # 只发送请求随机标识的散列，避免把用户、书籍或章节标识暴露给供应商。
            "SessionId": "mytools-" + hashlib.sha256(request_id.encode("utf-8")).hexdigest()[:32],
            "VoiceType": int(selected_voice),
            "PrimaryLanguage": 1,
            "SampleRate": self._sample_rate,
            "Codec": "mp3",
        }, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(TENCENT_TTS_ENDPOINT, data=body, method="POST",
                                         headers=self._headers(body))
        for attempt in range(self._max_retries + 1):
            try:
                with self._urlopen(request, timeout=self._timeout_seconds) as response:
                    return self._audio(VolcengineTtsClient._response_json(response))
            except urllib.error.HTTPError as exception:
                if exception.code in RETRYABLE_HTTP_STATUS and self._retry(attempt):
                    continue
                raise RuntimeError(f"Tencent TTS request failed with HTTP {exception.code}") from exception
            except (urllib.error.URLError, TimeoutError) as exception:
                if self._retry(attempt):
                    continue
                raise RuntimeError("Tencent TTS endpoint is unavailable") from exception
        raise RuntimeError("Tencent TTS retry state is invalid")

    @staticmethod
    def _valid_voice_type(value: object) -> bool:
        """限制目录中的腾讯数值音色 ID，排除快速复刻等未纳入首期目录的形态。"""
        return (isinstance(value, str) and TENCENT_TTS_VOICE_TYPE.fullmatch(value.strip()) is not None
                and int(value.strip()) <= 2_000_000_000)

    def _headers(self, body: bytes) -> dict[str, str]:
        """使用 TC3-HMAC-SHA256 生成只覆盖正文和固定主机的请求签名。"""
        timestamp = int(self._clock())
        if timestamp < 1:
            raise RuntimeError("Tencent TTS clock is invalid")
        date = time.strftime("%Y-%m-%d", time.gmtime(timestamp))
        payload_hash = hashlib.sha256(body).hexdigest()
        # 腾讯云会校验操作名和正文使用的是同一份签名，请求头中的操作名必须纳入规范请求。
        canonical_headers = ("content-type:application/json; charset=utf-8\nhost:" + TENCENT_TTS_HOST
                             + "\nx-tc-action:" + TENCENT_TTS_ACTION.lower() + "\n")
        signed_headers = "content-type;host;x-tc-action"
        canonical_request = "POST\n/\n\n" + canonical_headers + "\n" + signed_headers + "\n" + payload_hash
        credential_scope = f"{date}/{TENCENT_TTS_SERVICE}/tc3_request"
        string_to_sign = ("TC3-HMAC-SHA256\n" + str(timestamp) + "\n" + credential_scope + "\n"
                          + hashlib.sha256(canonical_request.encode("utf-8")).hexdigest())
        signing_key = self._hmac(("TC3" + self._secret_key).encode("utf-8"), date)
        signing_key = self._hmac(signing_key, TENCENT_TTS_SERVICE)
        signing_key = self._hmac(signing_key, "tc3_request")
        signature = hmac.new(signing_key, string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
        authorization = ("TC3-HMAC-SHA256 Credential=" + self._secret_id + "/" + credential_scope
                         + ", SignedHeaders=" + signed_headers + ", Signature=" + signature)
        headers = {
            "Authorization": authorization,
            "Content-Type": "application/json; charset=utf-8",
            "Host": TENCENT_TTS_HOST,
            "X-TC-Action": TENCENT_TTS_ACTION,
            "X-TC-Timestamp": str(timestamp),
            "X-TC-Version": TENCENT_TTS_VERSION,
        }
        if self._region:
            headers["X-TC-Region"] = self._region
        return headers

    @staticmethod
    def _hmac(key: bytes, value: str) -> bytes:
        """计算 TC3 的单步 HMAC，不保留任何中间密钥。"""
        return hmac.new(key, value.encode("utf-8"), hashlib.sha256).digest()

    def _retry(self, attempt: int) -> bool:
        """对可恢复的网络和限流错误退避，保留本次合成的会话语义。"""
        if attempt >= self._max_retries:
            return False
        self._sleep(min(4.0, 0.5 * (2 ** attempt)))
        return True

    @staticmethod
    def _audio(result: dict) -> bytes:
        """解析腾讯云标准响应，只发布验证通过的 base64 MP3。"""
        response = result.get("Response")
        if not isinstance(response, dict):
            raise RuntimeError("Tencent TTS response is invalid")
        error = response.get("Error")
        if error is not None:
            code = error.get("Code") if isinstance(error, dict) else None
            if not isinstance(code, str) or re.fullmatch(r"[A-Za-z0-9._-]{1,128}", code) is None:
                code = "unknown"
            raise RuntimeError(f"Tencent TTS rejected synthesis with code {code}")
        encoded_audio = response.get("Audio")
        if not isinstance(encoded_audio, str) or not encoded_audio or encoded_audio.startswith("data:"):
            raise RuntimeError("Tencent TTS response has no audio payload")
        try:
            audio = base64.b64decode(encoded_audio, validate=True)
        except (ValueError, binascii.Error) as exception:
            raise RuntimeError("Tencent TTS response audio is invalid") from exception
        if len(audio) < 4 or not (audio.startswith(b"ID3") or
                                  (audio[0] == 0xff and (audio[1] & 0xe0) == 0xe0)):
            raise RuntimeError("Tencent TTS response audio is empty")
        return audio


def split_text(text: str, limit: int = MAX_SEGMENT_UTF8_BYTES) -> list[str]:
    """按 UTF-8 字节上限优先在语句边界切分供应商可接受的文本。"""
    if limit < 32:
        raise ValueError("Segment byte limit is invalid")
    normalized = "\n".join(line.strip() for line in text.replace("\r", "").split("\n") if line.strip())
    if not normalized:
        raise ValueError("Chapter text is empty")
    segments: list[str] = []
    remaining = normalized
    while len(remaining.encode("utf-8")) > limit:
        prefix_end = utf8_prefix_end(remaining, limit)
        candidates = [match.end() for match in SENTENCE_ENDINGS.finditer(remaining[:prefix_end])]
        boundary = candidates[-1] if candidates else remaining.rfind("\n", 0, prefix_end + 1)
        if boundary <= 0:
            boundary = prefix_end
        segment = remaining[:boundary].strip()
        if segment:
            segments.append(segment)
        remaining = remaining[boundary:].lstrip()
    if remaining:
        segments.append(remaining)
    return segments


def utf8_prefix_end(text: str, maximum_bytes: int) -> int:
    """返回不超过字节上限的最长 Unicode 码点前缀终点。"""
    if maximum_bytes < 1:
        raise ValueError("Segment byte limit is invalid")
    used = 0
    for index, character in enumerate(text):
        size = len(character.encode("utf-8"))
        if used + size > maximum_bytes:
            if index == 0:
                raise ValueError("Segment byte limit is smaller than one Unicode code point")
            return index
        used += size
    return len(text)


def pronunciation_entries(values: object) -> list[tuple[str, str]]:
    """校验 Reader 冻结词典，只保留中文精确词条和带声调数字的拼音。"""
    if values is None:
        return []
    if not isinstance(values, list) or len(values) > MAX_PRONUNCIATIONS:
        raise ValueError("Audiobook pronunciation dictionary is invalid")
    result: list[tuple[str, str]] = []
    terms: set[str] = set()
    for value in values:
        if not isinstance(value, dict):
            raise ValueError("Audiobook pronunciation dictionary is invalid")
        term = value.get("term")
        pinyin = value.get("pinyin")
        if (not isinstance(term, str) or not 1 <= len(term) <= 128 or
                any(not "\u4e00" <= character <= "\u9fff" for character in term) or
                not isinstance(pinyin, str) or not PINYIN.fullmatch(pinyin)):
            raise ValueError("Audiobook pronunciation dictionary is invalid")
        if term in terms:
            raise ValueError("Audiobook pronunciation dictionary is invalid")
        terms.add(term)
        result.append((term, pinyin.lower()))
    # 重叠词条按最长优先，避免“张三”和“张三丰”被错误拆成两个读音。
    return sorted(result, key=lambda value: (-len(value[0]), value[0]))


def pronunciation_render(text: str, entries: list[tuple[str, str]]) -> tuple[str, str]:
    """以唯一允许的 phoneme 模板渲染命中词条；不匹配时仍请求纯文本。"""
    cursor = 0
    values: list[str] = []
    matched = False
    while cursor < len(text):
        selected = next(((term, pinyin) for term, pinyin in entries if text.startswith(term, cursor)), None)
        if selected is None:
            values.append(xml_escape(text[cursor]))
            cursor += 1
            continue
        term, pinyin = selected
        values.append(f'<phoneme alphabet="py" ph="{pinyin}">{xml_escape(term)}</phoneme>')
        cursor += len(term)
        matched = True
    if not matched:
        return text, "plain"
    rendered = "<speak>" + "".join(values) + "</speak>"
    validate_pronunciation_ssml(rendered)
    return rendered, "ssml"


def validate_pronunciation_ssml(value: str) -> None:
    """拒绝模板外的 SSML，阻止未审核标记、嵌套标签或任意属性进入供应商请求。"""
    try:
        root = element_tree.fromstring(value)
    except element_tree.ParseError as exception:
        raise ValueError("Audiobook pronunciation SSML is invalid") from exception
    if root.tag != "speak" or root.attrib:
        raise ValueError("Audiobook pronunciation SSML is invalid")
    for child in root:
        if (child.tag != "phoneme" or set(child.attrib) != {"alphabet", "ph"} or
                child.attrib.get("alphabet") != "py" or not PINYIN.fullmatch(child.attrib.get("ph", "")) or
                child.text is None or any(not "\u4e00" <= character <= "\u9fff" for character in child.text) or
                list(child)):
            raise ValueError("Audiobook pronunciation SSML is invalid")


def pronunciation_payloads(text: str, entries: list[tuple[str, str]],
                           limit: int = MAX_SEGMENT_UTF8_BYTES) -> list[tuple[str, str]]:
    """在源文本和渲染后的 SSML 都满足当前供应商字节上限时，返回可直接请求的分段。"""
    pending = split_text(text, limit)
    result: list[tuple[str, str]] = []
    while pending:
        source = pending.pop(0)
        rendered, text_type = pronunciation_render(source, entries)
        if len(rendered.encode("utf-8")) <= limit:
            result.append((rendered, text_type))
            continue
        if len(source) == 1:
            raise ValueError("Audiobook pronunciation SSML exceeds provider limit")
        source_bytes = len(source.encode("utf-8"))
        rendered_bytes = len(rendered.encode("utf-8"))
        source_limit = max(4, min(limit - 1, (source_bytes * limit) // rendered_bytes))
        prefix_end = utf8_prefix_end(source, source_limit)
        if prefix_end <= 0 or prefix_end >= len(source):
            prefix_end = max(1, len(source) // 2)
        # 将过长 SSML 对应的原文继续二分；两个子段都会重新进行安全模板渲染。
        pending.insert(0, source[prefix_end:])
        pending.insert(0, source[:prefix_end])
    return result


def validate_pronunciation_voice_capabilities(narrator_ssml_supported: object, chapters: object,
                                              minimum_confidence: float) -> None:
    """只允许已冻结为支持 SSML 的旁白和有效角色片段进入读音词典合成。"""
    if narrator_ssml_supported is not True or not isinstance(chapters, list):
        raise ValueError("Audiobook pronunciation SSML capability is unavailable")
    threshold = minimum_speaker_confidence(minimum_confidence)
    for chapter in chapters:
        if not isinstance(chapter, dict):
            raise ValueError("Audiobook synthesis chapter plan is invalid")
        segments = chapter.get("segments", [])
        if not isinstance(segments, list):
            raise ValueError("Audiobook speech segment plan is invalid")
        for segment in segments:
            if not isinstance(segment, dict):
                raise ValueError("Audiobook speech segment plan is invalid")
            if segment.get("speakerKind") != "CHARACTER":
                continue
            confidence = minimum_speaker_confidence(segment.get("confidence"))
            if confidence >= threshold and segment.get("ssmlSupported") is not True:
                raise ValueError("Audiobook pronunciation SSML capability is unavailable")


def concatenate_mp3(parts: list[Path], target: Path) -> None:
    """使用 ffmpeg 将同参数 MP3 分段无重编码地封装为章节音频。"""
    if not parts:
        raise ValueError("No MP3 segments were produced")
    listing = target.with_suffix(".concat.txt")
    listing.write_text("".join(f"file '{part.as_posix()}'\n" for part in parts), encoding="utf-8")
    command = [os.getenv("FFMPEG_BINARY", "ffmpeg"), "-nostdin", "-v", "error", "-f", "concat", "-safe", "0",
               "-i", str(listing), "-c", "copy", "-map_metadata", "-1", "-y", str(target)]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=300, check=False)
    except FileNotFoundError as exception:
        raise RuntimeError("ffmpeg is required for audiobook chapter assembly") from exception
    if result.returncode != 0 or not target.is_file() or target.stat().st_size == 0:
        raise RuntimeError("ffmpeg could not assemble audiobook chapter audio")


def measure_duration_ms(path: Path) -> int:
    """读取已封装音频的准确时长，以供播放器断点和下一章计算。"""
    command = [os.getenv("FFPROBE_BINARY", "ffprobe"), "-v", "error", "-show_entries", "format=duration",
               "-of", "default=nokey=1:noprint_wrappers=1", str(path)]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=60, check=False)
    except FileNotFoundError as exception:
        raise RuntimeError("ffprobe is required for audiobook duration measurement") from exception
    if result.returncode != 0:
        raise RuntimeError("ffprobe could not inspect audiobook chapter audio")
    try:
        duration_ms = round(float(result.stdout.strip()) * 1000)
    except ValueError as exception:
        raise RuntimeError("ffprobe returned an invalid audiobook duration") from exception
    if duration_ms <= 0:
        raise RuntimeError("audiobook chapter duration must be positive")
    return duration_ms


def register_audio_asset(assets: AssetRegistryClient, owner_id: int, generation_id: str, index: int,
                         storage_uri: str, content_sha256: str, size_bytes: int) -> str:
    """将已发布且已校验的章节 MP3 作为独立可复用资产登记。"""
    result = assets.register({
        "ownerId": owner_id,
        "idempotencyKey": f"audiobook-chapter:{generation_id}:{index}",
        "sourceType": "AUDIOBOOK_CHAPTER",
        "sourceBusinessId": f"{generation_id}:{index}",
        "contentSha256": content_sha256,
        "sizeBytes": size_bytes,
        "mimeType": "audio/mpeg",
        "location": {
            "idempotencyKey": f"audiobook-chapter-location:{generation_id}:{index}",
            "providerType": "STORAGE_GATEWAY",
            "storageUri": storage_uri,
            "providerVersion": "v1",
        },
    })
    return str(result["id"])


def minimum_speaker_confidence(value: object) -> float:
    """校验低置信度说话人片段回退旁白的部署阈值。"""
    if (not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value)
            or value < 0.0 or value > 1.0):
        raise ValueError("Audiobook speaker confidence threshold is invalid")
    return float(value)


def voice_render_segments(text: str, narrator_provider: str, narrator_voice_type: str, segments: object,
                          minimum_confidence: float = DEFAULT_MINIMUM_SPEAKER_CONFIDENCE) -> list[tuple[str, str, str]]:
    """填充旁白空白区间；只有高置信度角色片段才使用角色音色。"""
    if segments is None:
        segments = []
    if not isinstance(segments, list):
        raise ValueError("Audiobook speech segment plan is invalid")
    if not narrator_provider.strip() or not narrator_voice_type.strip():
        raise ValueError("Audiobook narrator voice plan is invalid")
    minimum_confidence = minimum_speaker_confidence(minimum_confidence)
    result: list[tuple[str, str, str]] = []
    previous_end = 0
    for segment in segments:
        if not isinstance(segment, dict):
            raise ValueError("Audiobook speech segment plan is invalid")
        start = segment.get("startCodepoint")
        end = segment.get("endCodepoint")
        provider = segment.get("provider")
        voice_type = segment.get("voiceType")
        speaker_kind = segment.get("speakerKind")
        confidence = segment.get("confidence")
        if not isinstance(start, int) or not isinstance(end, int) or start < previous_end or end <= start or end > len(text):
            raise ValueError("Audiobook speech segment bounds are invalid")
        if (not isinstance(provider, str) or not provider.strip() or not isinstance(voice_type, str) or
                not voice_type.strip()):
            raise ValueError("Audiobook speech segment voice is invalid")
        if speaker_kind not in {"CHARACTER", "NARRATOR", "UNKNOWN"}:
            raise ValueError("Audiobook speech segment speaker kind is invalid")
        confidence = minimum_speaker_confidence(confidence)
        if start > previous_end:
            result.append((narrator_provider, narrator_voice_type, text[previous_end:start]))
        if speaker_kind == "CHARACTER" and confidence >= minimum_confidence:
            result.append((provider, voice_type, text[start:end]))
        else:
            result.append((narrator_provider, narrator_voice_type, text[start:end]))
        previous_end = end
    if previous_end < len(text):
        result.append((narrator_provider, narrator_voice_type, text[previous_end:]))
    return [(provider, voice_type, value) for provider, voice_type, value in result if value.strip()]


def synthesize_chapter(storage: StorageGatewayClient, tts, work_directory: Path,
                       assets: AssetRegistryClient, owner_id: int, storage_root: str, generation_id: str, chapter: dict,
                       assemble=concatenate_mp3, duration_probe=measure_duration_ms,
                       narrator_provider: str = "", narrator_voice_type: str = "",
                       minimum_confidence: float = DEFAULT_MINIMUM_SPEAKER_CONFIDENCE,
                       pronunciations: list[tuple[str, str]] | None = None) -> dict:
    """下载、校验、合成、封装并发布一个章节音频。"""
    index = int(chapter["index"])
    expected_hash = str(chapter["contentSha256"])
    text_path = work_directory / f"chapter-{index:05d}.txt"
    storage.download(str(chapter["textStorageUri"]), text_path, MAX_CHAPTER_BYTES)
    text_bytes = text_path.read_bytes()
    if hashlib.sha256(text_bytes).hexdigest() != expected_hash:
        raise ValueError("Frozen chapter text checksum does not match")
    text = text_bytes.decode("utf-8", errors="strict")
    pronunciations = [] if pronunciations is None else pronunciations
    segment_paths: list[Path] = []
    sequence = 0
    for provider, voice_type, render_text in voice_render_segments(text, narrator_provider, narrator_voice_type,
                                                                     chapter.get("segments"), minimum_confidence):
        maximum_bytes = getattr(tts, "max_segment_utf8_bytes", MAX_SEGMENT_UTF8_BYTES)
        if not isinstance(maximum_bytes, int) or maximum_bytes < 32:
            raise ValueError("Audiobook TTS segment limit is invalid")
        for segment, text_type in pronunciation_payloads(render_text, pronunciations, maximum_bytes):
            segment_path = work_directory / f"chapter-{index:05d}-{sequence:04d}.mp3"
            segment_path.write_bytes(tts.synthesize(segment, str(uuid.uuid4()), voice_type, provider, text_type))
            segment_paths.append(segment_path)
            sequence += 1
    output_path = work_directory / f"chapter-{index:05d}.mp3"
    assemble(segment_paths, output_path)
    digest = hashlib.sha256(output_path.read_bytes()).hexdigest()
    size_bytes = output_path.stat().st_size
    duration_ms = duration_probe(output_path)
    storage_uri = storage.publish(output_path, storage_root,
                                  f"audiobooks/{generation_id}/audio/{index:05d}-{digest}.mp3",
                                  f"audiobook-audio:{generation_id}:{index}:{digest}", size_bytes, digest)
    asset_id = register_audio_asset(assets, owner_id, generation_id, index, storage_uri, digest, size_bytes)
    return {"index": index, "sourceContentSha256": expected_hash, "assetId": asset_id,
            "storageUri": storage_uri, "contentSha256": digest, "format": "mp3", "sizeBytes": size_bytes,
            "durationMs": duration_ms}


def execute(parameters: dict, storage: StorageGatewayClient, reader: ReaderServiceClient, tts,
            assets: AssetRegistryClient, work_directory: Path, assemble=concatenate_mp3,
            duration_probe=measure_duration_ms,
            minimum_confidence: float = DEFAULT_MINIMUM_SPEAKER_CONFIDENCE) -> dict:
    """按序处理所有冻结章节，并在每批提交后完成整书合成。"""
    generation_id = str(parameters["generationId"])
    owner_id = int(parameters["ownerId"])
    storage_root = str(parameters["storageRoot"])
    input_data = reader.input(generation_id)
    narrator_provider = input_data.get("narratorProvider")
    narrator_voice_type = input_data.get("narratorVoiceType")
    if (not isinstance(narrator_provider, str) or not narrator_provider.strip() or
            not isinstance(narrator_voice_type, str) or not narrator_voice_type.strip()):
        raise ValueError("Audiobook narrator voice plan is invalid")
    chapters = input_data.get("chapters")
    if not isinstance(chapters, list) or len(chapters) > MAX_CHAPTERS:
        raise ValueError("Audiobook synthesis chapter plan is invalid")
    rows: list[dict] = []
    pronunciations = pronunciation_entries(input_data.get("pronunciations", []))
    if pronunciations:
        validate_pronunciation_voice_capabilities(input_data.get("narratorSsmlSupported"), chapters,
                                                  minimum_confidence)
    # 任务可能已保存全部章节音频、但在完成回写前中断；此时只补做幂等完成回写。
    for chapter in chapters:
        rows.append(synthesize_chapter(storage, tts, work_directory, assets, owner_id, storage_root, generation_id,
                                       chapter, assemble, duration_probe, narrator_provider, narrator_voice_type,
                                       minimum_confidence, pronunciations))
        if len(rows) == BATCH_SIZE:
            reader.save(generation_id, rows)
            rows = []
    if rows:
        reader.save(generation_id, rows)
    result = reader.complete(generation_id)
    return {"generationId": generation_id, "synthesizedChapterCount": int(result["synthesizedChapterCount"])}


def write_result(result: dict) -> None:
    """以原子替换方式写入受限任务结果。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def tts_client_from_environment():
    """按显式供应商装配单一执行器，禁止在一次整书任务中混用计费与鉴权契约。"""
    provider = os.getenv("AUDIOBOOK_TTS_DEFAULT_PROVIDER", "VOLCENGINE").strip().upper()
    if provider == "TENCENT":
        return TencentTtsClient(
            os.environ.get("TENCENT_TTS_SECRET_ID", ""),
            os.environ.get("TENCENT_TTS_SECRET_KEY", ""),
            os.environ.get("TENCENT_TTS_VOICE_TYPE", ""),
            int(os.getenv("TENCENT_TTS_TIMEOUT_SECONDS", "90")),
            int(os.getenv("TENCENT_TTS_MAX_RETRIES", "3")),
            os.getenv("TENCENT_TTS_REGION", ""),
            int(os.getenv("TENCENT_TTS_SAMPLE_RATE", "16000")))
    if provider == "VOLCENGINE":
        return VolcengineTtsClient(
            os.environ.get("VOLCENGINE_TTS_ENDPOINT", ""),
            os.environ.get("VOLCENGINE_TTS_APP_ID", ""),
            os.environ.get("VOLCENGINE_TTS_ACCESS_TOKEN", ""),
            os.getenv("VOLCENGINE_TTS_CLUSTER", "volcano_tts"),
            os.environ.get("VOLCENGINE_TTS_VOICE_TYPE", ""),
            int(os.getenv("VOLCENGINE_TTS_TIMEOUT_SECONDS", "90")),
            int(os.getenv("VOLCENGINE_TTS_MAX_RETRIES", "3")),
            api_version=os.getenv("VOLCENGINE_TTS_API_VERSION", VOLCENGINE_TTS_API_V3),
            api_key=os.environ.get("VOLCENGINE_TTS_API_KEY", ""),
            resource_id=os.environ.get("VOLCENGINE_TTS_RESOURCE_ID", ""))
    raise ValueError("Audiobook TTS default provider is unsupported")


def main() -> None:
    """装配运行时客户端并执行整书章节音频合成。"""
    context_path = Path(os.environ["TASK_CONTEXT_FILE"])
    context = json.loads(context_path.read_text(encoding="utf-8"))
    storage = StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                                   os.environ.get("STORAGE_INTERNAL_TOKEN", ""))
    reader = ReaderServiceClient(os.getenv("READER_SERVICE_URL", "http://127.0.0.1:23230"),
                                 os.environ.get("READER_INTERNAL_TOKEN", ""))
    assets = AssetRegistryClient(os.getenv("ASSET_REGISTRY_URL", "http://127.0.0.1:23270"),
                                 os.environ.get("ASSET_REGISTRY_INTERNAL_TOKEN", ""))
    tts = tts_client_from_environment()
    write_result(execute(context["parameters"], storage, reader, tts, assets, context_path.parent,
                         minimum_confidence=float(os.getenv("AUDIOBOOK_VOICE_MINIMUM_SPEAKER_CONFIDENCE", "0.80"))))


if __name__ == "__main__":
    main()
