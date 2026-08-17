#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
POLICY="$APP_DIR/entry/src/main/ets/features/media/MediaPlaybackRecoveryPolicy.ets"
PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

cp "$POLICY" "$TEST_DIR/MediaPlaybackRecoveryPolicy.ts"
node "$TSC_BIN" "$TEST_DIR/MediaPlaybackRecoveryPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/media_playback_recovery_policy_test.cjs" \
  "$TEST_DIR/output/MediaPlaybackRecoveryPolicy.js"

grep -Fq 'private readonly mediaRecoveryPolicy: MediaPlaybackRecoveryPolicy' "$PAGE"
grep -Fq 'private async OpenRemoteMedia(item: RemoteMediaItem, resumeOverrideMs?: number)' "$PAGE"
grep -Fq 'this.playerTimeMs, this.RestoredMediaPosition(), this.playerDurationMs' "$PAGE"
grep -Fq "this.mediaBufferState = '正在申请新播放票据…'" "$PAGE"
grep -Fq 'this.OpenRemoteMedia(item, recoveryPosition)' "$PAGE"
test "$(grep -Fc 'this.PersistMediaPlaybackProgress();' "$PAGE")" -ge 8
echo 'Media playback recovery integration tests passed'
