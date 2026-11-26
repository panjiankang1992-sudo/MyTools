"""加载独立新闻技能，校验事件分组并保留合并后的来源证据。"""
import copy
import hashlib
import json
from pathlib import Path

SKILL_PATH = Path(__file__).resolve().parent.parent / "skills/analyze-daily-news/SKILL.md"
# 每日每类只发布十条，采集候选池仍保留三十条供合并和排序。
MAX_CATEGORY_ITEMS = 10


def skill_hash():
    """技能内容参与恢复校验，更新编辑规则后不能重放旧分析。"""
    return hashlib.sha256(SKILL_PATH.read_bytes()).hexdigest()


def instruction(mode, payload, skill_path=SKILL_PATH):
    """明确加载版本化技能，材料只作为数据，模型不接触本地配置。"""
    return skill_path.read_text() + "\nExecution mode: " + mode + "\nUntrusted source material:\n" + json.dumps(payload, ensure_ascii=False)


def aliases(sections):
    """用短编号关联原始事件，不要求模型复制长哈希。"""
    return {section["category"] + ":" + str(item["rank"]): (section["category"], item)
            for section in sections for item in section["items"]}


def cluster_prompt(sections):
    """跨分类合并只传紧凑证据，完整正文留给后续分类分析。"""
    rows = [{"id": key, "category": category, "title": item["title"], "url": item["url"],
             "publishedAt": item.get("publishedAt"), "evidence": item.get("evidence", "")[:600]}
            for key, (category, item) in aliases(sections).items()]
    return instruction("cluster", {"candidates": rows})


def validate_clusters(value, sections):
    """整份日报的候选必须恰好覆盖一次，合并代表只能来自组内。"""
    known = aliases(sections)
    groups = value.get("groups") if isinstance(value, dict) else None
    if not isinstance(groups, list) or len(groups) > len(known):
        raise ValueError("DSH_CLUSTER_SET_INVALID")
    seen, result = set(), []
    for group in groups:
        if not isinstance(group, dict):
            raise ValueError("DSH_CLUSTER_SET_INVALID")
        members = group.get("members")
        if not isinstance(members, list) or not members or any(not isinstance(x, str) or x not in known for x in members) \
                or len(set(members)) != len(members) or seen.intersection(members) \
                or group.get("representative") not in members:
            raise ValueError("DSH_CLUSTER_SET_INVALID")
        validate_reason(group.get("mergeReason"), len(members) > 1)
        seen.update(members)
        result.append({key: group[key] for key in ("members", "representative", "mergeReason")})
    if seen != set(known):
        raise ValueError("DSH_CLUSTER_SET_INVALID")
    return {"groups": result}


def validate_reason(value, required):
    """合并与排序依据必须有界，不能用缺失理由掩盖丢弃候选。"""
    if not isinstance(value, str) or len(value) > 600 or (required and not value.strip()):
        raise ValueError("DSH_REASON_INVALID")


def merged_item(items, representative, rank):
    """来源、正文与图片只来自组内，合并后保留原始事件映射。"""
    item = copy.deepcopy(representative)
    originals = sorted({identity for member in items for identity in member.get("originalIds", [member["eventId"]])})
    item.update(rank=rank, originalIds=originals,
                eventId=originals[0] if len(originals) == 1 else hashlib.sha256(json.dumps(originals).encode()).hexdigest())
    item["sources"] = sorted({source for member in items for source in member["sources"]})
    item["githubCandidate"] = any(member.get("githubCandidate") or (member.get("technical") or {}).get("github")
                                  for member in items)
    links, parts = [], []
    for member in items:
        links.extend(member.get("sourceLinks", [member["url"], member.get("evidenceUrl", member["url"])]))
        parts.extend(member.get("evidenceParts", [{"title": member["title"], "url": member.get("evidenceUrl", member["url"]),
                                                   "evidence": member.get("evidence", ""),
                                                   "detailAvailable": member.get("detailAvailable", False)}]))
    item["sourceLinks"] = list(dict.fromkeys(links))
    item["evidenceParts"] = parts
    item["evidence"] = "\n\n".join(dict.fromkeys(part["evidence"] for part in parts if part["evidence"]))
    item["detailAvailable"] = any(part["detailAvailable"] for part in parts)
    image_owner = next((member for member in [representative, *items] if member.get("image")), None)
    if image_owner:
        item["image"] = copy.deepcopy(image_owner["image"])
        item["image"].setdefault("sourceUrl", image_owner["url"])
    return item


