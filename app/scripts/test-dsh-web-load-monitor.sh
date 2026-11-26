#!/usr/bin/env bash
set -euo pipefail
app_root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d /tmp/mytools-dsh-load.XXXXXX)"
trap 'rm -rf "$work"' EXIT
cp "$app_root/entry/src/main/ets/features/dsh/DshWebLoadMonitor.ets" "$work/DshWebLoadMonitor.ts"
cp "$app_root/entry/src/main/ets/features/dsh/DshWebSessionRenewal.ets" "$work/DshWebSessionRenewal.ts"
node /Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc \
  "$work/DshWebLoadMonitor.ts" "$work/DshWebSessionRenewal.ts" --target ES2020 --module commonjs --outDir "$work/output" --skipLibCheck
node "$app_root/tests/dsh_web_load_monitor_test.cjs" "$work/output/DshWebLoadMonitor.js" \
  "$app_root/entry/src/main/ets/pages/Index.ets" "$work/output/DshWebSessionRenewal.js"
