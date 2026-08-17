#!/usr/bin/env bash
set -euo pipefail

app_dir="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
tsc_bin="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$app_dir/entry/src/main/ets/features/media/RemoteMediaPickerSelectionPolicy.ets" \
  "$test_dir/RemoteMediaPickerSelectionPolicy.ts"
node "$tsc_bin" "$test_dir/RemoteMediaPickerSelectionPolicy.ts" \
  --target ES2020 --module commonjs --outDir "$test_dir/output" --skipLibCheck
node "$app_dir/tests/remote_media_picker_selection_policy_test.cjs" \
  "$test_dir/output/RemoteMediaPickerSelectionPolicy.js"

page="$app_dir/entry/src/main/ets/pages/Index.ets"
grep -Fq 'mediaPickerPendingValue' "$page"
grep -Fq 'this.mediaPickerSelectionPolicy.resolve(value, index, rawOptions, displayOptions)' "$page"
grep -Fq 'const value = this.mediaPickerPendingValue.length > 0 ? this.mediaPickerPendingValue :' "$page"

echo 'Remote media picker selection integration tests passed'
