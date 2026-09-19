#!/usr/bin/env bash
set -euo pipefail

task_app_root="$(cd "$(dirname "$0")/.." && pwd)"
task_test_root="$(mktemp -d /private/tmp/mytools-adaptation-session.XXXXXX)"
trap 'rm -rf "$task_test_root"' EXIT
task_tsc="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
mkdir -p "$task_test_root/src/features/reader" "$task_test_root/src/shared/network"
for name in ReaderModels ChapterAdaptationModels ChapterAdaptationPolicy AdaptationConsentController AdaptationPendingJournal AdaptationChunkedStorage; do
  cp "$task_app_root/entry/src/main/ets/features/reader/$name.ets" "$task_test_root/src/features/reader/$name.ts"
done
cp "$task_app_root/entry/src/main/ets/shared/network/DownloadCancellationToken.ets" "$task_test_root/src/shared/network/DownloadCancellationToken.ts"
node "$task_tsc" "$task_test_root/src/features/reader/AdaptationConsentController.ts" \
  "$task_test_root/src/features/reader/AdaptationPendingJournal.ts" "$task_test_root/src/features/reader/AdaptationChunkedStorage.ts" --target ES2020 --module commonjs --strict \
  --rootDir "$task_test_root/src" --outDir "$task_test_root/output" --skipLibCheck
node "$task_app_root/tests/adaptation_session_policy_test.cjs" "$task_test_root/output" "$task_app_root"
