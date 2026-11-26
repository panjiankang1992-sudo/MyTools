#!/usr/bin/env bash
# 任务 3-2：发布失败时事务**不留痕迹**（真实 MySQL）。
#
# 触发方式：用 31 字符的 editionId 发布两次。第一次成功（基线写回
# `editionId@1.0.0`，37 字符）；第二次发布时 `toolbox_curriculum_audit.from_version`
# 是 VARCHAR(32)，写不下 37 字符，MySQL 严格模式报错，异常发生在
# **版本行已插入、current 指针已移动、目录世代已递增之后**，因此正好检验回滚。
#
# 断言（失败后必须与失败前逐项一致）：
#   version 行数 / current_version_id / 草稿 base_content_version / 草稿 revision /
#   目录 epoch 与 catalog_version / 审计行数 / 幂等回执行数。
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:23211}"
DIR=/tmp/toolbox-remaining/task3
OUT="$DIR/out"
LOG="$OUT/instance-a.log"
DB="${DB:-toolbox_verify_a}"
MYSQL="docker exec -i toolbox-mysql-test mysql -uroot -ptoolbox-test-only -N -e"
# 31 字符（前缀 27 + 4 位随机）：第一次发布后基线为 31+1+5=37 字符，
# 超过审计表 from_version 的 VARCHAR(32)。带随机后缀让脚本可重复执行。
EDITION="verify-rollback-2026-10-07-$(uuidgen | tr -d '-' | head -c 4 | tr 'A-Z' 'a-z')"

say() { printf '\n===== %s =====\n' "$1"; }
call() { curl -s -o "$2" -w '%{http_code}' "$1" "${@:3}"; }
# 打印某册的"痕迹"：版本行、指针、草稿、目录世代、审计与回执计数。
snapshot_state() {
  $MYSQL "SELECT CONCAT('editionId长度=', CHAR_LENGTH('$EDITION'));" 
  echo "-- version 行"
  $MYSQL "SELECT version_id, content_version FROM $DB.toolbox_textbook_version
          WHERE edition_id='$EDITION' ORDER BY content_version;"
  echo "-- textbook 指针"
  $MYSQL "SELECT edition_id, current_version_id FROM $DB.toolbox_textbook WHERE edition_id='$EDITION';"
  echo "-- 草稿修订与基线"
  $MYSQL "SELECT draft_id, revision, base_content_version FROM $DB.toolbox_textbook_draft
          WHERE edition_id='$EDITION';"
  echo "-- 目录世代"
  $MYSQL "SELECT epoch, catalog_version FROM $DB.toolbox_curriculum_catalog WHERE catalog_key='current';"
  echo "-- 该册审计行数与版本数"
  $MYSQL "SELECT (SELECT COUNT(*) FROM $DB.toolbox_curriculum_audit WHERE edition_id='$EDITION') AS audit_rows,
                 (SELECT COUNT(*) FROM $DB.toolbox_textbook_version WHERE edition_id='$EDITION') AS version_rows;"
  echo "-- 幂等回执行数（该册发布作用域）"
  $MYSQL "SELECT COUNT(*) FROM $DB.toolbox_admin_operation
          WHERE operation_scope='textbook.publish:$EDITION';"
}

bash "$DIR/login.sh" "$BASE" "$OUT/inst-a" >/dev/null
COOKIES="$OUT/inst-a/cookies.txt"
CSRF=$(cat "$OUT/inst-a/csrf.txt")

say "0. 建一册草稿（editionId 31 字符）"
cat > "$OUT/item2-create.json" <<JSON
{"editionId":"$EDITION","subject":"chinese","grade":1,"semester":"first",
 "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
 "sourceNote":"回滚验证用，第 1 页","coverageDeclaration":"partial",
 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1}],
 "lessons":[],
 "entries":[{"rowId":"row-1","entryId":null,"unitId":null,"clientUnitId":"cu1",
             "lessonId":null,"clientLessonId":null,"text":"山","pinyin":"shān",
             "meaning":null,"listTypes":["writing"],"confirmed":true}]}
JSON
echo "POST /admin/v1/textbooks：HTTP $(call "$BASE/admin/v1/textbooks" "$OUT/item2-create-resp.json" \
  -b "$COOKIES" -X POST -H "X-Toolbox-Admin-Csrf: $CSRF" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: item2-create-$(uuidgen)" --data-binary @"$OUT/item2-create.json")"
