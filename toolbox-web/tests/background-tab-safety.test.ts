import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile, readdir } from 'node:fs/promises';

/**
 * D05：「后台标签页不超时」。
 *
 * 浏览器对后台标签页有两条已知行为：
 * - `requestAnimationFrame` **完全停止**；
 * - `setTimeout` **节流到约 1 次/秒**。
 *
 * 因此"正确性依赖 rAF"是硬故障；"正确性依赖定时等待"只是变慢（但可能与
 * 服务端任务的过期时间相互作用）。这里把两类分别锁住。
 *
 * 为什么不用浏览器模拟后台：CDP 的 `Page.setWebLifecycleState('frozen')` 会让页面
 * **完全停止执行 JS**，那是"冻结"而非"转入后台"，用它验证会得到假结论。
 */

/** 收集 src 下所有源码（去掉注释，注释里提到 rAF 不算使用）。 */
async function collectSources(): Promise<{ path: string; code: string }[]> {
  const root = new URL('../src/', import.meta.url);
  const entries = await readdir(root, { withFileTypes: true, recursive: true });
  const files = entries.filter(entry => entry.isFile() && /\.(ts|vue)$/.test(entry.name));
  return Promise.all(files.map(async entry => {
    const url = new URL(`${entry.parentPath ?? root.pathname}/${entry.name}`, root);
    const raw = await readFile(url, 'utf8');
    const code = raw.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
    return { path: entry.name, code };
  }));
}

test('全仓库不使用 requestAnimationFrame（后台标签页会完全停止它）', async () => {
  const sources = await collectSources();
  assert.equal(sources.length > 20, true, `源码读取异常，只取到 ${sources.length} 个文件`);
  const offenders = sources.filter(f => /\brequestAnimationFrame\b/.test(f.code)).map(f => f.path);
  assert.deepEqual(offenders, [], `切到后台后会停止执行：${offenders.join(', ')}`);
});

test('Promise 内的定时等待只有"让步"与"轮询"两类，且都有界', async () => {
  const sources = await collectSources();
  const sites = sources.filter(f => /new Promise[^)]*setTimeout|setTimeout\(\s*resolve/.test(f.code)).map(f => f.path).sort();
  // 每一处都必须有明确理由：
  // - ocr-ink / ocr-meaning-image：0ms 让步（让浏览器有机会刷新界面），不是等条件成立
  // - pdf-adapter：服务端 PDF 任务的退避轮询（800ms→4000ms，上限 150 次）
  assert.deepEqual(sites, ['ocr-ink.ts', 'ocr-meaning-image.ts', 'pdf-adapter.ts'],
    `出现新的"定时等待"，需确认它在后台标签页下不会导致超时：${sites.join(', ')}`);
});

test('轮询有次数上限，不会无限等待', async () => {
  const code = (await readFile(new URL('../src/services/pdf-adapter.ts', import.meta.url), 'utf8'))
    .replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
  // 有界是前提：否则后台节流会让"看起来卡住"变成"永远不返回"。
  const bounded = /for \(let attempt = 0; attempt < \d+;/.test(code);
  assert.equal(bounded, true, '轮询必须写死次数上限');
});
