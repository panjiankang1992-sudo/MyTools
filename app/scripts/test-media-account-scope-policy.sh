#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"
POLICY="$APP_DIR/entry/src/main/ets/features/media/MediaPlaybackHistoryPolicy.ets"
DOWNLOAD_POLICY="$APP_DIR/entry/src/main/ets/features/media/DownloadHistoryPolicy.ets"

grep -Fq "export interface MediaPlaybackProgress" "$POLICY"
grep -Fq "export interface DownloadHistoryItem" "$DOWNLOAD_POLICY"
grep -Fq "owner: string;" "$PAGE"
grep -Fq "value.owner === this.localAccountScope && value.key === key" "$PAGE"
grep -Fq "value.owner !== this.localAccountScope || value.key !== key" "$PAGE"
grep -Fq 'return `${this.selectedMediaSourceId.length}:${this.selectedMediaSourceId}${this.currentMediaPath}`' "$PAGE"
grep -Fq 'key !== `${accountId.length}:${accountId}${path}`' "$POLICY"
grep -Fq "this.BoundedMediaPlaybackProgress(this.mediaPlaybackProgress)" "$PAGE"
grep -Fq "item.owner === taskOwner && item.id === taskId" "$PAGE"
grep -Fq "item.owner !== owner || item.id !== id" "$PAGE"
grep -Fq "this.CurrentDownloadHistory()" "$PAGE"
grep -Fq "item.owner !== this.localAccountScope || item.status === 'running'" "$PAGE"
grep -Fq "this.BoundedDownloadHistory(" "$PAGE"
grep -Fq "^account:v2:[a-f0-9]{64}$" "$POLICY"

if grep -F 'return `${this.selectedMediaSourceId}|${this.currentMediaPath}`' "$PAGE" >/dev/null; then
  echo "Media progress key must not use an ambiguous delimiter-only identity" >&2
  exit 1
fi

echo "Media account scope policy tests passed"
