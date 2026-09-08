#!/usr/bin/env python3
"""Validate service migration configuration without changing runtime state."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import sys
import urllib.parse

def resolve_sdk_root() -> Path:
    """Resolve the task SDK from either the source tree or an immutable release."""
    for ancestor in Path(__file__).resolve().parents:
        for candidate in (ancestor / "task-executor-sdk",
                          ancestor / "service" / "task-executor-service" / "sdk" / "python"):
            if candidate.is_dir():
                return candidate
    raise RuntimeError("Task SDK dependency is unavailable")


SDK_ROOT = resolve_sdk_root()
if str(SDK_ROOT) not in sys.path:
    sys.path.insert(0, str(SDK_ROOT))

from mytools_task_sdk.audiobook_quality_gate import parse_attestation

DEFAULT_SCHEMAS = {
    "DOWNLOAD_DB_NAME": "mytools_download",
    "STORAGE_DB_NAME": "mytools_storage",
    "ASSET_DB_NAME": "mytools_asset",
    "DRIVE_DB_NAME": "mytools_drive",
    "IDENTITY_DB_NAME": "mytools_identity",
    "MEDIA_LIBRARY_DB_NAME": "mytools_media",
    "READER_DB_NAME": "mytools_reader",
    "MESSAGING_DB_NAME": "mytools_messaging",
    "MESSAGE_AUTOMATION_DB_NAME": "mytools_message_automation",
    "APP_CATALOG_DB_NAME": "mytools_app_catalog",
    "DSH_CONNECTOR_DB_NAME": "mytools_dsh_connector",
}

SAFE_DISABLED_FLAGS = (
    "GATEWAY_IDENTITY_ROUTE_ENABLED",
    "GATEWAY_READER_ROUTE_ENABLED",
    "GATEWAY_DRIVE_ROUTE_ENABLED",
    "GATEWAY_DOWNLOAD_ROUTE_ENABLED",
    "GATEWAY_MEDIA_ROUTE_ENABLED",
    "GATEWAY_MESSAGING_ROUTE_ENABLED",
    "MEDIA_TAG_SIDECAR_ENABLED",
    "MEDIA_PROCESSING_SIDECAR_ENABLED",
    "READER_SEARCH_SIDECAR_ENABLED",
    "MESSAGING_REGISTRATION_MAIL_SIDECAR_ENABLED",
    "MESSAGING_ONEBOT_INGRESS_ENABLED",
    "MESSAGE_AUTOMATION_RELAY_ENABLED",
    "GATEWAY_APP_CATALOG_ROUTE_ENABLED",
    "GATEWAY_DSH_ROUTE_ENABLED",
)

FALSE_VALUES = {"", "0", "false", "no", "off"}
TRUE_VALUES = {"1", "true", "yes", "on"}
TENANT_ALLOWLIST_PATTERN = re.compile(r"[1-9][0-9]*(?:,[1-9][0-9]*)*")
PROVIDER_IDENTIFIER_PATTERN = re.compile(r"[A-Z][A-Z0-9_]{0,63}")
TENCENT_VOICE_TYPE_PATTERN = re.compile(r"[1-9][0-9]{0,9}")
TENCENT_TTS_CONTRACT_VERSION = "tencent-v1"
IMPLEMENTED_TTS_PROVIDERS = frozenset({"TENCENT", "VOLCENGINE"})
IMPLEMENTED_ANALYSIS_PROVIDERS = frozenset({"OPENAI_COMPATIBLE", "TENCENT_HUNYUAN"})


def parse_env_file(path: Path) -> dict[str, str]:
    """Read a simple KEY=VALUE file without evaluating shell expressions."""
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(r"(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)", line)
        if match is None:
            raise ValueError(f"Invalid environment line for key-only preflight: {raw_line}")
        value = match.group(2).strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[match.group(1)] = value
    return values


def boolean_setting(values: dict[str, str], key: str, errors: list[str]) -> bool:
    """Read one strict deployment boolean without including its value in diagnostics."""
    normalized = values.get(key, "false").strip().lower()
    if normalized in TRUE_VALUES:
        return True
    if normalized in FALSE_VALUES:
        return False
    errors.append(f"{key} must be a boolean")
    return False


def audiobook_boolean_setting(values: dict[str, str], key: str, errors: list[str]) -> bool:
    """Match the Executor's strict true/false parsing for audiobook safety controls."""
    normalized = values.get(key, "false").strip().lower()
    if normalized == "true":
        return True
    if normalized == "false":
        return False
    errors.append(f"{key} must be true or false")
    return False


