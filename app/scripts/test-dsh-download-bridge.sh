#!/usr/bin/env bash
set -euo pipefail
app_root="$(cd "$(dirname "$0")/.." && pwd)"
node "$app_root/tests/dsh_download_bridge_test.cjs" "$app_root/entry/src/main/ets/features/dsh/DshDownloadBridge.ets"
