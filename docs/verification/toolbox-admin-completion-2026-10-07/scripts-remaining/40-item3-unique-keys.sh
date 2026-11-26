#!/usr/bin/env bash
# 任务 3-3：唯一键——重复 version_id / (edition_id, content_version) 的插入必须被拒（真实 MySQL）。
#
# 只做 INSERT 尝试：两次都会失败，因此表里不会留下任何探针行（脚本末尾用计数复核）。
set -euo pipefail

DB="${DB:-toolbox_verify_a}"
MYSQL="docker exec -i toolbox-mysql-test mysql -uroot -ptoolbox-test-only"

say() { printf '\n===== %s =====\n' "$1"; }
# 执行一条会失败的 SQL：把 MySQL 的报错原样打印，并把退出码当作"被拒"的证据。
expect_rejected() {
  local label="$1"; shift
  echo "--- $label"
  set +e
  local output
  output=$("$@" 2>&1)
  local code=$?
  set -e
  echo "$output" | grep -v '^mysql: \[Warning\]' || true
  echo "mysql 退出码=${code}（非 0 表示插入被拒）"
  case "$output" in
    *"ERROR 1062"*) echo "结论：被拒（ERROR 1062 Duplicate entry）" ;;
    *) echo "结论：**未被拒**——与本项要求不符"; exit 1 ;;
  esac
}

say "当前表定义（主键与唯一键）"
$MYSQL -N -e "SHOW CREATE TABLE $DB.toolbox_textbook_version\G" 2>/dev/null | grep -E "PRIMARY KEY|UNIQUE KEY"

say "探针前该表行数"
$MYSQL -N -e "SELECT COUNT(*) FROM $DB.toolbox_textbook_version;" 2>/dev/null

say "a. 重复 version_id（与既有 lab-a@1.0.0 相同）"
expect_rejected "INSERT ... version_id='lab-a@1.0.0'" \
  $MYSQL -e "INSERT INTO $DB.toolbox_textbook_version
             (version_id, edition_id, content_version, content_bytes, content_sha256,
              edition_metadata_json, source_metadata_json, published_by, published_at)
             VALUES ('lab-a@1.0.0','unique-probe','9.9.9',UNHEX('7B7D'),REPEAT('a',64),'{}',NULL,
                     'sql-probe',NOW());"

say "b. 同 (edition_id, content_version) 但不同 version_id（lab-a + 1.0.0）"
expect_rejected "INSERT ... version_id='lab-a@9.9.9', (lab-a, 1.0.0)" \
  $MYSQL -e "INSERT INTO $DB.toolbox_textbook_version
             (version_id, edition_id, content_version, content_bytes, content_sha256,
              edition_metadata_json, source_metadata_json, published_by, published_at)
             VALUES ('lab-a@9.9.9','lab-a','1.0.0',UNHEX('7B7D'),REPEAT('a',64),'{}',NULL,
                     'sql-probe',NOW());"

say "探针后该表行数（必须与探针前一致）"
$MYSQL -N -e "SELECT COUNT(*) FROM $DB.toolbox_textbook_version;" 2>/dev/null
$MYSQL -N -e "SELECT version_id FROM $DB.toolbox_textbook_version ORDER BY version_id;" 2>/dev/null
