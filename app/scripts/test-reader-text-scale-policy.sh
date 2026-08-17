#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$APP_DIR/entry/src/main/ets/features/reader/ReaderTextScalePolicy.ets" "$TEST_DIR/ReaderTextScalePolicy.ts"
node "$TSC_BIN" "$TEST_DIR/ReaderTextScalePolicy.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/reader_text_scale_policy_test.cjs" "$TEST_DIR/output/ReaderTextScalePolicy.js"

PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"
ABILITY="$APP_DIR/entry/src/main/ets/ability/EntryAbility.ets"
grep -Fq "@StorageProp('systemFontScale')" "$PAGE"
grep -Fq 'this.ReaderSystemFontScale());' "$PAGE"
grep -Fq '.fontSize(this.ReaderScaledFontSize' "$PAGE"
grep -Fq 'onConfigurationUpdate(newConfig: Configuration)' "$ABILITY"
grep -Fq "AppStorage.setOrCreate('systemFontScale'" "$ABILITY"
echo 'Reader text scale integration policy tests passed'