def required_setting(values: dict[str, str], key: str, errors: list[str]) -> bool:
    """Require a nonblank setting while never returning or printing a secret value."""
    if values.get(key, "").strip():
        return True
    errors.append(f"{key} is required for audiobook generation")
    return False


def https_endpoint(values: dict[str, str], key: str, errors: list[str]) -> bool:
    """Require a credential-free HTTPS endpoint for one audiobook provider integration."""
    if not required_setting(values, key, errors):
        return False
    try:
        parsed = urllib.parse.urlsplit(values[key].strip())
    except ValueError:
        errors.append(f"{key} must be a credential-free HTTPS URL")
        return False
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username is not None
            or parsed.password is not None):
        errors.append(f"{key} must be a credential-free HTTPS URL")
        return False
    return True


def volcengine_api_version(values: dict[str, str], errors: list[str]) -> str:
    """读取已实现的火山协议版本，防止新旧鉴权配置被错误混用。"""
    version = values.get("VOLCENGINE_TTS_API_VERSION", "v3").strip().lower()
    if version in {"v1", "v3"}:
        return version
    errors.append("VOLCENGINE_TTS_API_VERSION must be v1 or v3")
    return ""


def volcengine_endpoint(values: dict[str, str], api_version: str, errors: list[str]) -> bool:
    """确认端点与当前执行器的 V1 或 V3 HTTP 契约严格一致。"""
    key = "VOLCENGINE_TTS_ENDPOINT"
    if not https_endpoint(values, key, errors):
        return False
    try:
        path = urllib.parse.urlsplit(values[key].strip()).path.rstrip("/")
    except ValueError:
        return False
    routes = {"v1": "/api/v1/tts", "v3": "/api/v3/tts/unidirectional"}
    expected = routes.get(api_version)
    if expected is None:
        return False
    if path != expected:
        errors.append(f"VOLCENGINE_TTS_ENDPOINT must use the implemented {expected} route")
        return False
    return True


def audiobook_tts_provider(values: dict[str, str], errors: list[str]) -> str:
    """读取本次整书任务唯一允许的已实现 TTS 供应商。"""
    provider = values.get("AUDIOBOOK_TTS_DEFAULT_PROVIDER", "VOLCENGINE").strip().upper()
    if provider not in IMPLEMENTED_TTS_PROVIDERS:
        errors.append("AUDIOBOOK_TTS_DEFAULT_PROVIDER is unsupported")
        return ""
    return provider


def audiobook_analysis_provider(values: dict[str, str], errors: list[str]) -> str:
    """读取人物分析阶段唯一允许的已实现模型供应商。"""
    provider = values.get("AUDIOBOOK_ANALYSIS_PROVIDER", "OPENAI_COMPATIBLE").strip().upper()
    if provider not in IMPLEMENTED_ANALYSIS_PROVIDERS:
        errors.append("AUDIOBOOK_ANALYSIS_PROVIDER is unsupported")
        return ""
    return provider


def bounded_number(values: dict[str, str], key: str, errors: list[str]) -> bool:
    """Validate a closed zero-to-one audiobook confidence threshold."""
    try:
        value = float(values.get(key, ""))
    except ValueError:
        errors.append(f"{key} must be a number from 0 to 1")
        return False
    if not 0.0 <= value <= 1.0:
        errors.append(f"{key} must be a number from 0 to 1")
        return False
    return True


def positive_integer(values: dict[str, str], key: str, errors: list[str]) -> bool:
    """Validate one positive audiobook quota without exposing its configured value."""
    value = values.get(key, "").strip()
    try:
        valid = value.isdecimal() and int(value) >= 1
    except ValueError:
        valid = False
    if not valid:
        errors.append(f"{key} must be a positive integer")
        return False
    return True


