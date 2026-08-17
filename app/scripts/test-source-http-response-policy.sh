#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
policy="$root_dir/app/entry/src/main/ets/features/reader/SourceHttpResponsePolicy.ets"
search_engine="$root_dir/app/entry/src/main/ets/features/reader/BookSourceSearchEngine.ets"
reader_engine="$root_dir/app/entry/src/main/ets/features/reader/BookSourceReaderEngine.ets"
tsc_bin="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$policy" "$test_dir/SourceHttpResponsePolicy.ts"
node "$tsc_bin" "$test_dir/SourceHttpResponsePolicy.ts" --target ES2020 --module commonjs \
  --outDir "$test_dir/output" --skipLibCheck
node - "$test_dir/output/SourceHttpResponsePolicy.js" \
  "$root_dir/app/tests/source_http_response_policy_test.cjs" <<'NODE'
const moduleFile = process.argv[2];
const testFile = process.argv[3];
require(testFile)(require(moduleFile).SourceHttpResponsePolicy);
NODE

for engine in "$search_engine" "$reader_engine"; do
  grep -Fq 'this.responsePolicy.headerError(' "$engine"
  grep -Fq 'this.responsePolicy.bodyError(text)' "$engine"
  grep -Fq "if (headerFailure.length > 0) throw new Error(headerFailure);" "$engine"
done

echo 'source HTTP response policy tests passed'
