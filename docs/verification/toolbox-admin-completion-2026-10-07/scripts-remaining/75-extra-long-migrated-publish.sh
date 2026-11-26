#!/usr/bin/env bash
# 附加探针（下半）：迁移长标识教材后，管理员补全来源说明再发布 —— 仍然失败在审计列。
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:23213}"
DIR=/tmp/toolbox-remaining/task3
OUT="$DIR/out"
LOG="$OUT/instance-c.log"
DB=toolbox_verify_c
EDITION="verify-migrated-2026-10-07-long"
MYSQL="docker exec -i toolbox-mysql-test mysql -uroot -ptoolbox-test-only -N -e"

say() { printf '\n===== %s =====\n' "$1"; }
call() { curl -s -o "$2" -w '%{http_code}' "$1" "${@:3}"; }

bash "$DIR/login.sh" "$BASE" "$OUT/inst-c" >/dev/null
COOKIES="$OUT/inst-c/cookies.txt"
CSRF=$(cat "$OUT/inst-c/csrf.txt")

say "1. 迁移（清单 catalogVersion=catalog-2026-10-05.9，editionId 31 字符）"
echo "HTTP $(call "$BASE/admin/v1/curriculum/import-from-files" "$OUT/extra-import.json" \
  -b "$COOKIES" -X POST -H "X-Toolbox-Admin-Csrf: $CSRF")"
jq -c '.data' "$OUT/extra-import.json"
$MYSQL "SELECT catalog_key, epoch, catalog_version FROM $DB.toolbox_curriculum_catalog;
        SELECT version_id, base_content_version FROM $DB.toolbox_textbook_version
          JOIN $DB.toolbox_textbook_draft ON 1=1 LIMIT 1;"

say "2. 直接发布（迁移草稿缺少来源说明，预期 422 —— 这一步不是本探针要证明的）"
echo "HTTP $(call "$BASE/admin/v1/textbooks/$EDITION/publish" "$OUT/extra-publish-without-note.json" \
  -b "$COOKIES" -X POST -H "X-Toolbox-Admin-Csrf: $CSRF" -H "Idempotency-Key: extra-pub0-$(uuidgen)")"
jq -c '{code, details}' "$OUT/extra-publish-without-note.json"

say "3. 补全来源说明后发布（本探针的关键一步）"
REV=$(curl -s -b "$COOKIES" -H "X-Toolbox-Admin-Csrf: $CSRF" \
  "$BASE/admin/v1/textbooks/$EDITION/draft" | jq -r '.data.revision')
curl -s -b "$COOKIES" -H "X-Toolbox-Admin-Csrf: $CSRF" \
  "$BASE/admin/v1/textbooks/$EDITION/draft" | jq '.data.draft' > "$OUT/extra-draft.json"
jq '.sourceNote = "迁移自文件仓储，第 1 页"' "$OUT/extra-draft.json" > "$OUT/extra-draft-save.json"
echo "PUT draft：HTTP $(call "$BASE/admin/v1/textbooks/$EDITION/draft" "$OUT/extra-save.json" \
  -b "$COOKIES" -X PUT -H "X-Toolbox-Admin-Csrf: $CSRF" -H 'Content-Type: application/json' \
  -H "If-Match: \"draft-$REV\"" -H "Idempotency-Key: extra-save-$(uuidgen)" \
  --data-binary @"$OUT/extra-draft-save.json")"
jq -c '{code, revision: .data.revision}' "$OUT/extra-save.json"
REV2=$(jq -r '.data.revision' "$OUT/extra-save.json")
echo "校验报告（应 publishable=true）："
curl -s -b "$COOKIES" -X POST -H "X-Toolbox-Admin-Csrf: $CSRF" \
  "$BASE/admin/v1/textbooks/$EDITION/validate?draftRevision=$REV2" | jq -c '.data | {draftRevision, publishable, issues}'

LOG_LINES_BEFORE=$(wc -l < "$LOG" | tr -d ' ')
STATUS=$(call "$BASE/admin/v1/textbooks/$EDITION/publish?draftRevision=$REV2" "$OUT/extra-publish.json" \
  -b "$COOKIES" -X POST -H "X-Toolbox-Admin-Csrf: $CSRF" -H "Idempotency-Key: extra-pub1-$(uuidgen)")
echo "发布 HTTP $STATUS"
jq -c '{code, messageKey, details}' "$OUT/extra-publish.json"
echo "--- 应用日志里的真实栈 ---"
tail -n +$((LOG_LINES_BEFORE + 1)) "$LOG" | grep -E "ERROR|Data too long|recordAudit|publishLocked" | head -6

say "4. 回滚后又留下的痕迹（数据库）"
$MYSQL "SELECT COUNT(*) AS version_rows FROM $DB.toolbox_textbook_version;
        SELECT edition_id, current_version_id FROM $DB.toolbox_textbook;
        SELECT catalog_key, epoch, catalog_version FROM $DB.toolbox_curriculum_catalog;"
