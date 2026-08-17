#!/usr/bin/env bash
set -euo pipefail

app_dir="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT

tsc_bin="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
policy="$app_dir/entry/src/main/ets/features/media/RemoteMediaPreloadPolicy.ets"
page="$app_dir/entry/src/main/ets/pages/Index.ets"

cp "$policy" "$test_dir/RemoteMediaPreloadPolicy.ts"
node "$tsc_bin" "$test_dir/RemoteMediaPreloadPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$test_dir/output" --skipLibCheck
node "$app_dir/tests/remote_media_preload_policy_test.cjs" \
  "$test_dir/output/RemoteMediaPreloadPolicy.js"

grep -Fq 'private readonly mediaPreloadPolicy: RemoteMediaPreloadPolicy' "$page"
grep -Fq 'private readonly mediaPreloadTasks: Map<string, Promise<RemotePlaybackDescriptor | undefined>>' "$page"
grep -Fq 'private readonly mediaPreloadDescriptors: Map<string, RemotePlaybackDescriptor>' "$page"
grep -Fq 'const descriptor = await this.ResolveRemotePlaybackDescriptor(api, source, item, cancellation);' "$page"
grep -Fq 'this.StartAdjacentMediaPreload(source, item);' "$page"
test "$(grep -Fc 'this.CancelMediaPreloads();' "$page")" -ge 2
test "$(grep -Fc 'this.ClearMediaPreloads();' "$page")" -ge 4

echo 'Remote media preload integration tests passed'
