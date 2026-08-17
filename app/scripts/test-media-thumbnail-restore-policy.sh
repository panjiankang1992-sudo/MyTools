#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
SOURCE="$APP_DIR/entry/src/main/ets/features/media/RemoteMediaThumbnailRestorePolicy.ets"
PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

cp "$SOURCE" "$TEST_DIR/RemoteMediaThumbnailRestorePolicy.ts"
node "$TSC_BIN" "$TEST_DIR/RemoteMediaThumbnailRestorePolicy.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/remote_media_thumbnail_restore_policy_test.cjs" \
  "$TEST_DIR/output/RemoteMediaThumbnailRestorePolicy.js"

grep -Fq 'this.mediaBrowseViewerSnapshotEntries = this.mediaBrowseThumbnailEntries.slice();' "$PAGE"
grep -Fq 'this.RestoreMediaBrowseThumbnailsAfterViewer();' "$PAGE"
grep -Fq 'this.mediaThumbnailRestorePolicy.restore(validPaths' "$PAGE"
grep -Fq 'this.mediaThumbnailRestorePolicy.missing(validPaths, restored)' "$PAGE"

echo 'Remote media thumbnail restore integration tests passed'
