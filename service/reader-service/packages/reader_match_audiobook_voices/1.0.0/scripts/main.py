#!/usr/bin/env python3
"""Freeze one deterministic narrator and character voice plan for an analyzed audiobook."""

from __future__ import annotations

import hashlib
import json
import math
import os
from pathlib import Path
import re
import tempfile
import urllib.request

from mytools_task_sdk.audiobook_quality_gate import parse_attestation

MAX_VOICES = 500
MAX_CHARACTERS = 5_000
DEFAULT_MINIMUM_CHARACTER_CONFIDENCE = 0.80
PRESENTATIONS = {"FEMININE", "MASCULINE", "NON_BINARY", "NEUTRAL", "UNKNOWN"}
PROVIDER_IDENTIFIER = re.compile(r"^[A-Z][A-Z0-9_]{0,63}$")
RESOURCE_IDENTIFIER = re.compile(r"^[A-Za-z0-9._-]{1,256}$")
NER_DEPLOYMENT_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
PREVIEW_STORAGE_URI = re.compile(r"^storage://[A-Za-z0-9][A-Za-z0-9._-]{0,127}/.+$")
PREVIEW_HASH = re.compile(r"^[a-fA-F0-9]{64}$")
PREVIEW_FORMATS = frozenset({"mp3", "aac", "ogg", "wav"})
IMPLEMENTED_PROVIDERS = frozenset({"TENCENT", "VOLCENGINE"})
# 腾讯云基础合成使用独立合同名，避免把它误认成火山引擎 V1 并绕开音色目录校验。
TENCENT_TTS_CONTRACT_VERSION = "tencent-v1"
IMPLEMENTED_API_VERSIONS = frozenset({"v1", "v3", TENCENT_TTS_CONTRACT_VERSION})


class ReaderServiceClient:
    """Call the Reader Service's authenticated voice-plan contract."""

    def __init__(self, base_url: str, token: str):
        if not token:
            raise ValueError("Reader Service internal token is missing")
        self._base_url = base_url.rstrip("/")
        self._token = token

    def input(self, generation_id: str) -> dict:
        """Read frozen characters and any current enabled directory snapshot."""
        return self._request("GET", f"/{generation_id}/voice-match-input")

    def save(self, generation_id: str, plan: dict) -> dict:
        """Persist one idempotent catalog snapshot and locked binding plan."""
        return self._request("POST", f"/{generation_id}/voice-plan", plan)

    def complete(self, generation_id: str) -> dict:
        """Complete voice planning and advance asynchronous synthesis."""
        return self._request("POST", f"/{generation_id}/complete-voice-plan", {})

    def _request(self, method: str, path: str, body: dict | None = None) -> dict:
        payload = None if body is None else json.dumps(body, ensure_ascii=False,
                                                       separators=(",", ":")).encode("utf-8")
        headers = {"Authorization": f"Bearer {self._token}", "Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(
            f"{self._base_url}/api/internal/v1/audiobook-generations{path}", data=payload,
            method=method, headers=headers)
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))


def text(value: object, maximum: int, field: str) -> str:
    """Normalize a bounded deployment-supplied catalog field."""
    if not isinstance(value, str) or not (result := value.strip()) or len(result) > maximum:
        raise ValueError(f"Audiobook voice catalog {field} is invalid")
    return result


def tags(value: object, maximum: int, field: str) -> list[str]:
    """Normalize unique bounded style or rationale tags."""
    if not isinstance(value, list) or len(value) > maximum:
        raise ValueError(f"Audiobook voice catalog {field} is invalid")
    result: list[str] = []
    for item in value:
        normalized = text(item, 64, field)
        if normalized in result:
            raise ValueError(f"Audiobook voice catalog {field} is invalid")
        result.append(normalized)
    return result


def supported_providers_from_configuration(raw: object) -> frozenset[str]:
    """读取已部署且允许为本次音色计划提供音频的供应商集合。"""
    if not isinstance(raw, str):
        raise ValueError("Audiobook supported provider configuration is invalid")
    result: set[str] = set()
    for value in raw.split(","):
        provider = value.strip()
        if PROVIDER_IDENTIFIER.fullmatch(provider) is None:
            raise ValueError("Audiobook supported provider configuration is invalid")
        result.add(provider)
    if not result or result - IMPLEMENTED_PROVIDERS:
        raise ValueError("Audiobook supported provider configuration is invalid")
    return frozenset(result)


