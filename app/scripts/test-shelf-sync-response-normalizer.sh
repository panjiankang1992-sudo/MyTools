#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
NORMALIZER="$APP_DIR/entry/src/main/ets/features/reader/ShelfSyncResponseNormalizer.ets"
MODELS="$APP_DIR/entry/src/main/ets/features/reader/ReaderModels.ets"
SYNC_MODELS="$APP_DIR/entry/src/main/ets/features/reader/ShelfSyncModels.ets"
API="$APP_DIR/entry/src/main/ets/features/reader/ShelfSyncApi.ets"

cp "$NORMALIZER" "$TEST_DIR/ShelfSyncResponseNormalizer.ts"
cp "$MODELS" "$TEST_DIR/ReaderModels.ts"
cp "$SYNC_MODELS" "$TEST_DIR/ShelfSyncModels.ts"
node "$TSC_BIN" "$TEST_DIR/ShelfSyncResponseNormalizer.ts" "$TEST_DIR/ReaderModels.ts" \
  "$TEST_DIR/ShelfSyncModels.ts" \
  --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/shelf_sync_response_normalizer_test.cjs" \
  "$TEST_DIR/output/ShelfSyncResponseNormalizer.js"

grep -Fq "this.normalizer.list(envelope.data)" "$API"
grep -Fq "this.normalizer.localItem(book)" "$API"
grep -Fq "this.normalizer.saveResult(envelope.data)" "$API"
grep -Fq "digestText(safeBook.id" "$API"

echo "Shelf sync response integration policy tests passed"