def compatible_voice_catalog(values: dict[str, str], provider: str, api_version: str,
                             errors: list[str]) -> bool:
    """检查音色目录能否被当前供应商契约实际合成，不输出目录或资源值。"""
    raw_catalog = values.get("AUDIOBOOK_VOICE_CATALOG_JSON", "")
    if not raw_catalog.strip():
        return False
    try:
        catalog = json.loads(raw_catalog)
    except json.JSONDecodeError:
        errors.append("AUDIOBOOK_VOICE_CATALOG_JSON must be a nonempty JSON array")
        return False
    if not isinstance(catalog, list) or not catalog:
        errors.append("AUDIOBOOK_VOICE_CATALOG_JSON must be a nonempty JSON array")
        return False
    compatible_narrator = False
    active_resource = values.get("VOLCENGINE_TTS_RESOURCE_ID", "").strip()
    for voice in catalog:
        if not isinstance(voice, dict):
            errors.append("AUDIOBOOK_VOICE_CATALOG_JSON has an invalid voice")
            return False
        default_versions = [TENCENT_TTS_CONTRACT_VERSION] if provider == "TENCENT" else ["v1"]
        versions = voice.get("apiVersions", default_versions)
        if (not isinstance(versions, list) or not versions or any(not isinstance(item, str)
                or item.strip().lower() not in {"v1", "v3", TENCENT_TTS_CONTRACT_VERSION} for item in versions)):
            errors.append("AUDIOBOOK_VOICE_CATALOG_JSON has invalid apiVersions")
            return False
        normalized_versions = {item.strip().lower() for item in versions}
        if api_version not in normalized_versions:
            continue
        if voice.get("provider") != provider or not isinstance(voice.get("voiceType"), str):
            errors.append("AUDIOBOOK_VOICE_CATALOG_JSON has an unsupported compatible voice")
            return False
        if provider == "TENCENT":
            voice_type = voice["voiceType"].strip()
            if (TENCENT_VOICE_TYPE_PATTERN.fullmatch(voice_type) is None or int(voice_type) > 2_000_000_000
                    or normalized_versions != {TENCENT_TTS_CONTRACT_VERSION}
                    or voice.get("resourceIds") is not None):
                errors.append("AUDIOBOOK_VOICE_CATALOG_JSON has incompatible Tencent voice capability")
                return False
        if api_version == "v3":
            resources = voice.get("resourceIds")
            if (not isinstance(resources, list) or active_resource not in resources
                    or voice.get("ssmlSupported") is True):
                errors.append("AUDIOBOOK_VOICE_CATALOG_JSON has incompatible V3 voice capability")
                return False
        if voice.get("narratorEligible") is True:
            compatible_narrator = True
    if not compatible_narrator:
        errors.append("AUDIOBOOK_VOICE_CATALOG_JSON has no compatible narrator voice")
        return False
    return True


