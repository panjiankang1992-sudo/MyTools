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
grep -Fq "private mediaCatalogFilterContext: string = '';" "$page"
grep -Fq 'private async LoadMediaCatalog(refreshFilters: boolean = false): Promise<void>' "$page"
grep -Fq 'this.mediaCatalogFilterContext !== filterContext' "$page"
grep -Fq 'await this.LoadMediaCatalog(true);' "$page"
grep -Fq 'if (selectedDirectory !== undefined) this.mediaCatalogTotal = selectedDirectory.fileCount;' "$page"
grep -Fq "if (this.mediaCatalogMode === 'gallery') this.mediaCatalogItems = [];" "$page"
grep -Fq 'this.activeMediaCatalogThumbnailCancellation?.cancel();' "$page"
grep -Fq '标签筛选后先恢复磁盘缓存' "$page"
grep -Fq 'revision !== this.mediaCatalogThumbnailRevision' "$page"
grep -Fq 'Text(`${this.MediaCatalogDirectoryCount(directory)} 个`)' "$page"
grep -Fq 'return this.mediaCatalogDirectoryId === directory.directoryId ? this.mediaCatalogTotal : directory.fileCount;' "$page"
if sed -n '/private MediaCatalogItemTags(/,/^  }/p' "$page" | grep -Fq "}.width('100%').padding(8)"; then
  echo 'media tag overlay must not cover the whole thumbnail click area' >&2
  exit 1
fi
grep -Fq 'const source = this.IsCatalogMediaPath(item.path) ? this.CatalogLocalMediaSource() : configuredSource;' "$page"
grep -Fq "if (this.mediaOpening && this.mediaOpeningPath === item.path) return;" "$page"
grep -Fq "this.mediaOpeningPath = item.path;" "$page"
grep -Fq "this.mediaOpeningPath = '';" "$page"
grep -Fq "SymbolGlyph(\$r('sys.symbol.ellipsis_circle')).fontSize(16)" "$page"
if grep -Fq ".backgroundColor('#B30F172A')" <(sed -n '/private MediaCatalogActionButton(/,/^  }/p' "$page"); then
  echo 'media gallery action button must not render as a black thumbnail overlay' >&2
  exit 1
fi

echo 'media gallery regression policy tests passed'
