#!/usr/bin/env python3
"""对有声书人物与说话人分析结果执行离线黄金集评测。"""

from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import stat
import sys
import tempfile
from typing import Any


DEFAULT_CHARACTER_RECALL = 0.90
DEFAULT_CHARACTER_PRECISION = 0.85
DEFAULT_ALIAS_F1 = 0.80
DEFAULT_RELATIONSHIP_F1 = 0.75
DEFAULT_EXPLICIT_SPEAKER_ACCURACY = 0.90
DEFAULT_SPEAKER_ACCURACY = 0.80
DEFAULT_PRESENTATION_ACCURACY = 0.85
DEFAULT_CHARACTER_TYPE_ACCURACY = 0.80
DEFAULT_TRAIT_F1 = 0.75
DEFAULT_MINIMUM_ATTRIBUTE_EXAMPLES = 20
PRESENTATIONS = frozenset({"feminine", "masculine", "non_binary", "neutral", "unknown"})
MAX_EVALUATION_INPUT_BYTES = 64 * 1024 * 1024
MAX_EVALUATION_REPORT_BYTES = 2 * 1024 * 1024


def normalized_text(value: object, field: str) -> str:
    """将可比较的文本字段归一化为非空大小写无关标识。"""
    if not isinstance(value, str) or not (result := value.strip()):
        raise ValueError(f"Audiobook evaluation {field} is invalid")
    return result.casefold()


def nonnegative_integer(value: object, field: str) -> int:
    """校验章节和 Unicode 码点等非负整数标识。"""
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise ValueError(f"Audiobook evaluation {field} is invalid")
    return value


def positive_integer(value: object, field: str) -> int:
    """校验 Unicode 区间结束等正整数标识。"""
    result = nonnegative_integer(value, field)
    if result == 0:
        raise ValueError(f"Audiobook evaluation {field} is invalid")
    return result


def object_list(value: object, field: str) -> list[dict[str, Any]]:
    """校验一个由 JSON 对象组成的受限列表。"""
    if not isinstance(value, list):
        raise ValueError(f"Audiobook evaluation {field} is invalid")
    result: list[dict[str, Any]] = []
    for item in value:
        if not isinstance(item, dict):
            raise ValueError(f"Audiobook evaluation {field} is invalid")
        result.append(item)
    return result


def analysis_object(value: object, field: str) -> dict[str, Any]:
    """校验包含人物、关系和说话人清单的一本书分析结果。"""
    if not isinstance(value, dict):
        raise ValueError(f"Audiobook evaluation {field} is invalid")
    for key in ("characters", "relationships", "speechSegments"):
        object_list(value.get(key), f"{field}.{key}")
    return value


def character_keys(analysis: dict[str, Any]) -> set[str]:
    """抽取稳定人物名集合。"""
    return set(character_index(analysis))


def character_index(analysis: dict[str, Any]) -> dict[str, dict[str, Any]]:
    """建立唯一规范人物名到人物记录的索引，拒绝会掩盖评测误差的重复名称。"""
    result: dict[str, dict[str, Any]] = {}
    for value in object_list(analysis["characters"], "characters"):
        key = normalized_text(value.get("canonicalName"), "character.canonicalName")
        if key in result:
            raise ValueError("Audiobook evaluation character is duplicated")
        result[key] = value
    return result


def alias_keys(analysis: dict[str, Any]) -> set[tuple[str, str]]:
    """抽取人物与别名的有向对应关系。"""
    result: set[tuple[str, str]] = set()
    for canonical_name, character in character_index(analysis).items():
        for alias in object_list(character.get("aliases", []), "character.aliases"):
            result.add((canonical_name, normalized_text(alias.get("alias"), "alias.alias")))
    return result


def relationship_keys(analysis: dict[str, Any]) -> set[tuple[str, str, str, str]]:
    """抽取可比较的人物关系集合。"""
    return {(normalized_text(value.get("sourceCanonicalName"), "relationship.sourceCanonicalName"),
             normalized_text(value.get("targetCanonicalName"), "relationship.targetCanonicalName"),
             normalized_text(value.get("relationshipType"), "relationship.relationshipType"),
             normalized_text(value.get("direction"), "relationship.direction"))
            for value in object_list(analysis["relationships"], "relationships")}


