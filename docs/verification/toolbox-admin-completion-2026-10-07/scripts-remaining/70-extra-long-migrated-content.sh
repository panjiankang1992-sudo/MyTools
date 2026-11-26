#!/usr/bin/env bash
# 附加探针（不属任务 3 的四条，用于把"审计列太窄"这一发现的适用范围测准）：
# 迁移一册 **editionId 很长** 的教材，然后模拟管理员补全来源说明后发布，
# 预期仍然失败在 `toolbox_curriculum_audit.from_version`。
#
# 用独立的库 toolbox_verify_c 与独立内容目录 content-long，不干扰任务 3 的主证据。
set -euo pipefail

DIR=/tmp/toolbox-remaining/task3
ROOT="$DIR/content-long"
DB=toolbox_verify_c
LONG_EDITION="verify-migrated-2026-10-07-long"
MYSQL="docker exec -i toolbox-mysql-test mysql -uroot -ptoolbox-test-only"

echo "===== 1. 造一个 editionId=${LONG_EDITION}（$(echo -n "$LONG_EDITION" | wc -c | tr -d ' ') 字符）的迁移包 ====="
mkdir -p "$ROOT/catalog" "$ROOT/textbooks/$LONG_EDITION/1.0.0"
# 复用 lab-a 的真实正文字节（哈希不变），只把清单里的 editionId 换成长标识。
cp "$DIR/content/textbooks/lab-a/1.0.0/book.json" "$ROOT/textbooks/$LONG_EDITION/1.0.0/book.json"
python3 - "$DIR/out/source-edition-metadata.json" "$ROOT/catalog/published.json" "$LONG_EDITION" <<'PYEOF'
import json, sys
src, dst, long_id = sys.argv[1], sys.argv[2], sys.argv[3]
edition = json.load(open(src, encoding='utf-8'))
edition['editionId'] = long_id
manifest = {"catalogVersion": "catalog-2026-10-05.9", "schemaVersion": 1, "editions": [edition]}
with open(dst, 'w', encoding='utf-8') as out:
    json.dump(manifest, out, ensure_ascii=False, indent=2)
    out.write("\n")
print(json.dumps({"catalogVersion": manifest['catalogVersion'], "editionId": edition['editionId'],
                  "versionId长度": len(long_id) + 1 + len(edition['contentVersion'])}, ensure_ascii=False))
PYEOF
echo "book.json 字节 SHA-256（必须仍等于清单里的 contentSha256）："
shasum -a 256 "$ROOT/textbooks/$LONG_EDITION/1.0.0/book.json" | awk '{print $1}'
jq -r '.editions[0].contentSha256' "$ROOT/catalog/published.json"

echo
echo "===== 2. 建独立空库 $DB ====="
$MYSQL -e "DROP DATABASE IF EXISTS $DB; CREATE DATABASE $DB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" 2>/dev/null
$MYSQL -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB';" 2>/dev/null
echo "内容目录："
find "$ROOT" -type f | sort
