#!/usr/bin/env bash
set -euo pipefail

app_dir="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
tsc_bin="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

mkdir -p "$test_dir/media" "$test_dir/connectivity"
cp "$app_dir/entry/src/main/ets/features/media/RemoteMediaPlaybackUrlPolicy.ets" \
  "$test_dir/media/RemoteMediaPlaybackUrlPolicy.ts"
cp "$app_dir/entry/src/main/ets/features/connectivity/TrustedLanEndpointPolicy.ets" \
  "$test_dir/connectivity/TrustedLanEndpointPolicy.ts"
cp "$app_dir/entry/src/main/ets/features/connectivity/HomeChannelOrigin.ets" "$test_dir/connectivity/HomeChannelOrigin.ts"
node "$tsc_bin" "$test_dir/media/RemoteMediaPlaybackUrlPolicy.ts" \
  "$test_dir/connectivity/TrustedLanEndpointPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$test_dir/output" --skipLibCheck
node "$app_dir/tests/remote_media_playback_url_policy_test.cjs" \
  "$test_dir/output/media/RemoteMediaPlaybackUrlPolicy.js"

player="$app_dir/entry/src/main/ets/features/media/RemoteMediaPlayer.ets"
grep -Fq 'const safeUrl = this.playbackUrlPolicy.url(url);' "$player"
grep -Fq 'player.url = safeUrl;' "$player"
echo 'Remote media playback URL integration tests passed'
