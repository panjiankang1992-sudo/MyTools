#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
policy="$root_dir/app/entry/src/main/ets/features/reader/OpdsNetworkResponsePolicy.ets"
provider="$root_dir/app/entry/src/main/ets/features/reader/OpdsProvider.ets"
tsc_bin="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$policy" "$test_dir/OpdsNetworkResponsePolicy.ts"
node "$tsc_bin" "$test_dir/OpdsNetworkResponsePolicy.ts" --target ES2020 --module commonjs \
  --outDir "$test_dir/output" --skipLibCheck
node - "$test_dir/output/OpdsNetworkResponsePolicy.js" \
  "$root_dir/app/tests/opds_network_response_policy_test.cjs" <<'NODE'
const moduleFile = process.argv[2];
const testFile = process.argv[3];
require(testFile)(require(moduleFile).OpdsNetworkResponsePolicy);
NODE

grep -Fq 'this.responsePolicy.headerError(receivedHeaders)' "$provider"
grep -Fq 'this.responsePolicy.bodyError(text)' "$provider"
! grep -Fq 'responseContentType.includes' "$provider"
! grep -Fq 'new util.TextEncoder()' "$provider"

echo 'OPDS network response policy tests passed'
