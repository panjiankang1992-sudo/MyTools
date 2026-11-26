#!/usr/bin/env bash
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
node "$APP_DIR/tests/playback_endpoint_switch_test.cjs"
