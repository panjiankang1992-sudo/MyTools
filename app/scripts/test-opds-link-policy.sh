#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
policy="$root_dir/app/entry/src/main/ets/features/reader/OpdsLinkPolicy.ets"
atom="$root_dir/app/entry/src/main/ets/features/reader/OpdsAtomCatalogNormalizer.ets"
json="$root_dir/app/entry/src/main/ets/features/reader/OpdsJsonCatalogNormalizer.ets"
tsc_bin="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$policy" "$test_dir/OpdsLinkPolicy.ts"
node "$tsc_bin" "$test_dir/OpdsLinkPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$test_dir/output" --skipLibCheck
node - "$test_dir/output/OpdsLinkPolicy.js" "$root_dir/app/tests/opds_link_policy_test.cjs" <<'NODE'
const moduleFile = process.argv[2];
const testFile = process.argv[3];
require(testFile)(require(moduleFile).OpdsLinkPolicy);
NODE

for normalizer in "$atom" "$json"; do
  grep -Fq 'this.linkPolicy.resolve(' "$normalizer"
  ! grep -Fq 'private safeUrl(' "$normalizer"
  ! grep -Fq 'private resolveRelative(' "$normalizer"
done

echo 'OPDS link policy tests passed'
