#!/usr/bin/env bash
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
cp "$APP_DIR/entry/src/main/ets/features/profile/LogoutRiskPolicy.ets" "$TEST_DIR/LogoutRiskPolicy.ts"
node "$TSC_BIN" "$TEST_DIR/LogoutRiskPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/logout_risk_policy_test.cjs" "$TEST_DIR/output/LogoutRiskPolicy.js"
SOURCE="$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq '.onClick(() => this.RequestLogout())' "$SOURCE"
grep -Fq "Button('继续同步')" "$SOURCE"
grep -Fq "Button('仍要退出')" "$SOURCE"
