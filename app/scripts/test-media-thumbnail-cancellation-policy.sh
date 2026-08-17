#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
api="$root_dir/app/entry/src/main/ets/features/media/RemoteMediaApi.ets"
cache="$root_dir/app/entry/src/main/ets/features/media/RemoteMediaThumbnailCache.ets"
page="$root_dir/app/entry/src/main/ets/pages/Index.ets"
client="$root_dir/app/entry/src/main/ets/shared/network/AuthorizedApiClient.ets"

grep -Fq 'cancellation?: DownloadCancellationToken' "$api"
grep -Fq 'targetUri, cancellation);' "$api"
grep -Fq 'maxBytes, '\''image/jpeg'\'', cancellation);' "$client"
grep -Fq 'this.throwIfCancelled(cancellation);' "$client"
grep -Fq '有取消所有权的页面任务不共享Promise' "$cache"
grep -Fq 'private activeOwnedCount: number = 0;' "$cache"
grep -Fq 'this.active.size > 0 || this.activeOwnedCount > 0' "$cache"
grep -Fq 'util.generateRandomUUID(true)' "$cache"
grep -Fq '另一个同键任务可能已率先提交' "$cache"
grep -Fq "if (cancellation?.isCancelled()) throw new Error('远程图片预览加载已取消');" "$cache"
grep -Fq 'api.downloadThumbnail(accountId, path, temporary, cancellation)' "$cache"
grep -Fq 'api.downloadShareImage(accountId, path, temporary, cancellation)' "$cache"

grep -Fq 'private activeThumbnailCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq 'private activeBrowseThumbnailCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq 'private activeImageShareCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq 'target.path, cancellation)' "$page"
grep -Fq 'window[index].path, cancellation, true)' "$page"
grep -Fq 'if (cancellation.isCancelled()) return;' "$page"

thumbnail_cancel_count="$(grep -Fc 'this.activeThumbnailCancellation?.cancel();' "$page")"
browse_cancel_count="$(grep -Fc 'this.activeBrowseThumbnailCancellation?.cancel();' "$page")"
share_cancel_count="$(grep -Fc 'this.activeImageShareCancellation?.cancel();' "$page")"
if [[ "$thumbnail_cancel_count" -lt 3 || "$browse_cancel_count" -lt 3 || "$share_cancel_count" -lt 2 ]]; then
  echo 'image derivative cancellation must cover replacement, close, and teardown' >&2
  exit 1
fi

echo 'media thumbnail cancellation policy tests passed'
