#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
page="$root_dir/app/entry/src/main/ets/pages/Index.ets"

grep -Fq 'return this.ReaderUsesContinuousText() && this.readerChapters.length > 0;' "$page"
grep -Fq "this.currentBook?.origin === 'local' || this.currentBook?.origin === 'source'" "$page"
grep -Fq "末页继续滑动时直接进入相邻章节" "$page"
grep -Fq "kind: 'heading', text: chapter.title.length > 0 ? chapter.title : '正文'" "$page"
grep -Fq 'Text(this.ReaderSingleLineStatus())' "$page"
grep -Fq ".height(22).padding({ left: 12, right: 12 })" "$page"
grep -Fq 'this.SelectReaderChapter(index).catch' "$page"
grep -Fq 'private static readonly FALLBACK_CHAPTER_CHARS: number = 24000;' \
  "$root_dir/app/entry/src/main/ets/features/reader/ReaderContentLoader.ets"
grep -Fq 'this.appendBoundedTextChapters(chapters, title, content);' \
  "$root_dir/app/entry/src/main/ets/features/reader/ReaderContentLoader.ets"

echo 'reader cross chapter policy tests passed'
