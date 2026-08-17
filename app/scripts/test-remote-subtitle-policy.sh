#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
POLICY="$APP_DIR/entry/src/main/ets/features/media/RemoteSubtitlePolicy.ets"
NETWORK_POLICY="$APP_DIR/entry/src/main/ets/features/media/RemoteSubtitleNetworkPolicy.ets"
MODELS="$APP_DIR/entry/src/main/ets/features/media/RemoteMediaModels.ets"
API="$APP_DIR/entry/src/main/ets/features/media/RemoteMediaApi.ets"
PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

cp "$POLICY" "$TEST_DIR/RemoteSubtitlePolicy.ts"
cp "$NETWORK_POLICY" "$TEST_DIR/RemoteSubtitleNetworkPolicy.ts"
cp "$MODELS" "$TEST_DIR/RemoteMediaModels.ts"
node "$TSC_BIN" "$TEST_DIR/RemoteSubtitlePolicy.ts" "$TEST_DIR/RemoteSubtitleNetworkPolicy.ts" \
  "$TEST_DIR/RemoteMediaModels.ts" \
  --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/remote_subtitle_policy_test.cjs" "$TEST_DIR/output/RemoteSubtitlePolicy.js"
node "$APP_DIR/tests/remote_subtitle_network_policy_test.cjs" "$TEST_DIR/output/RemoteSubtitleNetworkPolicy.js"

grep -Fq "this.subtitleNetworkPolicy.path(path)" "$API"
grep -Fq "this.resolvePlayback(source, safePath, cancellation)" "$API"
grep -Fq "this.subtitleNetworkPolicy.headerError(headers)" "$API"
grep -Fq "TextDecoder.create('utf-8', { fatal: true })" "$API"
grep -Fq "received > 1024 * 1024" "$API"
grep -Fq "this.mediaDirectoryItems = result.directoryItems" "$PAGE"
grep -Fq "revision !== this.mediaOpenRevision" "$PAGE"
grep -Fq "this.subtitlePolicy.findSidecars" "$PAGE"
grep -Fq "subtitleRevision !== this.mediaSubtitleRevision" "$PAGE"
grep -Fq "this.CycleRemoteSubtitle()" "$PAGE"
grep -Fq "this.subtitleParser.textAt" "$PAGE"

echo "Remote subtitle integration policy tests passed"
