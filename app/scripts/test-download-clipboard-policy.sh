#!/usr/bin/env bash
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
cp "$APP_DIR/entry/src/main/ets/features/tools/download/DownloadClipboardPolicy.ets" "$TEST_DIR/DownloadClipboardPolicy.ts"
node "$TSC_BIN" "$TEST_DIR/DownloadClipboardPolicy.ts" --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/download_clipboard_policy_test.cjs" "$TEST_DIR/output/DownloadClipboardPolicy.js"
