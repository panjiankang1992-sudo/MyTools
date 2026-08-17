#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT

cp "$APP_DIR/entry/src/main/ets/features/reader/RestrictedSourceScript.ets" "$TEST_DIR/RestrictedSourceScript.ts"
node "$TSC_BIN" "$TEST_DIR/RestrictedSourceScript.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/restricted_source_script_test.cjs" "$TEST_DIR/output/RestrictedSourceScript.js"
