#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
POLICY="$APP_DIR/entry/src/main/ets/features/reader/LocalBookImportPolicy.ets"
MODELS="$APP_DIR/entry/src/main/ets/features/reader/ReaderModels.ets"
PICKER="$APP_DIR/entry/src/main/ets/features/reader/LocalBookPicker.ets"
PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"

cp "$POLICY" "$TEST_DIR/LocalBookImportPolicy.ts"
cp "$MODELS" "$TEST_DIR/ReaderModels.ts"
node "$TSC_BIN" "$TEST_DIR/LocalBookImportPolicy.ts" "$TEST_DIR/ReaderModels.ts" \
  --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/local_book_import_policy_test.cjs" "$TEST_DIR/output/LocalBookImportPolicy.js"

grep -Fq "this.importPolicy.normalize(uris)" "$PICKER"
grep -Fq "options.maxSelectNumber = 50" "$PICKER"
grep -Fq 'fileShare.OperationMode.READ_MODE | fileShare.OperationMode.WRITE_MODE' "$PICKER"
grep -Fq 'async delete(uri: string): Promise<void>' "$PICKER"
grep -Fq 'await this.localBookPicker.delete(book.resourceUri);' "$PAGE"
grep -Fq "if (!merged.has(book.id)) addedCount++" "$PAGE"
grep -Fq "所选图书已在书架中" "$PAGE"

echo "Local book import integration policy tests passed"
