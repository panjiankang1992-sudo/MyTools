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
grep -A4 -F 'private CloseReader(): void {' "$SOURCE" | grep -Fq 'this.readerSearchRevision++;'
grep -A5 -F 'private CloseReader(): void {' "$SOURCE" | grep -Fq 'this.readerSearchBusy = false;'

echo "Reader search revision integration tests passed"
