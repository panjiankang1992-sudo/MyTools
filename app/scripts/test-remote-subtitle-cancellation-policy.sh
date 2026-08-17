#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
api="$root_dir/app/entry/src/main/ets/features/media/RemoteMediaApi.ets"
page="$root_dir/app/entry/src/main/ets/pages/Index.ets"

grep -Fq 'cancellation?: DownloadCancellationToken' "$api"
grep -Fq "if (cancellation?.isCancelled()) throw new Error('远程字幕加载已取消');" "$api"
grep -Fq 'cancellation?.bind(cancelHandler);' "$api"
grep -Fq 'cancellation?.unbind(cancelHandler);' "$api"
grep -Fq 'if (cancellation?.isCancelled() || streamError.length > 0) return;' "$api"

grep -Fq 'private activeSubtitleCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq 'this.activeSubtitleCancellation?.cancel();' "$page"
grep -Fq 'api.loadSubtitle(source, subtitle.path, cancellation)' "$page"
grep -Fq 'if (this.activeSubtitleCancellation === cancellation) this.activeSubtitleCancellation = undefined;' "$page"

cancel_site_count="$(grep -Fc 'this.activeSubtitleCancellation?.cancel();' "$page")"
if [[ "$cancel_site_count" -lt 3 ]]; then
  echo 'subtitle cancellation must cover replacement, viewer close, and page teardown' >&2
  exit 1
fi

echo 'remote subtitle cancellation policy tests passed'
