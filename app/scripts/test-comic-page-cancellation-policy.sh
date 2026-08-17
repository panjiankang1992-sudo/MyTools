#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
token="$root_dir/app/entry/src/main/ets/shared/network/DownloadCancellationToken.ets"
cache="$root_dir/app/entry/src/main/ets/shared/network/SafeRemoteCoverCache.ets"
engine="$root_dir/app/entry/src/main/ets/features/reader/BookSourceReaderEngine.ets"
page="$root_dir/app/entry/src/main/ets/pages/Index.ets"

grep -Fq 'private readonly handlers: Set<() => void>' "$token"
grep -Fq 'this.handlers.add(handler);' "$token"
grep -Fq 'this.handlers.delete(handler);' "$token"
grep -Fq 'Array.from(this.handlers.values()).forEach' "$token"

grep -Fq 'cancellation?.bind(cancelHandler);' "$cache"
grep -Fq 'cancellation?.unbind(cancelHandler);' "$cache"
grep -Fq 'if (cancellation?.isCancelled() || streamError.length > 0' "$cache"
grep -Fq 'cacheReaderPage(context, url, headers, cancellation)' "$engine"
grep -Fq 'cacheComicPages(context, source, urls, cancellation)' "$page"
grep -Fq 'private activeComicPageCancellation?: DownloadCancellationToken = undefined;' "$page"

cancel_count="$(grep -Fc 'this.activeComicPageCancellation?.cancel();' "$page")"
if [[ "$cancel_count" -lt 3 ]]; then
  echo 'comic cancellation must cover chapter replacement, reader close, and page teardown' >&2
  exit 1
fi

echo 'comic page cancellation policy tests passed'
