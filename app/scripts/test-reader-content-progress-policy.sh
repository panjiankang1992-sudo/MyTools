#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$APP_DIR/entry/src/main/ets/features/reader/ReaderContentProgressPolicy.ets" \
  "$TEST_DIR/ReaderContentProgressPolicy.ts"
cp "$APP_DIR/entry/src/main/ets/features/reader/ReaderModels.ets" "$TEST_DIR/ReaderModels.ts"
node "$TSC_BIN" --target es2020 --module commonjs --skipLibCheck \
  --outDir "$TEST_DIR/output" "$TEST_DIR/ReaderContentProgressPolicy.ts" "$TEST_DIR/ReaderModels.ts"
node "$APP_DIR/tests/reader_content_progress_policy_test.cjs" \
  "$TEST_DIR/output/ReaderContentProgressPolicy.js"

PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq 'private readerProgressReady: boolean = false;' "$PAGE"
grep -Fq 'if (this.currentBook === undefined || !this.readerProgressReady) return;' "$PAGE"
grep -Fq 'this.readerContentProgressPolicy.percentage(' "$PAGE"
grep -Fq 'this.readerContentProgressPolicy.location(' "$PAGE"