def cluster_sections(sections, result):
    """跨分类只保留一个事件，后续分类输入仍不超过原始三十条。"""
    known = aliases(sections)
    grouped = {section["category"]: [] for section in sections}
    for group in result["groups"]:
        category, representative = known[group["representative"]]
        item = merged_item([known[key][1] for key in group["members"]], representative, representative["rank"])
        item["mergeReason"] = group["mergeReason"]
        grouped[category].append(item)
    output = []
    for section in sections:
        items = sorted(grouped[section["category"]], key=lambda item: (item["rank"], item["eventId"]))
        for rank, item in enumerate(items, 1):
            item["rank"] = rank
        output.append(dict(section, items=items, candidateCount=len(section["items"])))
    return output


def selection_prompt(section, skill_path=SKILL_PATH):
    """给分类分析完整候选及合并来源，不预先截取前十。"""
    rows = [{key: item[key] for key in ("rank", "title", "url", "publishedAt", "sources", "score", "technical",
                                       "evidence", "detailAvailable", "evidenceParts", "mergeReason", "githubCandidate") if key in item}
            for item in section["items"]]
    # 有分来源证据时不重复发送拼接正文，减少上下文体积。
    for row in rows:
        if "evidenceParts" in row:
            row.pop("evidence", None)
    return instruction("select", {"category": section["category"], "candidates": rows}, skill_path)


def validate_selection(value, section):
    """校验榜单覆盖、分组唯一性与引用归属，拒绝未解释的丢条。"""
    value = normalize_selection_fields(value)
    # 结果上限由可信调用方传入，模型不能自行扩大榜单。
    limit = section.get("resultLimit", MAX_CATEGORY_ITEMS)
    if type(limit) is not int or limit not in (10, 20):
        raise ValueError("DSH_EVENT_SET_INVALID")
    known = {item["rank"]: item for item in section["items"]}
    rows = value.get("items") if isinstance(value, dict) else None
    excluded = value.get("excludedRanks") if isinstance(value, dict) else None
    if not isinstance(rows, list) or len(rows) > limit or not isinstance(excluded, list) \
            or (excluded and len(rows) != limit):
        raise ValueError("DSH_EVENT_SET_INVALID")
    seen, result = set(), []
    for rank, row in enumerate(rows, 1):
        if not isinstance(row, dict) or type(row.get("rank")) is not int or row["rank"] != rank:
            raise ValueError("DSH_RANK_SET_INVALID")
        members = row.get("memberRanks")
        if not isinstance(members, list) or not members or any(type(x) is not int or x not in known for x in members) \
                or len(set(members)) != len(members) or seen.intersection(members) \
                or type(row.get("representativeRank")) is not int or row["representativeRank"] not in members:
            raise ValueError("DSH_EVENT_SET_INVALID")
        validate_reason(row.get("mergeReason"), len(members) > 1)
        validate_reason(row.get("rankingReason"), True)
        for name, maximum in (("title", 400), ("summary", 1800), ("limitation", 600)):
            if not isinstance(row.get(name), str) or len(row[name]) > maximum or (name != "limitation" and not row[name].strip()):
                raise ValueError("DSH_CONTENT_INVALID")
        # 所有入选新闻均需可读详情，不再输出后十条简述。
        if not isinstance(row.get("points"), list) or not 1 <= len(row["points"]) <= (5 if limit == 20 else 3) \
                or any(not isinstance(point, str) or not point.strip() or len(point) > 500 for point in row["points"]):
            raise ValueError("DSH_POINTS_INVALID")
        item = merged_item([known[key] for key in members], known[row["representativeRank"]], rank)
        result.append({**{key: row[key] for key in ("rank", "memberRanks", "representativeRank", "mergeReason", "rankingReason",
                                                  "title", "summary", "points", "limitation")}, "eventId": item["eventId"]})
        seen.update(members)
    if any(type(x) is not int or x not in known for x in excluded) or len(set(excluded)) != len(excluded) \
            or seen.intersection(excluded) or seen.union(excluded) != set(known):
        raise ValueError("DSH_EVENT_SET_INVALID")
    github = {key for key, item in known.items() if item.get("githubCandidate")}
    if section["category"] == "TECH" and github and not seen.intersection(github):
        raise ValueError("DSH_SOURCE_COVERAGE_INVALID")
    return {"items": result, "excludedRanks": excluded}


