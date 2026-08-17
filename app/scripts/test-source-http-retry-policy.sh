#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
policy="$root_dir/app/entry/src/main/ets/features/reader/SourceHttpRetryPolicy.ets"
token="$root_dir/app/entry/src/main/ets/shared/network/DownloadCancellationToken.ets"
search_engine="$root_dir/app/entry/src/main/ets/features/reader/BookSourceSearchEngine.ets"
reader_engine="$root_dir/app/entry/src/main/ets/features/reader/BookSourceReaderEngine.ets"
opds_provider="$root_dir/app/entry/src/main/ets/features/reader/OpdsProvider.ets"
tsc_bin="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$token" "$test_dir/DownloadCancellationToken.ts"
sed "s#../../shared/network/DownloadCancellationToken#./DownloadCancellationToken#" "$policy" \
  > "$test_dir/SourceHttpRetryPolicy.ts"
node "$tsc_bin" "$test_dir/DownloadCancellationToken.ts" "$test_dir/SourceHttpRetryPolicy.ts" \
  --target ES2020 --module commonjs --outDir "$test_dir/output" --skipLibCheck
node - "$test_dir/output" "$root_dir/app/tests/source_http_retry_policy_test.cjs" <<'NODE'
const output = process.argv[2];
const testFile = process.argv[3];
const policy = require(`${output}/SourceHttpRetryPolicy.js`);
const token = require(`${output}/DownloadCancellationToken.js`);
require(testFile)(policy.SourceHttpRetryPolicy, token.DownloadCancellationToken).catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
NODE

for engine in "$search_engine" "$reader_engine"; do
  grep -Fq 'for (let attempt = 0; attempt < 2; attempt++)' "$engine"
  grep -Fq 'spec.method === http.RequestMethod.GET, attempt, responseStatus' "$engine"
  grep -Fq 'await this.retryPolicy.wait(cancellation);' "$engine"
done

grep -Fq 'for (let attempt = 0; attempt < 2; attempt++)' "$opds_provider"
grep -Fq 'this.retryPolicy.shouldRetry(true, attempt, responseStatus)' "$opds_provider"
grep -Fq 'await this.retryPolicy.wait(cancellation);' "$opds_provider"
grep -Fq 'if (responseError.length > 0) throw new Error(responseError);' "$opds_provider"

echo 'source HTTP retry policy tests passed'
