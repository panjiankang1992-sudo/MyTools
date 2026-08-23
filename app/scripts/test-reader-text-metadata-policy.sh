#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$APP_DIR/entry/src/main/ets/features/reader/ReaderTextMetadataPolicy.ets" \
  "$TEST_DIR/ReaderTextMetadataPolicy.ts"
node "$TSC_BIN" "$TEST_DIR/ReaderTextMetadataPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/reader_text_metadata_policy_test.cjs" \
  "$TEST_DIR/output/ReaderTextMetadataPolicy.js"

LOADER="$APP_DIR/entry/src/main/ets/features/reader/ReaderContentLoader.ets"
grep -Fq "import { ReaderTextMetadataPolicy }" "$LOADER"
[[ "$(grep -cF 'this.textMetadataPolicy.sanitize' "$LOADER")" -ge 3 ]]
echo 'Reader text metadata integration policy tests passed'
