#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$APP_DIR/entry/src/main/ets/features/reader/ReaderSpeechPolicy.ets" "$TEST_DIR/ReaderSpeechPolicy.ts"
cp "$APP_DIR/entry/src/main/ets/features/reader/ReaderModels.ets" "$TEST_DIR/ReaderModels.ts"
node "$TSC_BIN" "$TEST_DIR/ReaderSpeechPolicy.ts" "$TEST_DIR/ReaderModels.ts" --target ES2020 \
  --module commonjs --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/reader_speech_policy_test.cjs" "$TEST_DIR/output/ReaderSpeechPolicy.js"

PAGE="$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq "import { ReaderSpeechController }" "$PAGE"
grep -Fq "朗读当前内容" "$PAGE"
grep -Fq "this.StopReaderSpeech();" "$PAGE"
grep -Fq "online: 1" "$APP_DIR/entry/src/main/ets/features/reader/ReaderSpeechController.ets"
echo 'Reader speech integration policy tests passed'