def speech_key(value: dict[str, Any]) -> tuple[int, int, int]:
    """返回一个可跨模型版本比对的章节文本区间定位键。"""
    chapter_index = nonnegative_integer(value.get("chapterIndex"), "speechSegment.chapterIndex")
    start = nonnegative_integer(value.get("textStartCodepoint"), "speechSegment.textStartCodepoint")
    end = positive_integer(value.get("textEndCodepoint"), "speechSegment.textEndCodepoint")
    if end <= start:
        raise ValueError("Audiobook evaluation speech segment range is invalid")
    return chapter_index, start, end


def speaker_value(value: dict[str, Any]) -> tuple[str, str | None]:
    """返回说话人类别与可选角色名的规范化标识。"""
    kind = normalized_text(value.get("speakerKind"), "speechSegment.speakerKind")
    if kind not in {"character", "narrator", "unknown"}:
        raise ValueError("Audiobook evaluation speech segment speaker kind is invalid")
    raw_name = value.get("speakerCanonicalName")
    if kind == "character":
        return kind, normalized_text(raw_name, "speechSegment.speakerCanonicalName")
    if raw_name is not None:
        raise ValueError("Audiobook evaluation non-character speaker is invalid")
    return kind, None


def speech_index(analysis: dict[str, Any]) -> dict[tuple[int, int, int], tuple[str, str | None]]:
    """建立章节区间到说话人归因的唯一索引。"""
    result: dict[tuple[int, int, int], tuple[str, str | None]] = {}
    for value in object_list(analysis["speechSegments"], "speechSegments"):
        key = speech_key(value)
        if key in result:
            raise ValueError("Audiobook evaluation speech segment is duplicated")
        result[key] = speaker_value(value)
    return result


def ratio(numerator: int, denominator: int) -> float:
    """计算空集合可比较的稳定比例。"""
    return 1.0 if denominator == 0 else numerator / denominator


def set_metrics(expected: set[Any], actual: set[Any]) -> dict[str, float | int]:
    """计算实体、别名或关系集合的精确率、召回率与 F1。"""
    correct = len(expected.intersection(actual))
    precision = ratio(correct, len(actual))
    recall = ratio(correct, len(expected))
    f1 = 0.0 if precision + recall == 0 else 2 * precision * recall / (precision + recall)
    return {"expected": len(expected), "actual": len(actual), "correct": correct, "precision": precision,
            "recall": recall, "f1": f1}


def optional_label(value: object, field: str, allowed: frozenset[str] | None = None) -> str | None:
    """读取可选但一旦标注就必须合法的属性标签。"""
    if value is None:
        return None
    result = normalized_text(value, field)
    if allowed is not None and result not in allowed:
        raise ValueError(f"Audiobook evaluation {field} is invalid")
    return result


def optional_traits(value: object) -> set[str] | None:
    """读取可选人物特点标签；缺失表示该人物尚未完成此维度黄金标注。"""
    if value is None:
        return None
    if not isinstance(value, list):
        raise ValueError("Audiobook evaluation character.traits is invalid")
    result = {normalized_text(item, "character.traits") for item in value}
    if len(result) != len(value):
        raise ValueError("Audiobook evaluation character.traits is duplicated")
    return result


def label_metrics(expected: dict[str, str], actual: dict[str, str | None]) -> dict[str, float | int]:
    """在已标注的人物子集上计算单值属性准确率，避免未标注样本污染指标。"""
    correct = sum(actual.get(name) == label for name, label in expected.items())
    available = sum(actual.get(name) is not None for name in expected)
    return {"expected": len(expected), "actual": available, "correct": correct,
            "accuracy": ratio(correct, len(expected))}