jq -c '{code, data}' "$OUT/item2-create-resp.json"

say "1. 第一次发布（应当成功，建立 current 指针）"
echo "HTTP $(call "$BASE/admin/v1/textbooks/$EDITION/publish" "$OUT/item2-publish1.json" \
  -b "$COOKIES" -X POST -H "X-Toolbox-Admin-Csrf: $CSRF" -H "Idempotency-Key: item2-publish1-$(uuidgen)")"
jq -c '{code, data}' "$OUT/item2-publish1.json"

say "2. 第一次发布后的状态（S1）"
snapshot_state | tee "$OUT/item2-state-before.txt"

say "3. 第二次发布（预期失败：审计表 from_version 放不下 37 字符）"
LOG_LINES_BEFORE=$(wc -l < "$LOG" | tr -d ' ')
STATUS=$(call "$BASE/admin/v1/textbooks/$EDITION/publish" "$OUT/item2-publish2.json" \
  -b "$COOKIES" -X POST -H "X-Toolbox-Admin-Csrf: $CSRF" -H "Idempotency-Key: item2-publish2-$(uuidgen)")
echo "HTTP $STATUS"
jq -c '{code, messageKey, details}' "$OUT/item2-publish2.json"
echo "--- 本次失败在应用日志里的真实栈（节选） ---"
tail -n +$((LOG_LINES_BEFORE + 1)) "$LOG" \
  | grep -E "ERROR|Data too long|from_version|AdminTextbookRepository.recordAudit|CurriculumPublisher|AdminTextbookService|rollback|Exception" \
  | head -20
echo "--- 事务内已推进到 catalog-3 的证据（回滚前快照重载日志） ---"
tail -n +$((LOG_LINES_BEFORE + 1)) "$LOG" | grep -o "catalogVersion=catalog-[0-9]*, editions=[0-9]*, entries=[0-9]*" | head -3

say "4. 失败后的状态（S2）——必须与 S1 逐项一致"
snapshot_state | tee "$OUT/item2-state-after.txt"

say "5. 逐项比对（**不用 diff**：本机 PATH 上的 diff 是 DevEco 工具链的精简实现，"
echo "   它不支持 -u/-q，且报错时退出码仍为 0——会把\"没比较\"误判成\"一致\"。这里用 python 逐行比对）"
set +e
bash "$DIR/31-item2-compare-states.sh" "$OUT/item2-state-before.txt" "$OUT/item2-state-after.txt" \
  > "$OUT/item2-state-compare.txt" 2>&1
COMPARE_STATUS=$?
set -e
cat "$OUT/item2-state-compare.txt"
if [ "$COMPARE_STATUS" -eq 0 ]; then
  echo "状态完全一致：发布失败没有留下任何痕迹（version 行 / current 指针 / 草稿基线 / 草稿修订 / 世代 / 审计 / 回执）"
else
  echo "状态**发生了变化**（见上）"
  exit 1
fi

say "6. 【附加发现】失败发布在**内存目录快照**里留下的痕迹（数据库已回滚，但快照没有）"
echo "--- 库里到底有没有 1.0.1 ---"
$MYSQL "SELECT COUNT(*) AS rows_with_1_0_1 FROM $DB.toolbox_textbook_version
        WHERE edition_id='$EDITION' AND content_version='1.0.1';"
echo "--- 公开接口读被回滚的 1.0.1 ---"
curl -s -o "$OUT/item2-public-1.0.1.json" \
  -w 'GET /public/v1/textbooks/<edition>/versions/1.0.1 -> HTTP %{http_code}\n' \
  "$BASE/public/v1/textbooks/$EDITION/versions/1.0.1"
echo "--- 公开目录此刻声明的目录版本号 vs 库中列 ---"
curl -s "$BASE/public/v1/textbooks?subject=chinese&grade=1&semester=first" \
  | jq -c --arg e "$EDITION" '{servedCatalogVersion: .data.catalogVersion,
                               servedEditionVersion: ([.data.editions[] | select(.editionId==$e) | .contentVersion][0])}'
$MYSQL "SELECT CONCAT('db: epoch=', epoch, ' catalog_version=', catalog_version)
        FROM $DB.toolbox_curriculum_catalog WHERE catalog_key='current';"
echo "说明：上面若出现 1.0.1 / catalog-4 这类**只存在于内存快照**的值，"
echo "     即为新发现的缺陷（发布失败后目录快照未回退）。详见验证文档「任务 3-2 附加发现」。"
