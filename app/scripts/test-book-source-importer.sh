#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT

if [[ ! -x "$TSC_BIN" ]]; then
  echo "DevEco TypeScript compiler not found" >&2
  exit 1
fi

cp "$APP_DIR/entry/src/main/ets/features/reader/BookSourceImporter.ets" "$TEST_DIR/BookSourceImporter.ts"
cp "$APP_DIR/entry/src/main/ets/features/reader/ReaderModels.ets" "$TEST_DIR/ReaderModels.ts"
node "$TSC_BIN" "$TEST_DIR/BookSourceImporter.ts" "$TEST_DIR/ReaderModels.ts" \
  --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/book_source_importer_test.cjs" "$TEST_DIR/output/BookSourceImporter.js"
