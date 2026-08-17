#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$APP_DIR/entry/src/main/ets/features/media/RemoteMediaResponseNormalizer.ets" \
  "$TEST_DIR/RemoteMediaResponseNormalizer.ts"
cp "$APP_DIR/entry/src/main/ets/features/media/RemoteMediaModels.ets" "$TEST_DIR/RemoteMediaModels.ts"
node "$TSC_BIN" "$TEST_DIR/RemoteMediaResponseNormalizer.ts" "$TEST_DIR/RemoteMediaModels.ts" \
  --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/remote_media_response_normalizer_test.cjs" \
  "$TEST_DIR/output/RemoteMediaResponseNormalizer.js"
