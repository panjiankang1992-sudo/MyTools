import { readFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * 校验本工具站已安装依赖与 package.json 声明的精确版本一致。
 *
 * 历史背景：本脚本原名 prepare-local，曾经把 `webapp/node_modules` 里的框架包
 * 以符号链接铺进 `toolbox-web/node_modules`，以便复用同仓库已安装的精确版本。
 * 该做法会让 npm 在 `toolbox-web` 内执行安装时去跑这些链接包的 prepare 生命周期
 * （例如 echarts 的 `npm run build:lib`），而链接目标缺少构建源码，导致
 * `npm install` 必然失败。现在本工具站改为从 registry 自行安装，本脚本只做校验，
 * 不再创建任何链接。
 *
 * 用法：node scripts/verify-local-deps.mjs
 */

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const manifest = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'));
const declared = { ...manifest.dependencies, ...manifest.devDependencies };

/** 读取已安装包的版本，未安装时返回 null。 */
async function installedVersion(name) {
  try {
    const info = JSON.parse(await readFile(join(root, 'node_modules', name, 'package.json'), 'utf8'));
    return info.version;
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
}

const problems = [];
for (const [name, version] of Object.entries(declared).sort(([left], [right]) => left.localeCompare(right))) {
  const installed = await installedVersion(name);
  if (installed === null) problems.push(`未安装：${name}@${version}（运行 npm install）`);
  else if (installed !== version) problems.push(`版本不符：${name} 已安装 ${installed}，声明 ${version}`);
}

if (problems.length) {
  console.error('依赖校验未通过：');
  for (const problem of problems) console.error(`  - ${problem}`);
  process.exit(1);
}

console.log(`依赖校验通过：${Object.keys(declared).length} 个包与 package.json 精确版本一致。`);
console.log('提示：OCR 静态资产不随 node_modules 校验，需在构建前运行 npm run prepare:ocr。');
