#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

mkdir -p "$TEST_DIR/reader"
for file in AudiobookModels AudiobookResponseNormalizer AudiobookPlaybackPolicy AudiobookPlaybackProgressPolicy; do
  cp "$APP_DIR/entry/src/main/ets/features/reader/${file}.ets" "$TEST_DIR/reader/${file}.ts"
done
node "$TSC_BIN" "$TEST_DIR/reader/AudiobookModels.ts" "$TEST_DIR/reader/AudiobookResponseNormalizer.ts" \
  "$TEST_DIR/reader/AudiobookPlaybackPolicy.ts" "$TEST_DIR/reader/AudiobookPlaybackProgressPolicy.ts" \
  --target ES2020 --module commonjs --outDir "$TEST_DIR/output" \
  --skipLibCheck
node "$APP_DIR/tests/audiobook_response_normalizer_test.cjs" \
  "$TEST_DIR/output/AudiobookResponseNormalizer.js" "$TEST_DIR/output/AudiobookPlaybackProgressPolicy.js" \
  "$TEST_DIR/output/AudiobookPlaybackPolicy.js"

grep -Fq 'AudiobookResponseNormalizer' "$APP_DIR/entry/src/main/ets/features/reader/AudiobookApi.ets"
grep -Fq '/api/app/v1/reader/audiobook-generations' "$APP_DIR/entry/src/main/ets/features/reader/AudiobookApi.ets"
grep -Fq '/api/app/v1/reader/managed-ebook-imports' "$APP_DIR/entry/src/main/ets/features/reader/AudiobookApi.ets"
grep -Fq 'rightsConfirmed !== true' "$APP_DIR/entry/src/main/ets/features/reader/AudiobookApi.ets"
grep -Fq '/book-analysis' "$APP_DIR/entry/src/main/ets/features/reader/AudiobookApi.ets"
grep -Fq 'analysisModelVersion' "$APP_DIR/entry/src/main/ets/features/reader/AudiobookResponseNormalizer.ets"
grep -Fq 'analysisRuleVersion' "$APP_DIR/entry/src/main/ets/features/reader/AudiobookResponseNormalizer.ets"
grep -Fq 'reviseVoice' "$APP_DIR/entry/src/main/ets/features/reader/AudiobookApi.ets"
grep -Fq 'reviseSpeaker' "$APP_DIR/entry/src/main/ets/features/reader/AudiobookApi.ets"
grep -Fq 'revisePronunciation' "$APP_DIR/entry/src/main/ets/features/reader/AudiobookApi.ets"
grep -Fq '/pronunciations' "$APP_DIR/entry/src/main/ets/features/reader/AudiobookApi.ets"
grep -Fq 'pronunciationDictionary' "$APP_DIR/entry/src/main/ets/features/reader/AudiobookResponseNormalizer.ets"
grep -Fq 'createExport' "$APP_DIR/entry/src/main/ets/features/reader/AudiobookApi.ets"
grep -Fq '/exports/' "$APP_DIR/entry/src/main/ets/features/reader/AudiobookApi.ets"
grep -Fq 'exportDownloadTicket' "$APP_DIR/entry/src/main/ets/features/reader/AudiobookResponseNormalizer.ets"
grep -Fq "AppSegmentItem({ label: '文本'" "$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq '确认拥有朗读权并生成' "$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq '听书介绍' "$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq '人物画像' "$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq '人物关系' "$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq 'Select(this.AudiobookReviewVoiceOptions())' "$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq '试听音色' "$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq 'ApplyAudiobookReviewVoice' "$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq 'voicePreview' "$APP_DIR/entry/src/main/ets/features/reader/AudiobookApi.ets"
grep -Fq '分析追溯' "$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq 'qualityGateAttestationSha256' "$APP_DIR/entry/src/main/ets/features/reader/AudiobookResponseNormalizer.ets"
grep -Fq '多角色音色已通过黄金集、NER 与跨章节复核质量门禁。' "$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq '低置信度引语归因' "$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq '读音词典' "$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq 'ApplyAudiobookReviewPronunciation' "$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq 'evidenceExcerpt' "$APP_DIR/entry/src/main/ets/features/reader/AudiobookResponseNormalizer.ets"
grep -Fq '导出整书 ZIP' "$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq 'DownloadAudiobookExport' "$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq 'AudiobookPlaybackProgressPolicy' "$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq 'mode: this.audiobookSourceVersion > 1 ? '\''INCREMENTAL'\'' : '\''FULL'\''' "$APP_DIR/entry/src/main/ets/pages/Index.ets"
grep -Fq 'audiobookPlaybackPolicy.next' "$APP_DIR/entry/src/main/ets/pages/Index.ets"

echo "Audiobook App protocol tests passed"
