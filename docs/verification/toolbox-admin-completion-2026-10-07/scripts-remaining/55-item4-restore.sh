#!/usr/bin/env bash
# 任务 3-4（下半）：把导出包恢复进**真实 MySQL 的空库**，并逐项对账。
#
# 对账口径（期望值全部由**包内字节**独立算出，不采信接口报告）：
#   原 bytes 长度、原 bytes SHA-256、来源快照 SHA-256、声明元数据 SHA-256、
#   entryLocators 数、sourceRefs 条数、手工来源数、current 指针、每册历史版本数。
set -euo pipefail

BASE_B="${BASE_B:-http://127.0.0.1:23212}"
DIR=/tmp/toolbox-remaining/task3
OUT="$DIR/out"
PKG="$OUT/item4-package.json"
# 恢复接口的请求体是**导出包本身**（`data` 里的对象），不是 ApiResponse 信封：
# 本服务 `fail-on-unknown-properties=true`，把信封整个发过去会被 422 拒绝。
PKG_BODY="$OUT/item4-package-body.json"
jq '.data' "$PKG" > "$PKG_BODY"
SQL_B="docker exec -i toolbox-mysql-test mysql -uroot -ptoolbox-test-only --default-character-set=utf8mb4 -D toolbox_verify_b"

say() { printf '\n===== %s =====\n' "$1"; }
# 在目标库执行只读查询（-N 无表头，-B 制表符分隔）
query_b() { $SQL_B -N -B -e "$1" 2>/dev/null; }

say "0. 恢复前：目标库（toolbox_verify_b）必须为空"
query_b "SELECT COUNT(*) AS versions FROM toolbox_textbook_version;
         SELECT COUNT(*) AS textbooks FROM toolbox_textbook;
         SELECT catalog_key, epoch, catalog_version FROM toolbox_curriculum_catalog;"

say "1. 登录实例 B 并 POST /admin/v1/curriculum/restore-package"
bash "$DIR/login.sh" "$BASE_B" "$OUT/inst-b" >/dev/null
COOKIES_B="$OUT/inst-b/cookies.txt"
CSRF_B=$(cat "$OUT/inst-b/csrf.txt")
STATUS=$(curl -s -o "$OUT/item4-restore-report.json" -w '%{http_code}' \
  "$BASE_B/admin/v1/curriculum/restore-package" -b "$COOKIES_B" -X POST \
  -H "X-Toolbox-Admin-Csrf: $CSRF_B" -H 'Content-Type: application/json' \
  --data-binary @"$PKG_BODY")
echo "HTTP $STATUS"
jq -c '{code, restoredEditions: .data.restoredEditions, restoredVersions: .data.restoredVersions,
        skippedVersions: .data.skippedVersions, conflicts: .data.conflicts, ok: .data.ok}' \
  "$OUT/item4-restore-report.json"
echo "--- 服务端逐册对账报告 ---"
jq -c '.data.reconciliations[]' "$OUT/item4-restore-report.json"

say "2. 由包内字节独立算出期望值（python）"
python3 - "$PKG" > "$OUT/item4-expected.tsv" <<'PYEOF'
import base64, hashlib, json, sys
pkg = json.load(open(sys.argv[1], encoding='utf-8'))['data']
rows = []
for v in pkg['versions']:
    raw = base64.b64decode(v['contentBase64'])
    snap = v['sourceMetadataJson']
    snap_obj = json.loads(snap) if snap else None
    locators = len(snap_obj.get('entryLocators', [])) if snap_obj else 0
    refs = sum(len(item.get('sourceRefs') or []) for item in (snap_obj.get('entryLocators', []) if snap_obj else []))
    sources = len(snap_obj.get('sources', [])) if snap_obj else 0
    meta = v['editionMetadataJson'] or ''
    rows.append('\t'.join([
        f"{v['editionId']}@{v['contentVersion']}",
        str(len(raw)),
        hashlib.sha256(raw).hexdigest(),
        hashlib.sha256(snap.encode('utf-8')).hexdigest() if snap else 'NULL',
        hashlib.sha256(meta.encode('utf-8')).hexdigest() if meta else 'NULL',
        str(locators), str(refs), str(sources),
        '1' if v['current'] else '0',
    ]))
print('\n'.join(rows))
PYEOF
echo "期望值（version / bytes / sha256 / 快照sha / 元数据sha / locators / refs / sources / current）："
cat "$OUT/item4-expected.tsv"

say "3. 从目标库读实际值（SQL）"
cat > "$OUT/item4-actual.sql" <<'SQL'
SELECT CONCAT(v.edition_id, '@', v.content_version) AS version_id,
       LENGTH(v.content_bytes) AS byte_length,
       SHA2(v.content_bytes, 256) AS content_sha256,
       COALESCE(SHA2(v.source_metadata_json, 256), 'NULL') AS snapshot_sha256,
       COALESCE(SHA2(v.edition_metadata_json, 256), 'NULL') AS metadata_sha256,
       COALESCE(JSON_LENGTH(v.source_metadata_json, '$.entryLocators'), 0) AS locators,
       COALESCE((SELECT COUNT(*) FROM JSON_TABLE(v.source_metadata_json,
                 '$.entryLocators[*].sourceRefs[*]' COLUMNS (sid VARCHAR(128) PATH '$')) AS r), 0) AS source_refs,
       COALESCE(JSON_LENGTH(v.source_metadata_json, '$.sources'), 0) AS manual_sources,
       CASE WHEN t.current_version_id = v.version_id THEN '1' ELSE '0' END AS is_current
