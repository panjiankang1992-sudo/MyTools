#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
POLICY="$APP_DIR/entry/src/main/ets/features/media/RemoteAudioQueuePolicy.ets"
MODELS="$APP_DIR/entry/src/main/ets/features/media/RemoteMediaModels.ets"
PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

cp "$POLICY" "$TEST_DIR/RemoteAudioQueuePolicy.ts"
cp "$MODELS" "$TEST_DIR/RemoteMediaModels.ts"
node "$TSC_BIN" "$TEST_DIR/RemoteAudioQueuePolicy.ts" "$TEST_DIR/RemoteMediaModels.ts" \
  --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/remote_audio_queue_policy_test.cjs" "$TEST_DIR/output/RemoteAudioQueuePolicy.js"

grep -Fq "if (state === 'completed')" "$PAGE"
grep -Fq "if (!this.audioLoopEnabled) this.ChangeRemoteAudio(1)" "$PAGE"
grep -Fq "this.queuedAudioNavigationDirection = direction" "$PAGE"
grep -Fq "this.ChangeRemoteAudio(queuedAudioDirection)" "$PAGE"
grep -Fq "this.AudioQueueItems()" "$PAGE"

echo "Remote audio queue integration policy tests passed"
