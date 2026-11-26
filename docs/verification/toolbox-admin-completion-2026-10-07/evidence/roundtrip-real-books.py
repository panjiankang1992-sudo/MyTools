#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""真实教材无损往返验收脚本（B10 / D01）。

阶段：
  env      记录环境与源文件哈希
  import   POST /admin/v1/curriculum/import-from-files，并逐册核对磁盘字节 / 库内字节 / 清单哈希
  publish  对目标册做真实编辑（改文本、调 listTypes、增/删一条），保存草稿并发布，下载新版本原始字节
  compare  原始版本 vs 新版本逐字段对账，输出结构化差异清单

用法（每一步都可单独重跑）：
  python3 roundtrip-real-books.py all
  python3 roundtrip-real-books.py env|import|publish|compare

环境变量：
  ROUNDTRIP_BASE     管理接口基址（默认 http://127.0.0.1:23201）
  ROUNDTRIP_DB       隔离库名（默认 toolbox_roundtrip）
  ROUNDTRIP_MYSQL    容器名（默认 toolbox-mysql-test）
  ROUNDTRIP_PASSWORD 测试库口令（默认 toolbox-test-only）
  ROUNDTRIP_CONTENT  内容包根目录（默认 <repo>/toolbox-web/content）

本脚本只访问回环地址，不执行任何删除操作。
"""

import hashlib
import http.cookiejar
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("ROUNDTRIP_BASE", "http://127.0.0.1:23201")
DB = os.environ.get("ROUNDTRIP_DB", "toolbox_roundtrip")
MYSQL_CONTAINER = os.environ.get("ROUNDTRIP_MYSQL", "toolbox-mysql-test")
MYSQL_PASSWORD = os.environ.get("ROUNDTRIP_PASSWORD", "toolbox-test-only")
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", ".."))
CONTENT = os.environ.get("ROUNDTRIP_CONTENT", os.path.join(REPO, "toolbox-web", "content"))
OUT = os.path.dirname(os.path.abspath(__file__))
# 被验收的服务源码根目录。工作树会被其他线并发修改，因此对账跑在冻结副本上，
# 这里默认仍是工作树；跑冻结副本时用 ROUNDTRIP_SERVICE_ROOT 指过去，保证哈希与实际运行的代码一致。
SERVICE_ROOT = os.environ.get("ROUNDTRIP_SERVICE_ROOT",
                              os.path.join(REPO, "service", "toolbox-service"))
STUB_ACCOUNT = os.environ.get("ROUNDTRIP_ACCOUNT", "local-admin")
STUB_PASSWORD = os.environ.get("ROUNDTRIP_ADMIN_PASSWORD", "local-admin")

TARGETS = ["cn-ty-2-1-pep-2017", "cn-ty-3-1-pep-2024", "en-yl-3-1-yilin-2024"]
ROUNDS = {"cn-ty-2-1-pep-2017": 1, "cn-ty-3-1-pep-2024": 1, "en-yl-3-1-yilin-2024": 2}
STATE_FILE = "roundtrip-publish-state.json"

TEXT_EDIT = {
    "cn-ty-2-1-pep-2017": {"entryId": "cn-ty-2-1-pep-2017-0001", "text": "两个", "pinyin": "liǎng gè"},
    "cn-ty-3-1-pep-2024": {"entryId": "cn-ty-3-1-pep-2024-0001", "text": "绒毛", "pinyin": "róng máo"},
    "en-yl-3-1-yilin-2024": {"entryId": "en-yl-3-1-yilin-2024-0001", "text": "hi", "meaning": "嗨，你好"},
}
LISTTYPE_EDIT = {
    "cn-ty-2-1-pep-2017": {"entryId": "cn-ty-2-1-pep-2017-0011", "add": "expression"},
    "cn-ty-3-1-pep-2024": {"entryId": "cn-ty-3-1-pep-2024-0011", "add": "expression"},
    "en-yl-3-1-yilin-2024": {"entryId": "en-yl-3-1-yilin-2024-0011", "add": "expression"},
}
ADD_ENTRY = {
    "cn-ty-2-1-pep-2017": {"rowId": "roundtrip-new-2-1", "text": "返", "pinyin": "fǎn",
                           "listTypes": ["writing"]},
    "cn-ty-3-1-pep-2024": {"rowId": "roundtrip-new-3-1", "text": "返", "pinyin": "fǎn",
                           "listTypes": ["writing"]},
    "en-yl-3-1-yilin-2024": {"rowId": "roundtrip-new-en-3-1", "text": "roundtrip",
                             "meaning": "往返验收", "listTypes": ["vocabulary"]},
}
# 内容包里 cn-ty-3-1-pep-2024 的 editionLabel 含两个 U+FFFD（字节 EF BF BD），
# PUBLISH 校验会以 NAME_REPLACEMENT_CHAR 阻断（见 CurriculumValidator.requireName）。
# 先原样发布一次留下真实错误原文，再按管理员的一次真实编辑把标签补全——不放宽校验。
EDITION_LABEL_FIX = {"cn-ty-3-1-pep-2024": "2024 年版（2022 课标修订版，江苏）"}

ENTRY_DERIVED_FIELDS = {"entryVersion", "reviewStatus"}
# 教材级"允许变化"的字段：只包含**版本身份、时间戳与按内容重算的计数**。
# 其余（expectedEntryCount / missingScopes / regionCodes / curriculumYear / isbn / editionLabel）
# 即使代码里有理由，也**不算派生**，一律按"观察到的差异"报出来。
EDITION_DERIVED_FIELDS = {"contentVersion", "contentSha256", "verifiedAt", "publishedAt",
                          "availableEntryCount"}

REQUIRED_ENTRY_FIELDS = ["entryId", "text", "listTypes", "sourceRefs"]
# 条目级"系统性差异"的分类：这些差异与"有没有人编辑过这条"无关，每一条都会发生。
SYSTEMATIC_CATEGORY = {"entryVersion": "version-derived",
                       "order": "renumbered",
                       "reviewStatus": "status-product"}
# 已知变换（不是"编辑"，但也确实改了字段值）：
# - 草稿用空串承载"没有值"，公开正文用 null → null 被改写成 ""；
# - `form` 由正文重新推断（DraftToBookMapper.formOf），不来自 preserved。
NORMALIZED_FIELDS = {"meaning", "pinyinDisplay"}
KNOWN_TRANSFORM_CATEGORY = {"form": "form-recomputed"}
# 反向检查要看的"只在 preserved/具名往返里"的字段。
PRESERVED_ENTRY_FIELDS = ["pinyinSegments", "partOfSpeech", "senseId", "context", "promptContext",
                          "acceptableAnswers", "sourceRefs", "form", "blockingIssues",
                          "reviewStatus", "listTypes"]
ALL_ENTRY_FIELDS = ["entryId", "entryVersion", "editionId", "unitId", "lessonId", "order", "form",
                    "listTypes", "text", "pinyinDisplay", "pinyinSegments", "meaning",
                    "partOfSpeech", "senseId", "context", "promptContext", "acceptableAnswers",
                    "sourceRefs", "reviewStatus", "blockingIssues"]
SOURCE_FILES = [
    "src/main/java/com/yuyutian/mytools/toolbox/service/admin/curriculum/CurriculumPublisher.java",
    "src/main/java/com/yuyutian/mytools/toolbox/service/admin/curriculum/AdminTextbookService.java",
    "src/main/java/com/yuyutian/mytools/toolbox/service/admin/curriculum/DraftToBookMapper.java",
    "src/main/java/com/yuyutian/mytools/toolbox/service/admin/migration/CurriculumFileImporter.java",
    "src/main/java/com/yuyutian/mytools/toolbox/service/admin/migration/AdminCurriculumImportService.java",
    "src/main/java/com/yuyutian/mytools/toolbox/service/admin/validation/CurriculumValidator.java",
    "src/main/java/com/yuyutian/mytools/toolbox/mapper/admin/AdminTextbookRepository.java",
    "src/main/java/com/yuyutian/mytools/toolbox/controller/admin/curriculum/AdminTextbookController.java",
    "src/main/java/com/yuyutian/mytools/toolbox/controller/admin/curriculum/AdminCurriculumController.java",
]


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def manifest():
    with open(os.path.join(CONTENT, "catalog", "published.json"), encoding="utf-8") as handle:
        return json.load(handle)


def book_path(edition_id, content_version):
    return os.path.join(CONTENT, "textbooks", edition_id, content_version, "book.json")


def mysql(sql):
    out = subprocess.run(
        ["docker", "exec", MYSQL_CONTAINER, "mysql", "-uroot", "-p" + MYSQL_PASSWORD,
         "-N", "-B", "-e", sql], capture_output=True, text=True, check=True)
    return [line for line in out.stdout.splitlines() if line.strip()]


class Admin:
    """最小管理端 HTTP 客户端：CSRF nonce + 会话 Cookie（与浏览器同一条链路）。"""

    def __init__(self):
        jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
        self.csrf = None
        self.log = []

    def call(self, method, path, body=None, headers=None, raw=False):
        data = None
        request_headers = dict(headers or {})
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            request_headers["Content-Type"] = "application/json"
        request = urllib.request.Request(BASE + path, data=data, method=method,
                                         headers=request_headers)
        try:
            with self.opener.open(request, timeout=180) as response:
                payload = response.read()
                status = response.status
                resp_headers = dict(response.headers.items())
        except urllib.error.HTTPError as failure:
            payload = failure.read()
            status = failure.code
            resp_headers = dict(failure.headers.items())
        entry = {"method": method, "path": path, "status": status}
        if data is not None:
            entry["requestBodyBytes"] = len(data)
        self.log.append(entry)
        if raw:
            return status, payload, resp_headers
        try:
            return status, json.loads(payload.decode("utf-8", "replace")), resp_headers
        except json.JSONDecodeError:
            return status, {"_raw": payload.decode("utf-8", "replace")}, resp_headers

    def login(self):
        status, body, _ = self.call("GET", "/admin/v1/auth/csrf")
        if status != 200:
            raise SystemExit("csrf failed: %s %s" % (status, body))
        status, body, _ = self.call("POST", "/admin/v1/auth/login",
                                    {"account": STUB_ACCOUNT, "password": STUB_PASSWORD},
                                    {"X-Toolbox-Admin-Csrf": body["data"]["csrfToken"]})
        if status != 200:
            raise SystemExit("login failed: %s %s" % (status, body))
        self.csrf = body["data"]["csrfToken"]
        return body["data"]

    def write(self, method, path, body=None, extra=None):
        headers = {"X-Toolbox-Admin-Csrf": self.csrf, "Idempotency-Key": os.urandom(8).hex()}
        headers.update(extra or {})
        return self.call(method, path, body, headers)


def write_json(name, value):
    target = os.path.join(OUT, name)
    with open(target, "w", encoding="utf-8") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
    return target


def read_json(name):
    with open(os.path.join(OUT, name), encoding="utf-8") as handle:
        return json.load(handle)


def append_state(records):
    existing = read_json(STATE_FILE)["results"] if os.path.exists(os.path.join(OUT, STATE_FILE)) else []
    existing.extend(records)
    write_json(STATE_FILE, {"results": existing})
    return existing


def phase_env():
    root = SERVICE_ROOT
    digests = {}
    for relative in SOURCE_FILES:
        with open(os.path.join(root, relative), "rb") as handle:
            data = handle.read()
        digests[relative] = {"sha256": sha256_bytes(data), "bytes": len(data)}
    info = {"base": BASE, "database": DB, "mysqlContainer": MYSQL_CONTAINER,
            "contentRoot": CONTENT, "serviceRoot": root, "sourceDigests": digests,
            "manifestCatalogVersion": manifest().get("catalogVersion"),
            "mysqlVersion": mysql("SELECT VERSION();")}
    write_json("roundtrip-env.json", info)
    print("env -> roundtrip-env.json  manifest.catalogVersion=%s" % info["manifestCatalogVersion"])
    return info


def phase_import():
    admin = Admin()
    who = admin.login()
    status, body, _ = admin.call("POST", "/admin/v1/curriculum/import-from-files", None,
                                 {"X-Toolbox-Admin-Csrf": admin.csrf})
    print("import HTTP %s" % status)
    manifest_data = manifest()
    rows = []
    for declared in manifest_data["editions"]:
        edition_id, version = declared["editionId"], declared["contentVersion"]
        path = book_path(edition_id, version)
        exists = os.path.exists(path)
        item = {"editionId": edition_id, "contentVersion": version,
                "declaredSha256": declared["contentSha256"],
                "declaredEntryCount": declared.get("availableEntryCount"),
                "fileExists": exists}
        if exists:
            with open(path, "rb") as handle:
                data = handle.read()
            item["bytes"] = len(data)
            item["sha256"] = sha256_bytes(data)
            item["hashMatchesManifest"] = item["sha256"].lower() == declared["contentSha256"].lower()
            item["entryCountInFile"] = len(json.loads(data).get("entries", []))
        db_rows = mysql("SELECT version_id, content_sha256, LENGTH(content_bytes), published_by "
                        "FROM %s.toolbox_textbook_version WHERE edition_id='%s' AND content_version='%s';"
                        % (DB, edition_id, version))
        item["dbVersionRows"] = db_rows
        item["storedHashMatchesFile"] = bool(
            db_rows and db_rows[0].split("\t")[1].lower() == item.get("sha256", "").lower())
        code, payload, headers = admin.call(
            "GET", "/admin/v1/curriculum/versions/%s/%s/bytes" % (edition_id, version), raw=True)
        item["downloadStatus"] = code
        if code == 200:
            item["downloadedBytes"] = len(payload)
            item["downloadedSha256"] = sha256_bytes(payload)
            item["downloadHeaderSha256"] = headers.get("X-Content-Sha256")
            with open(path, "rb") as handle:
                item["downloadedBytesIdenticalToFile"] = payload == handle.read()
        rows.append(item)
    catalog = mysql("SELECT catalog_key, epoch, catalog_version, updated_at "
                    "FROM %s.toolbox_curriculum_catalog;" % DB)
    out = {"actor": who, "importHttpStatus": status, "importBody": body,
           "manifest": {"catalogVersion": manifest_data.get("catalogVersion"),
                        "publishedAt": manifest_data.get("publishedAt"),
                        "editionCount": len(manifest_data["editions"])},
           "perEdition": rows, "catalogRows": catalog, "callLog": admin.log}
    write_json("roundtrip-migration.json", out)
    print("migration -> roundtrip-migration.json ; catalog rows=%s" % catalog)
    print("import report: %s" % json.dumps(body.get("data"), ensure_ascii=False)[:600])
    return out


def load_draft(admin, edition_id):
    code, body, _ = admin.call("GET", "/admin/v1/textbooks/%s/draft" % edition_id)
    if code != 200:
        raise SystemExit("draft read failed for %s: %s %s" % (edition_id, code, body))
    return body["data"]


def apply_round1(edition_id, draft):
    changes = {"textEdit": None, "listTypeEdit": None, "addedEntryId": None}
    text_plan = TEXT_EDIT[edition_id]
    for entry in draft["entries"]:
        if entry.get("entryId") == text_plan["entryId"]:
            changes["textEdit"] = {"entryId": entry["entryId"], "before": entry["text"],
                                   "after": text_plan["text"]}
            entry["text"] = text_plan["text"]
            if "pinyin" in text_plan:
                changes["textEdit"]["pinyinBefore"] = entry.get("pinyin")
                changes["textEdit"]["pinyinAfter"] = text_plan["pinyin"]
                entry["pinyin"] = text_plan["pinyin"]
            if "meaning" in text_plan:
                changes["textEdit"]["meaningBefore"] = entry.get("meaning")
                changes["textEdit"]["meaningAfter"] = text_plan["meaning"]
                entry["meaning"] = text_plan["meaning"]
    if not changes["textEdit"]:
        raise SystemExit("text edit target not found: %s" % text_plan["entryId"])

    list_plan = LISTTYPE_EDIT[edition_id]
    for entry in draft["entries"]:
        if entry.get("entryId") == list_plan["entryId"]:
            before = list(entry.get("listTypes") or [])
            after = sorted(set(before + [list_plan["add"]]))
            entry["listTypes"] = after
            changes["listTypeEdit"] = {"entryId": entry["entryId"], "before": before, "after": after}
    if not changes["listTypeEdit"]:
        raise SystemExit("listTypes edit target not found: %s" % list_plan["entryId"])

    add_plan = ADD_ENTRY[edition_id]
    last_unit = draft["units"][-1]["unitId"]
    lesson_id = None
    for lesson in draft.get("lessons") or []:
        if lesson.get("unitId") == last_unit:
            lesson_id = lesson["lessonId"]
    source_ids = draft.get("sourceIds") or []
    draft["entries"].append({
        "rowId": add_plan["rowId"], "entryId": None, "unitId": last_unit,
        "clientUnitId": last_unit, "lessonId": lesson_id, "clientLessonId": lesson_id,
        "text": add_plan["text"], "pinyin": add_plan.get("pinyin"),
        "meaning": add_plan.get("meaning"), "listTypes": add_plan["listTypes"],
        "confirmed": True, "confirmedDigest": None, "automaticInitialized": False,
        "preserved": {},
        "sourceRefs": ([{"sourceId": source_ids[0], "locator": "roundtrip-added", "page": None}]
                       if source_ids else [])})
    changes["addedEntryId"] = add_plan["rowId"]
    changes["addedEntryUnit"] = last_unit
    changes["addedEntryLesson"] = lesson_id

    changes["sourceNoteBefore"] = draft.get("sourceNote")
    if not (draft.get("sourceNote") or "").strip():
        # sourceNote 是发布必填项（CurriculumValidator：SOURCE_REQUIRED），
        # 而 TextbookEdition 里没有这个字段，所以迁移草稿拿不到它——本次实测到的一处真实约束。
        draft["sourceNote"] = "来源：%s（迁移自内容包 %s）" % (
            ",".join(source_ids), manifest().get("catalogVersion"))
        changes["sourceNoteAfter"] = draft["sourceNote"]
    return changes


def apply_round2_delete(edition_id, draft, row_id):
    before = len(draft["entries"])
    kept = [entry for entry in draft["entries"] if entry.get("rowId") != row_id]
    if len(kept) == before:
        raise SystemExit("delete target not found: %s" % row_id)
    draft["entries"] = kept
    return {"deletedRowId": row_id, "entriesBefore": before, "entriesAfter": len(kept)}


def save_and_publish(admin, edition_id, draft, changes, tag):
    stored = load_draft(admin, edition_id)
    revision = stored["revision"]
    code, body, _ = admin.write("PUT", "/admin/v1/textbooks/%s/draft" % edition_id, draft,
                                {"If-Match": '"draft-%d"' % revision})
    record = {"tag": tag, "editionId": edition_id, "changes": changes,
              "baseRevision": revision, "saveStatus": code, "saveBody": body}
    if code != 200:
        return record
    revision = body["data"]["revision"]
    record["revisionAfterSave"] = revision
    code, vbody, _ = admin.write("POST", "/admin/v1/textbooks/%s/validate?draftRevision=%d"
                                 % (edition_id, revision))
    record["validateStatus"] = code
    record["validateBody"] = vbody
    code, pbody, _ = admin.write("POST", "/admin/v1/textbooks/%s/publish?draftRevision=%d"
                                 % (edition_id, revision))
    record["publishStatus"] = code
    record["publishBody"] = pbody
    if code == 200:
        version = pbody["data"]["contentVersion"]
        record["newContentVersion"] = version
        record["newVersionId"] = pbody["data"]["versionId"]
        record["newContentSha256"] = pbody["data"]["contentSha256"]
        record["snapshotReloaded"] = pbody["data"]["snapshotReloaded"]
        dcode, payload, dheaders = admin.call(
            "GET", "/admin/v1/curriculum/versions/%s/%s/bytes" % (edition_id, version), raw=True)
        record["downloadStatus"] = dcode
        if dcode == 200:
            name = "roundtrip-book-%s-%s.json" % (edition_id, version)
            with open(os.path.join(OUT, name), "wb") as handle:
                handle.write(payload)
            record["downloadedFile"] = name
            record["downloadedSha256"] = sha256_bytes(payload)
            record["downloadedHashMatchesPublish"] = (
                record["downloadedSha256"].lower() == record["newContentSha256"].lower())
            record["downloadHeaderSha256"] = dheaders.get("X-Content-Sha256")
    return record


def phase_repro_blocked():
    """复现 cn-ty-3-1-pep-2024 的阻断：把内容包原生的损坏 editionLabel 写回草稿，
    取回 validate 的完整问题清单（含问题码），再恢复已发布的修正值。不产生任何版本。"""
    edition_id = "cn-ty-3-1-pep-2024"
    admin = Admin()
    admin.login()
    fixed = EDITION_LABEL_FIX[edition_id]
    draft = load_draft(admin, edition_id)["draft"]
    corrupted = "2024 年版（2022 课标修订版，\ufffd\ufffd"
    draft["editionLabel"] = corrupted
    stored = load_draft(admin, edition_id)
    code, body, _ = admin.write("PUT", "/admin/v1/textbooks/%s/draft" % edition_id, draft,
                                {"If-Match": '"draft-%d"' % stored["revision"]})
    record = {"reproducedEditionLabel": corrupted, "saveStatus": code}
    revision = body["data"]["revision"]
    code, vbody, _ = admin.write("POST", "/admin/v1/textbooks/%s/validate?draftRevision=%d"
                                 % (edition_id, revision))
    record["validateStatus"] = code
    record["validateBody"] = vbody
    code, pbody, _ = admin.write("POST", "/admin/v1/textbooks/%s/publish?draftRevision=%d"
                                 % (edition_id, revision))
    record["publishStatus"] = code
    record["publishBody"] = pbody
    # 恢复修正值，保持草稿与已发布版本一致。
    draft = load_draft(admin, edition_id)["draft"]
    draft["editionLabel"] = fixed
    stored = load_draft(admin, edition_id)
    code, body, _ = admin.write("PUT", "/admin/v1/textbooks/%s/draft" % edition_id, draft,
                                {"If-Match": '"draft-%d"' % stored["revision"]})
    record["restoreStatus"] = code
    record["restoreRevision"] = body.get("data", {}).get("revision")
    write_json("roundtrip-blocked-repro.json", {"results": [record], "callLog": admin.log})
    print("repro blocked: validate=%s publish=%s issues=%s"
          % (record["validateStatus"], record["publishStatus"],
             json.dumps((vbody.get("data") or {}).get("issues"), ensure_ascii=False)[:500]))
    return record


def phase_dump_drafts():
    """把库里的草稿原样导出，供前端 `pairsToEntries` 用**真实数据**做往返检查。"""
    tag = os.environ.get("ROUNDTRIP_TAG", "")
    admin = Admin()
    admin.login()
    index = []
    for declared in manifest()["editions"]:
        edition_id = declared["editionId"]
        code, body, _ = admin.call("GET", "/admin/v1/textbooks/%s/draft" % edition_id)
        entry = {"editionId": edition_id, "status": code}
        if code == 200:
            name = "roundtrip-draft%s-%s.json" % (tag, edition_id)
            with open(os.path.join(OUT, name), "w", encoding="utf-8") as handle:
                json.dump(body["data"], handle, ensure_ascii=False, indent=2)
            entry["file"] = name
            entry["revision"] = body["data"]["revision"]
            entry["baseContentVersion"] = body["data"]["baseContentVersion"]
            entry["entries"] = len(body["data"]["draft"]["entries"])
            entry["entriesWithPreserved"] = sum(
                1 for item in body["data"]["draft"]["entries"] if item.get("preserved"))
        index.append(entry)
        print("%s status=%s entries=%s preserved=%s" % (edition_id, code, entry.get("entries"),
                                                        entry.get("entriesWithPreserved")))
    write_json("roundtrip-drafts-index%s.json" % tag, {"drafts": index, "callLog": admin.log})
    return index


def summarize_snapshot(snapshot):
    """压缩来源快照，只留可核对的骨架。"""
    baseline = snapshot.get("manifestBaseline") or {}
    sources = snapshot.get("sources") or []
    locators = snapshot.get("entryLocators") or []
    version = snapshot.get("version") or {}
    capture = snapshot.get("snapshot") or {}
    return {"version": {k: version.get(k) for k in ("editionId", "contentVersion", "versionId")},
            "contentSha256": (version.get("contentSha256") or "")[:16],
            "capture": {k: capture.get(k) for k in ("capturedAt", "capturedBy", "origin",
                                                    "operationId")},
            "manifestBaseline": {k: baseline.get(k) for k in
                                 ("present", "missingReason", "catalogVersion", "fileSha256",
                                  "byteLength")},
            "sourceCount": len(sources),
            "sourceIds": [(item.get("record") or {}).get("sourceId") if isinstance(item, dict)
                          else None for item in sources],
            "entryLocatorCount": len(locators),
            "entryLocatorWithSourceRefs": sum(
                1 for item in locators if (item.get("sourceRefs") or []))}


def phase_snapshots():
    """记录版本链与来源快照（D01：迁移冻结 manifest 基线，发布继承基线）。"""
    admin = Admin()
    admin.login()
    manifest_data = manifest()
    declared = {item["editionId"]: item for item in manifest_data["editions"]}
    out = {"versions": [], "sourceSnapshots": [], "reconciliation": None}
    for edition_id in TARGETS:
        code, body, _ = admin.call("GET", "/admin/v1/curriculum/versions/%s" % edition_id)
        out["versions"].append({"editionId": edition_id, "status": code, "data": body.get("data")})
        versions = [declared[edition_id]["contentVersion"]]
        for item in body.get("data") or []:
            version = item.get("contentVersion")
            if version and version not in versions:
                versions.append(version)
        for version in versions:
            code, body, _ = admin.call(
                "GET", "/admin/v1/curriculum/versions/%s/%s/source-snapshot" % (edition_id, version))
            entry = {"editionId": edition_id, "contentVersion": version, "status": code}
            if code == 200:
                raw = body["data"].get("snapshotJson") or body["data"].get("snapshot")
                if isinstance(raw, str):
                    raw = json.loads(raw)
                entry["summary"] = summarize_snapshot(raw or {})
                entry["snapshotSha256"] = body["data"].get("sha256")
            out["sourceSnapshots"].append(entry)
    code, body, _ = admin.call("GET", "/admin/v1/curriculum/reconciliation")
    out["reconciliation"] = {"status": code, "data": body.get("data")}
    write_json("roundtrip-snapshots.json", out)
    for item in out["sourceSnapshots"]:
        summary = item.get("summary") or {}
        print("%s@%s status=%s baseline=%s sources=%s locators=%s"
              % (item["editionId"], item["contentVersion"], item["status"],
                 json.dumps(summary.get("manifestBaseline"), ensure_ascii=False),
                 summary.get("sourceCount"), summary.get("entryLocatorCount")))
    recon = out["reconciliation"]["data"] or {}
    print("reconciliation identical=%s/%s switchSafe=%s"
          % (recon.get("identicalCount"), recon.get("totalCount"), recon.get("switchSafe")))
    return out


def phase_publish():
    admin = Admin()
    admin.login()
    state = read_json(STATE_FILE)["results"] if os.path.exists(os.path.join(OUT, STATE_FILE)) else []
    done = {(item["editionId"], item["tag"]) for item in state if item.get("publishStatus") == 200}
    records = []
    for edition_id in TARGETS:
        for index in range(1, ROUNDS[edition_id] + 1):
            tag = "round%d" % index
            if (edition_id, tag) in done:
                print("%s %s already published, skip" % (edition_id, tag))
                continue
            draft = load_draft(admin, edition_id)["draft"]
            changes = (apply_round1(edition_id, draft) if index == 1
                       else apply_round2_delete(edition_id, draft, ADD_ENTRY[edition_id]["rowId"]))
            if index == 1 and "\ufffd" in (draft.get("editionLabel") or ""):
                blocked = save_and_publish(admin, edition_id, draft, changes, "round1-as-is")
                records.append(blocked)
                print("%s round1-as-is publish=%s (expect blocked) body=%s"
                      % (edition_id, blocked.get("publishStatus"),
                         json.dumps(blocked.get("publishBody"), ensure_ascii=False)[:400]))
                draft = load_draft(admin, edition_id)["draft"]
                before = draft["editionLabel"]
                draft["editionLabel"] = EDITION_LABEL_FIX[edition_id]
                changes = {"editionLabelFix": {"before": before, "after": draft["editionLabel"]}}
            record = save_and_publish(admin, edition_id, draft, changes, tag)
            records.append(record)
            print("%s %s save=%s publishable=%s publish=%s version=%s"
                  % (edition_id, tag, record.get("saveStatus"),
                     (record.get("validateBody") or {}).get("data", {}).get("publishable"),
                     record.get("publishStatus"), record.get("newContentVersion")))
    append_state(records)
    return records


# ---------------------------------------------------------------- 对账

def diff_entries(original, new, subject):
    old_map = {entry["entryId"]: entry for entry in original}
    new_map = {entry["entryId"]: entry for entry in new}
    original_order = [entry["entryId"] for entry in original]
    new_order = [entry["entryId"] for entry in new]
    shared = [key for key in original_order if key in new_map]
    field_tally, samples, changed_entries = {}, {}, {}
    buckets = {}
    content_changed = []
    identical_all = identical_required = required_changed = 0
    identical_ignoring_systematic = 0
    required = list(REQUIRED_ENTRY_FIELDS) + (["pinyinDisplay"] if subject == "chinese"
                                              else ["meaning"])
    for key in shared:
        old_entry, new_entry = old_map[key], new_map[key]
        differences = {}
        categories = set()
        for field in ALL_ENTRY_FIELDS:
            if old_entry.get(field) == new_entry.get(field):
                continue
            differences[field] = {"original": old_entry.get(field), "new": new_entry.get(field)}
            field_tally[field] = field_tally.get(field, 0) + 1
            if len(samples.setdefault(field, [])) < 3:
                samples[field].append({"entryId": key, "original": old_entry.get(field),
                                       "new": new_entry.get(field)})
            if field in SYSTEMATIC_CATEGORY:
                categories.add(SYSTEMATIC_CATEGORY[field])
            elif field in NORMALIZED_FIELDS and old_entry.get(field) is None \
                    and new_entry.get(field) == "":
                categories.add("null-to-empty")
            elif field in KNOWN_TRANSFORM_CATEGORY:
                categories.add(KNOWN_TRANSFORM_CATEGORY[field])
            else:
                categories.add("content-change")
        if not differences:
            identical_all += 1
            identical_ignoring_systematic += 1
        else:
            changed_entries[key] = differences
            if "content-change" not in categories:
                identical_ignoring_systematic += 1
        if "content-change" in categories:
            content_changed.append(key)
        buckets["+".join(sorted(categories)) or "identical"] = \
            buckets.get("+".join(sorted(categories)) or "identical", 0) + 1
        if any(field in required for field in differences):
            required_changed += 1
        else:
            identical_required += 1
    return {
        "entriesOriginal": len(original), "entriesNew": len(new), "shared": len(shared),
        "identicalAllFields": identical_all, "changedEntries": len(changed_entries),
        "entriesWithNoUnexplainedContentChange": identical_ignoring_systematic,
        "contentLevelChanged": len(content_changed),
        "contentLevelChangedSample": content_changed[:10],
        "removed": [key for key in original_order if key not in new_map],
        "added": [key for key in new_order if key not in old_map],
        "requiredFieldSet": required,
        "identicalOnRequiredFields": identical_required,
        "requiredFieldsChanged": required_changed,
        "changedEntryBuckets": buckets,
        "fieldChangeTally": field_tally, "fieldChangeSamples": samples,
        "changedEntryDetail": changed_entries,
        "entryIdRelativeOrderPreserved":
            [key for key in new_order if key in old_map] == original_order,
    }


def diff_named_list(original, new, key):
    old_map = {item[key]: item for item in original}
    new_map = {item[key]: item for item in new}
    original_order = [item[key] for item in original]
    new_order = [item[key] for item in new]
    tally, detail = {}, {}
    for item_key in original_order:
        if item_key not in new_map:
            continue
        old_item, new_item = old_map[item_key], new_map[item_key]
        for field in set(list(old_item.keys()) + list(new_item.keys())):
            if old_item.get(field) != new_item.get(field):
                tally[field] = tally.get(field, 0) + 1
                detail.setdefault(item_key, {})[field] = {"original": old_item.get(field),
                                                          "new": new_item.get(field)}
    return {"countOriginal": len(original), "countNew": len(new),
            "removed": [k for k in original_order if k not in new_map],
            "added": [k for k in new_order if k not in old_map],
            "fieldChangeTally": tally, "detail": detail,
            "orderPreserved": [k for k in new_order if k in old_map] == original_order}


def diff_edition(original, new):
    diffs = {}
    for field in sorted(set(list(original.keys()) + list(new.keys()))):
        if original.get(field) != new.get(field):
            diffs[field] = {"original": original.get(field), "new": new.get(field),
                            "classification": ("allowed-derived" if field in EDITION_DERIVED_FIELDS
                                               else "observed-not-derived")}
    return diffs


def reverse_preserved_check(original, new):
    new_map = {entry["entryId"]: entry for entry in new}
    out = {}
    for field in PRESERVED_ENTRY_FIELDS:
        non_null = surviving = changed = lost = 0
        samples = []
        for entry in original:
            value = entry.get(field)
            if value is None or value == []:
                continue
            non_null += 1
            target = new_map.get(entry["entryId"])
            if target is None:
                lost += 1
            elif target.get(field) == value:
                surviving += 1
            else:
                changed += 1
                if len(samples) < 3:
                    samples.append({"entryId": entry["entryId"], "original": value,
                                    "new": target.get(field)})
        out[field] = {"originalNonNull": non_null, "survivedIdentical": surviving,
                      "changed": changed, "entryMissingInNew": lost, "samples": samples}
    return out


def compare_one(edition_id, original_version, new_version, note):
    with open(book_path(edition_id, original_version), "rb") as handle:
        original_bytes = handle.read()
    with open(os.path.join(OUT, "roundtrip-book-%s-%s.json" % (edition_id, new_version)),
              "rb") as handle:
        new_bytes = handle.read()
    original, new = json.loads(original_bytes), json.loads(new_bytes)
    return {"editionId": edition_id, "originalVersion": original_version,
            "newVersion": new_version, "note": note,
            "subject": original["edition"].get("subject"),
            "originalSha256": sha256_bytes(original_bytes),
            "newSha256": sha256_bytes(new_bytes),
            "originalBytes": len(original_bytes), "newBytes": len(new_bytes),
            "topLevelKeysEqual": sorted(original.keys()) == sorted(new.keys()),
            "editionFieldDiffs": diff_edition(original["edition"], new["edition"]),
            "units": diff_named_list(original["units"], new["units"], "unitId"),
            "lessons": diff_named_list(original["lessons"], new["lessons"], "lessonId"),
            "entries": diff_entries(original["entries"], new["entries"],
                                    original["edition"].get("subject")),
            "preservedReverseCheck": reverse_preserved_check(original["entries"],
                                                             new["entries"])}


def phase_compare():
    manifest_data = manifest()
    declared = {item["editionId"]: item for item in manifest_data["editions"]}
    published = read_json(STATE_FILE)["results"]
    comparisons = []
    for item in published:
        if item.get("publishStatus") != 200:
            continue
        edition_id = item["editionId"]
        comparisons.append(compare_one(edition_id, declared[edition_id]["contentVersion"],
                                       item["newContentVersion"], "tag=%s" % item["tag"]))
    summary = {"manifestCatalogVersion": manifest_data.get("catalogVersion"),
               "publishRounds": [{k: item.get(k) for k in
                                  ("editionId", "tag", "publishStatus", "newContentVersion",
                                   "newContentSha256", "snapshotReloaded")}
                                 for item in published],
               "comparisons": comparisons}
    write_json("roundtrip-comparison.json", summary)
    for item in comparisons:
        entries = item["entries"]
        print("%s %s->%s [%s]  %d->%d entries | shared=%d identicalRequired=%d "
              "requiredChanged=%d contentLevelChanged=%d noUnexplainedChange=%d added=%d removed=%d"
              % (item["editionId"], item["originalVersion"], item["newVersion"], item["note"],
                 entries["entriesOriginal"], entries["entriesNew"], entries["shared"],
                 entries["identicalOnRequiredFields"], entries["requiredFieldsChanged"],
                 entries["contentLevelChanged"],
                 entries["entriesWithNoUnexplainedContentChange"],
                 len(entries["added"]), len(entries["removed"])))
        print("   buckets: %s" % json.dumps(entries["changedEntryBuckets"], ensure_ascii=False))
    return summary


def main():
    phase = sys.argv[1] if len(sys.argv) > 1 else "all"
    if phase == "env":
        phase_env()
    elif phase == "import":
        phase_import()
    elif phase == "publish":
        phase_publish()
    elif phase == "compare":
        phase_compare()
    elif phase == "repro-blocked":
        phase_repro_blocked()
    elif phase == "dump-drafts":
        phase_dump_drafts()
    elif phase == "snapshots":
        phase_snapshots()
    elif phase == "all":
        phase_env()
        phase_import()
        phase_publish()
        phase_repro_blocked()
        phase_snapshots()
        phase_compare()
    else:
        raise SystemExit("unknown phase: %s" % phase)


if __name__ == "__main__":
    main()