def attribute_metrics(expected: dict[str, Any], actual: dict[str, Any]) -> dict[str, Any]:
    """比较已黄金标注的人物声音呈现、类型与特点，不将缺少标注误当作正确。"""
    expected_characters = character_index(expected)
    actual_characters = character_index(actual)
    expected_presentations: dict[str, str] = {}
    actual_presentations: dict[str, str | None] = {}
    expected_types: dict[str, str] = {}
    actual_types: dict[str, str | None] = {}
    expected_traits: set[tuple[str, str]] = set()
    actual_traits: set[tuple[str, str]] = set()
    trait_labeled_character_count = 0
    for canonical_name, expected_character in expected_characters.items():
        actual_character = actual_characters.get(canonical_name, {})
        presentation = optional_label(expected_character.get("presentation"), "character.presentation", PRESENTATIONS)
        if presentation is not None and presentation != "unknown":
            expected_presentations[canonical_name] = presentation
            actual_presentations[canonical_name] = optional_label(actual_character.get("presentation"),
                                                                    "character.presentation", PRESENTATIONS)
        character_type = optional_label(expected_character.get("characterType"), "character.characterType")
        if character_type is not None and character_type != "unknown":
            expected_types[canonical_name] = character_type
            actual_types[canonical_name] = optional_label(actual_character.get("characterType"),
                                                           "character.characterType")
        traits = optional_traits(expected_character.get("traits"))
        if traits is not None:
            trait_labeled_character_count += 1
            expected_traits.update((canonical_name, trait) for trait in traits)
            actual_values = optional_traits(actual_character.get("traits")) or set()
            actual_traits.update((canonical_name, trait) for trait in actual_values)
    trait_metrics = set_metrics(expected_traits, actual_traits)
    trait_metrics["labeledCharacters"] = trait_labeled_character_count
    return {"presentations": label_metrics(expected_presentations, actual_presentations),
            "characterTypes": label_metrics(expected_types, actual_types),
            "traits": trait_metrics}


def speaker_metrics(expected: dict[str, Any], actual: dict[str, Any]) -> dict[str, dict[str, float | int]]:
    """按黄金引语区间比较显式和全量说话人归因准确率。"""
    expected_index = speech_index(expected)
    actual_index = speech_index(actual)
    explicit = {speech_key(value) for value in object_list(expected["speechSegments"], "speechSegments")
                if value.get("isExplicit") is True}
    all_correct = sum(actual_index.get(key) == speaker for key, speaker in expected_index.items())
    explicit_correct = sum(actual_index.get(key) == expected_index[key] for key in explicit)
    return {
        "all": {"expected": len(expected_index), "correct": all_correct,
                "accuracy": ratio(all_correct, len(expected_index))},
        "explicit": {"expected": len(explicit), "correct": explicit_correct,
                     "accuracy": ratio(explicit_correct, len(explicit))},
    }


def score_record(expected: dict[str, Any], actual: dict[str, Any]) -> dict[str, Any]:
    """计算一本书的所有分析质量指标。"""
    return {"characters": set_metrics(character_keys(expected), character_keys(actual)),
            "aliases": set_metrics(alias_keys(expected), alias_keys(actual)),
            "relationships": set_metrics(relationship_keys(expected), relationship_keys(actual)),
            "speakers": speaker_metrics(expected, actual), "attributes": attribute_metrics(expected, actual)}


def read_jsonl(path: Path, expected: bool) -> dict[str, dict[str, Any]]:
    """读取并校验以书籍标识去重的一份黄金或实际结果 JSONL。"""
    if not path.is_file() or path.is_symlink() or path.stat().st_size > MAX_EVALUATION_INPUT_BYTES:
        raise ValueError("Audiobook evaluation input is invalid")
    result: dict[str, dict[str, Any]] = {}
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as exception:
            raise ValueError(f"Audiobook evaluation input line {number} is invalid JSON") from exception
        if not isinstance(value, dict):
            raise ValueError(f"Audiobook evaluation input line {number} is invalid")
        book_id = normalized_text(value.get("bookId"), "bookId")
        analysis = analysis_object(value.get("expected" if expected else "actual"), "analysis")
        if book_id in result:
            raise ValueError("Audiobook evaluation bookId is duplicated")
        result[book_id] = analysis
    if not result:
        raise ValueError("Audiobook evaluation input is empty")
    return result


