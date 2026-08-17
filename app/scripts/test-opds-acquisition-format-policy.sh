#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
policy="$root_dir/app/entry/src/main/ets/features/reader/OpdsAcquisitionFormatPolicy.ets"
models="$root_dir/app/entry/src/main/ets/features/reader/ReaderModels.ets"
atom="$root_dir/app/entry/src/main/ets/features/reader/OpdsAtomCatalogNormalizer.ets"
json="$root_dir/app/entry/src/main/ets/features/reader/OpdsJsonCatalogNormalizer.ets"
tsc_bin="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$models" "$test_dir/ReaderModels.ts"
sed "s#./ReaderModels#./ReaderModels#" "$policy" > "$test_dir/OpdsAcquisitionFormatPolicy.ts"
node "$tsc_bin" "$test_dir/ReaderModels.ts" "$test_dir/OpdsAcquisitionFormatPolicy.ts" \
  --target ES2020 --module commonjs --outDir "$test_dir/output" --skipLibCheck
node - "$test_dir/output/OpdsAcquisitionFormatPolicy.js" \
  "$root_dir/app/tests/opds_acquisition_format_policy_test.cjs" <<'NODE'
const moduleFile = process.argv[2];
const testFile = process.argv[3];
require(testFile)(require(moduleFile).OpdsAcquisitionFormatPolicy);
NODE

for normalizer in "$atom" "$json"; do
  grep -Fq 'this.formatPolicy.resolve(' "$normalizer"
  ! grep -Fq 'normalized.includes(' "$normalizer"
done

echo 'OPDS acquisition format policy tests passed'
