#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
client="$root_dir/app/entry/src/main/ets/shared/network/AuthorizedApiClient.ets"
api="$root_dir/app/entry/src/main/ets/features/media/RemoteMediaApi.ets"
market_api="$root_dir/app/entry/src/main/ets/features/tools/AppMarketApi.ets"
session_api="$root_dir/app/entry/src/main/ets/features/auth/SessionApi.ets"
progress_api="$root_dir/app/entry/src/main/ets/features/reader/ReaderProgressApi.ets"
marker_api="$root_dir/app/entry/src/main/ets/features/reader/ReaderMarkerApi.ets"
shelf_api="$root_dir/app/entry/src/main/ets/features/reader/ShelfSyncApi.ets"
source_sync_api="$root_dir/app/entry/src/main/ets/features/reader/BookSourceSyncApi.ets"
reader_data_api="$root_dir/app/entry/src/main/ets/features/reader/ReaderDataApi.ets"
page="$root_dir/app/entry/src/main/ets/pages/Index.ets"

grep -Fq 'async get(path: string, cancellation?: DownloadCancellationToken)' "$client"
grep -Fq 'cancellation?.bind(cancelHandler);' "$client"
grep -Fq 'cancellation?.unbind(cancelHandler);' "$client"
grep -Fq 'response.status === 401 && cancellation?.isCancelled() !== true' "$client"
grep -Fq 'async post(path: string, cancellation?: DownloadCancellationToken)' "$client"
grep -Fq 'listDirectory(accountId: string, path: string,' "$api"
grep -Fq 'accountId=${encodeURIComponent(safeAccountId)}`, cancellation)' "$api"
grep -Fq 'async listSources(cancellation?: DownloadCancellationToken)' "$api"
grep -Fq "this.client.get('/api/webdav/accounts', cancellation)" "$api"

grep -Fq 'private activeMediaSourceCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq 'private activeMediaDirectoryCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq 'private activeRemoteBookDirectoryCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq 'private activeToolDirectoryCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq '.listDirectory(sourceId, path, cancellation);' "$page"
grep -Fq 'api.listSources(sourceCancellation)' "$page"
grep -Fq 'this.activeMediaSourceCancellation?.cancel();' "$page"
grep -Fq 'this.activeMediaDirectoryCancellation?.cancel();' "$page"
grep -Fq 'this.activeRemoteBookDirectoryCancellation?.cancel();' "$page"
grep -Fq 'this.activeToolDirectoryCancellation?.cancel();' "$page"
grep -Fq 'api.listDirectory(sourceId, path, cancellation, localRootPath, 1, pageSize)' "$page"
grep -Fq 'private InvalidateRemoteDirectoryLoads(): void {' "$page"
grep -Fq 'this.InvalidateRemoteDirectoryLoads();' "$page"
grep -Fq 'private activeMediaOpenCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq 'api.resolvePlayback(source, item.path, cancellation)' "$page"
grep -Fq 'this.activeMediaOpenCancellation?.cancel();' "$page"
grep -Fq 'private activeReaderTicketCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq '.resolveBookPlayback(source, book.resourceUri, ticketCancellation);' "$page"
grep -Fq 'this.activeReaderTicketCancellation?.cancel();' "$page"
grep -Fq 'getPlaybackMetrics(ticket: string,' "$api"
grep -Fq 'metrics`, cancellation)' "$api"
grep -Fq 'private activeMediaMetricsCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq '.getPlaybackMetrics(ticket, cancellation);' "$page"
grep -Fq 'this.activeMediaMetricsCancellation?.cancel();' "$page"
grep -Fq 'if (this.activeMediaMetricsCancellation === cancellation) {' "$page"
grep -Fq 'async list(page: number, pageSize: number, name: string,' "$market_api"
grep -Fq 'this.client.get(path, cancellation)' "$market_api"
grep -Fq 'async detail(id: string, cancellation?: DownloadCancellationToken)' "$market_api"
grep -Fq 'private activeMarketCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq 'private marketOperationRevision: number = 0;' "$page"
grep -Fq '.list(targetPage, 20, query, cancellation);' "$page"
grep -Fq '.detail(id, cancellation);' "$page"
grep -Fq 'query !== this.marketQuery' "$page"
grep -Fq 'async list(cancellation?: DownloadCancellationToken)' "$session_api"
test "$(grep -Fc "this.client.get('/api/tokens" "$session_api")" -eq 2
test "$(grep -Fc ', cancellation);' "$session_api")" -ge 2
grep -Fq 'private activeSessionListCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq 'private sessionListRevision: number = 0;' "$page"
grep -Fq '.list(cancellation);' "$page"
grep -Fq 'this.sessionLoading && this.activeSessionListCancellation === undefined' "$page"
grep -Fq 'private ChangeMainTab(index: number): void {' "$page"
grep -Fq 'this.ChangeMainTab(index);' "$page"
grep -Fq 'private LeaveMarketPage(): void {' "$page"
test "$(grep -Fc 'this.LeaveMarketPage();' "$page")" -ge 2
grep -Fq 'private CancelSessionListOperation(): void {' "$page"
tab_change_body="$(sed -n '/private ChangeMainTab(index: number)/,/^  }/p' "$page")"
grep -Fq 'if (this.currentIndex === 3) this.activeMediaCatalogCancellation?.cancel();' <<<"$tab_change_body"
grep -Fq 'if (this.currentIndex === 4) this.activeDriveCancellation?.cancel();' <<<"$tab_change_body"
if grep -Eq 'onClick\(\(\) => this\.currentIndex =|this\.currentIndex = (0|3|4);' "$page"; then
  echo 'user-triggered main tab navigation must use ChangeMainTab' >&2
  exit 1
fi
for sync_api in "$progress_api" "$marker_api" "$shelf_api" "$source_sync_api"; do
  grep -Fq 'cancellation?: DownloadCancellationToken' "$sync_api"
  grep -Fq ', cancellation)' "$sync_api"
done
test "$(grep -Fc 'this.throwIfCancelled(cancellation);' "$progress_api")" -ge 4
test "$(grep -Fc 'this.throwIfCancelled(cancellation);' "$marker_api")" -ge 4
test "$(grep -Fc 'generation !== this.readerProgressSyncGeneration || cancellation.isCancelled()' "$page")" -ge 5
grep -Fq 'private CancelReaderPulls(): void {' "$page"
grep -Fq 'private activeProgressPullCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq 'private activeMarkerPullCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq 'private activeShelfPullCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq 'private activeBookSourcePullCancellation?: DownloadCancellationToken = undefined;' "$page"
test "$(grep -Fc 'this.CancelReaderPulls();' "$page")" -ge 3
cancel_reader_pulls_body="$(sed -n '/private CancelReaderPulls()/,/^  }/p' "$page")"
grep -Fq 'this.readerProgressPullPending = false;' <<<"$cancel_reader_pulls_body"
grep -Fq 'this.readerMarkerPullPending = false;' <<<"$cancel_reader_pulls_body"
grep -Fq 'this.shelfPullPending = false;' <<<"$cancel_reader_pulls_body"
grep -Fq 'this.bookSourcePullPending = false;' <<<"$cancel_reader_pulls_body"
if grep -Eq 'pendingReaderProgress =|pendingReaderMarkers =|pendingShelfBooks =|pendingBookSources =' \
  <<<"$cancel_reader_pulls_body"; then
  echo 'reader pull cancellation must preserve pending write queues' >&2
  exit 1
fi
for pull_method in PullReadingProgress PullBookSources PullShelfBooks PullReaderMarkers; do
  pull_body="$(sed -n "/private async ${pull_method}()/,/^  }/p" "$page")"
  grep -Fq 'if (cancellation.isCancelled()) {' <<<"$pull_body"
  grep -Fq 'PullPending = false;' <<<"$pull_body"
