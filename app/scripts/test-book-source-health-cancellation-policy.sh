#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
engine="$root_dir/app/entry/src/main/ets/features/reader/BookSourceSearchEngine.ets"
page="$root_dir/app/entry/src/main/ets/pages/Index.ets"

grep -Fq 'async checkHealth(source: BookSource, keyword: string,' "$engine"
grep -Fq 'cancellation?: DownloadCancellationToken' "$engine"
grep -Fq 'if (cancellation?.isCancelled()) return;' "$engine"
grep -Fq 'searchSource(source, this.inputPolicy.keyword(keyword.trim().length > 0 ?' "$engine"
grep -Fq "keyword : '测试'), 1," "$engine"

grep -Fq 'private activeBookSourceHealthCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq 'checkHealth(source, keyword, cancellation)' "$page"
grep -Fq 'if (cancellation.isCancelled()) return;' "$page"
grep -Fq 'this.activeBookSourceHealthCancellation?.cancel();' "$page"
grep -Fq 'this.sourceHealthLoading = false;' "$page"

echo 'book source health cancellation policy tests passed'
