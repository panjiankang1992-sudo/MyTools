#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
NORMALIZER="$APP_DIR/entry/src/main/ets/features/auth/SessionResponseNormalizer.ets"
MODELS="$APP_DIR/entry/src/main/ets/features/auth/SessionModels.ets"
API="$APP_DIR/entry/src/main/ets/features/auth/SessionApi.ets"

cp "$NORMALIZER" "$TEST_DIR/SessionResponseNormalizer.ts"
cp "$MODELS" "$TEST_DIR/SessionModels.ts"
node "$TSC_BIN" "$TEST_DIR/SessionResponseNormalizer.ts" "$TEST_DIR/SessionModels.ts" \
  --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/session_response_normalizer_test.cjs" "$TEST_DIR/output/SessionResponseNormalizer.js"

grep -Fq "this.normalizer.identitySessions" "$API"
grep -Fq "this.normalizer.revokeId" "$API"
grep -Fq "new AuthorizedApiClient(baseUrl, authManager)" "$API"
grep -Fq "this.client.get('/api/app/v1/identity/sessions', cancellation)" "$API"
grep -Fq 'this.client.delete(`/api/app/v1/identity/sessions/${safeId}`)' "$API"
grep -Fq 'if (!session.current) await this.revoke(session.id);' "$API"

echo "Session response integration policy tests passed"
