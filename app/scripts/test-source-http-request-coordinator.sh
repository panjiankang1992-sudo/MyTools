#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
coordinator="$root_dir/app/entry/src/main/ets/features/reader/SourceHttpRequestCoordinator.ets"
search_engine="$root_dir/app/entry/src/main/ets/features/reader/BookSourceSearchEngine.ets"
reader_engine="$root_dir/app/entry/src/main/ets/features/reader/BookSourceReaderEngine.ets"
token="$root_dir/app/entry/src/main/ets/shared/network/DownloadCancellationToken.ets"
tsc_bin="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"

cp "$token" "$test_dir/DownloadCancellationToken.ts"
sed "s#../../shared/network/DownloadCancellationToken#./DownloadCancellationToken#" "$coordinator" \
  > "$test_dir/SourceHttpRequestCoordinator.ts"
node "$tsc_bin" "$test_dir/DownloadCancellationToken.ts" "$test_dir/SourceHttpRequestCoordinator.ts" \
  --target ES2020 --module commonjs --outDir "$test_dir/output" --skipLibCheck
node - "$test_dir/output" "$root_dir/app/tests/source_http_request_coordinator_test.cjs" <<'NODE'
const output = process.argv[2];
const testFile = process.argv[3];
const coordinator = require(`${output}/SourceHttpRequestCoordinator.js`);
const token = require(`${output}/DownloadCancellationToken.js`);
require(testFile)(coordinator.SourceHttpRequestCoordinator, token.DownloadCancellationToken).catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
NODE

grep -Fq 'while (this.active.has(key))' "$coordinator"
grep -Fq 'cancellation?.bind(cancelHandler);' "$coordinator"
grep -Fq 'clearTimeout(timer);' "$coordinator"
grep -Fq 'this.active.get(lease.key) === lease.revision' "$coordinator"
grep -Fq 'export const sourceHttpRequestCoordinator' "$coordinator"

for engine in "$search_engine" "$reader_engine"; do
  grep -Fq 'sourceHttpRequestCoordinator.acquire(source.bookSourceUrl' "$engine"
  grep -Fq 'sourceHttpRequestCoordinator.release(lease);' "$engine"
done

echo 'source HTTP request coordinator policy tests passed'