def active_tts_contract(api_version: object, resource_id: object) -> tuple[str, str | None]:
    """校验执行器已实现的协议，并为火山 V3 音色按资源 ID 做硬隔离。"""
    if not isinstance(api_version, str) or (version := api_version.strip().lower()) not in IMPLEMENTED_API_VERSIONS:
        raise ValueError("Audiobook TTS API version is invalid")
    if version in {"v1", TENCENT_TTS_CONTRACT_VERSION}:
        return version, None
    selected_resource = text(resource_id, 256, "V3 resource ID")
    if RESOURCE_IDENTIFIER.fullmatch(selected_resource) is None:
        raise ValueError("Audiobook TTS V3 resource ID is invalid")
    return version, selected_resource


def voice_api_versions(value: object) -> list[str]:
    """解析目录项已实测支持的 TTS 协议版本，禁止把未知能力默认为可用。"""
    if not isinstance(value, list) or not value or len(value) > len(IMPLEMENTED_API_VERSIONS):
        raise ValueError("Audiobook voice catalog apiVersions is invalid")
    result: list[str] = []
    for item in value:
        if not isinstance(item, str) or (version := item.strip().lower()) not in IMPLEMENTED_API_VERSIONS:
            raise ValueError("Audiobook voice catalog apiVersions is invalid")
        if version in result:
            raise ValueError("Audiobook voice catalog apiVersions is invalid")
        result.append(version)
    return result


def voice_resource_ids(value: object, supports_v3: bool) -> list[str]:
    """只允许显式列入已验证资源的 V3 音色参与自动匹配。"""
    if not supports_v3:
        if value is None:
            return []
        raise ValueError("Audiobook voice catalog resourceIds is invalid")
    if not isinstance(value, list) or not value or len(value) > 32:
        raise ValueError("Audiobook voice catalog resourceIds is invalid")
    result: list[str] = []
    for item in value:
        resource_id = text(item, 256, "resourceIds")
        if RESOURCE_IDENTIFIER.fullmatch(resource_id) is None or resource_id in result:
            raise ValueError("Audiobook voice catalog resourceIds is invalid")
        result.append(resource_id)
    return result


def preview_from_configuration(value: object) -> dict[str, object]:
    """校验一个可选的、已审核样音资产；存储路径永不写入 App 响应。"""
    absent = {"previewStorageUri": None, "previewContentSha256": None, "previewSizeBytes": None,
              "previewFormat": None, "previewDurationMs": None}
    if value is None:
        return absent
    if not isinstance(value, dict):
        raise ValueError("Audiobook voice preview configuration is invalid")
    storage_uri = value.get("storageUri")
    content_sha256 = value.get("contentSha256")
    size_bytes = value.get("sizeBytes")
    audio_format = value.get("format")
    duration_ms = value.get("durationMs")
    if (not isinstance(storage_uri, str) or PREVIEW_STORAGE_URI.fullmatch(storage_uri.strip()) is None
            or ".." in storage_uri or not isinstance(content_sha256, str)
            or PREVIEW_HASH.fullmatch(content_sha256.strip()) is None
            or not isinstance(size_bytes, int) or isinstance(size_bytes, bool) or size_bytes < 1
            or not isinstance(audio_format, str) or audio_format.strip().lower() not in PREVIEW_FORMATS
            or not isinstance(duration_ms, int) or isinstance(duration_ms, bool) or duration_ms < 1):
        raise ValueError("Audiobook voice preview configuration is invalid")
    return {"previewStorageUri": storage_uri.strip(),
            "previewContentSha256": content_sha256.strip().lower(),
            "previewSizeBytes": size_bytes,
            "previewFormat": audio_format.strip().lower(),
            "previewDurationMs": duration_ms}


