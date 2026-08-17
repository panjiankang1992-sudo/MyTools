#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SOURCE="$APP_DIR/entry/src/main/ets/features/reader/OpdsCredentialPolicy.ets"
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

if [[ ! -x "$TSC_BIN" ]]; then
  echo "DevEco TypeScript compiler not found" >&2
  exit 1
fi

cp "$SOURCE" "$TEMP_DIR/OpdsCredentialPolicy.ts"
node "$TSC_BIN" "$TEMP_DIR/OpdsCredentialPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$TEMP_DIR/output" --skipLibCheck
node "$APP_DIR/tests/opds_credential_policy_test.cjs" "$TEMP_DIR/output/OpdsCredentialPolicy.js"

STORE="$APP_DIR/entry/src/main/ets/features/reader/OpdsCredentialStore.ets"
grep -Fq 'private scopeRevision: number = 0;' "$STORE"
grep -Fq 'if (this.accountScope !== scope) this.scopeRevision++;' "$STORE"
test "$(grep -Fc 'if (revision !== this.scopeRevision)' "$STORE")" -ge 2
grep -Fq 'secret.byteLength > 4 * 1024' "$STORE"
grep -Fq "TextDecoder.create('utf-8', { fatal: true })" "$STORE"
grep -Fq 'return this.policy.restore(parsed);' "$STORE"
