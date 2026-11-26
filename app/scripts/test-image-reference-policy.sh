#!/usr/bin/env bash
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
cp "$APP_DIR/entry/src/main/ets/features/tools/ImageReferencePolicy.ets" "$TEST_DIR/ImageReferencePolicy.ts"
node "$TSC_BIN" "$TEST_DIR/ImageReferencePolicy.ts" --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/image_reference_policy_test.cjs" "$TEST_DIR/output/ImageReferencePolicy.js"
