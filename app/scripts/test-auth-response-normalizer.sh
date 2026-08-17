#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
NORMALIZER="$APP_DIR/entry/src/main/ets/features/auth/AuthResponseNormalizer.ets"
NETWORK_POLICY="$APP_DIR/entry/src/main/ets/features/auth/AuthNetworkResponsePolicy.ets"
MODELS="$APP_DIR/entry/src/main/ets/features/auth/AuthModels.ets"
API="$APP_DIR/entry/src/main/ets/features/auth/AuthApi.ets"
STORE="$APP_DIR/entry/src/main/ets/features/auth/SecureSessionStore.ets"

cp "$NORMALIZER" "$TEST_DIR/AuthResponseNormalizer.ts"
cp "$NETWORK_POLICY" "$TEST_DIR/AuthNetworkResponsePolicy.ts"
cp "$MODELS" "$TEST_DIR/AuthModels.ts"
node "$TSC_BIN" "$TEST_DIR/AuthResponseNormalizer.ts" "$TEST_DIR/AuthNetworkResponsePolicy.ts" \
  "$TEST_DIR/AuthModels.ts" \
  --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/auth_response_normalizer_test.cjs" "$TEST_DIR/output/AuthResponseNormalizer.js"
node "$APP_DIR/tests/auth_network_response_policy_test.cjs" "$TEST_DIR/output/AuthNetworkResponsePolicy.js"

grep -Fq "this.normalizer.normalizeLogin" "$API"
grep -Fq "this.normalizer.normalizeRefresh" "$API"
grep -Fq "this.validateLoginRequest(requestBody)" "$API"
grep -Fq "this.networkPolicy.headerError" "$API"
grep -Fq "this.networkPolicy.envelope(text, bytes)" "$API"
grep -Fq "this.networkPolicy.requestBody" "$API"
grep -Fq "body === undefined ? '{}' : JSON.stringify(body)" "$API"
grep -Fq "request.off('headersReceive')" "$API"
grep -Fq "secret.length > 64 * 1024" "$STORE"
grep -Fq "this.normalizer.normalizeStored" "$STORE"

echo "Auth response integration policy tests passed"
