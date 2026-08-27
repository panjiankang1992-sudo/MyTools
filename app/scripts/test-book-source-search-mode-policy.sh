#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
page="$root_dir/app/entry/src/main/ets/pages/Index.ets"
api="$root_dir/app/entry/src/main/ets/features/reader/BookSourceRuntimeSearchApi.ets"
sync_api="$root_dir/app/entry/src/main/ets/features/reader/BookSourceSyncApi.ets"

grep -Fq "@State sourceSearchMode: string = 'FUZZY';" "$page"
grep -Fq "ForEach(['FUZZY', 'EXACT', 'PROBE']" "$page"
grep -Fq 'private BookSourceSearchModeDescription(mode: string): string' "$page"
grep -Fq 'this.detailIntro = this.PreferredDetailIntro(this.detailIntro, catalog.intro);' "$page"
grep -Fq 'if (!skipShelfPrompt && this.ShouldPromptReaderShelf())' "$page"
grep -Fq 'private BookSourceSearchResultsPage()' "$page"
grep -Fq '.onAppear(() => this.RestoreBookSourceSearchReturnPosition())' "$page"
grep -Fq 'this.CaptureBookSourceSearchReturnPosition();' "$page"
grep -Fq 'private RestoreBookSourceSearchReturnPositionAttempt(' "$page"
grep -Fq 'const delays: number[] = [0, 16, 64, 160, 320];' "$page"
grep -Fq 'private CancelBookSourceSearch(): void' "$page"
grep -Fq 'this.BookSourceResultIdentity(result)' "$page"
grep -Fq 'mode: string;' "$api"
grep -Fq 'cachedSources: number;' "$api"
grep -Fq 'pendingSources: number;' "$api"
grep -Fq 'cachedSources + pendingSources !== totalSources' "$api"
grep -Fq 'async cancel(taskId: string): Promise<void>' "$api"
grep -Fq "postJson('/api/app/v1/reader/source-search', request)" "$api"
grep -Fq 'client.delete(`/api/app/v1/reader/source-search/${encodeURIComponent(taskId)}`)' "$api"
grep -Fq '`?offset=${offset}&limit=200`' "$api"
if grep -Fq '/api/app/v1/reader/book-searches' "$api"; then
  echo 'book source search must use the backend cache-aware source-search API' >&2
  exit 1
fi
grep -Fq '缓存 ${task.cachedSources} · 已查 ${searchedSources}/${task.pendingSources}' "$page"
grep -Fq 'await this.EnsureRuntimeBookSourcesSynchronized();' "$page"
grep -Fq 'private static readonly MAX_BATCH_ITEMS: number = 50;' "$sync_api"
grep -Fq 'private static readonly MAX_BATCH_ESTIMATED_BYTES: number = 256 * 1024;' "$sync_api"
grep -Fq "putJson('/api/app/v1/reader/sources/batch', request)" "$sync_api"

echo 'book source search mode policy tests passed'
