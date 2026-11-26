#!/usr/bin/env bash
set -euo pipefail

app_dir="$(cd "$(dirname "$0")/.." && pwd)"
page="$app_dir/entry/src/main/ets/pages/Index.ets"
api="$app_dir/entry/src/main/ets/features/media/RemoteMediaApi.ets"
controls="$app_dir/entry/src/main/ets/components/AppControls.ets"

preview="$(sed -n '/private MediaPreviewCard(/,/^  }/p' "$page")"
gallery="$(sed -n '/private MediaCatalogGalleryItem(/,/^  }/p' "$page")"
sheet="$(sed -n '/private MediaActionSheet(/,/^  }/p' "$page")"
execute="$(sed -n '/private async ExecuteMediaAction(/,/^  }/p' "$page")"
refresh="$(sed -n '/private async RefreshMediaAfterMutation(/,/^  }/p' "$page")"
projection="$(sed -n '/private RemoveMediaMutationProjection(/,/^  }/p' "$page")"
tags="$(sed -n '/private MediaCatalogItemTags(/,/^  }/p' "$page")"
action_button="$(sed -n '/private MediaCatalogActionButton(/,/^  }/p' "$page")"
menu_button="$(sed -n '/private MediaActionButton(/,/^  }/p' "$page")"
danger_button="$(sed -n '/private MediaActionDangerButton(/,/^  }/p' "$page")"
secondary_button="$(sed -n '/private MediaActionSecondaryButton(/,/^  }/p' "$page")"
close="$(sed -n '/private CloseMediaActionSheet(/,/^  }/p' "$page")"
action_group="$(sed -n '/export struct AppActionGroup {/,/^}/p' "$controls")"

printf '%s\n' "$preview" | grep -Fq 'LongPressGesture({ repeat: false, duration: 500 })'
printf '%s\n' "$preview" | grep -Fq '.onAction(() => this.OpenMediaActionSheet(item))'
printf '%s\n' "$gallery" | grep -Fq '.onClick(() => this.TapMediaCatalogItem(item, false))'
printf '%s\n' "$gallery" | grep -Fq 'action: () => this.OpenMediaCatalogActionSheet(item)'
printf '%s\n' "$action_button" | grep -Fq "AppIconButton({ icon: \$r('sys.symbol.ellipsis_circle')"
printf '%s\n' "$action_button" | grep -Fq 'action: () => this.OpenMediaCatalogActionSheet(item)'
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
# 菜单操作交由公共按钮统一尺寸，确认与取消必须共用等宽操作组。
test "$(printf '%s\n' "$sheet" | grep -Fc 'AppEqualActionRow()')" -eq 2
printf '%s\n' "$menu_button" | grep -Fq 'AppGroupedButton({ label: label,'
printf '%s\n' "$menu_button" | grep -Fq "variant: 'secondary', fullWidth: true"
printf '%s\n' "$menu_button" | grep -Fq 'action: action })'
printf '%s\n' "$danger_button" | grep -Fq "variant: 'danger'"
printf '%s\n' "$danger_button" | grep -Fq 'available: available'
printf '%s\n' "$danger_button" | grep -Fq 'action: action'
printf '%s\n' "$secondary_button" | grep -Fq "variant: 'secondary'"
printf '%s\n' "$secondary_button" | grep -Fq 'action: action'
printf '%s\n' "$sheet" | grep -Fq 'AppActionGroup({ primaryLabel: this.mediaActionBusy'
printf '%s\n' "$sheet" | grep -Fq "(this.mediaActionMode === 'delete-confirm' ? '确认删除' : '确认'), secondaryLabel: '取消'"
printf '%s\n' "$sheet" | grep -Fq "dangerous: this.mediaActionMode === 'delete-confirm', available: !this.mediaActionBusy"
printf '%s\n' "$sheet" | grep -Fq 'secondaryAvailable: !this.mediaActionBusy, loading: this.mediaActionBusy'
printf '%s\n' "$sheet" | grep -Fq 'primaryAction: () => this.ExecuteMediaAction(item), secondaryAction: () => this.CloseMediaActionSheet()'
printf '%s\n' "$action_group" | grep -Fq "variant: 'secondary', fullWidth: true"
printf '%s\n' "$action_group" | grep -Fq "available: this.secondaryAvailable"
printf '%s\n' "$action_group" | grep -Fq "variant: this.dangerous ? 'destructive' : 'primary'"
test "$(printf '%s\n' "$action_group" | grep -Fc 'AppGroupedButton(')" -eq 2
printf '%s\n' "$action_group" | grep -Fq 'AppEqualActionRow({ widthLimit:'
printf '%s\n' "$action_group" | grep -Fq 'AppTheme.groupMinWidth * 2 + 8'
grep -Fq '.enabled(this.available && !this.loading)' "$controls"
# 删除只在二次确认态执行，忙碌时既不能重复提交，也不能关闭正在执行的操作。
printf '%s\n' "$execute" | grep -Fq 'if (this.mediaActionBusy) return;'
printf '%s\n' "$close" | grep -Fq 'if (this.mediaActionBusy) return;'
printf '%s\n' "$execute" | grep -Fq "} else if (this.mediaActionMode === 'delete-confirm') {"
printf '%s\n' "$execute" | grep -Fq 'await api.renameEntry('
printf '%s\n' "$execute" | grep -Fq 'await api.moveEntry('
printf '%s\n' "$execute" | grep -Fq 'await api.replaceTags('
printf '%s\n' "$execute" | grep -Fq 'await api.deleteEntry(accountId, item.path, false);'
printf '%s\n' "$execute" | grep -Fq 'this.RemoveMediaMutationProjection(item);'
printf '%s\n' "$execute" | grep -Fq 'await this.RefreshMediaAfterMutation(item);'
printf '%s\n' "$projection" | grep -Fq 'this.mediaCatalogItems = this.mediaCatalogItems.filter'
printf '%s\n' "$projection" | grep -Fq 'this.mediaVideoDirectoryItems = this.mediaVideoDirectoryItems.filter'
printf '%s\n' "$refresh" | grep -Fq 'await this.LoadMediaCatalog(true);'
printf '%s\n' "$refresh" | grep -Fq 'this.mediaCatalogRootNeedsRefresh = this.selectedVideoDirectoryId.length > 0;'
printf '%s\n' "$refresh" | grep -Fq 'await this.LoadMediaDirectory();'
grep -Fq 'await this.client.delete(`/api/localfiles/${this.localFileId(path)}`);' "$api"
grep -Fq "accountId === 'library' || accountId === 'ebook-library'" "$api"
grep -Fq 'await this.client.delete(`/api/app/v1/media/items/${this.libraryMediaId(path)}`);' "$api"

echo 'Media action sheet integration policy tests passed'
