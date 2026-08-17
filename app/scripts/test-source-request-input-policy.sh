#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
policy="$root_dir/app/entry/src/main/ets/features/reader/SourceRequestInputPolicy.ets"
engine="$root_dir/app/entry/src/main/ets/features/reader/BookSourceSearchEngine.ets"
tsc_bin="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$policy" "$test_dir/SourceRequestInputPolicy.ts"
node "$tsc_bin" "$test_dir/SourceRequestInputPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$test_dir/output" --skipLibCheck
node - "$test_dir/output/SourceRequestInputPolicy.js" \
  "$root_dir/app/tests/source_request_input_policy_test.cjs" <<'NODE'
const moduleFile = process.argv[2];
const testFile = process.argv[3];
require(testFile)(require(moduleFile).SourceRequestInputPolicy);
NODE

grep -Fq 'const safeKeyword = this.inputPolicy.keyword(keyword);' "$engine"
grep -Fq 'const safePage = this.inputPolicy.page(page);' "$engine"
grep -Fq 'this.inputPolicy.page(page)' "$engine"
grep -Fq "keyword : '测试'" "$engine"

echo 'source request input policy tests passed'
