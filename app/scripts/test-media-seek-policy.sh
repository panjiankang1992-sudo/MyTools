#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
POLICY="$APP_DIR/entry/src/main/ets/features/media/MediaSeekPolicy.ets"
PLAYER="$APP_DIR/entry/src/main/ets/features/media/RemoteMediaPlayer.ets"
PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

cp "$POLICY" "$TEST_DIR/MediaSeekPolicy.ts"
node "$TSC_BIN" "$TEST_DIR/MediaSeekPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/media_seek_policy_test.cjs" "$TEST_DIR/output/MediaSeekPolicy.js"

grep -Fq 'private readonly seekPolicy: MediaSeekPolicy' "$PLAYER"
grep -Fq 'this.seekPolicy.absolute(timeMs, this.durationMs)' "$PLAYER"
grep -Fq 'this.seekPolicy.offset(this.lastTimeMs, offsetMs, this.durationMs)' "$PLAYER"
grep -Fq "ownedSession.on('fastForward', (time?: number) =>" "$PLAYER"
grep -Fq 'this.seekOffset(time ?? 10000)' "$PLAYER"
grep -Fq 'private readonly mediaSeekPolicy: MediaSeekPolicy' "$PAGE"
grep -Fq 'this.SeekVideoTo(this.currentMediaResumeMs)' "$PAGE"
grep -Fq 'this.SeekAudioBy(-10000)' "$PAGE"
grep -Fq 'this.mediaSeekPolicy.offset(this.playerTimeMs, offsetMs, this.playerDurationMs)' "$PAGE"
echo 'Media seek integration policy tests passed'
