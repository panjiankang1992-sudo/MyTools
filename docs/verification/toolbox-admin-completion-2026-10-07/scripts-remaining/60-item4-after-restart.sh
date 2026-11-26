#!/usr/bin/env bash
# 任务 3-4（收尾）：恢复写入数据库后，公开目录需要**一次重启**才会重建快照。
# 本脚本在实例 B 重启后复核：恢复出来的内容确实对外可读，且目录行仍是恢复前的值。
set -euo pipefail

BASE_B="${BASE_B:-http://127.0.0.1:23212}"
DIR=/tmp/toolbox-remaining/task3
OUT="$DIR/out"
SQL_B="docker exec -i toolbox-mysql-test mysql -uroot -ptoolbox-test-only --default-character-set=utf8mb4 -D toolbox_verify_b"

say() { printf '\n===== %s =====\n' "$1"; }

say "1. 重启后实例 B 的公开目录"
curl -s "$BASE_B/public/v1/textbooks" | jq -c '{code, catalogVersion: .data.catalogVersion, editionCount: (.data.editions|length)}'
echo "--- 目录行的值（恢复**不**改动 epoch/catalog_version，与恢复前一致） ---"
$SQL_B -N -B -e "SELECT catalog_key, epoch, catalog_version FROM toolbox_curriculum_catalog;" 2>/dev/null

say "2. 逐册读回（内容来自恢复到空库的那一份）"
python3 - "$OUT/item4-package.json" <<'PYEOF' > "$OUT/item4-current-versions.tsv"
import json, sys
pkg = json.load(open(sys.argv[1], encoding='utf-8'))['data']
for v in pkg['versions']:
    if v['current']:
        print(f"{v['editionId']}\t{v['contentVersion']}\t{v['contentSha256']}")
PYEOF
while IFS=$'\t' read -r edition version sha; do
  STATUS=$(curl -s -o "$OUT/item4-served-$edition.json" -w '%{http_code}' \
    "$BASE_B/public/v1/textbooks/$edition/versions/$version")
  SERVED_SHA=$(jq -r '.data.edition.contentSha256' "$OUT/item4-served-$edition.json" 2>/dev/null)
  echo "$edition@$version HTTP=$STATUS 包内sha=${sha:0:16} 服务端sha=${SERVED_SHA:0:16} 一致=$([ "$sha" = "$SERVED_SHA" ] && echo yes || echo no)"
done < "$OUT/item4-current-versions.tsv"
