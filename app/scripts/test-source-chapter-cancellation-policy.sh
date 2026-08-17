#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
engine="$root_dir/app/entry/src/main/ets/features/reader/BookSourceReaderEngine.ets"
page="$root_dir/app/entry/src/main/ets/pages/Index.ets"

grep -Fq 'loadChapter(source: BookSource, chapter: ReaderChapter,' "$engine"
grep -Fq 'const response = await this.fetch(source, chapter.resourceUri, cancellation);' "$engine"
grep -Fq "if (cancellation?.isCancelled()) throw new Error('书源章节加载已取消');" "$engine"
grep -Fq 'cancellation?.bind(cancelHandler);' "$engine"
grep -Fq 'cancellation?.unbind(cancelHandler);' "$engine"

grep -Fq 'private activeSourceChapterCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq 'loadChapter(source, chapter, cancellation)' "$page"
grep -Fq 'if (cancellation.isCancelled()) return;' "$page"
grep -Fq 'if (this.activeSourceChapterCancellation === cancellation)' "$page"

cancel_count="$(grep -Fc 'this.activeSourceChapterCancellation?.cancel();' "$page")"
if [[ "$cancel_count" -lt 3 ]]; then
  echo 'source chapter cancellation must cover replacement, reader close, and page teardown' >&2
  exit 1
fi

echo 'source chapter cancellation policy tests passed'
