#!/usr/bin/env bash
set -euo pipefail

app_dir="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
tsc_bin="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$app_dir/entry/src/main/ets/features/media/RemoteMediaLoadResultPolicy.ets" \
  "$test_dir/RemoteMediaLoadResultPolicy.ts"
cp "$app_dir/entry/src/main/ets/features/media/RemoteMediaModels.ets" \
  "$test_dir/RemoteMediaModels.ts"
node "$tsc_bin" "$test_dir/RemoteMediaLoadResultPolicy.ts" "$test_dir/RemoteMediaModels.ts" \
  --target ES2020 --module commonjs --outDir "$test_dir/output" --skipLibCheck
node "$app_dir/tests/remote_media_load_result_policy_test.cjs" \
  "$test_dir/output/RemoteMediaLoadResultPolicy.js"

page="$app_dir/entry/src/main/ets/pages/Index.ets"
grep -Fq 'mediaKindFilter !== this.mediaKindFilter' "$page"
grep -Fq 'this.mediaLoadResultPolicy.resolve(' "$page"
grep -Fq 'this.MediaServerFileType(mediaKindFilter)' "$page"

echo 'Remote media load result integration tests passed'
