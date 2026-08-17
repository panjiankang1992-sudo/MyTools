#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT

if [[ ! -x "$TSC_BIN" ]]; then
  echo "DevEco TypeScript compiler not found" >&2
  exit 1
fi

cp "$APP_DIR/entry/src/main/ets/features/reader/BookSourceCredentialPolicy.ets" \
  "$TEST_DIR/BookSourceCredentialPolicy.ts"
node "$TSC_BIN" "$TEST_DIR/BookSourceCredentialPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/book_source_credential_policy_test.cjs" \
  "$TEST_DIR/output/BookSourceCredentialPolicy.js"

STORE="$APP_DIR/entry/src/main/ets/features/reader/BookSourceCredentialStore.ets"
grep -Fq 'secret.byteLength > 64 * 1024' "$STORE"
grep -Fq "TextDecoder.create('utf-8', { fatal: true })" "$STORE"
grep -Fq 'return this.policy.restore(parsed);' "$STORE"
