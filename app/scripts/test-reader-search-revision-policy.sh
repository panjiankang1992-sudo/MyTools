#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

grep -Fq 'private readerSearchRevision: number = 0;' "$SOURCE"
grep -Fq 'const searchRevision = ++this.readerSearchRevision;' "$SOURCE"
grep -Fq 'const searchedBook = this.currentBook;' "$SOURCE"
grep -Fq 'const searchedLoader = this.readerContentLoader;' "$SOURCE"
grep -Fq 'searchRevision !== this.readerSearchRevision' "$SOURCE"
grep -Fq 'searchedBook !== this.currentBook' "$SOURCE"
grep -Fq 'searchedLoader !== this.readerContentLoader' "$SOURCE"
grep -Fq 'searchedLoader !== this.readerContentLoader || !this.readerVisible' "$SOURCE"
close_reader="$(sed -n '/private CloseReader(skipShelfPrompt: boolean = false)/,/^  }/p' "$SOURCE")"
grep -Fq 'this.readerSearchRevision++;' <<<"$close_reader"
grep -Fq 'this.readerSearchBusy = false;' <<<"$close_reader"

echo "Reader search revision integration tests passed"
