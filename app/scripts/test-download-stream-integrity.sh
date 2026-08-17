#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
POLICY="$APP_DIR/entry/src/main/ets/shared/network/DownloadStreamIntegrityPolicy.ets"
CLIENT="$APP_DIR/entry/src/main/ets/shared/network/AuthorizedApiClient.ets"

cp "$POLICY" "$TEST_DIR/DownloadStreamIntegrityPolicy.ts"
node "$TSC_BIN" "$TEST_DIR/DownloadStreamIntegrityPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/download_stream_integrity_policy_test.cjs" \
  "$TEST_DIR/output/DownloadStreamIntegrityPolicy.js"

grep -Fq 'private readonly downloadIntegrityPolicy: DownloadStreamIntegrityPolicy' "$CLIENT"
grep -Fq 'const written = fs.writeSync(file.fd, buffer)' "$CLIENT"
grep -Fq 'written !== buffer.byteLength' "$CLIENT"
grep -Fq 'this.downloadIntegrityPolicy.error(status, received, total' "$CLIENT"
grep -Fq 'committed = integrityError.length === 0' "$CLIENT"
grep -Fq 'if (!committed) this.truncateTarget(targetUri)' "$CLIENT"
echo 'Download stream integrity integration tests passed'
