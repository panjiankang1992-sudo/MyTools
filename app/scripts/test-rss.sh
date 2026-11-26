#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
for source in RssModels RssResponseNormalizer RssHtmlPolicy; do
  cp "$root/entry/src/main/ets/features/rss/$source.ets" "$test_dir/$source.ts"
done
node /Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc \
  "$test_dir"/*.ts --target ES2020 --module commonjs --outDir "$test_dir/output" --skipLibCheck
node "$root/tests/rss_test.cjs" "$test_dir/output"
