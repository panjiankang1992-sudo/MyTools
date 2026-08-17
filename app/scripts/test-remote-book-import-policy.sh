#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
POLICY="$APP_DIR/entry/src/main/ets/features/reader/RemoteBookImportPolicy.ets"
MODELS="$APP_DIR/entry/src/main/ets/features/reader/ReaderModels.ets"
MEDIA_MODELS="$APP_DIR/entry/src/main/ets/features/media/RemoteMediaModels.ets"
DISPLAY_POLICY="$APP_DIR/entry/src/main/ets/features/reader/RemoteBookDisplayMetadataPolicy.ets"
PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

mkdir -p "$TEST_DIR/reader" "$TEST_DIR/media"
cp "$POLICY" "$TEST_DIR/reader/RemoteBookImportPolicy.ts"
cp "$DISPLAY_POLICY" "$TEST_DIR/reader/RemoteBookDisplayMetadataPolicy.ts"
cp "$MODELS" "$TEST_DIR/reader/ReaderModels.ts"
cp "$MEDIA_MODELS" "$TEST_DIR/media/RemoteMediaModels.ts"
node "$TSC_BIN" "$TEST_DIR/reader/RemoteBookImportPolicy.ts" "$TEST_DIR/reader/RemoteBookDisplayMetadataPolicy.ts" \
  "$TEST_DIR/reader/ReaderModels.ts" \
  "$TEST_DIR/media/RemoteMediaModels.ts" --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/remote_book_import_policy_test.cjs" "$TEST_DIR/output/reader/RemoteBookImportPolicy.js"

grep -Fq "this.remoteBookImportPolicy.candidate(item, source)" "$PAGE"
grep -Fq 'this.remoteBookSources.find((value: RemoteMediaSource)' "$PAGE"
grep -Fq '.listBookSources(sourceCancellation);' "$PAGE"
grep -Fq 'selectedRemoteBookSourceId' "$PAGE"
grep -Fq 'private async LoadMoreRemoteBookDirectory(): Promise<void>' "$PAGE"
grep -Fq 'this.remoteBookLoadedCount < directory.total' "$PAGE"
grep -Fq 'this.LoadMoreRemoteBookDirectory();' "$PAGE"
grep -Fq 'id: `remote:sha256:${digest}`' "$PAGE"
grep -Fq "generation !== this.readerProgressSyncGeneration" "$PAGE"
grep -Fq 'this.OpenRemoteBookDetail(item);' "$PAGE"
grep -Fq 'this.OpenOpdsBookDetail(entry)' "$PAGE"
grep -Fq 'this.OpenBookDetail(book, entry.summary);' "$PAGE"
if grep -Fq 'this.AddRemoteBook(item);' "$PAGE" || grep -Fq 'this.AddOpdsBook(entry)' "$PAGE"; then
  echo "Remote book import integration policy failed: list rows must open details before shelf mutation" >&2
  exit 1
fi

echo "Remote book import integration policy tests passed"
