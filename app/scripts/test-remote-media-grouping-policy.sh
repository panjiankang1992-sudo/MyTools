#!/usr/bin/env bash
set -euo pipefail

app_dir="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
tsc_bin="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$app_dir/entry/src/main/ets/features/media/RemoteMediaGroupingPolicy.ets" \
  "$test_dir/RemoteMediaGroupingPolicy.ts"
cp "$app_dir/entry/src/main/ets/features/media/RemoteMediaModels.ets" "$test_dir/RemoteMediaModels.ts"
node "$tsc_bin" "$test_dir/RemoteMediaGroupingPolicy.ts" "$test_dir/RemoteMediaModels.ts" \
  --target ES2020 --module commonjs --outDir "$test_dir/output" --skipLibCheck
node "$app_dir/tests/remote_media_grouping_policy_test.cjs" \
  "$test_dir/output/RemoteMediaGroupingPolicy.js"

page="$app_dir/entry/src/main/ets/pages/Index.ets"
grep -Fq "this.MediaFilterTrigger('directory')" "$page"
grep -Fq "this.MediaFilterTrigger('tag')" "$page"
grep -Fq 'Text(this.MediaFilterLabel(mode))' "$page"
grep -Fq 'TextPicker({ range: this.MediaPickerDisplayOptions(), selected: this.mediaPickerSelectedIndex })' "$page"
grep -Fq "if (this.mediaSelectorMode === 'tag') return this.MediaTagNames();" "$page"
grep -Fq "placeholder: this.mediaSelectorMode === 'tag' ? '输入内容筛选标签' : '输入内容筛选目录'" "$page"
grep -Fq 'ForEach(this.FilteredMediaSelectorOptions(), (name: string)' "$page"
grep -Fq '.onClick(() => this.SelectMediaSearchableOption(name))' "$page"
grep -Fq 'private FilteredMediaTagNames(): string[] {' "$page"
grep -Fq 'private FilteredMediaDirectoryNames(): string[] {' "$page"
grep -Fq 'const dateOrder = rightDate.localeCompare(leftDate);' "$page"
grep -Fq 'return right.localeCompare(left);' "$page"
grep -Fq 'private UpdateMediaSearchableSelectorQuery(value: string): void {' "$page"
grep -Fq 'this.mediaTagSelectorScroller.scrollTo({ xOffset: 0, yOffset: 0, animation: false });' "$page"
grep -Fq 'private RestoreMediaSearchableSelectorOffset(): void {' "$page"
grep -Fq 'private SelectMediaSearchableOption(name: string): void {' "$page"
grep -Fq 'private ClearMediaSearchableSelector(): void {' "$page"
grep -Fq 'private ConfirmMediaSelector(): void {' "$page"
grep -Fq "if (this.mediaSelectorMode === 'directory') return ['all'].concat(this.MediaDirectoryNames());" "$page"
grep -Fq 'ForEach(this.MediaDirectoryGroups(), (group: RemoteMediaDirectoryGroup)' "$page"
grep -Fq 'Text(this.MediaDirectoryDisplayLabel(group.directoryName))' "$page"
grep -Fq 'this.mediaGroupingPolicy.displayName(name)' "$page"
! grep -Fq 'Text(`${group.items.length} 项`)' "$page"
grep -Fq 'const directory = await api.listDirectory(sourceId, path, cancellation, localRootPath, 1, 24,' "$page"
grep -Fq "return kind === 'all' ? 'MEDIA' : kind.toUpperCase();" "$page"
grep -Fq 'localSource ? this.MediaServerFileType(mediaKindFilter) : ' "$page"
grep -Fq 'localSource ? this.mediaSelectedTags : [], localSource && this.mediaRequireAllTags,' "$page"
grep -Fq "localSource ? this.mediaSearchQuery : '');" "$page"
grep -Fq 'this.mediaRequireAllTags, this.mediaSearchQuery);' "$page"
grep -Fq '.onChange((value: string) => this.ScheduleMediaSearch(value))' "$page"
grep -Fq '.onSubmit(() => this.CommitMediaSearch())' "$page"
grep -Fq 'this.mediaSearchTimerId = setTimeout(() => {' "$page"
grep -Fq 'this.mediaSelectedTags = [name];' "$page"
grep -Fq 'this.mediaDirectoryGroupFilter = name;' "$page"
grep -Fq 'this.ApplyMediaServerFilters();' "$page"
grep -Fq "return this.mediaDirectoryGroupFilter === '根目录' ? '.' : this.mediaDirectoryGroupFilter;" "$page"
grep -Fq 'private async LoadMoreMediaDirectory(): Promise<void>' "$page"
grep -Fq 'if (index === 3) this.LoadMoreMediaDirectory();' "$page"
grep -Fq '继续上滑加载' "$page"
! grep -Fq "Text('今天')" "$page"

open_selector="$(sed -n '/private OpenMediaSelector(/,/^  }/p' "$page")"
close_selector="$(sed -n '/private CloseMediaSelector(/,/^  }/p' "$page")"
printf '%s\n' "$open_selector" | grep -Fq 'this.RestoreMediaSearchableSelectorOffset();'
! printf '%s\n' "$open_selector" | grep -Fq "this.mediaTagSelectorQuery = '';"
! printf '%s\n' "$close_selector" | grep -Fq "this.mediaTagSelectorQuery = '';"
! printf '%s\n' "$close_selector" | grep -Fq "this.mediaDirectorySelectorQuery = '';"

echo 'Remote media grouping integration tests passed'
