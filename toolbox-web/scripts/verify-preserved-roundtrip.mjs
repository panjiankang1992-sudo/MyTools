/**
 * 用**真实教材草稿**验证 `pairsToEntries` 不丢 `preserved`（审查 §B10/F03 反向检查）。
 *
 * 背景：编辑器每次重建条目都走
 * `entriesToPairs`（草稿 → 编辑器行）→ `pairsToEntries`（编辑器行 → 草稿）。
 * 这条链路上若不带 `preserved`，管理员改一个字就会把整册书的
 * `senseId` / `context` / `acceptableAnswers` / `sourceRefs` / `pinyinSegments` 等
 * 界面上没有编辑入口的字段**静默清空**。
 *
 * 本脚本不做任何"重新实现"：它直接调用产品代码里的两个函数。
 *
 * 用法：
 *   node --import tsx scripts/verify-preserved-roundtrip.mjs <draft.json> [<draft.json> ...]
 *   node --import tsx scripts/verify-preserved-roundtrip.mjs --out result.json <draft.json> ...
 *
 * 输入是管理接口 `GET /admin/v1/textbooks/{editionId}/draft` 的响应体（含 `draft` 与 `revision`）。
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { entriesToPairs, pairsToEntries } from '../src/lib/admin-textbook-draft.ts';

/** 深比较（只比较 JSON 可表达的值，键顺序无关）。 */
function deepEqual(a, b) {
  if (a === b) return true;
  if (typeof a !== typeof b) return false;
  if (a === null || b === null) return false;
  if (Array.isArray(a) || Array.isArray(b)) {
    if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
    return a.every((item, index) => deepEqual(item, b[index]));
  }
  if (typeof a === 'object') {
    const keysA = Object.keys(a).sort();
    const keysB = Object.keys(b).sort();
    if (keysA.length !== keysB.length || keysA.some((key, index) => key !== keysB[index])) return false;
    return keysA.every(key => deepEqual(a[key], b[key]));
  }
  return false;
}

/** 比较一组"必须原样保留"的字段。 */
const IDENTITY_FIELDS = ['rowId', 'entryId', 'unitId', 'clientUnitId', 'lessonId', 'clientLessonId'];

function compareEntries(before, after, protectedFields) {
  const mismatches = [];
  const tally = {};
  const length = Math.min(before.length, after.length);
  if (before.length !== after.length) {
    mismatches.push({ kind: 'length', before: before.length, after: after.length });
  }
  for (let index = 0; index < length; index += 1) {
    for (const field of [...IDENTITY_FIELDS, ...protectedFields]) {
      if (!deepEqual(before[index][field], after[index][field])) {
        tally[field] = (tally[field] ?? 0) + 1;
        if (mismatches.length < 10) {
          mismatches.push({ kind: 'field', index, rowId: before[index].rowId, field,
            before: before[index][field], after: after[index][field] });
        }
      }
    }
  }
  return { compared: length, tally, mismatches };
}

/** 对单份草稿跑两个用例。 */
function verifyDraft(label, payload) {
  const draft = payload.draft ?? payload;
  const subject = draft.subject;
  const entries = draft.entries ?? [];
  const result = { label, subject, entries: entries.length, cases: {} };

  // 用例 A：原样往返（等价于"打开编辑器又原封不动保存"）。
  const pairsA = entriesToPairs(entries, subject);
  const rebuiltA = pairsToEntries(pairsA, entries, subject);
  result.cases.identityRoundTrip = compareEntries(entries, rebuiltA,
    ['listTypes', 'preserved', 'automaticInitialized']);

  // 用例 B：只改**一行**的正文（模拟管理员改一个字后保存）。
  const targetIndex = entries.findIndex(entry => entry.preserved && Object.keys(entry.preserved).length > 0);
  const pairsB = entriesToPairs(entries, subject).map((pair, index) => {
    if (index !== targetIndex) return pair;
    if (subject === 'english') {
      return { ...pair, english: `${pair.english}q` };
    }
    return { ...pair, text: `${pair.text}试`, pinyin: `${pair.pinyin} shì` };
  });
  const rebuiltB = pairsToEntries(pairsB, entries, subject);
  const comparisonB = compareEntries(entries, rebuiltB, ['listTypes', 'preserved']);
  // 被改的那一行允许 text/pinyin/meaning 与 confirmed 变化；其余行必须逐字段相同。
  const unexpected = comparisonB.mismatches.filter(item => item.index !== targetIndex);
  result.cases.singleRowEdit = { ...comparisonB, targetIndex,
    targetRowId: entries[targetIndex]?.rowId ?? null,
    mismatchesOnUntouchedRows: unexpected.length, mismatches: unexpected.slice(0, 5) };
  result.cases.singleRowEdit.editedRow = targetIndex >= 0 ? {
    before: { text: entries[targetIndex].text, pinyin: entries[targetIndex].pinyin,
      meaning: entries[targetIndex].meaning },
    after: { text: rebuiltB[targetIndex].text, pinyin: rebuiltB[targetIndex].pinyin,
      meaning: rebuiltB[targetIndex].meaning }
  } : null;

  result.pass = result.cases.identityRoundTrip.mismatches.length === 0
    && unexpected.length === 0
    && comparisonB.mismatches.filter(item => item.kind === 'length').length === 0;
  return result;
}

const args = process.argv.slice(2);
let outPath = null;
const files = [];
for (let index = 0; index < args.length; index += 1) {
  if (args[index] === '--out') {
    outPath = args[index + 1];
    index += 1;
  } else {
    files.push(args[index]);
  }
}
if (files.length === 0) {
  console.error('用法：node --import tsx scripts/verify-preserved-roundtrip.mjs [--out result.json] <draft.json> ...');
  process.exit(2);
}

const results = [];
for (const file of files) {
  const payload = JSON.parse(readFileSync(file, 'utf8'));
  const label = file.split('/').pop().replace(/^roundtrip-draft/, '').replace(/\.json$/, '');
  const result = verifyDraft(label, payload);
  results.push(result);
  const roundTrip = result.cases.identityRoundTrip;
  const edit = result.cases.singleRowEdit;
  console.log(`${result.pass ? 'PASS' : 'FAIL'}  ${label}  subject=${result.subject}  entries=${result.entries}`
    + `  | 原样往返不一致字段=${JSON.stringify(roundTrip.tally)}`
    + `  | 改一行后未改动行不一致=${edit.mismatchesOnUntouchedRows}`
    + (Object.keys(edit.tally).length ? ` (编辑行允许变化: ${JSON.stringify(edit.tally)})` : ''));
}
const summary = { files, pass: results.every(item => item.pass), results };
if (outPath) writeFileSync(outPath, JSON.stringify(summary, null, 2), 'utf8');
process.exit(summary.pass ? 0 : 1);