FROM toolbox_textbook_version v
JOIN toolbox_textbook t ON t.edition_id = v.edition_id
ORDER BY v.edition_id, v.content_version;
SQL
$SQL_B -N -B < "$OUT/item4-actual.sql" 2>/dev/null > "$OUT/item4-actual.tsv"
echo "实际值："
cat "$OUT/item4-actual.tsv"

say "4. 逐项对账（期望 vs 实际）"
python3 - "$OUT/item4-expected.tsv" "$OUT/item4-actual.tsv" <<'PYEOF'
import sys
FIELDS = ['bytes', 'content_sha256', 'snapshot_sha256', 'metadata_sha256',
          'locators', 'sourceRefs', 'manualSources', 'current']
def load(path):
    rows = {}
    for line in open(path, encoding='utf-8'):
        line = line.rstrip('\n')
        if not line:
            continue
        parts = line.split('\t')
        rows[parts[0]] = parts[1:]
    return rows
expected, actual = load(sys.argv[1]), load(sys.argv[2])
ok = True
if set(expected) != set(actual):
    ok = False
    print(f"版本集合不一致：期望 {sorted(expected)} / 实际 {sorted(actual)}")
for version in sorted(expected):
    exp, act = expected[version], actual.get(version)
    if act is None:
        ok = False; print(f"MISSING  {version}"); continue
    for name, e, a in zip(FIELDS, exp, act):
        mark = 'OK  ' if e == a else 'FAIL'
        if e != a:
            ok = False
        shown_e = e if len(e) <= 24 else e[:24] + '…'
        shown_a = a if len(a) <= 24 else a[:24] + '…'
        print(f"{mark} {version:34s} {name:16s} 期望={shown_e:26s} 实际={shown_a}")
print(f"\n逐项对账结论：{'全部一致' if ok else '存在不一致'}")
if not ok:
    raise SystemExit(1)
PYEOF

say "5. 每册历史版本数与 current 指针"
query_b "SELECT v.edition_id, COUNT(*) AS history_rows, MAX(t.current_version_id) AS current_version_id
         FROM toolbox_textbook_version v JOIN toolbox_textbook t ON t.edition_id = v.edition_id
         GROUP BY v.edition_id ORDER BY v.edition_id;"
echo "--- 包中声明的每册版本数与 current ---"
python3 - "$PKG" <<'PYEOF'
import json, sys
pkg = json.load(open(sys.argv[1], encoding='utf-8'))['data']
per = {}
for v in pkg['versions']:
    item = per.setdefault(v['editionId'], {'count': 0, 'current': None})
    item['count'] += 1
    if v['current']:
        item['current'] = f"{v['editionId']}@{v['contentVersion']}"
for edition in sorted(per):
    print(f"{edition}\t{per[edition]['count']}\t{per[edition]['current']}")
PYEOF

say "6. 恢复后的实例 B 能对外服务（公开接口读回）"
for edition in lab-a restore-src-a; do
  VERSION=$(python3 - "$PKG" "$edition" <<'PYEOF'
import json, sys
pkg = json.load(open(sys.argv[1], encoding='utf-8'))['data']
print(next(v['contentVersion'] for v in pkg['versions'] if v['editionId'] == sys.argv[2] and v['current']))
PYEOF
)
  curl -s -o "$OUT/item4-public-$edition.json" -w "GET /public/v1/textbooks/$edition/versions/$VERSION -> HTTP %{http_code}\n" \
    "$BASE_B/public/v1/textbooks/$edition/versions/$VERSION"
  jq -c --arg e "$edition" '{code, editionId: .data.edition.editionId, contentVersion: .data.edition.contentVersion,
                             entryCount: .data.entryCount}' "$OUT/item4-public-$edition.json"
done
echo "--- 恢复后实例 B 的公开目录 ---"
curl -s "$BASE_B/public/v1/textbooks" | jq -c '{catalogVersion: .data.catalogVersion, editionCount: (.data.editions|length)}'

say "7. 幂等复核：同一包再恢复一次，必须全部跳过且无冲突"
STATUS=$(curl -s -o "$OUT/item4-restore-again.json" -w '%{http_code}' \
  "$BASE_B/admin/v1/curriculum/restore-package" -b "$COOKIES_B" -X POST \
  -H "X-Toolbox-Admin-Csrf: $CSRF_B" -H 'Content-Type: application/json' --data-binary @"$PKG_BODY")
echo "HTTP $STATUS"
jq -c '{code, restoredVersions: .data.restoredVersions, skippedVersions: .data.skippedVersions,
        conflicts: .data.conflicts, ok: .data.ok}' "$OUT/item4-restore-again.json"
query_b "SELECT COUNT(*) AS versions_after_second_restore FROM toolbox_textbook_version;"
