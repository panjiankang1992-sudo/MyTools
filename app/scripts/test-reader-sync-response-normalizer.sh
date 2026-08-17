#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
NORMALIZER="$APP_DIR/entry/src/main/ets/features/reader/ReaderSyncResponseNormalizer.ets"
SYNC_MODELS="$APP_DIR/entry/src/main/ets/features/reader/ReaderSyncModels.ets"
READER_MODELS="$APP_DIR/entry/src/main/ets/features/reader/ReaderModels.ets"

mkdir -p "$TEST_DIR/reader"
cp "$NORMALIZER" "$TEST_DIR/reader/ReaderSyncResponseNormalizer.ts"
cp "$SYNC_MODELS" "$TEST_DIR/reader/ReaderSyncModels.ts"
cp "$READER_MODELS" "$TEST_DIR/reader/ReaderModels.ts"
node "$TSC_BIN" "$TEST_DIR/reader/ReaderSyncResponseNormalizer.ts" "$TEST_DIR/reader/ReaderSyncModels.ts" \
  "$TEST_DIR/reader/ReaderModels.ts" --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/reader_sync_response_normalizer_test.cjs" \
  "$TEST_DIR/output/ReaderSyncResponseNormalizer.js"

for api in ReaderProgressApi BookSourceSyncApi ReaderMarkerApi ReaderDataApi; do
  grep -Fq 'ReaderSyncResponseNormalizer' "$APP_DIR/entry/src/main/ets/features/reader/${api}.ets"
done
! grep -Eq 'envelope\.data as (Array|Record)' \
  "$APP_DIR/entry/src/main/ets/features/reader/ReaderProgressApi.ets" \
  "$APP_DIR/entry/src/main/ets/features/reader/BookSourceSyncApi.ets" \
  "$APP_DIR/entry/src/main/ets/features/reader/ReaderMarkerApi.ets" \
  "$APP_DIR/entry/src/main/ets/features/reader/ReaderDataApi.ets"

echo "Reader sync response integration policy tests passed"
