#!/usr/bin/env bash
# 任务 3-4（上半）：在真实 MySQL 上造出带**条目级 sourceRefs** 的教材，发布两个版本，
# 然后导出完整包，并**独立重算**包级摘要与逐条字节哈希（不采信接口自报的值）。
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:23211}"
DIR=/tmp/toolbox-remaining/task3
OUT="$DIR/out"
DB=verify-source
EDITION="restore-src-a"
MYSQL="docker exec -i toolbox-mysql-test mysql -uroot -ptoolbox-test-only -N -e"

say() { printf '\n===== %s =====\n' "$1"; }
call() { curl -s -o "$2" -w '%{http_code}' "$1" "${@:3}"; }

bash "$DIR/login.sh" "$BASE" "$OUT/inst-a" >/dev/null
COOKIES="$OUT/inst-a/cookies.txt"
CSRF=$(cat "$OUT/inst-a/csrf.txt")

say "0. 建一册带**手工来源**与**条目级 sourceRefs** 的草稿"
cat > "$OUT/item4-create.json" <<JSON
{"editionId":"$EDITION","subject":"chinese","grade":2,"semester":"second",
 "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
 "sourceNote":"恢复对账用，第 12 页","coverageDeclaration":"partial",
 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1}],
 "lessons":[],
 "entries":[{"rowId":"row-1","entryId":null,"unitId":null,"clientUnitId":"cu1",
             "lessonId":null,"clientLessonId":null,"text":"春","pinyin":"chūn",
             "meaning":null,"listTypes":["reading"],"confirmed":true,
             "sourceRefs":[{"sourceId":"manual-restore-1","locator":"第 12 页","page":12}]}],
 "sources":[{"sourceId":"manual-restore-1","kind":"manual","title":"恢复对账手册",
             "url":null,"publisher":"人民教育出版社","isbn":"978-7-107-00000-0",
             "editionLabel":"2024 年修订版","locator":"第 12 页","page":12,"note":"仅用于恢复对账"}]}
JSON
echo "POST /admin/v1/textbooks：HTTP $(call "$BASE/admin/v1/textbooks" "$OUT/item4-create-resp.json" \
  -b "$COOKIES" -X POST -H "X-Toolbox-Admin-Csrf: $CSRF" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: item4-create-$(uuidgen)" --data-binary @"$OUT/item4-create.json")"
jq -c '{code, revision: .data.revision}' "$OUT/item4-create-resp.json"

say "1. 发布 1.0.0"
echo "HTTP $(call "$BASE/admin/v1/textbooks/$EDITION/publish" "$OUT/item4-publish1.json" \
  -b "$COOKIES" -X POST -H "X-Toolbox-Admin-Csrf: $CSRF" -H "Idempotency-Key: item4-pub1-$(uuidgen)")"
jq -c '{code, data}' "$OUT/item4-publish1.json"

say "2. 追加第二条（带自己的 sourceRefs）并发布 1.0.1（让该册有两个历史版本）"
REV=$(curl -s -b "$COOKIES" -H "X-Toolbox-Admin-Csrf: $CSRF" \
  "$BASE/admin/v1/textbooks/$EDITION/draft" | jq -r '.data.revision')
jq '.entries += [{"rowId":"row-2","entryId":null,"unitId":null,"clientUnitId":"cu1",
     "lessonId":null,"clientLessonId":null,"text":"夏","pinyin":"xià","meaning":null,
     "listTypes":["reading"],"confirmed":true,
     "sourceRefs":[{"sourceId":"manual-restore-1","locator":"第 13 页","page":13}]}]' \
  "$OUT/item4-create.json" > "$OUT/item4-save.json"
echo "PUT draft：HTTP $(call "$BASE/admin/v1/textbooks/$EDITION/draft" "$OUT/item4-save-resp.json" \
  -b "$COOKIES" -X PUT -H "X-Toolbox-Admin-Csrf: $CSRF" -H 'Content-Type: application/json' \
  -H "If-Match: \"draft-$REV\"" -H "Idempotency-Key: item4-save-$(uuidgen)" \
  --data-binary @"$OUT/item4-save.json")"
jq -c '{code, revision: .data.revision}' "$OUT/item4-save-resp.json"
REV2=$(jq -r '.data.revision' "$OUT/item4-save-resp.json")
echo "HTTP $(call "$BASE/admin/v1/textbooks/$EDITION/publish?draftRevision=$REV2" "$OUT/item4-publish2.json" \
  -b "$COOKIES" -X POST -H "X-Toolbox-Admin-Csrf: $CSRF" -H "Idempotency-Key: item4-pub2-$(uuidgen)")"
jq -c '{code, data}' "$OUT/item4-publish2.json"

say "3. 导出完整包 GET /admin/v1/curriculum/export-package"
curl -s -b "$COOKIES" "$BASE/admin/v1/curriculum/export-package" > "$OUT/item4-package.json"
jq -c '{code, format: .data.format, versionCount: .data.versionCount, packageSha256: .data.packageSha256}' \
  "$OUT/item4-package.json"

say "4. 独立重算包级摘要与逐条哈希（只看包内字节，不采信接口自报）"
python3 - "$OUT/item4-package.json" <<'PYEOF'
import base64, hashlib, json, sys
pkg = json.load(open(sys.argv[1], encoding='utf-8'))['data']
lines = []
ok = True
for v in pkg['versions']:
    raw = base64.b64decode(v['contentBase64'])
    sha = hashlib.sha256(raw).hexdigest()
    lines.append(f"{v['editionId']}@{v['contentVersion']}:{v['contentSha256']}")
    if sha != v['contentSha256']:
        ok = False; print(f"MISMATCH contentSha256 {v['editionId']}@{v['contentVersion']}")
    if len(raw) != v['byteLength']:
        ok = False; print(f"MISMATCH byteLength {v['editionId']}@{v['contentVersion']}")
    snap = v['sourceMetadataJson']
    if snap is not None:
        if hashlib.sha256(snap.encode('utf-8')).hexdigest() != v['sourceSnapshotSha256']:
            ok = False; print(f"MISMATCH sourceSnapshotSha256 {v['editionId']}@{v['contentVersion']}")
    print(f"OK {v['editionId']}@{v['contentVersion']} bytes={len(raw)} sha={sha[:16]}")
lines.sort()
recomputed = hashlib.sha256("\n".join(lines).encode('utf-8')).hexdigest()
print(f"独立重算 packageSha256={recomputed}")
print(f"接口自报 packageSha256 ={pkg['packageSha256']}")
print(f"包级摘要一致：{recomputed == pkg['packageSha256']}")
if not ok or recomputed != pkg['packageSha256']:
    raise SystemExit(1)
PYEOF

say "5. 导出来源快照里的 sourceRefs（恢复对账的期望值）"
for i in $(seq 0 $(( $(jq '.data.versions | length' "$OUT/item4-package.json") - 1 ))); do
  jq -r ".data.versions[$i].sourceMetadataJson" "$OUT/item4-package.json" | jq -c '{
    locators: (.entryLocators | length),
    sourceRefs: ([.entryLocators[].sourceRefs[]?] | length),
    manualSources: (.sources | length),
    refsDetail: [.entryLocators[] | select((.sourceRefs|length) > 0) | {entryId, refs: .sourceRefs}]}'
done
