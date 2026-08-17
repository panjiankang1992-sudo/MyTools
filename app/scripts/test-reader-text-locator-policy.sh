#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$APP_DIR/entry/src/main/ets/features/reader/ReaderTextLocatorPolicy.ets" "$TEST_DIR/ReaderTextLocatorPolicy.ts"
node "$TSC_BIN" "$TEST_DIR/ReaderTextLocatorPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/reader_text_locator_policy_test.cjs" "$TEST_DIR/output/ReaderTextLocatorPolicy.js"

PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq 'ChangeReaderPageOrSection' "$PAGE"
grep -Fq "new ReaderTextLocatorPolicy().encode" "$PAGE"
grep -Fq 'decoded.legacyPixels' "$PAGE"
echo 'Reader text locator integration tests passed'
