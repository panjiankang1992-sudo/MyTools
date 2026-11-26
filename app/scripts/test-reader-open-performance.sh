#!/usr/bin/env bash
set -euo pipefail
root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root_dir"
node app/tests/reader_cached_open_test.cjs
node app/tests/reader_open_performance_test.cjs
node app/tests/reader_text_document_cache_test.cjs
node app/tests/reader_shelf_cache_test.cjs
node app/tests/reader_detail_open_test.cjs
