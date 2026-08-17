#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SOURCE="$APP_DIR/entry/src/main/ets/features/reader/SourceUrlResolver.ets"
TEST_SOURCE="$APP_DIR/tests/source_url_resolver_test.cjs"
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT

if [[ ! -x "$TSC_BIN" ]]; then
  echo "DevEco TypeScript compiler not found" >&2
  exit 1
fi

cp "$SOURCE" "$TEST_DIR/SourceUrlResolver.ts"
node "$TSC_BIN" "$TEST_DIR/SourceUrlResolver.ts" \
  --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$TEST_SOURCE" "$TEST_DIR/output/SourceUrlResolver.js"
