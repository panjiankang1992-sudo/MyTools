#!/usr/bin/env bash
set -euo pipefail

app_dir="$(cd "$(dirname "$0")/.." && pwd)"
page="$app_dir/entry/src/main/ets/pages/Index.ets"
api="$app_dir/entry/src/main/ets/features/reader/BookSourceDiscoveryApi.ets"

grep -Fq "Text('从网站导入书源')" "$page"
grep -Fq "AppPrimaryPillButton({ label: this.sourceDiscoveryBusy ? '正在导入…' : '导入并添加'" "$page"
grep -Fq 'private async DiscoverBookSource()' "$page"
grep -Fq 'this.ApplyImportedBookSources(task.sourceJson' "$page"
grep -Fq 'new BookSourceDiscoveryApi(this.ActiveServiceUrl()' "$page"
grep -Fq '/api/app/v1/reader/source-discovery' "$api"
grep -Fq "['RUNNING', 'SUCCEEDED', 'FAILED']" "$api"
grep -Fq 'sourceJson.length > 5 * 1024 * 1024' "$api"
grep -Fq 'private safeHtmlTextAt(' "$app_dir/entry/src/main/ets/features/reader/BookSourceSearchEngine.ets"
grep -Fq "this.bookSourceManagementVisible = false;" "$page"
grep -Fq 'private BookSourceManageFilterMenu()' "$page"

printf '%s\n' 'Book source discovery policy tests passed'