def audiobook_readiness(values: dict[str, str], errors: list[str]) -> dict[str, object]:
    """Validate the secret-free shape of an enabled audiobook production configuration."""
    generation_enabled = audiobook_boolean_setting(values, "AUDIOBOOK_GENERATION_ENABLED", errors)
    multi_character_enabled = audiobook_boolean_setting(values, "AUDIOBOOK_MULTI_CHARACTER_ENABLED", errors)
    quality_gate_approved = audiobook_boolean_setting(values, "AUDIOBOOK_MULTI_CHARACTER_QUALITY_GATE_APPROVED",
                                                       errors)
    if not generation_enabled:
        errors.append("AUDIOBOOK_GENERATION_ENABLED must be true for audiobook production readiness")
    if multi_character_enabled and not quality_gate_approved:
        errors.append("AUDIOBOOK_MULTI_CHARACTER_QUALITY_GATE_APPROVED is required for multi-character voices")
    quality_gate_attestation_valid = False
    quality_gate_attestation: dict[str, object] | None = None
    if multi_character_enabled and quality_gate_approved:
        try:
            quality_gate_attestation = parse_attestation(
                values.get("AUDIOBOOK_MULTI_CHARACTER_QUALITY_GATE_ATTESTATION_JSON", ""))
            quality_gate_attestation_valid = True
        except ValueError:
            errors.append("AUDIOBOOK_MULTI_CHARACTER_QUALITY_GATE_ATTESTATION_JSON is invalid or unapproved")

    provider = audiobook_tts_provider(values, errors)
    analysis_provider = audiobook_analysis_provider(values, errors)
    api_version = volcengine_api_version(values, errors) if provider == "VOLCENGINE" else TENCENT_TTS_CONTRACT_VERSION
    if provider == "TENCENT":
        tts_required = ("TENCENT_TTS_SECRET_ID", "TENCENT_TTS_SECRET_KEY", "TENCENT_TTS_VOICE_TYPE")
        tts_endpoint_valid = True
    else:
        tts_required = (("VOLCENGINE_TTS_APP_ID", "VOLCENGINE_TTS_ACCESS_TOKEN", "VOLCENGINE_TTS_CLUSTER")
                        if api_version == "v1" else ("VOLCENGINE_TTS_API_KEY", "VOLCENGINE_TTS_RESOURCE_ID"))
        tts_required = (*tts_required, "VOLCENGINE_TTS_VOICE_TYPE")
        tts_endpoint_valid = volcengine_endpoint(values, api_version, errors)
    if analysis_provider == "TENCENT_HUNYUAN":
        analysis_required = ["AUDIOBOOK_ANALYSIS_MODEL"]
        if provider != "TENCENT":
            analysis_required.extend(("TENCENT_HUNYUAN_SECRET_ID", "TENCENT_HUNYUAN_SECRET_KEY"))
        analysis_endpoint_valid = True
    else:
        analysis_required = ["AUDIOBOOK_ANALYSIS_API_KEY", "AUDIOBOOK_ANALYSIS_MODEL"]
        analysis_endpoint_valid = https_endpoint(values, "AUDIOBOOK_ANALYSIS_ENDPOINT", errors)
    required = (*tts_required, *analysis_required, "AUDIOBOOK_VOICE_CATALOG_JSON", "STORAGE_INTERNAL_TOKEN",
        "READER_INTERNAL_TOKEN", "ASSET_REGISTRY_INTERNAL_TOKEN", "FFMPEG_BINARY", "FFPROBE_BINARY")
    present = {key: required_setting(values, key, errors) for key in required}
    ner_endpoint = values.get("AUDIOBOOK_NER_ENDPOINT", "").strip()
    valid_ner_endpoint = False
    if ner_endpoint:
        try:
            parsed_ner = urllib.parse.urlsplit(ner_endpoint)
            valid_ner_endpoint = (parsed_ner.scheme == "https" and parsed_ner.hostname
                                  and parsed_ner.username is None and parsed_ner.password is None)
        except ValueError:
            valid_ner_endpoint = False
        if not valid_ner_endpoint:
            errors.append("AUDIOBOOK_NER_ENDPOINT must be a credential-free HTTPS URL")
    if multi_character_enabled and not valid_ner_endpoint:
        errors.append("AUDIOBOOK_NER_ENDPOINT is required for multi-character voices")
    ner_deployment_id = values.get("AUDIOBOOK_NER_DEPLOYMENT_ID", "").strip()
    if multi_character_enabled:
        if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", ner_deployment_id) is None:
            errors.append("AUDIOBOOK_NER_DEPLOYMENT_ID is required for multi-character voices")
        elif quality_gate_attestation is not None:
            attested_ner = quality_gate_attestation.get("ner")
            if not isinstance(attested_ner, dict) or ner_deployment_id != attested_ner.get("deploymentId"):
                errors.append("AUDIOBOOK_NER_DEPLOYMENT_ID is not covered by the quality gate attestation")

    catalog_valid = compatible_voice_catalog(values, provider, api_version, errors)

    providers = {value.strip() for value in values.get("AUDIOBOOK_TTS_SUPPORTED_PROVIDERS", "").split(",")
                 if value.strip()}
    if not providers or any(PROVIDER_IDENTIFIER_PATTERN.fullmatch(value) is None for value in providers):
        errors.append("AUDIOBOOK_TTS_SUPPORTED_PROVIDERS is invalid")
    elif provider and providers != {provider}:
        errors.append("AUDIOBOOK_TTS_SUPPORTED_PROVIDERS must contain only the selected implemented provider")

    for key in ("AUDIOBOOK_MAXIMUM_CHARACTERS_PER_GENERATION", "AUDIOBOOK_DAILY_CHARACTERS_PER_OWNER"):
        positive_integer(values, key, errors)
    for key in ("AUDIOBOOK_VOICE_MINIMUM_CHARACTER_CONFIDENCE",
                "AUDIOBOOK_VOICE_MINIMUM_SPEAKER_CONFIDENCE"):
        bounded_number(values, key, errors)

    return {
        "required": True,
        "generationEnabled": generation_enabled,
        "ttsProvider": provider,
        "ttsApiVersion": api_version,
        "analysisProvider": analysis_provider,
        "multiCharacterEnabled": multi_character_enabled,
        "qualityGateApproved": quality_gate_approved,
        "qualityGateAttestationValid": quality_gate_attestation_valid,
        "nerDeploymentAttested": (quality_gate_attestation is not None
                                     and ner_deployment_id == quality_gate_attestation["ner"]["deploymentId"]),
        "ttsEndpointValid": tts_endpoint_valid,
        "analysisEndpointValid": analysis_endpoint_valid,
        "voiceCatalogJsonValid": catalog_valid,
        "requiredSettingsPresent": sum(present.values()),
        "requiredSettingsCount": len(required),
    }