def catalog_from_configuration(raw: str, supported_providers: frozenset[str], api_version: object = "v1",
                               resource_id: object = None, active_provider: object = "VOLCENGINE") -> list[dict]:
    """Load an explicit nonsecret voice inventory from private deployment configuration."""
    if not supported_providers:
        raise ValueError("Audiobook supported provider configuration is invalid")
    active_version, active_resource = active_tts_contract(api_version, resource_id)
    if (not isinstance(active_provider, str) or active_provider.strip() not in supported_providers):
        raise ValueError("Audiobook active TTS provider is invalid")
    selected_provider = active_provider.strip()
    try:
        values = json.loads(raw)
    except json.JSONDecodeError as exception:
        raise ValueError("Audiobook voice catalog configuration is invalid JSON") from exception
    if not isinstance(values, list) or not values or len(values) > MAX_VOICES:
        raise ValueError("Audiobook voice catalog configuration is invalid")
    result: list[dict] = []
    identities: set[tuple[str, str]] = set()
    for value in values:
        if not isinstance(value, dict):
            raise ValueError("Audiobook voice catalog configuration is invalid")
        presentation = value.get("presentation")
        if (presentation not in PRESENTATIONS or not isinstance(value.get("narratorEligible"), bool)
                or not isinstance(value.get("ssmlSupported"), bool)):
            raise ValueError("Audiobook voice catalog configuration is invalid")
        provider = text(value.get("provider"), 64, "provider")
        if PROVIDER_IDENTIFIER.fullmatch(provider) is None or provider not in supported_providers:
            raise ValueError("Audiobook voice catalog provider is unsupported")
        if provider != selected_provider:
            continue
        # 未声明 apiVersions 的历史目录只能用于 V1；V3 必须显式声明资源匹配。
        api_versions = voice_api_versions(value.get("apiVersions", ["v1"]))
        resource_ids = voice_resource_ids(value.get("resourceIds"), "v3" in api_versions)
        if active_version not in api_versions or (active_version == "v3" and active_resource not in resource_ids):
            continue
        if active_version == "v3" and value["ssmlSupported"]:
            # V3 发音标记尚未完成供应商 POC；即使目录误配，也不能让未验证 SSML 进入正文。
            raise ValueError("Audiobook voice catalog V3 SSML capability is not verified")
        entry = {"provider": provider,
                 "voiceType": text(value.get("voiceType"), 256, "voiceType"),
                 "language": text(value.get("language"), 32, "language"),
                 "presentation": presentation, "ageGroup": text(value.get("ageGroup"), 32, "ageGroup"),
                 "styleTags": tags(value.get("styleTags", []), 32, "styleTags"),
                 "narratorEligible": value["narratorEligible"],
                 "catalogVersion": text(value.get("catalogVersion"), 64, "catalogVersion"),
                 # 仅允许经部署验证的非实时端点和具体音色进入读音词典路径。
                 "ssmlSupported": value["ssmlSupported"],
                 **preview_from_configuration(value.get("preview"))}
        identity = (entry["provider"], entry["voiceType"])
        if identity in identities:
            raise ValueError("Audiobook voice catalog configuration has duplicate voice")
        identities.add(identity)
        result.append(entry)
    if not result:
        raise ValueError("Audiobook voice catalog has no compatible voice")
    if not any(value["narratorEligible"] for value in result):
        raise ValueError("Audiobook voice catalog has no compatible narrator voice")
    return sorted(result, key=lambda value: (value["provider"], value["voiceType"]))


def overlap(left: list[str], right: list[str]) -> int:
    """Return the case-insensitive intersection size for bounded tag lists."""
    return len({item.lower() for item in left}.intersection(item.lower() for item in right))


def minimum_character_confidence(value: object) -> float:
    """校验低置信度角色回退旁白的部署阈值。"""
    if (not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value)
            or value < 0.0 or value > 1.0):
        raise ValueError("Audiobook character confidence threshold is invalid")
    return float(value)