def normalize_selection_fields(value):
    """仅兼容真实出现过的成员字段拼写错误，保留原值并继续完整校验。"""
    if not isinstance(value, dict) or not isinstance(value.get("items"), list):
        return value
    normalized = copy.deepcopy(value)
    for row in normalized["items"]:
        if not isinstance(row, dict) or "memberManks" not in row:
            continue
        # 双字段含义不明确时拒绝，不能擅自选择成员归属。
        if "memberRanks" in row:
            raise ValueError("DSH_EVENT_SET_INVALID")
        row["memberRanks"] = row.pop("memberManks")
    return normalized


def validation_feedback(value, material):
    """只生成结构与编号诊断，供模型修复草稿，不把新闻正文当成指令。"""
    if not isinstance(material, dict):
        return {"requiredGroupFields": ["members", "representative", "mergeReason"],
                "requiredCandidateIds": sorted(aliases(material)), "coverage": "exactly_once"}
    known = {item["rank"] for item in material["items"]}
    try:
        value = normalize_selection_fields(value)
    except ValueError:
        return {"issue": "ambiguous_member_fields", "requiredField": "memberRanks"}
    rows = value.get("items") if isinstance(value, dict) else None
    excluded = value.get("excludedRanks") if isinstance(value, dict) else None
    required = ["rank", "memberRanks", "representativeRank", "mergeReason", "rankingReason",
                "title", "summary", "points", "limitation"]
    feedback = {"requiredFields": required, "allowedCandidateRanks": sorted(known), "maximumItems": material.get("resultLimit", MAX_CATEGORY_ITEMS),
                "minimumItemsWhenExcluding": material.get("resultLimit", MAX_CATEGORY_ITEMS), "rowIssues": []}
    if not isinstance(rows, list) or not isinstance(excluded, list):
        return dict(feedback, issue="items_and_excludedRanks_must_be_arrays")
    used = []
    for index, row in enumerate(rows, 1):
        if not isinstance(row, dict):
            feedback["rowIssues"].append({"row": index, "issue": "must_be_object"})
            continue
        problems = ["missing_" + field for field in required if field not in row]
        members = row.get("memberRanks")
        if type(row.get("rank")) is not int or row["rank"] != index:
            problems.append("rank_must_equal_row_position")
        if not isinstance(members, list) or not members:
            problems.append("memberRanks_must_be_nonempty_array")
            members = []
        integers = [number for number in members if type(number) is int]
        if len(integers) != len(members) or any(number not in known for number in integers):
            problems.append("memberRanks_must_use_allowed_integers")
        if type(row.get("representativeRank")) is not int or row["representativeRank"] not in integers:
            problems.append("representativeRank_must_belong_to_memberRanks")
        if not isinstance(row.get("points"), list) or not 1 <= len(row["points"]) <= 3:
            problems.append("points_must_have_1_to_3_items")
        if problems:
            feedback["rowIssues"].append({"row": index, "issues": problems})
        used.extend(integers)
    used.extend(number for number in excluded if type(number) is int)
    feedback.update(outputItems=len(rows), unassignedCandidateRanks=sorted(known - set(used)),
                    repeatedCandidateRanks=sorted({number for number in used if used.count(number) > 1}),
                    unknownCandidateRanks=sorted(set(used) - known))
    return feedback


def selected_section(section, result):
    """把合法模型选择关联回已采集证据，模型不能决定链接或图片路径。"""
    known = {item["rank"]: item for item in section["items"]}
    items = [merged_item([known[key] for key in row["memberRanks"]], known[row["representativeRank"]], row["rank"])
             for row in result["items"]]
    return dict(section, items=items, clusteredCount=len(section["items"]), excludedCount=len(result["excludedRanks"]))
