#!/usr/bin/env bash
set -euo pipefail

app_dir="$(cd "$(dirname "$0")/.." && pwd)"
page="$app_dir/entry/src/main/ets/pages/Index.ets"
api="$app_dir/entry/src/main/ets/features/media/RemoteMediaApi.ets"

preview="$(sed -n '/private MediaPreviewCard(/,/^  }/p' "$page")"
sheet="$(sed -n '/private MediaActionSheet(/,/^  }/p' "$page")"
execute="$(sed -n '/private async ExecuteMediaAction(/,/^  }/p' "$page")"

printf '%s\n' "$preview" | grep -Fq 'LongPressGesture({ repeat: false, duration: 500 })'
printf '%s\n' "$preview" | grep -Fq '.onAction(() => this.OpenMediaActionSheet(item))'
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
printf '%s\n' "$execute" | grep -Fq 'await api.deleteEntry(this.selectedMediaSourceId, item.path, false);'
printf '%s\n' "$execute" | grep -Fq 'await this.LoadMediaDirectory();'
grep -Fq 'await this.client.delete(`/api/localfiles/${this.localFileId(path)}`);' "$api"

echo 'Media action sheet integration policy tests passed'
