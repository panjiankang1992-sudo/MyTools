#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$APP_DIR/entry/src/main/ets/features/media/DownloadHistoryPolicy.ets" "$TEST_DIR/DownloadHistoryPolicy.ts"
node "$TSC_BIN" "$TEST_DIR/DownloadHistoryPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/download_history_policy_test.cjs" "$TEST_DIR/output/DownloadHistoryPolicy.js"

PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq "import { DownloadHistoryItem, DownloadHistoryPolicy }" "$PAGE"
grep -Fq 'this.downloadHistoryPolicy.normalize(JSON.parse(value) as Object, true)' "$PAGE"
grep -Fq 'this.downloadHistoryPolicy.normalize(updated as Object)' "$PAGE"
grep -Fq 'return this.downloadHistoryPolicy.normalize(items as Object)' "$PAGE"
echo 'Download history integration policy tests passed'
