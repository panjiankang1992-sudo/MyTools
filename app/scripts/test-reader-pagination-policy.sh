#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$APP_DIR/entry/src/main/ets/features/reader/ReaderPaginationPolicy.ets" "$TEST_DIR/ReaderPaginationPolicy.ts"
cp "$APP_DIR/entry/src/main/ets/features/reader/ReaderModels.ets" "$TEST_DIR/ReaderModels.ts"
cp "$APP_DIR/entry/src/main/ets/features/reader/ReaderViewportPolicy.ets" "$TEST_DIR/ReaderViewportPolicy.ts"
node "$TSC_BIN" "$TEST_DIR/ReaderPaginationPolicy.ts" "$TEST_DIR/ReaderModels.ts" \
  "$TEST_DIR/ReaderViewportPolicy.ts" --target ES2020 \
  --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/reader_pagination_policy_test.cjs" "$TEST_DIR/output/ReaderPaginationPolicy.js"

PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq 'ReaderPaginationPolicy' "$PAGE"
grep -Fq "this.readerSettings.pageTurnMode === 'scroll'" "$PAGE"
grep -Fq 'this.ReaderPagedContent()' "$PAGE"
grep -Fq 'this.readerTextPageIndex' "$PAGE"
grep -Fq 'new ReaderTextLocatorPolicy().encode' "$PAGE"
grep -Fq "this.readerSettings.pageTurnMode === 'cover'" "$PAGE"
grep -Fq 'TurnReaderTextPageBySwipe(event.offsetX)' "$PAGE"
grep -Fq "window.Orientation.LANDSCAPE" "$PAGE"
grep -Fq "this.ApplyReaderOrientation('system')" "$PAGE"
grep -Fq '.fontFamily(this.ReaderFontName())' "$PAGE"
grep -Fq "this.ReaderFontButton('宋体', 'serif')" "$PAGE"
grep -Fq 'this.ReaderSystemFontScale());' "$PAGE"
grep -Fq '.duration(300)' "$PAGE"
[[ "$(grep -cF ".align(Alignment.TopStart).scrollBar(BarState.Off)" "$PAGE")" -ge 2 ]]
grep -Fq 'private ReaderCompactProgressLabel(): string' "$PAGE"
grep -Fq 'this.readerTextPages.length}页' "$PAGE"
grep -Fq 'private ReaderPlainTextContent(content: string, chapterTitle: string)' "$PAGE"
grep -Fq 'private ReaderPlainParagraphs(content: string, chapterTitle: string): string[]' "$PAGE"
grep -Fq 'this.ReaderChapterHeader(index, index === this.readerContinuousStartIndex ? 24 : 34)' "$PAGE"
grep -Fq 'Text(this.ReaderSingleLineStatus())' "$PAGE"
grep -Fq 'private PrepareReaderContinuousWindowAtChapter(index: number): void' "$PAGE"
grep -Fq 'this.PrepareReaderContinuousWindowAtChapter(index);' "$PAGE"
grep -Fq '.id(`reader-scroll-${this.readerScrollEpoch}`)' "$PAGE"
grep -Fq 'this.readerScroller = new Scroller();' "$PAGE"
grep -Fq 'this.readerScrollEpoch++;' "$PAGE"
grep -Fq 'private ReaderIndentedParagraph(content: string): string' "$PAGE"
grep -Fq 'return `\u3000\u3000${trimmed}`;' "$PAGE"
grep -Fq 'private ReaderIsSceneBreak(content: string): boolean' "$PAGE"
grep -Fq "this.ReaderTypographyPresetButton('舒适', 19, 1.8, 14, 24)" "$PAGE"
grep -Fq 'private ApplyReaderTypographyPreset(fontSize: number, lineHeight: number, paragraphSpacing: number,' "$PAGE"
echo 'Reader pagination integration policy tests passed'
