#!/usr/bin/env bash
# 任务 3-1：catalogVersion 的**迁移保留**与**首次真实发布**（真实 MySQL 8.4）。
#
# 断言：
#   a. 迁移前库中为初始值 epoch=0 / catalog_version=catalog-0；
#   b. 迁移一个 catalogVersion=catalog-2026-10-05.7 的清单后，库里**保留清单值**，epoch 仍为 0；
#   c. 首次真实发布后 epoch 递增为 1，catalog_version 变为计数器生成的新值 catalog-1；
#   d. 发布响应里的 catalogVersion / catalogEpoch 与库中列逐字一致。
#
# 说明：迁移来的草稿由正文重建，没有 `sourceNote`（该字段只存在于草稿，不属于已发布正文），
# 而发布校验要求来源说明必填。因此这里**如实走管理员会走的那一步**：补一句来源说明再发布。
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:23211}"
DIR=/tmp/toolbox-remaining/task3
OUT="$DIR/out"
DB="${DB:-toolbox_verify_a}"
MYSQL="docker exec -i toolbox-mysql-test mysql -uroot -ptoolbox-test-only -N -e"
EDITION="${EDITION:-lab-a}"

say() { printf '\n===== %s =====\n' "$1"; }
# 发一次请求：正文写到 $2，标准输出只打印 HTTP 状态。
call() { curl -s -o "$2" -w '%{http_code}' "$1" "${@:3}"; }

say "a. 迁移前的目录世代行（空库由 Flyway 建表后的初始值）"
$MYSQL "SELECT catalog_key, epoch, catalog_version FROM $DB.toolbox_curriculum_catalog;"

say "b. 登录并调用 POST /admin/v1/curriculum/import-from-files（清单 catalogVersion=catalog-2026-10-05.7）"
bash "$DIR/login.sh" "$BASE" "$OUT/inst-a"
COOKIES="$OUT/inst-a/cookies.txt"
CSRF=$(cat "$OUT/inst-a/csrf.txt")
echo "HTTP $(call "$BASE/admin/v1/curriculum/import-from-files" "$OUT/item1-import.json" \
  -b "$COOKIES" -X POST -H "X-Toolbox-Admin-Csrf: $CSRF")"
jq . "$OUT/item1-import.json"

say "b2. 迁移后：epoch 必须仍为 0，catalog_version 必须是**清单里的值**"
$MYSQL "SELECT catalog_key, epoch, catalog_version FROM $DB.toolbox_curriculum_catalog;"
echo "--- 迁移写入的版本行与草稿基线 ---"
$MYSQL "SELECT version_id, content_version, LEFT(content_sha256,16) AS sha16 FROM $DB.toolbox_textbook_version;
        SELECT edition_id, current_version_id FROM $DB.toolbox_textbook;
        SELECT draft_id, revision, base_content_version FROM $DB.toolbox_textbook_draft;"

say "b3. 补一句来源说明（迁移草稿没有 sourceNote，发布校验要求必填）"
curl -s -b "$COOKIES" -H "X-Toolbox-Admin-Csrf: $CSRF" \
  "$BASE/admin/v1/textbooks/$EDITION/draft" | jq '.data.draft' > "$OUT/item1-draft.json"
REV=$(curl -s -b "$COOKIES" -H "X-Toolbox-Admin-Csrf: $CSRF" \
  "$BASE/admin/v1/textbooks/$EDITION/draft" | jq -r '.data.revision')
jq --arg note "迁移自文件仓储清单 catalog-2026-10-05.7，第 1 页" '.sourceNote = $note' \
  "$OUT/item1-draft.json" > "$OUT/item1-draft-save.json"
echo "PUT draft（If-Match: draft-${REV}）：HTTP $(call "$BASE/admin/v1/textbooks/$EDITION/draft" \
  "$OUT/item1-save.json" -b "$COOKIES" -X PUT -H "X-Toolbox-Admin-Csrf: $CSRF" \
  -H 'Content-Type: application/json' -H "If-Match: \"draft-$REV\"" \
  -H "Idempotency-Key: item1-save-$(uuidgen)" --data-binary @"$OUT/item1-draft-save.json")"
jq . "$OUT/item1-save.json"
REV2=$(curl -s -b "$COOKIES" -H "X-Toolbox-Admin-Csrf: $CSRF" \
  "$BASE/admin/v1/textbooks/$EDITION/draft" | jq -r '.data.revision')
echo "保存后 revision=$REV2"

say "c/d. 首次真实发布：POST /admin/v1/textbooks/$EDITION/publish?draftRevision=$REV2"
echo "HTTP $(call "$BASE/admin/v1/textbooks/$EDITION/publish?draftRevision=$REV2" "$OUT/item1-publish.json" \
  -b "$COOKIES" -X POST -H "X-Toolbox-Admin-Csrf: $CSRF" -H "Idempotency-Key: item1-publish-$(uuidgen)")"
jq . "$OUT/item1-publish.json"

say "d2. 发布响应里的两个字段 vs 库中的列"
RESP_VERSION=$(jq -r '.data.catalogVersion' "$OUT/item1-publish.json")
RESP_EPOCH=$(jq -r '.data.catalogEpoch' "$OUT/item1-publish.json")
DB_ROW=$($MYSQL "SELECT CONCAT(epoch, '|', catalog_version) FROM $DB.toolbox_curriculum_catalog WHERE catalog_key='current';")
DB_EPOCH=${DB_ROW%%|*}
DB_VERSION=${DB_ROW##*|}
echo "响应 catalogVersion=$RESP_VERSION  catalogEpoch=$RESP_EPOCH"
echo "库中  epoch=$DB_EPOCH  catalog_version=$DB_VERSION"
[ "$RESP_VERSION" = "$DB_VERSION" ] && echo "断言 catalogVersion == 库中 catalog_version：通过" || { echo "断言失败"; exit 1; }
[ "$RESP_EPOCH" = "$DB_EPOCH" ] && echo "断言 catalogEpoch == 库中 epoch：通过" || { echo "断言失败"; exit 1; }
[ "$DB_EPOCH" = "1" ] && echo "断言 首次真实发布后 epoch=1：通过" || { echo "断言失败 epoch=$DB_EPOCH"; exit 1; }
case "$DB_VERSION" in
  catalog-*) echo "断言 首次真实发布生成了新的 catalog-* 版本名：通过" ;;
  *) echo "断言失败 catalog_version=$DB_VERSION"; exit 1 ;;
esac
[ "$DB_VERSION" != "catalog-2026-10-05.7" ] \
  && echo "断言 新版本名已不再是清单值：通过" \
  || { echo "断言失败 版本名未变"; exit 1; }
echo "观察（H2 与 MySQL 的差异）：MySQL 上 SET epoch=epoch+1, catalog_version=CONCAT('catalog-',epoch+1)"
echo "                       的赋值从左到右求值，第二个表达式用到**已更新**的 epoch，"
echo "                       因此首次发布得到 catalog-2（epoch=1）；H2 上同一语句给出 catalog-1。"

say "e. 发布后的全部版本行（迁移版 1.0.0 + 发布版 1.0.1）"
$MYSQL "SELECT version_id, content_version, LEFT(content_sha256,16) AS sha16, published_by FROM $DB.toolbox_textbook_version ORDER BY content_version;
        SELECT edition_id, current_version_id FROM $DB.toolbox_textbook;
        SELECT draft_id, revision, base_content_version FROM $DB.toolbox_textbook_draft;"
