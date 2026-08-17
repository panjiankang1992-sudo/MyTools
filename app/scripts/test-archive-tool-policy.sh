#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT

if [[ ! -x "$TSC_BIN" ]]; then
  echo "DevEco TypeScript compiler not found" >&2
  exit 1
fi

cp "$APP_DIR/entry/src/main/ets/features/tools/ArchiveToolPolicy.ets" "$TEST_DIR/ArchiveToolPolicy.ts"
node "$TSC_BIN" "$TEST_DIR/ArchiveToolPolicy.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --skipLibCheck
node "$APP_DIR/tests/archive_tool_policy_test.cjs" "$TEST_DIR/output/ArchiveToolPolicy.js"

TOOL="$APP_DIR/entry/src/main/ets/features/tools/ArchiveTool.ets"
grep -Fq 'const expectedFiles = this.zipValidator.validateZipArchive(archive)' "$TOOL"
grep -Fq 'ZIP声明的文件未被完整解压' "$TOOL"
grep -Fq 'const stat = fs.lstatSync(path)' "$TOOL"
grep -Fq 'ZIP解压产生未声明文件' "$TOOL"
grep -Fq 'ZIP解压文件尺寸与声明不一致' "$TOOL"
grep -Fq '待导出文件已发生变化' "$TOOL"
