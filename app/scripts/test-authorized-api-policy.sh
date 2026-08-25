#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
POLICY="$APP_DIR/entry/src/main/ets/shared/network/AuthorizedApiPolicy.ets"
MODELS="$APP_DIR/entry/src/main/ets/shared/network/AuthorizedApiModels.ets"
CLIENT="$APP_DIR/entry/src/main/ets/shared/network/AuthorizedApiClient.ets"
DOWNLOAD_POLICY="$APP_DIR/entry/src/main/ets/shared/network/DownloadStreamIntegrityPolicy.ets"

mkdir -p "$TEST_DIR/network"
cp "$POLICY" "$TEST_DIR/network/AuthorizedApiPolicy.ts"
cp "$MODELS" "$TEST_DIR/network/AuthorizedApiModels.ts"
node "$TSC_BIN" "$TEST_DIR/network/AuthorizedApiPolicy.ts" "$TEST_DIR/network/AuthorizedApiModels.ts" \
  --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/authorized_api_policy_test.cjs" "$TEST_DIR/output/AuthorizedApiPolicy.js"

grep -Fq 'this.baseUrl = authManager.normalizeBusinessBaseUrl(baseUrl)' "$CLIENT"
grep -Fq 'const safePath = this.policy.path(path)' "$CLIENT"
grep -Fq 'this.policy.requestBody(body, bodyBytes)' "$CLIENT"
grep -Fq "lower === 'location'" "$CLIENT"
grep -Fq 'length > 64 * 1024 * 1024' "$CLIENT"
grep -Fq 'this.policy.envelope(text, bytes)' "$CLIENT"
grep -Fq "request.off('headersReceive')" "$CLIENT"
grep -Fq 'downloadJpegThumbnailToUri' "$CLIENT"
grep -Fq 'downloadJpegShareToUri' "$CLIENT"
grep -Fq "expectedContentType === 'image/jpeg'" "$DOWNLOAD_POLICY"
grep -Fq 'signature[0] !== 0xFF' "$DOWNLOAD_POLICY"
grep -Fq "lower === 'content-encoding'" "$CLIENT"
grep -Fq 'this.downloadIntegrityPolicy.error(status, received, total' "$CLIENT"
grep -Fq 'if (!committed) this.truncateTarget(targetUri)' "$CLIENT"

echo "Authorized API integration policy tests passed"
