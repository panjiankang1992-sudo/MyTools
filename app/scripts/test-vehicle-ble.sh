#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
SOURCE_DIR="$APP_DIR/entry/src/main/ets/features/tools/vehicle"

cp "$SOURCE_DIR/VehicleBleModels.ets" "$TEST_DIR/VehicleBleModels.ts"
cp "$SOURCE_DIR/VehicleBlePolicy.ets" "$TEST_DIR/VehicleBlePolicy.ts"
cp "$SOURCE_DIR/VehicleShortcutPolicy.ets" "$TEST_DIR/VehicleShortcutPolicy.ts"
node "$TSC_BIN" "$TEST_DIR/VehicleBleModels.ts" "$TEST_DIR/VehicleBlePolicy.ts" "$TEST_DIR/VehicleShortcutPolicy.ts" \
  --target ES2020 --module commonjs --outDir "$TEST_DIR/output" --strict --skipLibCheck

# 原生端口在运行测试时替换，真实 SDK 类型另由 assembleHap 校验。
node - "$TSC_BIN" "$SOURCE_DIR" "$TEST_DIR/output" <<'JS'
const fs = require('node:fs');
const path = require('node:path');
const compiler = require(path.resolve(process.argv[2], '../../lib/typescript.js'));
function emit(name, source) {
  const result = compiler.transpileModule(source, {
    compilerOptions: { target: compiler.ScriptTarget.ES2020, module: compiler.ModuleKind.CommonJS },
    fileName: `${name}.ts`,
    reportDiagnostics: true
  });
  const errors = (result.diagnostics || []).filter(item => item.category === compiler.DiagnosticCategory.Error);
  if (errors.length > 0) {
    throw new Error(compiler.formatDiagnosticsWithColorAndContext(errors, {
      getCurrentDirectory: () => process.cwd(),
      getCanonicalFileName: value => value,
      getNewLine: () => '\n'
    }));
  }
  fs.writeFileSync(path.join(process.argv[4], `${name}.js`), result.outputText);
}
emit('VehicleBleTransport', fs.readFileSync(path.join(process.argv[3], 'VehicleBleTransport.ets'), 'utf8'));

// 直接执行生产页面的非渲染方法，仅移除 ArkUI 声明和构建器。
const page = fs.readFileSync(path.join(process.argv[3], 'VehiclePage.ets'), 'utf8');
const boundary = page.indexOf('  @Builder');
if (boundary < 0 || !page.includes('export struct VehiclePage')) throw new Error('Vehicle page extraction boundary missing');
const behavior = page.slice(0, boundary).replace('export struct VehiclePage', 'export class VehiclePage')
  .replace(/@(Component|Prop|Watch|StorageProp|State)\b(?:\([^)]*\))?[ \t]*/g, '') + '\n}\n';
emit('VehiclePage', behavior);
JS

node "$APP_DIR/tests/vehicle_ble_policy_test.cjs" "$TEST_DIR/output/VehicleBlePolicy.js"
node "$APP_DIR/tests/vehicle_ble_test.cjs" "$TEST_DIR/output/VehicleBleTransport.js"
node "$APP_DIR/tests/vehicle_page_test.cjs" "$TEST_DIR/output/VehiclePage.js"
