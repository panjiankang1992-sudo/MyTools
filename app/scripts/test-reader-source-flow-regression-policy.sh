#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
page="$root_dir/app/entry/src/main/ets/pages/Index.ets"
api="$root_dir/app/entry/src/main/ets/features/reader/BookSourceRuntimeReaderApi.ets"

grep -Fq 'index + this.readerChapterPrefetchCount' "$page"
grep -Fq 'this.readerContinuousEndIndex + this.readerChapterPrefetchCount' "$page"
grep -Fq 'this.readerChapters = this.readerChapters.slice();' "$page"
grep -Fq "this.readerStatus = '章节加载已中断，请点击重试';" "$page"
grep -Fq '阅读方向切换会触发页面可见性回调' "$page"
grep -Fq 'this.currentBook?.id === book.id' "$page"
if sed -n '/if (catalog === undefined) {/,/const cancellation = new DownloadCancellationToken();/p' "$page" |
  grep -Fq 'sourceUrlPolicy.assertSafe'; then
  echo 'backend-executed source catalogs must not block on local DNS validation' >&2
  exit 1
fi
grep -Fq "if (book.format !== 'unknown') {" "$page"
grep -Fq '规则型书源由后端执行，避免Asset Store阻塞打开流程' "$page"
grep -Fq 'Scroll(this.sourceSearchScroller)' "$page"
grep -Fq '.onReachEnd(() => this.LoadMoreBookSourceSearchResults())' "$page"
grep -Fq 'this.sourceSearchScroller.currentOffset().yOffset' "$page"
grep -Fq 'cancellation?: DownloadCancellationToken' "$api"
grep -Fq "postJson('/api/app/v1/reader/source-runtime/catalog', request," "$api"

echo 'reader source flow regression policy tests passed'
