import { cp, mkdir, realpath } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const source = await realpath(join(root, 'node_modules/pdfjs-dist'));
const destination = join(root, 'public/pdfjs');
await mkdir(destination, { recursive: true });
// 字符映射与标准字体随应用分发，外来 PDF 的预览不访问第三方字体服务器。
for (const name of ['cmaps', 'standard_fonts']) {
  await cp(join(source, name), join(destination, name), { recursive: true });
}
await cp(join(source, 'LICENSE'), join(destination, 'LICENSE'));
console.log('Prepared local PDF.js character maps and standard fonts.');
