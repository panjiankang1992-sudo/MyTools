#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"
API="$APP_DIR/entry/src/main/ets/features/media/RemoteMediaApi.ets"

fail() {
  echo "Ebook UI policy failed: $1" >&2
  exit 1
}

tabs_block="$(sed -n '/private readonly tabs:/,/^  ];/p' "$PAGE")"
expected_tabs=$'阅读\n工具\nDSH\n多媒体\n网盘'
actual_tabs="$(printf '%s\n' "$tabs_block" | sed -n "s/.*{ title: '\([^']*\)'.*/\1/p")"
[[ "$actual_tabs" == "$expected_tabs" ]] || fail "main navigation is stale"

for pattern in \
  "ForEach(['书源', '远程', '本地']" \
  'this.BookShelfHeader()' \
  "AppSecondaryPillButton({ label: this.DetailBookInShelf() ? '书架中' : '试读'" \
  "AppPrimaryPillButton({ label: this.DetailBookInShelf() ? '继续阅读' : '加入书架'" \
  "AppDangerPillButton({ label: '仅移出书架'" \
  "AppDangerPillButton({ label: '移出并清除阅读记录'" \
  "'清除离线缓存'" \
  "'删除远程文件并移出书架'" \
  "'删除本地文件并移出书架'" \
  'private async DeleteDetailBookFile(): Promise<void>' \
  '.deleteEntry(book.sourceId, book.resourceUri, false);' \
  'private CaptureBookSourceSearchReturnPosition(): void' \
  'private RestoreBookSourceSearchReturnPosition(): void' \
  'this.sourceSearchReturnOffset = Math.max(0, this.sourceSearchScroller.currentOffset().yOffset);' \
  'this.sourceSearchScroller.scrollTo({ xOffset: 0, yOffset: offset, animation: false });' \
  'private ReaderContentHorizontalPadding(): number' \
  'return Math.max(6, this.readerSettings.horizontalPadding - 16);' \
  'left: this.ReaderContentHorizontalPadding()' \
  'right: this.ReaderContentHorizontalPadding()' \
  'this.readerViewportWidth - this.ReaderContentHorizontalPadding() * 2' \
  '.parallelGesture(TapGesture({ count: 1, distanceThreshold: 6 }).onAction(() => {' \
  'private ReaderFooter()' \
  "SymbolGlyph(\$r('sys.symbol.headphones'))" \
  "SymbolGlyph(\$r('sys.symbol.list_bullet'))" \
  "SymbolGlyph(\$r('sys.symbol.textformat'))" \
  "SymbolGlyph(\$r('sys.symbol.ellipsis_message'))" \
  'private async SeekReaderProgress(value: number): Promise<void>' \
  'private ToggleReaderNightMode(): void' \
  'private ReaderRemainingLabel(): string' \
  'private ReaderUsesContinuousText(): boolean' \
  'private ContinueReaderScroll(direction: number): void' \
  'private readonly readerChapterPrefetchCount: number = 20;' \
  'private readonly sourceSearchRenderBatchSize: number = 20;' \
  'private readonly sourceManageRenderBatchSize: number = 30;' \
  'ForEach(this.VisibleBookSourceSearchResults()' \
  'ForEach(this.VisibleManagedBookSources()' \
  'private LoadMoreManagedBookSources(): void' \
  'private LoadMoreBookSourceSearchResults(): void' \
  '.onReachEnd(() => this.LoadMoreManagedBookSources())' \
  'private ScheduleReaderChapterPrefetch(): void' \
  'private async PrefetchReaderChapterWindow(' \
  'this.activeReaderChapterPrefetchCancellation?.cancel();' \
  '.onReachStart(() => this.ContinueReaderScroll(-1))' \
  '.onReachEnd(() => this.ContinueReaderScroll(1))' \
  ".width('86%').height('100%').padding" \
  '.position({ x: 0, y: 0 })' \
  'Text(this.CurrentReaderLocationTitle())' \
  ".width('100%').height('78%').padding" \
  'const restoredProgress = Math.round((this.comicPageIndex + 1) * 100 / document.imageUris.length);' \
  'this.CopyBookWithProgress(book, progress.deleted ? 0 : progress.percentage)' \
  'private async LoadRemoteBookCovers(items: RemoteMediaItem[]' \
  "Image(item.bookCoverUri ?? '').width(44).height(58).objectFit(ImageFit.Cover)"; do
  grep -Fq "$pattern" "$PAGE" || fail "missing: $pattern"
done

reader_controls="$(sed -n '/private ReaderControls()/,/^  }/p' "$PAGE")"
! grep -Fq 'this.ReaderPreviousLabel()' <<<"$reader_controls" ||
  fail "reader controls must not render previous-page action"
! grep -Fq 'this.ReaderNextLabel()' <<<"$reader_controls" ||
  fail "reader controls must not render next-page action"

remove_panel="$(sed -n '/private BookShelfRemovePanel()/,/^  }/p' "$PAGE")"
! grep -Fq "Button('移出并清除').layoutWeight(1)" <<<"$remove_panel" ||
  fail "destructive shelf action must not share a cramped three-column row"
grep -Fq "AppDangerPillButton({ label: '移出并清除阅读记录'" <<<"$remove_panel" ||
  fail "destructive shelf action must use a full-width readable button"

! grep -Fq 'ForEach(this.sourceSearchResults' "$PAGE" ||
  fail "book source search results must not render all rows eagerly"
! grep -Fq 'ForEach(this.FilteredManagedBookSources()' "$PAGE" ||
  fail "book source management must not render all sources eagerly"
! grep -Fq '显示更多书源' "$PAGE" || fail "book source management must auto-load on scroll"
! grep -Fq '显示更多结果' "$PAGE" || fail "book source search must auto-load on scroll"

[[ "$(grep -cF '.align(Alignment.TopStart).scrollBar(BarState.Off)' "$PAGE")" -ge 2 ]] ||
  fail "paged reader content must stay top aligned"
grep -Fq "this.textMetadataPolicy.sanitize" \
  "$APP_DIR/entry/src/main/ets/features/reader/ReaderContentLoader.ets" ||
  fail "TXT metadata must be removed before rendering"
grep -Fq 'excludeAdult: boolean = false' "$API" || fail "remote directory API must expose adult filtering"
grep -Fq 'async listBookDirectory(' "$API" || fail "MyTools ebooks must use the catalog API with fallback"
[[ "$(grep -cF 'this.remoteBookSearchText.trim(), this.hideAdultContent' "$PAGE")" -ge 2 ]] ||
  fail "ebook initial and next-page requests must apply the global adult filter"
grep -Fq 'private OnRemoteBookSearchChanged(value: string): void' "$PAGE" ||
  fail "MyTools ebook search must use a debounced server request"
grep -Fq 'this.activeRemoteBookCoverCancellation?.cancel();' "$PAGE" ||
  fail "ebook cover loading must be cancelled when the active directory changes"

echo 'Ebook UI policy tests passed'
