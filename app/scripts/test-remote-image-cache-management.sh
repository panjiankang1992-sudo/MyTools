#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"
COVER="$APP_DIR/entry/src/main/ets/shared/network/SafeRemoteCoverCache.ets"
MEDIA="$APP_DIR/entry/src/main/ets/features/media/RemoteMediaThumbnailCache.ets"

grep -Fq 'size(context: common.UIAbilityContext): number' "$COVER"
grep -Fq 'readerPageSize(context: common.UIAbilityContext): number' "$COVER"
grep -Fq 'size(context: common.UIAbilityContext): number' "$MEDIA"
grep -Fq 'if (this.active.size > 0 || this.activeOwnedCount > 0)' "$MEDIA"
grep -Fq "throw new Error('远程媒体缩略图仍在生成，请稍后重试')" "$MEDIA"
grep -Fq 'this.remoteCoverCache.size(context)' "$PAGE"
grep -Fq 'this.remoteCoverCache.readerPageSize(context)' "$PAGE"
grep -Fq 'this.mediaThumbnailCache.size(context)' "$PAGE"
grep -Fq 'this.remoteCoverCache.clear(context) + this.remoteCoverCache.clearReaderPages(context) +' "$PAGE"
grep -Fq 'this.mediaThumbnailCache.clear(context)' "$PAGE"
grep -Fq 'this.mediaThumbnailEntries = []' "$PAGE"
grep -Fq "this.ProfileInfoRow('书源漫画页'" "$PAGE"
grep -Fq "this.ProfileInfoRow('媒体缩略图'" "$PAGE"
grep -Fq "Button('清理全部远程图片缓存')" "$PAGE"
echo 'Remote image cache management integration tests passed'
