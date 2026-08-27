#!/usr/bin/env bash
set -euo pipefail

app_dir="$(cd "$(dirname "$0")/.." && pwd)"
page="$app_dir/entry/src/main/ets/pages/Index.ets"
api="$app_dir/entry/src/main/ets/features/media/RemoteMediaApi.ets"

preview="$(sed -n '/private MediaPreviewCard(/,/^  }/p' "$page")"
gallery="$(sed -n '/private MediaCatalogGalleryItem(/,/^  }/p' "$page")"
sheet="$(sed -n '/private MediaActionSheet(/,/^  }/p' "$page")"
execute="$(sed -n '/private async ExecuteMediaAction(/,/^  }/p' "$page")"
refresh="$(sed -n '/private async RefreshMediaAfterMutation(/,/^  }/p' "$page")"
projection="$(sed -n '/private RemoveMediaMutationProjection(/,/^  }/p' "$page")"
tags="$(sed -n '/private MediaCatalogItemTags(/,/^  }/p' "$page")"
action_button="$(sed -n '/private MediaCatalogActionButton(/,/^  }/p' "$page")"

printf '%s\n' "$preview" | grep -Fq 'LongPressGesture({ repeat: false, duration: 500 })'
printf '%s\n' "$preview" | grep -Fq '.onAction(() => this.OpenMediaActionSheet(item))'
printf '%s\n' "$gallery" | grep -Fq '.onClick(() => this.TapMediaCatalogItem(item, false))'
printf '%s\n' "$gallery" | grep -Fq 'this.MediaCatalogActionButton(item)'
printf '%s\n' "$action_button" | grep -Fq '.onClick(() => this.OpenMediaCatalogActionSheet(item))'
printf '%s\n' "$tags" | grep -Fq '.onClick(() => this.SelectMediaCatalogItemTag(tag))'
grep -Fq 'private SelectMediaCatalogItemTag(tag: string): void' "$page"
if grep -Fq 'mediaCatalogTapSuppressedUntil' "$page"; then
  echo 'Obsolete media tap suppression remains' >&2
  exit 1
fi
if printf '%s\n' "$gallery" | grep -Fq 'LongPressGesture'; then
  echo 'Gallery preview still competes with a long-press gesture' >&2
  exit 1
fi
for action in 重命名 移动到 管理标签 查看详情 删除 取消; do
  printf '%s\n' "$sheet" | grep -Fq "'$action'" || {
    echo "Missing long-press media action: $action" >&2
    exit 1
  }
done
printf '%s\n' "$sheet" | grep -Fq "this.mediaActionMode = 'delete-confirm'"
printf '%s\n' "$sheet" | grep -Fq "Text('删除后无法恢复，确认删除这个远程媒体？')"
printf '%s\n' "$sheet" | grep -Fq '.enabled(!this.mediaActionBusy)'
printf '%s\n' "$execute" | grep -Fq 'await api.renameEntry('
printf '%s\n' "$execute" | grep -Fq 'await api.moveEntry('
printf '%s\n' "$execute" | grep -Fq 'await api.replaceTags('
printf '%s\n' "$execute" | grep -Fq 'await api.deleteEntry(accountId, item.path, false);'
printf '%s\n' "$execute" | grep -Fq 'this.RemoveMediaMutationProjection(item);'
printf '%s\n' "$execute" | grep -Fq 'await this.RefreshMediaAfterMutation(item);'
printf '%s\n' "$projection" | grep -Fq 'this.mediaCatalogItems = this.mediaCatalogItems.filter'
printf '%s\n' "$projection" | grep -Fq 'this.mediaVideoDirectoryItems = this.mediaVideoDirectoryItems.filter'
printf '%s\n' "$refresh" | grep -Fq 'await this.LoadMediaCatalog();'
printf '%s\n' "$refresh" | grep -Fq '.videoDirectoryItems(directoryId, this.hideAdultContent, cancellation);'
printf '%s\n' "$refresh" | grep -Fq 'await this.LoadMediaDirectory();'
grep -Fq 'await this.client.delete(`/api/localfiles/${this.localFileId(path)}`);' "$api"
grep -Fq "accountId === 'library' || accountId === 'ebook-library'" "$api"
grep -Fq 'await this.client.delete(`/api/app/v1/media/items/${this.libraryMediaId(path)}`);' "$api"

echo 'Media action sheet integration policy tests passed'