def multi_character_voice_policy(value: object, quality_gate_approved: object = "false",
                                 quality_gate_attestation: object = "",
                                 ner_deployment_id: object = "") -> tuple[bool, str | None]:
    """读取多角色换声灰度开关，并验证独立质量门禁证明后返回冻结摘要。"""
    if not isinstance(value, str):
        raise ValueError("Audiobook multi-character voice configuration is invalid")
    normalized = value.strip().lower()
    if normalized == "true":
        if not isinstance(quality_gate_approved, str):
            raise ValueError("Audiobook multi-character voice quality gate is invalid")
        if quality_gate_approved.strip().lower() != "true":
            # 多角色音色会直接改变听感，不能仅凭部署开关绕过黄金集和人工审核验收。
            raise ValueError("Audiobook multi-character voice quality gate is not approved")
        try:
            attestation = parse_attestation(quality_gate_attestation)
        except ValueError as exception:
            raise ValueError("Audiobook multi-character voice quality gate attestation is invalid") from exception
        if (not isinstance(ner_deployment_id, str)
                or NER_DEPLOYMENT_IDENTIFIER.fullmatch(ner_deployment_id.strip()) is None
                or ner_deployment_id.strip() != attestation["ner"]["deploymentId"]):
            raise ValueError("Audiobook multi-character voice NER deployment is not attested")
        return True, str(attestation["attestationSha256"])
    if normalized == "false":
        return False, None
    raise ValueError("Audiobook multi-character voice configuration is invalid")


def multi_character_enabled(value: object, quality_gate_approved: object = "false",
                            quality_gate_attestation: object = "", ner_deployment_id: object = "") -> bool:
    """保留布尔调用点，同时将启用判断绑定到可验证的质量门禁证明。"""
    return multi_character_voice_policy(value, quality_gate_approved, quality_gate_attestation, ner_deployment_id)[0]


def choose_voice(voices: list[dict], presentation: str, traits: list[str], narrator: bool) -> tuple[dict, float, list[str]]:
    """Score only configured candidates and choose deterministically with a safe fallback."""
    candidates = [voice for voice in voices if not narrator or voice["narratorEligible"]]
    if not candidates:
        raise ValueError("Audiobook voice catalog has no eligible voice")
    scored: list[tuple[float, dict, list[str]]] = []
    for voice in candidates:
        score = 0.20
        reasons = ["configured_catalog"]
        if voice["language"].lower().startswith("zh"):
            score += 0.15
            reasons.append("zh_language")
        if narrator and voice["narratorEligible"]:
            score += 0.20
            reasons.append("narrator_eligible")
        if presentation == voice["presentation"]:
            score += 0.35
            reasons.append("presentation_exact")
        elif presentation in {"UNKNOWN", "NEUTRAL"} or voice["presentation"] in {"UNKNOWN", "NEUTRAL"}:
            score += 0.15
            reasons.append("presentation_fallback")
        shared = overlap(traits, voice["styleTags"])
        if shared:
            score += min(0.10, shared * 0.05)
            reasons.append("style_overlap")
        scored.append((min(score, 1.0), voice, reasons))
    scored.sort(key=lambda value: (-value[0], value[1]["provider"], value[1]["voiceType"]))
    score, voice, reasons = scored[0]
    return voice, score, reasons


def locked_bindings_from_input(input_data: dict, voices: list[dict]) -> dict[str, dict]:
    """Validate frozen bindings inherited from unchanged chapters against the current approved catalog."""
    raw_bindings = input_data.get("lockedBindings", [])
    if not isinstance(raw_bindings, list) or len(raw_bindings) > MAX_CHARACTERS + 1:
        raise ValueError("Audiobook inherited voice bindings are invalid")
    voices_by_identity = {(voice["provider"], voice["voiceType"]): voice for voice in voices}
    result: dict[str, dict] = {}
    for value in raw_bindings:
        if not isinstance(value, dict):
            raise ValueError("Audiobook inherited voice binding is invalid")
        role_key = text(value.get("roleKey"), 512, "inherited role")
        provider = text(value.get("provider"), 64, "inherited provider")
        voice_type = text(value.get("voiceType"), 256, "inherited voice type")
        voice = voices_by_identity.get((provider, voice_type))
        if voice is None:
            # 新章节不得继续合成已从当前审核音色目录撤销的音色。
            raise ValueError("Audiobook inherited voice binding is no longer approved")
        canonical_name = value.get("characterCanonicalName")
        if role_key == "NARRATOR":
            if canonical_name is not None:
                raise ValueError("Audiobook inherited narrator binding is invalid")
        elif role_key.startswith("CHARACTER:"):
            canonical_name = text(canonical_name, 256, "inherited character")
            if role_key != "CHARACTER:" + canonical_name:
                raise ValueError("Audiobook inherited character binding is invalid")
        else:
            raise ValueError("Audiobook inherited voice binding role is invalid")
        if role_key in result:
            raise ValueError("Audiobook inherited voice binding is duplicated")
        result[role_key] = {"voice": voice,
                            "matchScore": minimum_character_confidence(value.get("matchScore")),
                            "matchMethod": text(value.get("matchMethod"), 64, "inherited match method"),
                            "rationaleTags": tags(value.get("rationaleTags", []), 32,
                                                  "inherited rationale tags")}
    return result


