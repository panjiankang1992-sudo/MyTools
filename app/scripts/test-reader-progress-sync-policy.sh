#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
INDEX_SOURCE="$APP_DIR/entry/src/main/ets/pages/Index.ets"
API_SOURCE="$APP_DIR/entry/src/main/ets/features/reader/ReaderProgressApi.ets"
SERVER_REQUEST="$APP_DIR/../src/main/java/com/yuyutian/mytools/reader/model/SaveReadingProgressRequest.java"
MARKER_API="$APP_DIR/entry/src/main/ets/features/reader/ReaderMarkerApi.ets"
MARKER_REQUEST="$APP_DIR/../src/main/java/com/yuyutian/mytools/reader/model/SaveReaderMarkerRequest.java"
SHELF_API="$APP_DIR/entry/src/main/ets/features/reader/ShelfSyncApi.ets"
SHELF_NORMALIZER="$APP_DIR/entry/src/main/ets/features/reader/ShelfSyncResponseNormalizer.ets"
SOURCE_API="$APP_DIR/entry/src/main/ets/features/reader/BookSourceSyncApi.ets"
DATA_API="$APP_DIR/entry/src/main/ets/features/reader/ReaderDataApi.ets"

fail() {
  echo "Reader progress sync policy failed: $1" >&2
  exit 1
}

rg -q -F 'private pendingReaderProgress: ReadingProgress[] = [];' "$INDEX_SOURCE" ||
  fail "latest progress must survive an in-flight request"
rg -q -F '.filter((value: ReadingProgress) => value.bookId !== progress.bookId).concat(progress)' "$INDEX_SOURCE" ||
  fail "pending checkpoints must coalesce by book"
rg -q -F 'this.pendingReaderProgress = [progress].concat(this.pendingReaderProgress);' "$INDEX_SOURCE" ||
  fail "failed checkpoints must be retained for retry"
rg -q -F 'private ScheduleReaderProgressSave(): void {' "$INDEX_SOURCE" ||
  fail "page changes must schedule a near-real-time server checkpoint"
rg -q -F 'this.ClearScheduledReaderProgressSave();' "$INDEX_SOURCE" ||
  fail "background and close must flush without leaving a stale delayed checkpoint"
rg -q -F 'private readerProgressSyncGeneration: number = 0;' "$INDEX_SOURCE" ||
  fail "account and session changes must invalidate stale asynchronous results"
rg -q -F 'private readingProgressTombstones: ReadingProgressTombstone[] = [];' "$INDEX_SOURCE" ||
  fail "cleared progress must retain a cross-device deletion tombstone"
rg -q -F 'const localProgress = this.AllLocalReadingProgress();' "$INDEX_SOURCE" ||
  fail "login reconciliation must include offline records missing on the server"
rg -q -F 'this.PrepareReadingSyncForAccount(this.localAccountScope);' "$INDEX_SOURCE" ||
  fail "login must isolate pending progress by server-account scope"
rg -q -F "return \`sha256:\${await this.digestTool.digestText(bookId, 'SHA256')}\`;" "$API_SOURCE" ||
  fail "the client must not transmit raw local or remote book identifiers"
rg -q -F '@Pattern(regexp = "sha256:[a-f0-9]{64}")' "$SERVER_REQUEST" ||
  fail "the server must reject unhashed book identifiers"
rg -q -F 'private boolean deleted;' "$SERVER_REQUEST" ||
  fail "the progress contract must carry deletion tombstones"
rg -q -F '/api/app/v1/reader/markers' "$MARKER_API" ||
  fail "bookmarks and annotations must use the authenticated sync API"
rg -q -F 'deleted: boolean;' "$MARKER_API" ||
  fail "cross-device marker deletion needs a persisted tombstone"
rg -q -F "return \`sha256:\${await this.digestTool.digestText(bookId, 'SHA256')}\`;" "$MARKER_API" ||
  fail "markers must not transmit raw local or remote book identifiers"
rg -q -F '@Pattern(regexp = "sha256:[a-f0-9]{64}")' "$MARKER_REQUEST" ||
  fail "the marker endpoint must reject unhashed book identifiers"
rg -q -F "if (id.startsWith('local:')) throw new Error" "$SHELF_NORMALIZER" ||
  fail "device-local file identifiers must never enter shelf sync"
rg -q -F "this.books.filter((book: Book) => book.origin !== 'local')" "$INDEX_SOURCE" ||
  fail "login reconciliation must exclude local shelf entries"
rg -q -F 'private shelfBookTombstones: ShelfBookTombstone[] = [];' "$INDEX_SOURCE" ||
  fail "cross-device shelf removal needs a persisted tombstone"
rg -q -F '/api/app/v1/reader/sources' "$SOURCE_API" ||
  fail "book source snapshots must use the authenticated sync API"
rg -q -F 'this.bookSourceImporter.importJson(item.snapshotJson)' "$INDEX_SOURCE" ||
  fail "downloaded book sources must pass the current importer again"
rg -q -F 'this.PullBookSources();' "$INDEX_SOURCE" ||
  fail "login must restore sources before shelf and reading data"
rg -q -F "this.ProfileActionRow('阅读数据'" "$INDEX_SOURCE" ||
  fail "the profile page must expose reading sync state"
rg -q -F 'this.HandleSyncedBookSourceRemoval(item.sourceUrl);' "$INDEX_SOURCE" ||
  fail "remote source deletion must migrate affected shelf books"
rg -q -F 'this.InvalidateBookSourceOperation();' "$INDEX_SOURCE" ||
  fail "remote source changes must invalidate stale source operations"
rg -q -F '/api/app/v1/reader/data' "$DATA_API" ||
  fail "users need an authenticated reading-data deletion API"
rg -q -F "Button('删除阅读同步数据')" "$INDEX_SOURCE" ||
  fail "the privacy page must expose reading-data deletion"
rg -q -F '!this.ReaderSyncOperationActive()' "$INDEX_SOURCE" ||
  fail "destructive deletion must not race active synchronization requests"

echo 'Reader progress sync policy tests passed'
