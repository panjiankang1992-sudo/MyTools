#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
node app/tests/media_image_performance_test.cjs
