#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
POLICY="$APP_DIR/entry/src/main/ets/features/media/RemoteUploadTaskPolicy.ets"
API="$APP_DIR/entry/src/main/ets/features/media/RemoteMediaApi.ets"

cp "$POLICY" "$TEST_DIR/RemoteUploadTaskPolicy.ts"
node "$TSC_BIN" "$TEST_DIR/RemoteUploadTaskPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/remote_upload_task_policy_test.cjs" "$TEST_DIR/output/RemoteUploadTaskPolicy.js"

grep -Fq 'this.uploadTaskPolicy.progress(' "$API"
grep -Fq 'this.uploadTaskPolicy.headers(header)' "$API"
grep -Fq 'responseStatus >= 200 && responseStatus < 300' "$API"
grep -Fq '上传响应缺少可信HTTP状态' "$API"
grep -Fq 'responseError.length === 0' "$API"

echo "Remote upload task integration policy tests passed"
