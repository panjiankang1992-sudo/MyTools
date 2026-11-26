#!/usr/bin/env bash
set -euo pipefail
app_root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d /tmp/mytools-dsh-renewal.XXXXXX)"
trap 'rm -rf "$work"' EXIT
cp "$app_root/entry/src/main/ets/features/dsh/DshWebSessionRenewal.ets" "$work/DshWebSessionRenewal.ts"
node /Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc "$work/DshWebSessionRenewal.ts" --target ES2020 --module commonjs --outDir "$work/output" --skipLibCheck
node "$app_root/tests/dsh_web_session_renewal_test.cjs" "$work/output/DshWebSessionRenewal.js"
