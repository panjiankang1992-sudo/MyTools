#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
TOKEN="$APP_DIR/entry/src/main/ets/shared/network/DownloadCancellationToken.ets"
CLIENT="$APP_DIR/entry/src/main/ets/shared/network/AuthorizedApiClient.ets"
API="$APP_DIR/entry/src/main/ets/features/media/RemoteMediaApi.ets"
PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

cp "$TOKEN" "$TEST_DIR/DownloadCancellationToken.ts"
node "$TSC_BIN" "$TEST_DIR/DownloadCancellationToken.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/download_cancellation_token_test.cjs" "$TEST_DIR/output/DownloadCancellationToken.js"

grep -Fq 'cancellation?.bind(cancelHandler)' "$CLIENT"
grep -Fq 'cancellation?.unbind(cancelHandler)' "$CLIENT"
grep -Fq 'this.truncateTarget(targetUri)' "$CLIENT"
grep -Fq "throw new Error('下载已取消')" "$CLIENT"
grep -Fq 'cancellation?: DownloadCancellationToken' "$API"
grep -Fq 'this.activeDownloadCancellation.cancel()' "$PAGE"
test "$(grep -Fc 'this.activeDownloadCancellation?.cancel();' "$PAGE")" -ge 2
grep -Fq "Button('取消下载')" "$PAGE"
grep -Fq "cancelled ? 'cancelled' : 'failed'" "$PAGE"
grep -Fq "if (status === 'cancelled') return '已取消'" "$PAGE"
echo 'Download cancellation integration tests passed'
