#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$APP_DIR/entry/src/main/ets/features/reader/ReaderViewportPolicy.ets" "$TEST_DIR/ReaderViewportPolicy.ts"
node "$TSC_BIN" "$TEST_DIR/ReaderViewportPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/reader_viewport_policy_test.cjs" "$TEST_DIR/output/ReaderViewportPolicy.js"

PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq '.onAreaChange' "$PAGE"
grep -Fq 'this.readerViewportTimer = setTimeout' "$PAGE"
grep -Fq 'width: this.readerViewportWidth, height: this.readerViewportHeight' "$PAGE"
echo 'Reader viewport integration policy tests passed'
