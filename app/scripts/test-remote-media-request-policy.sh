#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
POLICY="$APP_DIR/entry/src/main/ets/features/media/RemoteMediaRequestPolicy.ets"
API="$APP_DIR/entry/src/main/ets/features/media/RemoteMediaApi.ets"
PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

cp "$POLICY" "$TEST_DIR/RemoteMediaRequestPolicy.ts"
node "$TSC_BIN" "$TEST_DIR/RemoteMediaRequestPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/remote_media_request_policy_test.cjs" "$TEST_DIR/output/RemoteMediaRequestPolicy.js"

grep -Fq 'this.baseUrl = authManager.normalizeBaseUrl(baseUrl)' "$API"
grep -Fq 'this.requestPolicy.accountId(accountId)' "$API"
grep -Fq 'this.requestPolicy.path(path, false)' "$API"
grep -Fq 'this.requestPolicy.name(filename)' "$API"
grep -Fq 'this.requestPolicy.fileUri(uri)' "$API"
grep -Fq 'this.requestPolicy.ticket(ticket)' "$API"
grep -Fq 'encodeURIComponent(safeAccountId)' "$API"
! grep -Eq 'accountId=\$\{accountId\}' "$API"
grep -Fq "Button('确认永久删除')" "$PAGE" || grep -Fq "'确认永久删除'" "$PAGE"
grep -Fq "item.path === path && item.kind === 'image'" "$PAGE"
grep -Fq 'await api.deleteEntry(accountId, path, false)' "$PAGE"
grep -Fq 'item.id === accountId && item.isActive' "$PAGE"
grep -Fq 'this.PersistMediaFavorites();' "$PAGE"
grep -Fq "if (this.mediaImageDeleting || this.currentMediaKind !== 'image' || !this.authenticated) return;" "$PAGE"

echo "Remote media request integration policy tests passed"
