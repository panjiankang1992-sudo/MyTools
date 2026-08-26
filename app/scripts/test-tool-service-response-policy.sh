#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
TOOLS_DIR="$APP_DIR/entry/src/main/ets/features/tools"
SHARED_DIR="$APP_DIR/entry/src/main/ets/shared/network"

mkdir -p "$TEST_DIR/features/tools" "$TEST_DIR/shared/network"
cp "$TOOLS_DIR/AppMarketResponseNormalizer.ets" "$TEST_DIR/features/tools/AppMarketResponseNormalizer.ts"
cp "$TOOLS_DIR/AppMarketModels.ets" "$TEST_DIR/features/tools/AppMarketModels.ts"
cp "$TOOLS_DIR/FeedbackPolicy.ets" "$TEST_DIR/features/tools/FeedbackPolicy.ts"
cp "$SHARED_DIR/AuthorizedApiModels.ets" "$TEST_DIR/shared/network/AuthorizedApiModels.ts"
node "$TSC_BIN" "$TEST_DIR/features/tools/AppMarketResponseNormalizer.ts" "$TEST_DIR/features/tools/AppMarketModels.ts" \
  "$TEST_DIR/features/tools/FeedbackPolicy.ts" "$TEST_DIR/shared/network/AuthorizedApiModels.ts" \
  --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/tool_service_response_policy_test.cjs" \
  "$TEST_DIR/output/features/tools/AppMarketResponseNormalizer.js" \
  "$TEST_DIR/output/features/tools/FeedbackPolicy.js"

MARKET_API="$TOOLS_DIR/AppMarketApi.ets"
FEEDBACK_API="$TOOLS_DIR/FeedbackApi.ets"
grep -Fq 'this.normalizer.catalogPage(envelope.data, page, pageSize, name)' "$MARKET_API"
grep -Fq 'this.normalizer.catalogDetail(envelope.data, id)' "$MARKET_API"
! grep -Fq 'envelope.data as' "$MARKET_API"
grep -Fq 'AuthApi.normalizeBusinessBaseUrl(baseUrl)' "$FEEDBACK_API"
grep -Fq "lower === 'location'" "$FEEDBACK_API"
grep -Fq 'length > 1024 * 1024' "$FEEDBACK_API"
grep -Fq 'this.feedbackPolicy.receipt(envelope)' "$FEEDBACK_API"
grep -Fq "request.off('headersReceive')" "$FEEDBACK_API"

echo "Tool service response integration policy tests passed"
