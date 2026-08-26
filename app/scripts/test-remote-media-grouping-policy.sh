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
grep -Fq "this.MediaCatalogFilterButton('directory')" "$page"
grep -Fq "this.MediaCatalogFilterButton('tag')" "$page"
grep -Fq 'private MediaCatalogSelectorSheet()' "$page"
grep -Fq 'ForEach(this.FilteredMediaCatalogDirectories()' "$page"
grep -Fq 'ForEach(this.FilteredMediaCatalogTags()' "$page"
grep -Fq "this.mediaCatalogMode === 'gallery'" "$page"
grep -Fq 'this.MediaCatalogGallery()' "$page"
grep -Fq 'this.MediaCatalogVideos()' "$page"
grep -Fq '.onReachEnd(() => this.LoadMoreMediaCatalog())' "$page"
grep -Fq '.onRefreshing(() => this.RefreshMediaCatalog())' "$page"
grep -Fq "placeholder: '搜索媒体'" "$page"
grep -Fq 'private async LoadMediaCatalog(): Promise<void>' "$page"
grep -Fq 'private async LoadMoreMediaCatalog(): Promise<void>' "$page"

echo 'Remote media grouping integration tests passed'
