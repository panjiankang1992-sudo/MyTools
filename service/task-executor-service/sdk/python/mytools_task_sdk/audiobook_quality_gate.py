"""Validate the nonsecret evidence required before multi-character audiobook voices are enabled."""

from __future__ import annotations

import hashlib
import json
import math
import re
from typing import Any


ATTESTATION_SCHEMA_VERSION = "AUDIOBOOK_MULTI_CHARACTER_QUALITY_GATE_V1"
NER_VERIFICATION_SCHEMA_VERSION = "AUDIOBOOK_NER_VERIFICATION_V1"
CROSS_CHAPTER_REVIEW_SCHEMA_VERSION = "AUDIOBOOK_CROSS_CHAPTER_REVIEW_V1"
REQUIRED_EVALUATION_GATES = frozenset({
    "characterRecall",
    "characterPrecision",
    "aliasF1",
    "relationshipF1",
    "explicitSpeakerAccuracy",
    "speakerAccuracy",
    "presentationAccuracy",
    "characterTypeAccuracy",
    "traitF1",
})
MINIMUM_GOLDEN_BOOKS = 10
MINIMUM_NER_PERSON_EXAMPLES = 100
MINIMUM_NER_PRECISION = 0.85
MINIMUM_NER_RECALL = 0.85
MINIMUM_REVIEWED_CHARACTERS = 20
MINIMUM_REVIEWED_RELATIONSHIPS = 20
MINIMUM_REVIEWED_SPEECH_SEGMENTS = 100
MAX_ATTESTATION_BYTES = 16 * 1024
SHA256 = re.compile(r"[a-fA-F0-9]{64}\Z")
IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\Z")


def _bounded_integer(value: object, minimum: int, field: str) -> int:
    """Return a bounded positive evidence count without accepting booleans."""
    if (not isinstance(value, int) or isinstance(value, bool) or value < minimum or value > 10_000_000):
        raise ValueError(f"Audiobook quality gate {field} is invalid")
    return value


def _ratio(value: object, minimum: float, field: str) -> float:
    """Return a finite quality score that meets the approved minimum threshold."""
    if (not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value)
            or value < minimum or value > 1.0):
        raise ValueError(f"Audiobook quality gate {field} is invalid")
    return float(value)


def _sha256(value: object, field: str) -> str:
    """Validate one lowercased nonsecret integrity digest."""
    if not isinstance(value, str) or SHA256.fullmatch(value.strip()) is None:
        raise ValueError(f"Audiobook quality gate {field} is invalid")
    return value.strip().lower()


def _identifier(value: object, field: str) -> str:
    """Validate a deployment-safe opaque identifier without URLs or credentials."""
    if not isinstance(value, str) or IDENTIFIER.fullmatch(value.strip()) is None:
        raise ValueError(f"Audiobook quality gate {field} is invalid")
    return value.strip()


def _canonical_payload(value: dict[str, Any]) -> bytes:
    """Encode an attestation deterministically before its self-integrity digest is calculated."""
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode("utf-8")


def attestation_sha256(value: dict[str, Any]) -> str:
    """Calculate the integrity digest over an attestation excluding the self-referential digest field."""
    if not isinstance(value, dict):
        raise ValueError("Audiobook quality gate attestation is invalid")
    unsigned = {key: item for key, item in value.items() if key != "attestationSha256"}
    return hashlib.sha256(_canonical_payload(unsigned)).hexdigest()


