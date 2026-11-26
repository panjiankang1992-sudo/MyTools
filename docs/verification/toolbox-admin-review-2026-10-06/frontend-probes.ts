/**
 * 管理教材前端只读反例。运行现有纯函数及从 Vue 文件提取的实际处理函数。
 * 不挂载页面、不发网络请求、不修改应用、测试或教材数据。
 * 运行：在 toolbox-web 中执行 node --import tsx ../docs/verification/toolbox-admin-review-2026-10-06/frontend-probes.ts。
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import ts from '../../../toolbox-web/node_modules/typescript/lib/typescript.js';
import { pairsToEntries, mergeSubset, applyCompletionResults, beginAutomaticCompletion } from '../../../toolbox-web/src/lib/admin-textbook-draft.ts';
import { parseBulkText } from '../../../toolbox-web/src/lib/admin-bulk-paste.ts';
import { suggestPinyin } from '../../../toolbox-web/src/lib/pinyin-adapter.ts';

async function main(): Promise<void> {
const probeDirectory = dirname(resolve(process.argv[1]));
const editorPath = resolve(probeDirectory, '../../../toolbox-web/src/views/admin/TextbookEditorView.vue');
const editorSource = readFileSync(editorPath, 'utf8');
const evidence: Array<Record<string, unknown>> = [];

/** 提取原文件顶层函数并转译类型，不复制或改写函数逻辑。 */
function originalHandler(name: string, bindings: Record<string, unknown>): (...args: unknown[]) => unknown {
  const marker = new RegExp(`(?:async )?function ${name}\\(`).exec(editorSource);
  if (!marker) throw new Error(`Handler missing: ${name}`);
  const end = editorSource.indexOf('\n}\n', marker.index);
  if (end < 0) throw new Error(`Handler end missing: ${name}`);
  const source = editorSource.slice(marker.index, end + 2);
  const output = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText;
  return new Function(...Object.keys(bindings), `${output}\nreturn ${name};`)(...Object.values(bindings));
}

/** 使用原 onRowsUpdate，visibleEntries 按实际页面选择范围计算。 */
function editorHarness(selectedUnit: string | null, subjectName: 'chinese' | 'english' = 'chinese') {
  const entries = { value: [] as ReturnType<typeof pairsToEntries> };
  const rows = { value: [] as Array<Record<string, unknown>> };
  const visibleEntries = {
    get value() { return selectedUnit === null ? entries.value : entries.value.filter(entry => entry.clientUnitId === selectedUnit); }
  };
  const update = originalHandler('onRowsUpdate', {
    entries, rows, visibleEntries, pairsToEntries, mergeSubset, subject: { value: subjectName }
  });
  return { entries, rows, update };
}

const leftOnlyInput = [{ meaning: '星期一', english: '' }];
const leftOnlyActual = pairsToEntries(leftOnlyInput, [], 'english');
evidence.push({
  id: 'F01-one-sided-input-loss', severity: 'P1',
  execution: 'Direct application pure function: pairsToEntries',
  input: leftOnlyInput, expected: 'Keep one row with meaning 星期一 and blank English for later completion.',
  actual: leftOnlyActual, issueReproduced: leftOnlyActual.length === 0,
  sources: ['toolbox-web/src/lib/admin-textbook-draft.ts:123']
});

const allEditor = editorHarness(null);
allEditor.update([{ text: '山' }]);
const firstId = allEditor.entries.value[0]?.rowId;
allEditor.update([{ text: '山峰' }]);
const secondId = allEditor.entries.value[0]?.rowId;
evidence.push({
  id: 'F02-new-row-identity-changes', severity: 'P1',
  execution: 'Actual onRowsUpdate extracted from TextbookEditorView.vue; two successive input events.',
  expected: 'One stable rowId for the same new row, returned to editor rows.',
  actual: { firstId, secondId, editorRows: allEditor.rows.value },
  issueReproduced: firstId !== secondId && !allEditor.rows.value[0]?.tokenId,
  sources: ['toolbox-web/src/views/admin/TextbookEditorView.vue:395', 'toolbox-web/src/lib/admin-textbook-draft.ts:126']
});

