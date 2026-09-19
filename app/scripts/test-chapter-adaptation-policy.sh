#!/usr/bin/env bash
set -euo pipefail

task_app_root="$(cd "$(dirname "$0")/.." && pwd)"
task_test_root="$(mktemp -d /private/tmp/mytools-adaptation-test.XXXXXX)"
trap 'rm -rf "$task_test_root"' EXIT
task_tsc="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
for name in ReaderModels ChapterAdaptationModels ChapterAdaptationPolicy ChapterAdaptationResponseNormalizer; do
  cp "$task_app_root/entry/src/main/ets/features/reader/$name.ets" "$task_test_root/$name.ts"
done
node "$task_tsc" "$task_test_root/ChapterAdaptationResponseNormalizer.ts" "$task_test_root/ChapterAdaptationPolicy.ts" \
  --target ES2020 --module commonjs --strict --outDir "$task_test_root/output" --skipLibCheck
node "$task_app_root/tests/chapter_adaptation_policy_test.cjs" "$task_test_root/output"
node "$task_app_root/tests/chapter_adaptation_navigation_test.cjs" "$task_test_root/output"