def inspect(values: dict[str, str], allow_enabled: bool = False,
            require_audiobook: bool = False) -> dict[str, object]:
    """Build a secret-free readiness report from environment values."""
    schemas = {key: values.get(key, default) for key, default in DEFAULT_SCHEMAS.items()}
    errors: list[str] = []
    warnings: list[str] = []
    task_url = values.get("TASK_DB_URL", "jdbc:mysql://127.0.0.1:3306/mytools_task")
    task_match = re.match(r"jdbc:mysql://[^/]+/([A-Za-z0-9_]+)(?:\?|$)", task_url)
    if task_match is None:
        errors.append("TASK_DB_URL does not contain a valid MySQL schema")
    else:
        schemas["TASK_DB_URL_SCHEMA"] = task_match.group(1)
    by_name: dict[str, list[str]] = {}
    for key, schema in schemas.items():
        if not re.fullmatch(r"[A-Za-z0-9_]+", schema):
            errors.append(f"{key} has an invalid schema name")
        by_name.setdefault(schema, []).append(key)
    for schema, keys in by_name.items():
        if len(keys) > 1:
            errors.append(f"Schema {schema} is shared by: {', '.join(sorted(keys))}")

    flags: dict[str, bool] = {}
    for key in SAFE_DISABLED_FLAGS:
        enabled = values.get(key, "false").strip().lower() not in FALSE_VALUES
        flags[key] = enabled
        if enabled and not allow_enabled:
            errors.append(f"{key} must remain disabled before an approved grey release")
        elif enabled:
            warnings.append(f"{key} is enabled for an approved rehearsal")

    tenant_counts: dict[str, int] = {}
    for domain in ("READER", "DRIVE", "DOWNLOAD"):
        allowlist_key = f"GATEWAY_{domain}_TENANT_ALLOWLIST"
        route_key = f"GATEWAY_{domain}_ROUTE_ENABLED"
        allowlist = values.get(allowlist_key, "").strip()
        tenant_count = 0
        if allowlist and TENANT_ALLOWLIST_PATTERN.fullmatch(allowlist) is None:
            errors.append(f"{allowlist_key} must be comma-separated positive numeric IDs")
        elif allowlist:
            tenants = allowlist.split(",")
            if len(tenants) != len(set(tenants)):
                errors.append(f"{allowlist_key} contains duplicate IDs")
            tenant_count = len(tenants)
        if flags[route_key] and tenant_count == 0:
            errors.append(f"{allowlist_key} is required when {domain.title()} routing is enabled")
        tenant_counts[domain] = tenant_count

    identity_mode = values.get("IDENTITY_VALIDATION_MODE", "LEGACY").strip().upper()
    if identity_mode not in {"LEGACY", "DUAL", "IDENTITY"}:
        errors.append("IDENTITY_VALIDATION_MODE must be LEGACY, DUAL or IDENTITY")
    if flags["GATEWAY_IDENTITY_ROUTE_ENABLED"] and identity_mode == "LEGACY":
        errors.append("IDENTITY_VALIDATION_MODE must be DUAL or IDENTITY when Identity routing is enabled")

    audiobook = {"required": False}
    if require_audiobook:
        audiobook = audiobook_readiness(values, errors)

    grey_release = {"readerTenantCount": tenant_counts["READER"],
                    "driveTenantCount": tenant_counts["DRIVE"],
                    "downloadTenantCount": tenant_counts["DOWNLOAD"],
                    "identityValidationMode": identity_mode}
    return {"ready": not errors, "schemas": schemas, "flags": flags, "greyRelease": grey_release,
            "audiobook": audiobook, "errors": errors, "warnings": warnings}


def main() -> int:
    """Run the read-only cutover preflight command."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--allow-enabled", action="store_true")
    parser.add_argument("--require-audiobook", action="store_true",
                        help="require all nonsecret audiobook production settings without printing their values")
    arguments = parser.parse_args()
    values = dict(os.environ)
    if arguments.env_file is not None:
        values.update(parse_env_file(arguments.env_file))
    report = inspect(values, arguments.allow_enabled, arguments.require_audiobook)
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if report["ready"] else 1


if __name__ == "__main__":
    sys.exit(main())
