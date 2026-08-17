#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$APP_DIR/entry/src/main/ets/features/reader/ReaderSnapshotNormalizer.ets" "$TEST_DIR/ReaderSnapshotNormalizer.ts"
cp "$APP_DIR/entry/src/main/ets/features/reader/ReaderModels.ets" "$TEST_DIR/ReaderModels.ts"
node "$TSC_BIN" "$TEST_DIR/ReaderSnapshotNormalizer.ts" "$TEST_DIR/ReaderModels.ts" \
  --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/reader_snapshot_normalizer_test.cjs" "$TEST_DIR/output/ReaderSnapshotNormalizer.js"
