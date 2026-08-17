#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PLAYER="$APP_DIR/entry/src/main/ets/features/media/RemoteMediaPlayer.ets"

grep -Fq 'private sessionRevision: number = 0' "$PLAYER"
grep -Fq 'private backgroundRevision: number = 0' "$PLAYER"
grep -Fq 'this.startSystemSession(context, title,' "$PLAYER"
grep -Fq 'this.currentSystemSession(ownedSession, revision)' "$PLAYER"
test "$(grep -Fc 'if (this.currentSystemSession(ownedSession, revision))' "$PLAYER")" -ge 8
grep -Fq 'private async stopSystemSession(expectedRevision?: number)' "$PLAYER"
grep -Fq 'const ownsSession = expectedRevision === undefined || this.sessionRevision === expectedRevision' "$PLAYER"
grep -Fq 'const ownsBackground = expectedRevision === undefined || this.backgroundRevision === expectedRevision' "$PLAYER"
grep -Fq 'await this.stopSystemSession(revision)' "$PLAYER"
if grep -F "session.on('play', () => this.playFromSystem())" "$PLAYER" >/dev/null; then
  echo 'AVSession callback must be revision guarded' >&2
  exit 1
fi
echo 'Media AVSession revision integration tests passed'
