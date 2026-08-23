#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
page="$root_dir/app/entry/src/main/ets/pages/Index.ets"
api="$root_dir/app/entry/src/main/ets/features/reader/BookSourceRuntimeSearchApi.ets"

grep -Fq "@State sourceSearchMode: string = 'FUZZY';" "$page"
grep -Fq "ForEach(['FUZZY', 'EXACT', 'PROBE']" "$page"
grep -Fq 'private BookSourceSearchModeDescription(mode: string): string' "$page"
grep -Fq 'this.detailIntro = this.PreferredDetailIntro(this.detailIntro, catalog.intro);' "$page"
grep -Fq 'if (!skipShelfPrompt && this.ShouldPromptReaderShelf())' "$page"
grep -Fq 'private BookSourceSearchResultsPage()' "$page"
grep -Fq 'private CancelBookSourceSearch(): void' "$page"
grep -Fq 'this.BookSourceResultIdentity(result)' "$page"
grep -Fq 'mode: string;' "$api"
grep -Fq 'cachedSources: number;' "$api"
grep -Fq 'pendingSources: number;' "$api"
grep -Fq 'cachedSources + pendingSources !== totalSources' "$api"
grep -Fq 'async cancel(taskId: string): Promise<void>' "$api"
grep -Fq '缓存 ${task.cachedSources} · 已查 ${searchedSources}/${task.pendingSources}' "$page"

echo 'book source search mode policy tests passed'
