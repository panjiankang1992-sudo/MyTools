#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

fail() {
  echo "Book source layout policy failed: $1" >&2
  exit 1
}

source_row="$(sed -n '/private BookSourceManageRow(source: BookSource)/,/^  }/p' "$PAGE")"
source_tools="$(sed -n '/private BookSourceManageToolsSheet()/,/^  }/p' "$PAGE")"
loading_row="$(sed -n '/private NetworkLoadingRows(label: string)/,/^  }/p' "$PAGE")"

grep -Fq '.maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })' <<<"$source_row" ||
  fail 'source names must truncate horizontally'
grep -Fq '.height(72)' <<<"$source_row" || fail 'source metadata must keep its readable row height'
grep -Fq '.width(44).height(26)' <<<"$source_row" || fail 'source enabled switch visual size changed'
grep -Fq 'if (enabled !== source.enabled) this.ToggleBookSource(source.bookSourceUrl);' <<<"$source_row" ||
  fail 'source switch must only submit a changed value'
[[ "$(grep -cF '.layoutWeight(1)' <<<"$source_row")" -ge 3 ]] ||
  fail 'source content and expanded actions must keep allocated widths'
grep -Fq "AppTextAction({ label: '凭据'" <<<"$source_row" || fail 'credentials must use the shared text action'
grep -Fq "AppButton({ label: '删除'" <<<"$source_row" || fail 'delete entry must use a shared semantic button'
grep -Fq 'this.PrepareDeleteBookSource(source)' <<<"$source_row" || fail 'delete entry must open confirmation'
grep -Fq "Text('导入 JSON')" <<<"$source_tools" || fail 'JSON import entry is missing'
grep -Fq 'this.ImportBookSources()' <<<"$source_tools" || fail 'JSON import callback is missing'
grep -Fq 'this.DiscoverBookSource()' <<<"$source_tools" || fail 'website discovery callback is missing'
grep -Fq 'Text(label)' <<<"$loading_row" || fail 'network state must name the operation'
if grep -Fq 'ForEach([0, 1, 2]' <<<"$loading_row"; then
  fail 'network loading state renders duplicate placeholders'
fi
grep -Fq '!(this.bookModeIndex === 0 && this.sourceSearchLoading)' "$PAGE" ||
  fail 'source search must not duplicate the loading status'
standard_page="$(sed -n '/private StandardTabPage(/,/private PageHeader(/p' "$PAGE")"
books_page="$(sed -n '/private BooksPage()/,/^  }/p' "$PAGE")"
grep -Fq 'if (index === 0) this.BookModeSelector()' <<<"$standard_page" ||
  fail 'book modes must stay above the independently scrolling content'
if grep -Fq 'this.BookModeSelector()' <<<"$books_page"; then
  fail 'book mode selector still consumes a content row'
fi
if grep -Fq '.columnsTemplate(' <<<"$books_page"; then
  fail 'ebook shelf still uses a cover grid'
fi
shelf_row="$(sed -n '/private BookShelfCard(book: Book)/,/^  }/p' "$PAGE")"
grep -Fq 'this.BookShelfChapterTitle(book)' <<<"$shelf_row" || fail 'shelf row must show reading location'
grep -Fq 'this.ShelfBookCacheLabel(book)' <<<"$shelf_row" || fail 'shelf row must show cache state'
grep -Fq "book.coverUrl : \$r('app.media.reader_default_cover')" <<<"$shelf_row" ||
  fail 'shelf row must retain a default cover'
grep -Fq '.width(56).height(84)' <<<"$shelf_row" || fail 'shelf cover visual size changed'
grep -Fq '.constraintSize({ minHeight: 112 })' <<<"$shelf_row" || fail 'shelf row must allow readable metadata'
grep -Fq '.border({ width: { bottom: 1 }' <<<"$shelf_row" || fail 'shelf row divider is missing'
grep -Fq '.onClick(() => this.OpenBookDetail(book))' <<<"$shelf_row" || fail 'cover must open book details'
grep -Fq 'TapGesture({ count: 1 }).onAction(() => this.ReadShelfBook(book))' <<<"$shelf_row" ||
  fail 'text area must retain the reading action'
if grep -Eq "(AppButton|AppTextAction|Button)\(.*(label: )?'详情'" <<<"$shelf_row"; then
  fail 'shelf must not restore a separate details button'
fi
if grep -Fq 'ContinueReadingCard' "$PAGE"; then
  fail 'ebook homepage still contains the continue-reading card'
fi
book_detail="$(sed -n '/private BookDetailPage()/,/^  }/p' "$PAGE")"
grep -Fq "Text('书籍详情')" <<<"$book_detail" || fail 'book detail title is missing'
grep -Fq '.width(92).height(138)' <<<"$book_detail" || fail 'book detail cover size changed'
grep -Fq ".width('100%').height(58).padding({ left: 18, right: 18 })" <<<"$book_detail" ||
  fail 'book detail header must keep its allocated row'
grep -Fq 'this.ReadDetailBook(true)' <<<"$book_detail" || fail 'detail catalog entry must open the book catalog'

echo 'Book source layout policy tests passed'
