#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TYPESCRIPT_DIR="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript"

node "$APP_DIR/tests/endpoint_router_test.cjs" \
  "$APP_DIR/entry/src/main/ets" "$TYPESCRIPT_DIR"