done
grep -Fq 'async summary(cancellation?: DownloadCancellationToken)' "$reader_data_api"
grep -Fq "this.client.get('/api/app/v1/reader/data/summary', cancellation)" "$reader_data_api"
grep -Fq 'private activeReaderDataSummaryCancellation?: DownloadCancellationToken = undefined;' "$page"
grep -Fq 'private readerDataSummaryRevision: number = 0;' "$page"
grep -Fq 'private readerDataDeleteRevision: number = 0;' "$page"
grep -Fq '.summary(cancellation);' "$page"
grep -Fq 'private LeaveProfileSubpage(): void {' "$page"
grep -Fq 'private CancelReaderDataSummary(): void {' "$page"
test "$(grep -Fc 'this.CancelReaderDataSummary();' "$page")" -ge 4
delete_reader_data_body="$(sed -n '/private async DeleteReaderCloudData()/,/private ReaderSyncOperationActive()/p' "$page")"
grep -Fq 'this.CancelReaderDataSummary();' <<<"$delete_reader_data_body"
grep -Fq 'const deleteRevision = ++this.readerDataDeleteRevision;' <<<"$delete_reader_data_body"
grep -Fq 'const sources = this.bookSources.slice();' <<<"$delete_reader_data_body"
test "$(grep -Fc 'this.AccountOperationCurrent(accountRevision, owner)' <<<"$delete_reader_data_body")" -ge 5
grep -Fq 'deleteRevision === this.readerDataDeleteRevision' <<<"$delete_reader_data_body"

tool_directory_body="$(sed -n '/private async LoadToolFileDirectory()/,/private ParentToolFilePath()/p' "$page")"
if grep -Fq 'activeMediaOpenCancellation' <<<"$tool_directory_body"; then
  echo 'tool directory load must not cancel media open transactions' >&2
  exit 1
fi
if grep -Fq 'activeReaderTicketCancellation' <<<"$tool_directory_body"; then
  echo 'tool directory load must not cancel reader ticket transactions' >&2
  exit 1
fi

logout_body="$(sed -n '/private async Logout(/,/private HandleSessionInvalidated()/p' "$page")"
test "$(grep -Fc 'this.activeMediaOpenCancellation?.cancel();' <<<"$logout_body")" -eq 1
grep -Fq 'this.readerOpeningLoader?.cancel();' <<<"$logout_body"

session_body="$(sed -n '/private HandleSessionInvalidated()/,/private SessionStatusText(/p' "$page")"
grep -Fq 'this.activeMediaOpenCancellation?.cancel();' <<<"$session_body"
grep -Fq 'this.activeReaderTicketCancellation?.cancel();' <<<"$session_body"
grep -Fq 'this.readerOpeningLoader?.cancel();' <<<"$session_body"

echo 'remote directory cancellation policy tests passed'