def build_attestation(evaluation_report: dict[str, Any], ner_verification: dict[str, Any],
                      cross_chapter_review: dict[str, Any]) -> dict[str, Any]:
    """Build one compact attestation from three separately retained validation records."""
    if not isinstance(evaluation_report, dict):
        raise ValueError("Audiobook quality gate evaluation report is invalid")
    if evaluation_report.get("ready") is not True:
        raise ValueError("Audiobook quality gate evaluation report is not approved")
    metrics = evaluation_report.get("metrics")
    gates = evaluation_report.get("gates")
    if not isinstance(metrics, dict) or not isinstance(gates, dict) or set(gates) != REQUIRED_EVALUATION_GATES:
        raise ValueError("Audiobook quality gate evaluation report is invalid")
    if any(value is not True for value in gates.values()):
        raise ValueError("Audiobook quality gate evaluation report is not approved")
    book_count = _bounded_integer(metrics.get("bookCount"), MINIMUM_GOLDEN_BOOKS, "evaluation book count")

    if (not isinstance(ner_verification, dict)
            or ner_verification.get("schemaVersion") != NER_VERIFICATION_SCHEMA_VERSION
            or ner_verification.get("validated") is not True):
        raise ValueError("Audiobook quality gate NER verification is invalid")
    ner = {
        "deploymentId": _identifier(ner_verification.get("deploymentId"), "NER deployment ID"),
        "verificationSha256": hashlib.sha256(_canonical_payload(ner_verification)).hexdigest(),
        "personExampleCount": _bounded_integer(ner_verification.get("personExampleCount"),
                                                 MINIMUM_NER_PERSON_EXAMPLES, "NER person example count"),
        "precision": _ratio(ner_verification.get("precision"), MINIMUM_NER_PRECISION, "NER precision"),
        "recall": _ratio(ner_verification.get("recall"), MINIMUM_NER_RECALL, "NER recall"),
    }

    if (not isinstance(cross_chapter_review, dict)
            or cross_chapter_review.get("schemaVersion") != CROSS_CHAPTER_REVIEW_SCHEMA_VERSION
            or cross_chapter_review.get("approved") is not True):
        raise ValueError("Audiobook quality gate cross-chapter review is invalid")
    review = {
        "reviewSha256": hashlib.sha256(_canonical_payload(cross_chapter_review)).hexdigest(),
        "reviewedBookCount": _bounded_integer(cross_chapter_review.get("reviewedBookCount"),
                                                MINIMUM_GOLDEN_BOOKS, "reviewed book count"),
        "reviewedCharacterCount": _bounded_integer(cross_chapter_review.get("reviewedCharacterCount"),
                                                     MINIMUM_REVIEWED_CHARACTERS, "reviewed character count"),
        "reviewedRelationshipCount": _bounded_integer(cross_chapter_review.get("reviewedRelationshipCount"),
                                                        MINIMUM_REVIEWED_RELATIONSHIPS,
                                                        "reviewed relationship count"),
        "reviewedSpeechSegmentCount": _bounded_integer(cross_chapter_review.get("reviewedSpeechSegmentCount"),
                                                         MINIMUM_REVIEWED_SPEECH_SEGMENTS,
                                                         "reviewed speech segment count"),
    }
    report_sha256 = hashlib.sha256(_canonical_payload(evaluation_report)).hexdigest()
    result: dict[str, Any] = {
        "schemaVersion": ATTESTATION_SCHEMA_VERSION,
        "evaluation": {"reportSha256": report_sha256, "bookCount": book_count,
                       "gates": {key: True for key in sorted(REQUIRED_EVALUATION_GATES)}},
        "ner": ner,
        "crossChapterReview": review,
    }
    result["attestationSha256"] = attestation_sha256(result)
    return result


def parse_attestation(raw: object) -> dict[str, Any]:
    """Validate a canonical deployment attestation without exposing the contained audit metadata."""
    if not isinstance(raw, str) or not raw.strip() or len(raw.encode("utf-8")) > MAX_ATTESTATION_BYTES:
        raise ValueError("Audiobook quality gate attestation is invalid")
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as exception:
        raise ValueError("Audiobook quality gate attestation is invalid") from exception
    if not isinstance(value, dict) or set(value) != {
            "schemaVersion", "evaluation", "ner", "crossChapterReview", "attestationSha256"}:
        raise ValueError("Audiobook quality gate attestation is invalid")
    if value.get("schemaVersion") != ATTESTATION_SCHEMA_VERSION:
        raise ValueError("Audiobook quality gate attestation is invalid")
    evaluation = value.get("evaluation")
    ner = value.get("ner")
    review = value.get("crossChapterReview")
    if not isinstance(evaluation, dict) or set(evaluation) != {"reportSha256", "bookCount", "gates"}:
        raise ValueError("Audiobook quality gate attestation is invalid")
    _sha256(evaluation.get("reportSha256"), "evaluation report digest")
    _bounded_integer(evaluation.get("bookCount"), MINIMUM_GOLDEN_BOOKS, "evaluation book count")
    gates = evaluation.get("gates")
    if not isinstance(gates, dict) or set(gates) != REQUIRED_EVALUATION_GATES or any(item is not True
                                                                                        for item in gates.values()):
        raise ValueError("Audiobook quality gate attestation is invalid")
    if not isinstance(ner, dict) or set(ner) != {
            "deploymentId", "verificationSha256", "personExampleCount", "precision", "recall"}:
        raise ValueError("Audiobook quality gate attestation is invalid")
    _identifier(ner.get("deploymentId"), "NER deployment ID")
    _sha256(ner.get("verificationSha256"), "NER verification digest")
    _bounded_integer(ner.get("personExampleCount"), MINIMUM_NER_PERSON_EXAMPLES, "NER person example count")
    _ratio(ner.get("precision"), MINIMUM_NER_PRECISION, "NER precision")
    _ratio(ner.get("recall"), MINIMUM_NER_RECALL, "NER recall")
    if not isinstance(review, dict) or set(review) != {
            "reviewSha256", "reviewedBookCount", "reviewedCharacterCount", "reviewedRelationshipCount",
            "reviewedSpeechSegmentCount"}:
        raise ValueError("Audiobook quality gate attestation is invalid")
    _sha256(review.get("reviewSha256"), "review digest")
    _bounded_integer(review.get("reviewedBookCount"), MINIMUM_GOLDEN_BOOKS, "reviewed book count")
    _bounded_integer(review.get("reviewedCharacterCount"), MINIMUM_REVIEWED_CHARACTERS,
                     "reviewed character count")
    _bounded_integer(review.get("reviewedRelationshipCount"), MINIMUM_REVIEWED_RELATIONSHIPS,
                     "reviewed relationship count")
    _bounded_integer(review.get("reviewedSpeechSegmentCount"), MINIMUM_REVIEWED_SPEECH_SEGMENTS,
                     "reviewed speech segment count")
    expected = attestation_sha256(value)
    actual = _sha256(value.get("attestationSha256"), "attestation digest")
    if actual != expected:
        raise ValueError("Audiobook quality gate attestation integrity is invalid")
    return value
