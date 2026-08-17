#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SOURCE="$APP_DIR/entry/src/main/ets/features/reader/BookSourceExploreParser.ets"
MODELS="$APP_DIR/entry/src/main/ets/features/reader/ReaderModels.ets"
TEST_SOURCE="$APP_DIR/tests/book_source_explore_parser_test.cjs"
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT

cp "$SOURCE" "$TEST_DIR/BookSourceExploreParser.ts"
cp "$MODELS" "$TEST_DIR/ReaderModels.ts"
node "$TSC_BIN" "$TEST_DIR/BookSourceExploreParser.ts" "$TEST_DIR/ReaderModels.ts" \
  --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$TEST_SOURCE" "$TEST_DIR/output/BookSourceExploreParser.js"
