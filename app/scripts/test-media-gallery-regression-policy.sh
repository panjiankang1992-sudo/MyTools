#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
page="$root_dir/app/entry/src/main/ets/pages/Index.ets"
api="$root_dir/app/entry/src/main/ets/features/media/MediaCatalogApi.ets"
controls="$root_dir/app/entry/src/main/ets/components/AppControls.ets"

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
grep -Fq "if (this.MediaCatalogUsesGrid()) this.mediaCatalogItems = [];" "$page"
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
# 独立管理入口复用公共图标按钮，保留完整命中区与媒体名称，避免重新覆盖预览。
action_button="$(sed -n '/private MediaCatalogActionButton(/,/^  }/p' "$page")"
icon_button="$(sed -n '/export struct AppIconButton {/,/^}/p' "$controls")"
test "$(grep -Fc 'private MediaCatalogActionButton(' "$page")" -eq 1
printf '%s\n' "$action_button" | grep -Fq "AppIconButton({ icon: \$r('sys.symbol.ellipsis_circle')"
printf '%s\n' "$action_button" | grep -Fq 'description: `管理媒体：${item.name}`'
printf '%s\n' "$action_button" | grep -Fq 'action: () => this.OpenMediaCatalogActionSheet(item)'
printf '%s\n' "$icon_button" | grep -Fq '.width(this.targetSize()).height(this.targetSize())'
printf '%s\n' "$icon_button" | grep -Fq '.accessibilityText(this.description)'
printf '%s\n' "$icon_button" | grep -Fq '.onClick(this.action)'
if grep -Fq ".backgroundColor('#B30F172A')" <<< "$action_button"; then
  echo 'media gallery action button must not render as a black thumbnail overlay' >&2
  exit 1
fi

python3 - "$page" <<'PY'
from pathlib import Path
import sys
source = Path(sys.argv[1]).read_text()
def builder(name):
    start = source.index('  private ' + name + '(')
    return source[start:source.index('\n  }', start)]
toolbar = builder('MediaCatalogToolbar')
row = toolbar.index('Row({ space: 6 })')
end = toolbar.index("}.width('100%').height(this.MediaCatalogControlHeight()).alignItems(VerticalAlign.Center)", row)
for control in ['AppSearchField(', "this.MediaCatalogFilterButton('directory')", "this.MediaCatalogFilterButton('tag')"]:
    assert row < toolbar.index(control) < end, 'Search, directory and tag must share one native row'
assert toolbar.index("this.MediaCatalogFilterButton('directory')") < toolbar.index("this.MediaCatalogFilterButton('tag')") < toolbar.index('AppSearchField('), 'Directory and tag must precede search'
assert toolbar.count(".height('100%')") == 2, 'Search field and its native slot must fill the shared height'
trigger = builder('MediaCatalogFilterButton')
assert '.width(68)' in trigger and '.flexShrink(0)' in trigger and '.fontSize(13)' in trigger
assert trigger.count(".height('100%')") == 2 and '.padding(0)' in trigger, 'Visible filter plate must fill the shared control height'
assert 'minHeight: 36' not in trigger and 'top: 6, bottom: 6' not in trigger, 'A shorter plate inside a tall hit area must not return'
assert 'radius: AppTheme.inputRadius' in trigger
for viewport in [320, 360, 390]:
    search_width = viewport - 40 - 68 * 2 - 6 * 2
    assert search_width >= 132, 'Compact filters must leave usable search width'
tags = builder('MediaCatalogItemTags')
assert '.fontSize(9).lineHeight(12)' in tags and '.fontColor(Color.White)' in tags
assert "}.width('100%')" not in tags, 'Tag overlay must only occupy the actual label width'
assert "top: 1, bottom: 1" in tags and "backgroundColor('#AD101815')" in tags
for name in ['MediaCatalogTopItem', 'MediaCatalogGalleryItem']:
    card = builder(name)
    assert card.index('this.MediaCatalogItemTags(item') < card.index('.clip(true)'), 'Tags must remain inside the clipped thumbnail'
    if name == 'MediaCatalogGalleryItem':
        assert 'Text(item.name)' not in card, 'Gallery filename must not occupy a separate row'
        assert card.index('OpenMediaCatalogActionSheet(item)') < card.index('.clip(true)'), 'Actions must stay inside the thumbnail'
        assert '.aspectRatio(this.MediaCatalogImageAspect(item))' in card
    else:
        assert card.index('this.MediaCatalogItemTags(item') < card.index('this.MediaCatalogActionButton(item)'), 'Tags remain on the preview'
for name in ['MediaCatalogSelectorSheet', 'MediaSelectorSheet', 'RemoteBookSourceSelectorSheet', 'DriveSelectorSheet']:
    assert 'AppSheetHeader(' in builder(name), 'Selection sheets must use bounded header slots'
PY

echo 'media gallery regression policy tests passed'
