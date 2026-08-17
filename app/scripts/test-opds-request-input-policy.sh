#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
policy="$root_dir/app/entry/src/main/ets/features/reader/OpdsRequestInputPolicy.ets"
provider="$root_dir/app/entry/src/main/ets/features/reader/OpdsProvider.ets"
tsc_bin="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$policy" "$test_dir/OpdsRequestInputPolicy.ts"
node "$tsc_bin" "$test_dir/OpdsRequestInputPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$test_dir/output" --skipLibCheck
node - "$test_dir/output/OpdsRequestInputPolicy.js" \
  "$root_dir/app/tests/opds_request_input_policy_test.cjs" <<'NODE'
const moduleFile = process.argv[2];
const testFile = process.argv[3];
require(testFile)(require(moduleFile).OpdsRequestInputPolicy);
NODE

grep -Fq 'const requestedUrl = this.inputPolicy.url(url);' "$provider"
grep -Fq 'const requestedAuthorization = this.inputPolicy.authorization(authorization);' "$provider"
grep -Fq 'this.urlPolicy.assertSafe(requestedUrl)' "$provider"
test "$(grep -Fc 'normalize(requestedUrl, text)' "$provider")" -eq 2
! grep -Fq 'private validateHttpsUrl(' "$provider"

echo 'OPDS request input policy tests passed'
