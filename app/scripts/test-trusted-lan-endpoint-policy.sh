#!/usr/bin/env bash
set -euo pipefail

app_dir="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
tsc_bin="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$app_dir/entry/src/main/ets/features/connectivity/TrustedLanEndpointPolicy.ets" \
  "$test_dir/TrustedLanEndpointPolicy.ts"
node "$tsc_bin" "$test_dir/TrustedLanEndpointPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$test_dir/output" --skipLibCheck
node "$app_dir/tests/trusted_lan_endpoint_policy_test.cjs" \
  "$test_dir/output/TrustedLanEndpointPolicy.js"

page="$app_dir/entry/src/main/ets/pages/Index.ets"
grep -Fq 'await this.DiscoverLanEndpoint(false);' "$page"
grep -Fq 'new DshApi(this.ActiveServiceUrl(), this.authManager)' "$page"
grep -Fq 'this.RefreshAfterEndpointSwitch();' "$page"
grep -Fq 'return `${this.ActiveServiceUrl()}${avatar}`;' "$page"
grep -Fq 'new ProfileApi(result.baseUrl, this.authManager, false)' "$page"
grep -Fq 'onBusinessEndpointUnavailable = (baseUrl: string)' "$page"
for source in \
  "$app_dir/entry/src/main/ets/shared/network/AuthorizedApiClient.ets" \
  "$app_dir/entry/src/main/ets/features/media/MediaCatalogApi.ets" \
  "$app_dir/entry/src/main/ets/features/media/RemoteMediaApi.ets" \
  "$app_dir/entry/src/main/ets/features/drive/DriveApi.ets"; do
  grep -Fq 'normalizeBusinessBaseUrl(baseUrl)' "$source"
done
grep -Fq 'new AuthorizedApiClient(baseUrl, authManager)' \
  "$app_dir/entry/src/main/ets/features/dsh/DshApi.ets"
echo 'Trusted LAN endpoint integration tests passed'
