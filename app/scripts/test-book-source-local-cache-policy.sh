#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$APP_DIR/entry/src/main/ets/features/reader/BookSourceLocalCachePolicy.ets" \
  "$TEST_DIR/BookSourceLocalCachePolicy.ts"
cp "$APP_DIR/entry/src/main/ets/features/reader/ReaderModels.ets" "$TEST_DIR/ReaderModels.ts"
node "$TSC_BIN" "$TEST_DIR/BookSourceLocalCachePolicy.ts" "$TEST_DIR/ReaderModels.ts" \
  --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/book_source_local_cache_policy_test.cjs" \
  "$TEST_DIR/output/BookSourceLocalCachePolicy.js"

PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"
CACHE="$APP_DIR/entry/src/main/ets/features/reader/BookSourceLocalCache.ets"
grep -Fq 'private readonly readerChapterPrefetchCount: number = 20;' "$PAGE"
grep -Fq 'this.bookSourceLocalCache.catalog(context, owner' "$PAGE"
grep -Fq 'this.bookSourceLocalCache.chapter(context, this.localAccountScope' "$PAGE"
grep -Fq 'this.bookSourceLocalCache.putChapter(context, this.localAccountScope' "$PAGE"
grep -Fq 'this.bookSourceLocalCache.clearBook(context, this.localAccountScope' "$PAGE"
grep -Fq 'chapter.content.trim().length > 0' "$PAGE"
grep -Fq 'this.RefreshSourceCatalogCache(book, source, context, owner);' "$PAGE"
grep -Fq "ROOT_NAME: string = 'reader-source-cache-v1'" "$CACHE"
grep -Fq 'MAX_CHAPTER_FILES_PER_BOOK: number = 64' "$CACHE"
grep -Fq 'async clearBook(context: common.UIAbilityContext' "$CACHE"
grep -Fq 'fs.rmdirSync(directory);' "$CACHE"
grep -Fq '/^account:v2:([a-f0-9]{64})$/' "$CACHE"

echo 'Book source local cache integration policy tests passed'
