import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import ts from 'typescript';
import { pairHasAnyContent, pairsToEntries, mergeSubset } from '../src/lib/admin-textbook-draft.ts';

/**
 * 审查反例 `F02-new-row-identity-changes`（对应 F04，P1）的回归测试。
 *
 * **为什么用"提取真实处理器"而不是重写一遍逻辑**：
 * 这个缺陷就长在 `TextbookEditorView.vue` 的 `onRowsUpdate` 里——
 * 把逻辑抄一份到测试里，测的就是抄件，而不是产品代码。
 * 这里采取的做法与审查探针一致：**从真实 `.vue` 源码里取出那个函数并执行**。
 *
 * **边界写清楚**：它验证的是**真实处理器函数**的行为，
 * **不是**完整挂载页面（没有模板、没有响应式组件的生命周期）。
 * 因此它不能替代"挂载真实 Editor"的组合验收——那一条仍未做。
 */

const editorPath = resolve(dirname(new URL(import.meta.url).pathname), '../src/views/admin/TextbookEditorView.vue');
const editorSource = readFileSync(editorPath, 'utf8');

/** 从真实 Vue 源码里取出顶层函数并转译；不复制、不改写其中的逻辑。 */
function originalHandler(name: string, bindings: Record<string, unknown>): (...args: unknown[]) => unknown {
  const marker = new RegExp(`(?:async )?function ${name}\\(`).exec(editorSource);
  if (!marker) throw new Error(`处理器缺失：${name}`);
  const end = editorSource.indexOf('\n}\n', marker.index);
  if (end < 0) throw new Error(`处理器结束位置缺失：${name}`);
  const source = editorSource.slice(marker.index, end + 2);
  const output = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None }
  }).outputText;
  return new Function(...Object.keys(bindings), `${output}\nreturn ${name};`)(...Object.values(bindings));
}

/** 造一个最小编辑器环境；`selectedNode` 决定新增行的归属。 */
function editorHarness(selectedKind: 'all' | 'unit' | 'lesson', selectedId: string) {
  const entries = { value: [] as ReturnType<typeof pairsToEntries> };
  const rows = { value: [] as Array<Record<string, unknown>> };
  const selectedNode = { value: { kind: selectedKind, id: selectedId } };
  const visibleEntries = {
    get value() {
      if (selectedKind === 'all') return entries.value;
      if (selectedKind === 'unit') return entries.value.filter(e => e.clientUnitId === selectedId);
      return entries.value.filter(e => e.clientLessonId === selectedId);
    }
  };
  const update = originalHandler('onRowsUpdate', {
    entries, rows, visibleEntries, selectedNode, pairsToEntries, mergeSubset, pairHasAnyContent,
    subject: { value: 'chinese' },
    // F05 之后 `onRowsUpdate` 会标脏并安排自动保存。这里给最小桩：
    // 本测试关心的是**行身份**，不是保存时序；保存时序由
    // `tests/admin-save-queue.test.ts` 单独覆盖。
    saveStateRef: { value: { markDirty: () => {} } },
    // 第 79 轮给 `onRowsUpdate` 加了这个守卫（加载期间不标脏）；
    // 本测试只关心行身份，给它一个不在加载中的值即可。
    loadingDraft: false,
    // R1 修复又加了一个同类守卫（**保存过程中的同步**也不标脏，否则
    // `save()` 会给自己排下一次保存、形成每约 2 秒写库的循环）。
    // 与 `loadingDraft` 同理：本测试只关心行身份，给它"不在保存同步中"即可。
    // 保存时序由 `tests/admin-save-queue.test.ts` 与
    // `tests/e2e/remaining-b09b-autosave-loop.mjs` 单独覆盖。
    syncingForSave: false,
    // 它是同文件里的另一个函数；本测试只提取 onRowsUpdate，因此要给桩。
    markDirtyAndSchedule: () => {},
    busy: { value: false },
    save: async () => {},
    clearTimeout: () => {},
    setTimeout: () => 0
  });
  return { entries, rows, update };
}

test('F02：同一行连续输入时 rowId 不变，且编辑器行拿回了 tokenId', () => {
  const h = editorHarness('unit', 'u1');
  h.update([{ text: '山' }]);
  const firstId = h.entries.value[0]?.rowId;
  // 这是缺陷的核心：回填之后，编辑器行必须带上 tokenId，
  // 否则下一次输入又会被当成新行。
  assert.ok(h.rows.value[0]?.tokenId, '编辑器行必须拿回 tokenId');
  assert.equal(h.rows.value[0]?.tokenId, firstId);

  h.update([{ ...(h.rows.value[0] as Record<string, unknown>), text: '山峰' }]);
  assert.equal(h.entries.value.length, 1, '同一行不应变成多条');
  assert.equal(h.entries.value[0]?.rowId, firstId, '同一行的 rowId 必须稳定');
  assert.equal(h.entries.value[0]?.text, '山峰');
});

test('F03：在选中单元里逐字输入，只产生一条且归属该单元', () => {
  const h = editorHarness('unit', 'u1');
  h.update([{ text: '山' }]);
  h.update([{ ...(h.rows.value[0] as Record<string, unknown>), text: '山峰' }]);
  h.update([{ ...(h.rows.value[0] as Record<string, unknown>), text: '山峰高' }]);
  assert.equal(h.entries.value.length, 1, '逐字输入不应产生多条');
  assert.equal(h.entries.value[0]?.clientUnitId, 'u1', '必须归属当前选中的单元');
});

test('前面有一行两侧全空时，后面那行的身份不会被错位安到它头上', () => {
  // 这是我自己在实现回填时踩到的坑：按**过滤后**的下标对齐会整体错位一格。
  const h = editorHarness('unit', 'u1');
  h.update([{ text: '山' }]);
  const mountainId = h.entries.value[0]?.rowId;
  h.update([{ text: '', pinyin: '' }, { ...(h.rows.value[0] as Record<string, unknown>), text: '山' }]);
  const blankRow = h.rows.value[0];
  assert.ok(!blankRow?.tokenId, '两侧全空的行不应被安上身份');
  assert.equal(h.rows.value[1]?.tokenId, mountainId, '有内容的那行必须保住自己的身份');
});
