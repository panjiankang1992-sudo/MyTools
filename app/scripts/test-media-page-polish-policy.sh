#!/usr/bin/env bash
set -euo pipefail

app_dir="$(cd "$(dirname "$0")/.." && pwd)"
page="$app_dir/entry/src/main/ets/pages/Index.ets"

grep -Fq "SymbolGlyph(\$r('sys.symbol.magnifyingglass'))" "$page"
grep -Fq "placeholder: '搜索媒体'" "$page"
grep -Fq "SymbolGlyph(\$r('sys.symbol.xmark'))" "$page"
grep -Fq "accessibilityText('清除媒体搜索')" "$page"
grep -Fq 'private ClearMediaSearch(): void {' "$page"
grep -Fq 'private MediaFilterActive(mode: string): boolean {' "$page"
grep -Fq 'this.MediaFilterActive(mode) ? AppTheme.surfaceTint : AppTheme.surface' "$page"
grep -Fq 'private MediaEmptyState()' "$page"
grep -Fq "Button('清除筛选')" "$page"
grep -Fq 'private ResetMediaFilters(): void {' "$page"
grep -Fq "this.MediaThumbnailFailed(item.path) ? '预览加载失败' : '正在加载预览'" "$page"
grep -Fq 'private MediaStatusRetryable(): boolean {' "$page"
if grep -Fq "this.RetryAction('重新加载远程媒体'" "$page"; then
  echo 'Media empty state still renders the duplicate retry action' >&2
  exit 1
fi

echo 'Media page polish integration policy tests passed'
