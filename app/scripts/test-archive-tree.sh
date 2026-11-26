#!/usr/bin/env bash
set -euo pipefail
app_root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d /tmp/mytools-archive-tree-tests.XXXXXX)"
trap 'rm -rf "$work"' EXIT
cp "$app_root/entry/src/main/ets/features/tools/archive/ArchiveModels.ets" "$work/ArchiveModels.ts"
cp "$app_root/entry/src/main/ets/features/tools/archive/ArchiveTree.ets" "$work/ArchiveTree.ts"
node /Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc "$work/ArchiveModels.ts" "$work/ArchiveTree.ts" --target ES2020 --module commonjs --outDir "$work/out" --skipLibCheck
node "$app_root/tests/archive_tree_test.cjs" "$work/out/ArchiveTree.js"
