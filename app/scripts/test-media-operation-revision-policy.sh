#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
POLICY="$APP_DIR/entry/src/main/ets/features/media/MediaOperationRevisionPolicy.ets"
PLAYER="$APP_DIR/entry/src/main/ets/features/media/RemoteMediaPlayer.ets"

cp "$POLICY" "$TEST_DIR/MediaOperationRevisionPolicy.ts"
node "$TSC_BIN" "$TEST_DIR/MediaOperationRevisionPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/media_operation_revision_policy_test.cjs" \
  "$TEST_DIR/output/MediaOperationRevisionPolicy.js"

grep -Fq 'private readonly revisionPolicy: MediaOperationRevisionPolicy' "$PLAYER"
test "$(grep -Fc 'if (!this.currentPlayerEvent(player, revision)) return;' "$PLAYER")" -ge 7
grep -Fq 'this.prepareCurrentPlayer(player, revision)' "$PLAYER"
grep -Fq 'this.playCurrentPlayer(player, revision)' "$PLAYER"
grep -Fq 'this.player === player' "$PLAYER"
if grep -F 'this.player?.prepare()' "$PLAYER" >/dev/null; then
  echo 'Player event must not prepare an implicitly current replacement instance' >&2
  exit 1
fi
echo 'Media operation revision integration tests passed'
