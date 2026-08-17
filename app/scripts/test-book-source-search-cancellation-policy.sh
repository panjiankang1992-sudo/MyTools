#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
engine="$root_dir/app/entry/src/main/ets/features/reader/BookSourceSearchEngine.ets"
page="$root_dir/app/entry/src/main/ets/pages/Index.ets"

grep -Fq 'cancellation?: DownloadCancellationToken' "$engine"
grep -Fq 'cancellation?.bind(cancelHandler);' "$engine"
grep -Fq 'cancellation?.unbind(cancelHandler);' "$engine"
grep -Fq 'sanitizeCoverUrls(results, cancellation)' "$engine"
grep -Fq 'if (cancellation?.isCancelled()) return { results: [] };' "$engine"

grep -Fq 'private activeBookSourceSearchCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq 'this.activeBookSourceSearchCancellation?.cancel();' "$page"
grep -Fq 'this.activeBookSourceSearchCancellation = undefined;' "$page"
grep -Fq 'CacheSourceSearchCovers(results, cancellation)' "$page"
grep -Fq 'remoteCoverUrl, headers, cancellation)' "$page"

echo 'book source search cancellation policy tests passed'
