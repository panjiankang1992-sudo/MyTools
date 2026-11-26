#!/usr/bin/env bash
set -euo pipefail
app_root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d /tmp/mytools-password-tests.XXXXXX)"
trap 'rm -rf "$work"' EXIT
cp "$app_root/entry/src/main/ets/features/tools/archive/ArchiveModels.ets" "$work/ArchiveModels.ts"
cp "$app_root/entry/src/main/ets/features/tools/archive/ArchivePasswordPolicy.ets" "$work/ArchivePasswordPolicy.ts"
node /Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc "$work/ArchiveModels.ts" "$work/ArchivePasswordPolicy.ts" --target ES2020 --module commonjs --outDir "$work/out" --skipLibCheck
node "$app_root/tests/archive_password_policy_test.cjs" "$work/out/ArchivePasswordPolicy.js"
