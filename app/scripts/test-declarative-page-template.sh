#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SOURCE="$APP_DIR/entry/src/main/ets/features/reader/DeclarativePageTemplate.ets"
TEST_SOURCE="$APP_DIR/tests/declarative_page_template_test.cjs"
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT

cp "$SOURCE" "$TEST_DIR/DeclarativePageTemplate.ts"
node "$TSC_BIN" "$TEST_DIR/DeclarativePageTemplate.ts" \
  --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$TEST_SOURCE" "$TEST_DIR/output/DeclarativePageTemplate.js"
