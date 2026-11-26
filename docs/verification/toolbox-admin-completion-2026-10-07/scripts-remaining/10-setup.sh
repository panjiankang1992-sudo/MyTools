#!/usr/bin/env bash
# 任务 3 的环境准备（真实 MySQL，隔离容器 toolbox-mysql-test）。
#
# 只做三件事，全部可重复执行：
#   1. 建两个**独立空库** toolbox_verify_a / toolbox_verify_b（不碰 toolbox_test，不碰容器与卷）；
#   2. 从 toolbox_test 里一册已发布教材导出真实的 book.json（按 Base64 取字节，保证逐字节一致）；
#   3. 用该册的声明元数据拼出迁移清单 catalog/published.json，catalogVersion 取
#      catalog-2026-10-05.7 —— 与库初始值 catalog-0 **不同**，用于验证"迁移保留清单值"。
set -euo pipefail

MYSQL="docker exec -i toolbox-mysql-test mysql -uroot -ptoolbox-test-only"
DIR=/tmp/toolbox-remaining/task3
ROOT="$DIR/content"
SRC_EDITION="${SRC_EDITION:-lab-a}"
SRC_VERSION="${SRC_VERSION:-1.0.0}"
MANIFEST_CATALOG_VERSION="${MANIFEST_CATALOG_VERSION:-catalog-2026-10-05.7}"

echo "===== 0. 容器与版本 ====="
docker inspect toolbox-mysql-test --format 'Name={{.Name}} Image={{.Config.Image}} Running={{.State.Running}} Ports={{json .NetworkSettings.Ports}} Mounts={{range .Mounts}}{{.Type}}:{{.Name}}->{{.Destination}} {{end}}'
$MYSQL -N -e "SELECT VERSION();"

echo
echo "===== 1. 重置两个**本脚本专用的临时库**（可重跑；绝不碰 toolbox_test / 容器 / 数据卷） ====="
$MYSQL -e "DROP DATABASE IF EXISTS toolbox_verify_a;
           DROP DATABASE IF EXISTS toolbox_verify_b;
           CREATE DATABASE toolbox_verify_a CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
           CREATE DATABASE toolbox_verify_b CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
echo "--- 未被触碰的库（toolbox_test 必须仍在，且行数不变） ---"
$MYSQL -e "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'toolbox%';"
$MYSQL -e "SELECT COUNT(*) AS toolbox_test_versions FROM toolbox_test.toolbox_textbook_version;"
$MYSQL -N -e "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'toolbox%';"
echo "--- 两个目标库的表数（应为 0，即空库） ---"
$MYSQL -N -e "SELECT table_schema, COUNT(*) FROM information_schema.tables
              WHERE table_schema IN ('toolbox_verify_a','toolbox_verify_b') GROUP BY table_schema;"

echo
echo "===== 2. 从 toolbox_test 取真实已发布字节（HEX，逐字节写入） ====="
mkdir -p "$DIR/out" "$ROOT/catalog" "$ROOT/textbooks/$SRC_EDITION/$SRC_VERSION"
$MYSQL -N -e "SELECT HEX(content_bytes) FROM toolbox_test.toolbox_textbook_version
              WHERE version_id='$SRC_EDITION@$SRC_VERSION'" | tr -d '\n' \
  | python3 -c 'import sys,base64; sys.stdout.buffer.write(bytes.fromhex(sys.stdin.read().strip()))' \
  > "$ROOT/textbooks/$SRC_EDITION/$SRC_VERSION/book.json"
$MYSQL -N -e "SELECT HEX(edition_metadata_json) FROM toolbox_test.toolbox_textbook_version
              WHERE version_id='$SRC_EDITION@$SRC_VERSION'" | tr -d '\n' \
  | python3 -c 'import sys,base64; sys.stdout.buffer.write(bytes.fromhex(sys.stdin.read().strip()))' \
  > "$DIR/out/source-edition-metadata.json"

echo "落盘 book.json："
ls -l "$ROOT/textbooks/$SRC_EDITION/$SRC_VERSION/book.json"
echo "落盘字节的 SHA-256（必须等于库里的 content_sha256）："
shasum -a 256 "$ROOT/textbooks/$SRC_EDITION/$SRC_VERSION/book.json" | awk '{print $1}'
echo "库中 content_sha256："
$MYSQL -N -e "SELECT content_sha256 FROM toolbox_test.toolbox_textbook_version
              WHERE version_id='$SRC_EDITION@$SRC_VERSION'"

echo
echo "===== 3. 拼迁移清单 catalog/published.json ====="
python3 - "$DIR/out/source-edition-metadata.json" "$ROOT/catalog/published.json" "$MANIFEST_CATALOG_VERSION" <<'PYEOF'
import json, sys
src, dst, catalog_version = sys.argv[1], sys.argv[2], sys.argv[3]
edition = json.load(open(src, encoding='utf-8'))
manifest = {"catalogVersion": catalog_version, "schemaVersion": 1, "editions": [edition]}
with open(dst, 'w', encoding='utf-8') as out:
    json.dump(manifest, out, ensure_ascii=False, indent=2)
    out.write("\n")
print(json.dumps({"catalogVersion": manifest["catalogVersion"],
                  "editions": [{"editionId": edition.get("editionId"),
                                "contentVersion": edition.get("contentVersion"),
                                "contentSha256": edition.get("contentSha256")} for edition in manifest["editions"]]},
                 ensure_ascii=False))
PYEOF
echo "内容目录树："
find "$ROOT" -type f | sort
