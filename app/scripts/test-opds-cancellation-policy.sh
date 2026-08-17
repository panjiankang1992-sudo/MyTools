#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
provider="$root_dir/app/entry/src/main/ets/features/reader/OpdsProvider.ets"
page="$root_dir/app/entry/src/main/ets/pages/Index.ets"

grep -Fq 'cancellation?: DownloadCancellationToken' "$provider"
grep -Fq "if (cancellation?.isCancelled()) throw new Error('OPDS目录加载已取消');" "$provider"
grep -Fq 'cancellation?.bind(cancelHandler);' "$provider"
grep -Fq 'cancellation?.unbind(cancelHandler);' "$provider"
grep -Fq 'sanitizeCoverUrls(catalog.books, cancellation)' "$provider"
grep -Fq 'CacheOpdsCovers(catalog.books, revision, owner, navigationRevision, cancellation)' "$page"
grep -Fq 'remoteCoverUrl, headers, cancellation)' "$page"
grep -Fq 'if (cancellation.isCancelled()) return;' "$page"

grep -Fq 'private activeOpdsCatalogCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq 'loadCatalog(requestedUrl, authorization, cancellation)' "$page"
grep -Fq 'if (this.activeOpdsCatalogCancellation === cancellation)' "$page"

cancel_count="$(grep -Fc 'this.activeOpdsCatalogCancellation?.cancel();' "$page")"
if [[ "$cancel_count" -lt 5 ]]; then
  echo 'OPDS cancellation must cover navigation, credentials, logout, scope, and teardown' >&2
  exit 1
fi

echo 'OPDS cancellation policy tests passed'
