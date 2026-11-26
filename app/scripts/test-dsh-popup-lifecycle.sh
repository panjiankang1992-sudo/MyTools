#!/usr/bin/env bash
set -euo pipefail
app_root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d /tmp/mytools-dsh-popup.XXXXXX)"
trap 'rm -rf "$work"' EXIT
cp "$app_root/entry/src/main/ets/features/dsh/DshPopupLifecycle.ets" "$work/DshPopupLifecycle.ts"
node /Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc "$work/DshPopupLifecycle.ts" --target ES2020 --module commonjs --outDir "$work/output" --skipLibCheck
node "$app_root/tests/dsh_popup_lifecycle_test.cjs" "$work/output/DshPopupLifecycle.js"
