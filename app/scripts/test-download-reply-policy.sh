#!/usr/bin/env bash
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
cp "$APP_DIR/entry/src/main/ets/features/tools/download/DownloadReplyPolicy.ets" "$TEST_DIR/DownloadReplyPolicy.ts"
node /Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc "$TEST_DIR/DownloadReplyPolicy.ts" --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/download_reply_policy_test.cjs" "$TEST_DIR/output/DownloadReplyPolicy.js"