def locked_binding(role_key: str, canonical_name: str | None, inherited: dict) -> dict:
    """Build one persisted binding while making the reuse provenance visible to later review."""
    voice = inherited["voice"]
    reasons = list(inherited["rationaleTags"])
    if "incremental_voice_lock" not in reasons:
        reasons.append("incremental_voice_lock")
    return {"roleKey": role_key, "characterCanonicalName": canonical_name, "provider": voice["provider"],
            "voiceType": voice["voiceType"], "matchScore": inherited["matchScore"],
            "matchMethod": inherited["matchMethod"], "rationaleTags": reasons, "locked": True}


def build_plan(input_data: dict, voices: list[dict], minimum_confidence: float,
               allow_character_voices: bool = False,
               quality_gate_attestation_sha256: str | None = None) -> dict:
    """Create a complete locked narrator plus one binding per stable character."""
    characters = input_data.get("characters")
    if not isinstance(characters, list) or len(characters) > MAX_CHARACTERS:
        raise ValueError("Audiobook voice match input is invalid")
    if not isinstance(allow_character_voices, bool):
        raise ValueError("Audiobook multi-character voice configuration is invalid")
    if quality_gate_attestation_sha256 is not None and PREVIEW_HASH.fullmatch(quality_gate_attestation_sha256) is None:
        raise ValueError("Audiobook multi-character voice quality gate attestation is invalid")
    if allow_character_voices and quality_gate_attestation_sha256 is None:
        raise ValueError("Audiobook multi-character voice quality gate attestation is invalid")
    minimum_confidence = minimum_character_confidence(minimum_confidence)
    inherited_bindings = locked_bindings_from_input(input_data, voices)
    inherited_narrator = inherited_bindings.get("NARRATOR")
    if inherited_narrator is not None:
        narrator = inherited_narrator["voice"]
        bindings = [locked_binding("NARRATOR", None, inherited_narrator)]
    else:
        narrator, narrator_score, narrator_reasons = choose_voice(voices, "NEUTRAL", [], True)
        bindings = [{"roleKey": "NARRATOR", "characterCanonicalName": None, "provider": narrator["provider"],
                     "voiceType": narrator["voiceType"], "matchScore": narrator_score,
                     "matchMethod": "RULES_V1", "rationaleTags": narrator_reasons, "locked": True}]
    names: set[str] = set()
    for character in characters:
        if not isinstance(character, dict):
            raise ValueError("Audiobook voice match character is invalid")
        canonical_name = text(character.get("canonicalName"), 256, "character")
        if canonical_name in names:
            raise ValueError("Audiobook voice match character is duplicated")
        names.add(canonical_name)
        presentation = character.get("presentation")
        if presentation not in PRESENTATIONS:
            raise ValueError("Audiobook voice match character presentation is invalid")
        traits = tags(character.get("traits", []), 32, "characterTraits")
        confidence = minimum_character_confidence(character.get("confidence"))
        inherited = inherited_bindings.get("CHARACTER:" + canonical_name)
        if inherited is not None:
            bindings.append(locked_binding("CHARACTER:" + canonical_name, canonical_name, inherited))
            continue
        if not allow_character_voices:
            voice = narrator
            score = confidence
            reasons = ["multi_character_voice_disabled", "narrator_fallback"]
            match_method = "RULES_V1_NARRATOR_FALLBACK"
        elif confidence < minimum_confidence:
            voice = narrator
            score = confidence
            reasons = ["character_confidence_below_threshold", "narrator_fallback"]
            match_method = "RULES_V1_NARRATOR_FALLBACK"
        else:
            voice, voice_score, reasons = choose_voice(voices, presentation, traits, False)
            score = min(voice_score, confidence)
            reasons.append("character_confidence_accepted")
            match_method = "RULES_V1"
        bindings.append({"roleKey": "CHARACTER:" + canonical_name, "characterCanonicalName": canonical_name,
                         "provider": voice["provider"], "voiceType": voice["voiceType"],
                         "matchScore": score, "matchMethod": match_method, "rationaleTags": reasons,
                         "locked": True})
    binding_source = json.dumps({"qualityGateAttestationSha256": quality_gate_attestation_sha256,
                                 "voices": voices, "bindings": bindings}, ensure_ascii=False, sort_keys=True,
                                separators=(",", ":")).encode("utf-8")
    return {"voicePlanFingerprintSha256": hashlib.sha256(binding_source).hexdigest(), "voices": voices,
            "bindings": bindings, "qualityGateAttestationSha256": quality_gate_attestation_sha256}


