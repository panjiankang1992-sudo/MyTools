#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
TSC_BIN="/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor/node_modules/typescript/bin/tsc"
SOURCE="$APP_DIR/entry/src/main/ets/features/tools/vehicle/VehicleShortcutPolicy.ets"
ROUTE_SOURCE="$APP_DIR/entry/src/main/ets/features/tools/vehicle/VehicleShortcutRoutePolicy.ets"

cp "$SOURCE" "$TEST_DIR/VehicleShortcutPolicy.ts"
cp "$ROUTE_SOURCE" "$TEST_DIR/VehicleShortcutRoutePolicy.ts"
node "$TSC_BIN" "$TEST_DIR/VehicleShortcutPolicy.ts" "$TEST_DIR/VehicleShortcutRoutePolicy.ts" --target ES2020 --module commonjs \
  --outDir "$TEST_DIR/output" --strict --skipLibCheck

# 实际执行入口处理方法，只替换原生依赖；不在测试中另写入口逻辑。
node - "$TSC_BIN" "$APP_DIR" "$TEST_DIR/output" <<'JS'
const fs = require('node:fs');
const path = require('node:path');
const compiler = require(path.resolve(process.argv[2], '../../lib/typescript.js'));
const source = fs.readFileSync(path.join(process.argv[3], 'entry/src/main/ets/ability/EntryAbility.ets'), 'utf8')
  .replace('../features/tools/vehicle/VehicleShortcutPolicy', './VehicleShortcutPolicy');
const result = compiler.transpileModule(source, {
  compilerOptions: { target: compiler.ScriptTarget.ES2020, module: compiler.ModuleKind.CommonJS },
  fileName: 'EntryAbility.ts', reportDiagnostics: true
});
const errors = (result.diagnostics || []).filter(item => item.category === compiler.DiagnosticCategory.Error);
if (errors.length > 0) throw new Error('EntryAbility test compilation failed');
fs.writeFileSync(path.join(process.argv[4], 'EntryAbility.js'), result.outputText);

// 抽取生产 Index 的导航方法，依赖页面行为由运行时测试替换。
const index = fs.readFileSync(path.join(process.argv[3], 'entry/src/main/ets/pages/Index.ets'), 'utf8');
const begin = index.indexOf('  private HandleIncomingVehicleShortcut():');
const end = index.indexOf('  private HandleIncomingArchive():', begin);
if (begin < 0 || end <= begin) throw new Error('Vehicle shortcut navigation extraction boundary missing');
const navigation = `import { VehicleShortcutPolicy } from './VehicleShortcutPolicy';\n` +
  `import { VehicleShortcutRoutePolicy } from './VehicleShortcutRoutePolicy';\n` +
  `export class VehicleShortcutNavigation {\n${index.slice(begin, end)}\n}\n`;
const emittedNavigation = compiler.transpileModule(navigation, {
  compilerOptions: { target: compiler.ScriptTarget.ES2020, module: compiler.ModuleKind.CommonJS },
  fileName: 'VehicleShortcutNavigation.ts', reportDiagnostics: true
});
if ((emittedNavigation.diagnostics || []).some(item => item.category === compiler.DiagnosticCategory.Error)) {
  throw new Error('Vehicle shortcut navigation test compilation failed');
}
fs.writeFileSync(path.join(process.argv[4], 'VehicleShortcutNavigation.js'), emittedNavigation.outputText);
JS

node "$APP_DIR/tests/vehicle_shortcut_test.cjs" "$TEST_DIR/output/VehicleShortcutPolicy.js" "$APP_DIR" \
  "$TEST_DIR/output/EntryAbility.js" "$TEST_DIR/output/VehicleShortcutRoutePolicy.js"
node "$APP_DIR/tests/vehicle_shortcut_navigation_test.cjs" "$TEST_DIR/output/VehicleShortcutNavigation.js" \
  "$TEST_DIR/output/VehicleShortcutPolicy.js"
