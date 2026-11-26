#!/usr/bin/env bash
set -euo pipefail
root_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root_dir"
node app/tests/media_catalog_prefetch_policy_test.cjs
node app/tests/media_catalog_batch_api_test.cjs
node app/tests/media_catalog_refresh_test.cjs