def write_evaluation_report(path: Path, report: dict[str, Any]) -> None:
    """原子写入仅含指标的私有评测证据文件，拒绝覆盖既有审计记录。"""
    if path.exists() or path.is_symlink():
        raise ValueError("Audiobook evaluation output already exists")
    if not path.parent.is_dir():
        raise ValueError("Audiobook evaluation output directory is unavailable")
    serialized = json.dumps(report, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
    if len(serialized.encode("utf-8")) > MAX_EVALUATION_REPORT_BYTES:
        raise ValueError("Audiobook evaluation output is too large")
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(serialized)
            handle.flush()
            os.fchmod(handle.fileno(), stat.S_IRUSR | stat.S_IWUSR)
        temporary.replace(path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def aggregate(records: list[dict[str, Any]]) -> dict[str, Any]:
    """按计数汇总多本书评测，避免简单平均掩盖小样本偏差。"""
    def merge_sets(key: str) -> dict[str, float | int]:
        expected = sum(record[key]["expected"] for record in records)
        actual = sum(record[key]["actual"] for record in records)
        correct = sum(record[key]["correct"] for record in records)
        precision = ratio(correct, actual)
        recall = ratio(correct, expected)
        f1 = 0.0 if precision + recall == 0 else 2 * precision * recall / (precision + recall)
        return {"expected": expected, "actual": actual, "correct": correct, "precision": precision,
                "recall": recall, "f1": f1}

    def merge_speakers(key: str) -> dict[str, float | int]:
        expected = sum(record["speakers"][key]["expected"] for record in records)
        correct = sum(record["speakers"][key]["correct"] for record in records)
        return {"expected": expected, "correct": correct, "accuracy": ratio(correct, expected)}

    def merge_labels(key: str) -> dict[str, float | int]:
        expected = sum(record["attributes"][key]["expected"] for record in records)
        actual = sum(record["attributes"][key]["actual"] for record in records)
        correct = sum(record["attributes"][key]["correct"] for record in records)
        return {"expected": expected, "actual": actual, "correct": correct, "accuracy": ratio(correct, expected)}

    def merge_attribute_sets(key: str) -> dict[str, float | int]:
        expected = sum(record["attributes"][key]["expected"] for record in records)
        actual = sum(record["attributes"][key]["actual"] for record in records)
        correct = sum(record["attributes"][key]["correct"] for record in records)
        precision = ratio(correct, actual)
        recall = ratio(correct, expected)
        f1 = 0.0 if precision + recall == 0 else 2 * precision * recall / (precision + recall)
        labeled_characters = sum(record["attributes"][key].get("labeledCharacters", 0) for record in records)
        return {"expected": expected, "actual": actual, "correct": correct, "precision": precision,
                "recall": recall, "f1": f1, "labeledCharacters": labeled_characters}

    return {"bookCount": len(records), "characters": merge_sets("characters"), "aliases": merge_sets("aliases"),
            "relationships": merge_sets("relationships"), "speakers": {"all": merge_speakers("all"),
                                                                            "explicit": merge_speakers("explicit")},
            "attributes": {"presentations": merge_labels("presentations"),
                           "characterTypes": merge_labels("characterTypes"),
                           "traits": merge_attribute_sets("traits")}}


def gate(metrics: dict[str, Any], minimum_character_recall: float,
         minimum_explicit_speaker_accuracy: float, minimum_speaker_accuracy: float, *,
         minimum_character_precision: float = DEFAULT_CHARACTER_PRECISION,
         minimum_alias_f1: float = DEFAULT_ALIAS_F1,
         minimum_relationship_f1: float = DEFAULT_RELATIONSHIP_F1,
         minimum_presentation_accuracy: float = DEFAULT_PRESENTATION_ACCURACY,
         minimum_character_type_accuracy: float = DEFAULT_CHARACTER_TYPE_ACCURACY,
         minimum_trait_f1: float = DEFAULT_TRAIT_F1,
         minimum_attribute_examples: int = DEFAULT_MINIMUM_ATTRIBUTE_EXAMPLES) -> dict[str, bool]:
    """依据产品门槛返回每一项质量门禁是否通过。"""
    thresholds = {"characterRecall": minimum_character_recall,
                  "characterPrecision": minimum_character_precision, "aliasF1": minimum_alias_f1,
                  "relationshipF1": minimum_relationship_f1,
                  "explicitSpeakerAccuracy": minimum_explicit_speaker_accuracy,
                  "speakerAccuracy": minimum_speaker_accuracy, "presentationAccuracy": minimum_presentation_accuracy,
                  "characterTypeAccuracy": minimum_character_type_accuracy, "traitF1": minimum_trait_f1}
    if any(not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value)
           or value < 0.0 or value > 1.0 for value in thresholds.values()):
        raise ValueError("Audiobook evaluation threshold is invalid")
    if (not isinstance(minimum_attribute_examples, int) or isinstance(minimum_attribute_examples, bool)
            or minimum_attribute_examples < 1):
        raise ValueError("Audiobook evaluation attribute sample size is invalid")
    result = {"characterRecall": metrics["characters"]["expected"] > 0
                                  and metrics["characters"]["recall"] >= minimum_character_recall,
              "characterPrecision": metrics["characters"]["expected"] > 0
                                    and metrics["characters"]["precision"] >= minimum_character_precision,
              "aliasF1": metrics["aliases"]["expected"] > 0 and metrics["aliases"]["f1"] >= minimum_alias_f1,
              "relationshipF1": metrics["relationships"]["expected"] > 0
                                and metrics["relationships"]["f1"] >= minimum_relationship_f1,
              "explicitSpeakerAccuracy": metrics["speakers"]["explicit"]["expected"] > 0
                                         and metrics["speakers"]["explicit"]["accuracy"]
                                         >= minimum_explicit_speaker_accuracy,
              "speakerAccuracy": metrics["speakers"]["all"]["expected"] > 0
                                 and metrics["speakers"]["all"]["accuracy"] >= minimum_speaker_accuracy}
    result["presentationAccuracy"] = metrics["attributes"]["presentations"]["expected"] >= minimum_attribute_examples \
        and metrics["attributes"]["presentations"]["accuracy"] >= minimum_presentation_accuracy
    result["characterTypeAccuracy"] = metrics["attributes"]["characterTypes"]["expected"] >= minimum_attribute_examples \
        and metrics["attributes"]["characterTypes"]["accuracy"] >= minimum_character_type_accuracy
    result["traitF1"] = metrics["attributes"]["traits"]["labeledCharacters"] >= minimum_attribute_examples \
        and metrics["attributes"]["traits"]["f1"] >= minimum_trait_f1
    return result


def main(argv: list[str] | None = None) -> int:
    """读取黄金与实际 JSONL，输出稳定指标并以退出码表达质量门禁。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--golden", required=True, type=Path)
    parser.add_argument("--actual", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--minimum-character-recall", type=float, default=DEFAULT_CHARACTER_RECALL)
    parser.add_argument("--minimum-character-precision", type=float, default=DEFAULT_CHARACTER_PRECISION)
    parser.add_argument("--minimum-alias-f1", type=float, default=DEFAULT_ALIAS_F1)
    parser.add_argument("--minimum-relationship-f1", type=float, default=DEFAULT_RELATIONSHIP_F1)
    parser.add_argument("--minimum-explicit-speaker-accuracy", type=float, default=DEFAULT_EXPLICIT_SPEAKER_ACCURACY)
    parser.add_argument("--minimum-speaker-accuracy", type=float, default=DEFAULT_SPEAKER_ACCURACY)
    parser.add_argument("--minimum-presentation-accuracy", type=float, default=DEFAULT_PRESENTATION_ACCURACY)
    parser.add_argument("--minimum-character-type-accuracy", type=float, default=DEFAULT_CHARACTER_TYPE_ACCURACY)
    parser.add_argument("--minimum-trait-f1", type=float, default=DEFAULT_TRAIT_F1)
    parser.add_argument("--minimum-attribute-examples", type=int, default=DEFAULT_MINIMUM_ATTRIBUTE_EXAMPLES)
    arguments = parser.parse_args(argv)
    try:
        golden = read_jsonl(arguments.golden, expected=True)
        actual = read_jsonl(arguments.actual, expected=False)
        if golden.keys() != actual.keys():
            raise ValueError("Audiobook evaluation golden and actual bookId sets differ")
        metrics = aggregate([score_record(golden[book_id], actual[book_id]) for book_id in sorted(golden)])
        gates = gate(metrics, arguments.minimum_character_recall, arguments.minimum_explicit_speaker_accuracy,
                     arguments.minimum_speaker_accuracy,
                     minimum_character_precision=arguments.minimum_character_precision,
                     minimum_alias_f1=arguments.minimum_alias_f1,
                     minimum_relationship_f1=arguments.minimum_relationship_f1,
                     minimum_presentation_accuracy=arguments.minimum_presentation_accuracy,
                     minimum_character_type_accuracy=arguments.minimum_character_type_accuracy,
                     minimum_trait_f1=arguments.minimum_trait_f1,
                     minimum_attribute_examples=arguments.minimum_attribute_examples)
    except (OSError, ValueError) as exception:
        print(json.dumps({"ready": False, "error": str(exception)}, ensure_ascii=False, sort_keys=True))
        return 1
    report = {"ready": all(gates.values()), "metrics": metrics, "gates": gates}
    if arguments.output is not None:
        try:
            write_evaluation_report(arguments.output, report)
        except (OSError, ValueError) as exception:
            print(json.dumps({"ready": False, "error": str(exception)}, ensure_ascii=False, sort_keys=True))
            return 1
        print(json.dumps({"ready": report["ready"], "written": True}, ensure_ascii=False, sort_keys=True))
    else:
        print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if report["ready"] else 2


if __name__ == "__main__":
    sys.exit(main())
