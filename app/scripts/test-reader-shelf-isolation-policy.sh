#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"
PICKER="$APP_DIR/entry/src/main/ets/features/reader/LocalBookPicker.ets"
IMPORT_API="$APP_DIR/entry/src/main/ets/features/reader/EbookImportApi.ets"

fail() {
  echo "Reader shelf isolation policy failed: $1" >&2
  exit 1
}

for pattern in \
  "book.origin === 'source'" \
  "book.origin === 'remote'" \
  "book.origin === 'local'" \
  "if (this.bookModeIndex === 1) return this.ebookTransferBusy ? '上传中' : '上传书籍';" \
  "return '添加书籍';" \
  'private async UploadSelectedBooksToRemote(): Promise<void>' \
  'private async UploadDetailBookToRemote(): Promise<void>' \
  'private async LoadRemoteBookProgress(' \
  'await this.RestoreRemoteBookProgress(book, generation, accountScope);' \
  'Text(`${this.RemoteBookProgress(item)}%`)' \
  "source.localDirectoryType === 'EBOOK'" \
  '.parallelGesture(TapGesture({ count: 1, distanceThreshold: 6 })' \
  'private readerScrollContinuationBlockedUntil: number = 0;' \
  'return estimatedLineCount * this.ReaderScaledFontSize(this.readerSettings.fontSize)' \
  "return '';"; do
  grep -Fq "$pattern" "$PAGE" || fail "missing: $pattern"
done

books_page="$(sed -n '/private BooksPage()/,/^  }/p' "$PAGE")"
! grep -Fq 'this.RemoteBookSourceSelector()' <<<"$books_page" ||
  fail "remote shelf must not expose source or OPDS selection"
! grep -Fq "BookActionCard('从本地添加'" <<<"$books_page" ||
  fail "local shelf must not keep the duplicate add card"

reader_controls="$(sed -n '/private ReaderControls()/,/^  }/p' "$PAGE")"
! grep -Fq 'this.ReaderPreviousLabel()' <<<"$reader_controls" ||
  fail "reader controls must not render previous-page action"
! grep -Fq 'this.ReaderNextLabel()' <<<"$reader_controls" ||
  fail "reader controls must not render next-page action"

grep -Fq 'options.maxSelectNumber = 50;' "$PICKER" ||
  fail "local picker must allow multiple books"
grep -Fq '/api/ebooks/import/upload' "$IMPORT_API" ||
  fail "remote upload must use the backend ebook import endpoint"
grep -Fq '/api/ebooks/import/source' "$IMPORT_API" ||
  fail "source download must use the backend asynchronous import endpoint"

echo 'Reader shelf isolation policy tests passed'
