#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

source_row="$(sed -n '/private BookSourceManageRow(source: BookSource)/,/^  }/p' "$PAGE")"
source_tools="$(sed -n '/private BookSourceManageToolsSheet()/,/^  }/p' "$PAGE")"
loading_row="$(sed -n '/private NetworkLoadingRows(label: string)/,/^  }/p' "$PAGE")"

grep -Fq '.maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })' <<<"$source_row"
grep -Fq ".height(72)" <<<"$source_row"
grep -Fq ".width(44).height(26)" <<<"$source_row"
[[ "$(grep -cF '.layoutWeight(1).height(44)' <<<"$source_row")" -ge 2 ]]
grep -Fq "Text('导入 JSON')" <<<"$source_tools"
grep -Fq 'this.ImportBookSources()' <<<"$source_tools"
grep -Fq 'this.DiscoverBookSource()' <<<"$source_tools"
grep -Fq 'Text(label)' <<<"$loading_row"
if grep -Fq 'ForEach([0, 1, 2]' <<<"$loading_row"; then
  echo 'Book source loading state renders duplicate placeholders' >&2
  exit 1
fi
grep -Fq '!(this.bookModeIndex === 0 && this.sourceSearchLoading)' "$PAGE"
page_header="$(sed -n '/private PageHeader(title: string)/,/^  }/p' "$PAGE")"
books_page="$(sed -n '/private BooksPage()/,/^  }/p' "$PAGE")"
grep -Fq 'this.BookModeSelector()' <<<"$page_header"
if grep -Fq 'this.BookModeSelector()' <<<"$books_page"; then
  echo 'Book mode selector still consumes a content row' >&2
  exit 1
fi
if grep -Fq '.columnsTemplate(' <<<"$books_page"; then
  echo 'Ebook shelf still uses a cover grid' >&2
  exit 1
fi
shelf_row="$(sed -n '/private BookShelfCard(book: Book)/,/^  }/p' "$PAGE")"
grep -Fq 'this.BookShelfMetadataLine(book)' <<<"$shelf_row"
grep -Fq "Image(\$r('app.media.reader_default_cover'))" <<<"$shelf_row"
grep -Fq ".height(94)" <<<"$shelf_row"
grep -Fq ".border({ width: { bottom: 1 }" <<<"$shelf_row"
if grep -Fq 'ContinueReadingCard' "$PAGE"; then
  echo 'Ebook homepage still contains the continue-reading card' >&2
  exit 1
fi
book_detail="$(sed -n '/private BookDetailPage()/,/^  }/p' "$PAGE")"
grep -Fq "Text('书籍详情')" <<<"$book_detail"
grep -Fq ".width(92).height(138)" <<<"$book_detail"
grep -Fq ".width('100%').height(58).padding({ left: 18, right: 18 })" <<<"$book_detail"

echo 'Book source layout policy tests passed'
