#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT

cp "$APP_DIR/entry/src/main/ets/features/media/MediaPlaybackHistoryPolicy.ets" "$TEST_DIR/MediaPlaybackHistoryPolicy.ts"
node "$TSC_BIN" "$TEST_DIR/MediaPlaybackHistoryPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/media_playback_history_policy_test.cjs" "$TEST_DIR/output/MediaPlaybackHistoryPolicy.js"

SOURCE="$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq 'private PersistMediaPlaybackProgress(): void' "$SOURCE"
grep -Fq 'private RestoreMediaPlaybackProgress(value: string): void' "$SOURCE"
grep -Fq 'this.mediaHistoryPolicy.normalize' "$SOURCE"
grep -Fq 'this.OpenMediaHistory(progress)' "$SOURCE"
echo 'Media playback history integration policy tests passed'
