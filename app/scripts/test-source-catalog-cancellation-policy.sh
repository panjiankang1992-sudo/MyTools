#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
engine="$root_dir/app/entry/src/main/ets/features/reader/BookSourceReaderEngine.ets"
page="$root_dir/app/entry/src/main/ets/pages/Index.ets"

grep -Fq 'loadCatalog(source: BookSource, bookUrl: string,' "$engine"
grep -Fq 'const detail = await this.fetch(source, bookUrl, cancellation);' "$engine"
grep -Fq 'this.urlResolver.resolveRequest(detail.url, rawTocUrl), cancellation);' "$engine"
grep -Fq 'private activeSourceCatalogCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq 'loadCatalog(source, book.resourceUri, cancellation)' "$page"
grep -Fq 'if (cancellation.isCancelled()) return;' "$page"

cancel_count="$(grep -Fc 'this.activeSourceCatalogCancellation?.cancel();' "$page")"
if [[ "$cancel_count" -lt 6 ]]; then
  echo 'catalog cancellation must cover detail replacement, source switch, reader open, close, and teardown' >&2
  exit 1
fi

echo 'source catalog cancellation policy tests passed'
