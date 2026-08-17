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
echo 'Reader pagination integration policy tests passed'
