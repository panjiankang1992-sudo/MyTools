#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
page="$root_dir/app/entry/src/main/ets/pages/Index.ets"
api="$root_dir/app/entry/src/main/ets/features/media/MediaCatalogApi.ets"

grep -Fq 'private static readonly MAX_LIBRARY_ITEMS: number = 10000;' "$api"
grep -Fq "this.client.get('/api/app/v1/media/catalog'" "$api"
grep -Fq 'private async allCatalogItems' "$api"
if sed -n '/async gallery(/,/^  }/p' "$api" | grep -Fq 'allLibraryItems'; then
  echo 'gallery filtering must run in media-library-service' >&2
  exit 1
fi
grep -Fq 'private mediaCatalogThumbnailRevision: number = 0;' "$page"
grep -Fq 'this.activeMediaCatalogThumbnailCancellation?.cancel();' "$page"
grep -Fq '标签筛选后先恢复磁盘缓存' "$page"
grep -Fq 'revision !== this.mediaCatalogThumbnailRevision' "$page"
grep -Fq 'const source = this.IsCatalogMediaPath(item.path) ? this.CatalogLocalMediaSource() : configuredSource;' "$page"
grep -Fq "SymbolGlyph(\$r('sys.symbol.ellipsis_circle')).fontSize(16)" "$page"
if grep -Fq ".backgroundColor('#B30F172A')" <(sed -n '/private MediaCatalogActionButton(/,/^  }/p' "$page"); then
  echo 'media gallery action button must not render as a black thumbnail overlay' >&2
  exit 1
fi

echo 'media gallery regression policy tests passed'
