#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
policy="$root_dir/app/entry/src/main/ets/features/reader/SourceHttpRequestHeaderPolicy.ets"
search_engine="$root_dir/app/entry/src/main/ets/features/reader/BookSourceSearchEngine.ets"
reader_engine="$root_dir/app/entry/src/main/ets/features/reader/BookSourceReaderEngine.ets"
tsc_bin="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$policy" "$test_dir/SourceHttpRequestHeaderPolicy.ts"
node "$tsc_bin" "$test_dir/SourceHttpRequestHeaderPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$test_dir/output" --skipLibCheck
node - "$test_dir/output/SourceHttpRequestHeaderPolicy.js" \
  "$root_dir/app/tests/source_http_request_header_policy_test.cjs" <<'NODE'
const moduleFile = process.argv[2];
const testFile = process.argv[3];
require(testFile)(require(moduleFile).SourceHttpRequestHeaderPolicy);
NODE

grep -Fq 'this.headerPolicy.parse(source.header)' "$search_engine"
grep -Fq 'this.headerPolicy.merge(headers, config.headers)' "$search_engine"
test "$(grep -Fc 'this.headerPolicy.parse(source.header)' "$reader_engine")" -ge 2
grep -Fq 'this.headerPolicy.merge(this.headerPolicy.parse(source.header), spec.headers)' "$reader_engine"
! grep -Fq 'private parseHeaders(' "$search_engine"
! grep -Fq 'private parseHeaders(' "$reader_engine"

echo 'source HTTP request header policy tests passed'
