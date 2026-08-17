#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
POLICY="$APP_DIR/entry/src/main/ets/features/reader/RemoteBookDisplayMetadataPolicy.ets"
MEDIA_MODELS="$APP_DIR/entry/src/main/ets/features/media/RemoteMediaModels.ets"

mkdir -p "$TEST_DIR/reader" "$TEST_DIR/media"
cp "$POLICY" "$TEST_DIR/reader/RemoteBookDisplayMetadataPolicy.ts"
cp "$MEDIA_MODELS" "$TEST_DIR/media/RemoteMediaModels.ts"
node "$TSC_BIN" "$TEST_DIR/reader/RemoteBookDisplayMetadataPolicy.ts" "$TEST_DIR/media/RemoteMediaModels.ts" \
  --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/remote_book_display_metadata_policy_test.cjs" \
  "$TEST_DIR/output/reader/RemoteBookDisplayMetadataPolicy.js"