def execute(parameters: dict, reader: ReaderServiceClient, catalog_json: str,
            supported_providers_csv: str = "VOLCENGINE",
            minimum_confidence: float = DEFAULT_MINIMUM_CHARACTER_CONFIDENCE,
            allow_character_voices: bool = False, api_version: object = "v1",
            resource_id: object = None, quality_gate_attestation_sha256: str | None = None,
            active_provider: object = "VOLCENGINE") -> dict:
    """Read analyzed characters, lock a deterministic plan, and advance chapter synthesis."""
    generation_id = str(parameters["generationId"])
    voices = catalog_from_configuration(catalog_json,
                                        supported_providers_from_configuration(supported_providers_csv),
                                        api_version, resource_id, active_provider)
    plan = build_plan(reader.input(generation_id), voices, minimum_confidence, allow_character_voices,
                      quality_gate_attestation_sha256)
    reader.save(generation_id, plan)
    completed = reader.complete(generation_id)
    return {"generationId": generation_id, "bindingCount": int(completed["bindingCount"])}


def write_result(result: dict) -> None:
    """Write a minimal scheduler-visible result using atomic replacement."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Build the internal client from deployment configuration and execute one plan task."""
    context_path = Path(os.environ["TASK_CONTEXT_FILE"])
    context = json.loads(context_path.read_text(encoding="utf-8"))
    reader = ReaderServiceClient(os.getenv("READER_SERVICE_URL", "http://127.0.0.1:23230"),
                                 os.environ.get("READER_INTERNAL_TOKEN", ""))
    allow_character_voices, attestation_sha256 = multi_character_voice_policy(
        os.getenv("AUDIOBOOK_MULTI_CHARACTER_ENABLED", "false"),
        os.getenv("AUDIOBOOK_MULTI_CHARACTER_QUALITY_GATE_APPROVED", "false"),
        os.getenv("AUDIOBOOK_MULTI_CHARACTER_QUALITY_GATE_ATTESTATION_JSON", ""),
        os.getenv("AUDIOBOOK_NER_DEPLOYMENT_ID", ""))
    active_provider = os.getenv("AUDIOBOOK_TTS_DEFAULT_PROVIDER", "VOLCENGINE").strip().upper()
    contract_version = (TENCENT_TTS_CONTRACT_VERSION if active_provider == "TENCENT"
                        else os.getenv("VOLCENGINE_TTS_API_VERSION", "v3"))
    resource_id = "" if active_provider == "TENCENT" else os.environ.get("VOLCENGINE_TTS_RESOURCE_ID", "")
    write_result(execute(context["parameters"], reader, os.environ.get("AUDIOBOOK_VOICE_CATALOG_JSON", ""),
                         os.getenv("AUDIOBOOK_TTS_SUPPORTED_PROVIDERS", "VOLCENGINE"),
                         float(os.getenv("AUDIOBOOK_VOICE_MINIMUM_CHARACTER_CONFIDENCE", "0.80")),
                         allow_character_voices,
                         contract_version, resource_id, attestation_sha256, active_provider))


if __name__ == "__main__":
    main()