const unitEditor = editorHarness('unit-selected');
unitEditor.update([{ text: '山' }]);
unitEditor.update([{ text: '山峰' }]);
unitEditor.update([{ text: '山峰高' }]);
evidence.push({
  id: 'F03-current-unit-input-duplicates-unassigned', severity: 'P1',
  execution: 'Actual onRowsUpdate; initially empty selected unit; three input events for a single cell.',
  expected: [{ text: '山峰高', clientUnitId: 'unit-selected', stableRowId: true }],
  actual: unitEditor.entries.value,
  issueReproduced: unitEditor.entries.value.length === 3 && unitEditor.entries.value.every(entry => entry.clientUnitId === null),
  sources: ['toolbox-web/src/views/admin/TextbookEditorView.vue:395', 'toolbox-web/src/lib/admin-textbook-draft.ts:137']
});

const staleActual = applyCompletionResults(
  [{ tokenId: 'r1', english: 'dog', meaning: '' }], new Set(['r1']), [{ rowId: 'r1', meaning: '猫' }]
);
evidence.push({
  id: 'F04-stale-translation-wrong-pair', severity: 'P1',
  execution: 'Direct applyCompletionResults; request began for cat, user changed source to dog before cat result arrived.',
  expected: { english: 'dog', meaning: '', filledCount: 0 }, actual: staleActual,
  issueReproduced: staleActual.pairs[0]?.english === 'dog' && staleActual.pairs[0]?.meaning === '猫',
  sources: ['toolbox-web/src/views/admin/TextbookEditorView.vue:231', 'toolbox-web/src/lib/admin-textbook-draft.ts:326']
});

const tsvInput = '明天见\tSee you tomorrow!\n\n放学后\tafter school';
const tsvActual = parseBulkText(tsvInput);
evidence.push({
  id: 'F05-tsv-not-paired-punctuation-and-blank-loss', severity: 'P1',
  execution: 'Direct parseBulkText used by onBulkPaste.', input: tsvInput,
  expected: { rows: [{ meaning: '明天见', english: 'See you tomorrow!' }, { meaning: '', english: '' }, { meaning: '放学后', english: 'after school' }], blankRowsPreserved: true },
  actual: tsvActual,
  issueReproduced: tsvActual.items[0] === '明天见\tSee you tomorrow' && tsvActual.items.length === 2 && tsvActual.blankCount === 1,
  sources: ['toolbox-web/src/lib/admin-bulk-paste.ts:27', 'toolbox-web/src/lib/admin-bulk-paste.ts:43', 'toolbox-web/src/views/admin/TextbookEditorView.vue:139']
});

const pinyinRows = { value: [{ tokenId: 'r-long', text: '长', pinyin: 'zhǎng', automaticInitialized: false }] };
const pinyinBefore = pinyinRows.value[0].pinyin;
const pinyinNotice = { value: '' };
const runCompletion = originalHandler('runAutomaticCompletion', {
  rows: pinyinRows, subject: { value: 'chinese' }, notice: pinyinNotice,
  beginAutomaticCompletion, suggestPinyin, onRowsUpdate: () => {},
  selectCompletionTargets: () => { throw new Error('Unexpected English path'); },
  translateEntries: () => { throw new Error('Network requests forbidden in probe'); }, applyCompletionResults
});
await runCompletion(false);
evidence.push({
  id: 'F06-completion-overwrites-nonempty-pinyin', severity: 'P1',
  execution: 'Actual runAutomaticCompletion(false) extracted from Vue source. Existing loaded/manual pinyin has no initialized flag; no input event sets that flag.',
  expected: { text: '长', pinyin: pinyinBefore, reason: 'Nonempty pinyin must be retained regardless of initialized flag.' },
  actual: { rows: pinyinRows.value, message: pinyinNotice.value },
  issueReproduced: pinyinRows.value[0].pinyin !== pinyinBefore,
  sources: ['toolbox-web/src/views/admin/TextbookEditorView.vue:190', 'toolbox-web/src/views/admin/TextbookEditorView.vue:201', 'toolbox-web/src/views/admin/TextbookEditorView.vue:210']
});

const report = {
  generatedAt: new Date().toISOString(), scope: 'Read-only frontend function probes. No browser mount, production API, OCR upload, PDF generation or physical printing.',
  editorSource: editorPath,
  summary: { probes: evidence.length, issuesReproduced: evidence.filter(item => item.issueReproduced).length },
  evidence
};
const outputPath = resolve(probeDirectory, 'frontend-probes.json');
writeFileSync(outputPath, `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify({ output: outputPath, ...report.summary }, null, 2));
if (evidence.some(item => !item.issueReproduced)) process.exitCode = 2;
}

void main().catch(error => { console.error(error); process.exitCode = 1; });
