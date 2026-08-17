#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
READER_DIR="$APP_DIR/entry/src/main/ets/features/reader"

mkdir -p "$TEST_DIR/reader"
cp "$READER_DIR/OpdsJsonCatalogNormalizer.ets" "$TEST_DIR/reader/OpdsJsonCatalogNormalizer.ts"
cp "$READER_DIR/OpdsModels.ets" "$TEST_DIR/reader/OpdsModels.ts"
cp "$READER_DIR/ReaderModels.ets" "$TEST_DIR/reader/ReaderModels.ts"
cp "$READER_DIR/OpdsAcquisitionFormatPolicy.ets" "$TEST_DIR/reader/OpdsAcquisitionFormatPolicy.ts"
cp "$READER_DIR/OpdsLinkPolicy.ets" "$TEST_DIR/reader/OpdsLinkPolicy.ts"
node "$TSC_BIN" "$TEST_DIR/reader/OpdsJsonCatalogNormalizer.ts" "$TEST_DIR/reader/OpdsModels.ts" \
  "$TEST_DIR/reader/ReaderModels.ts" "$TEST_DIR/reader/OpdsAcquisitionFormatPolicy.ts" \
  "$TEST_DIR/reader/OpdsLinkPolicy.ts" --target ES2020 \
  --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/opds_json_catalog_normalizer_test.cjs" \
  "$TEST_DIR/output/OpdsJsonCatalogNormalizer.js"

PROVIDER="$READER_DIR/OpdsProvider.ets"
grep -Fq 'this.jsonNormalizer.normalize(requestedUrl, text)' "$PROVIDER"
grep -Fq 'this.responsePolicy.headerError(' "$PROVIDER"
grep -Fq 'this.responsePolicy.bodyError(text)' "$PROVIDER"
! grep -Fq "root['publications'] as" "$PROVIDER"

echo "OPDS JSON catalog integration policy tests passed"
