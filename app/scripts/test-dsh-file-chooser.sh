#!/usr/bin/env bash
set -euo pipefail
app_root="$(cd "$(dirname "$0")/.." && pwd)"
node "$app_root/tests/dsh_web_file_chooser_test.cjs" "$app_root/entry/src/main/ets/features/dsh/DshWebFileChooser.ets"
