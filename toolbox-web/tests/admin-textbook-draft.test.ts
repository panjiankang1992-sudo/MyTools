import assert from 'node:assert/strict';
import test from 'node:test';
import {
  applyCompletionResults, beginAutomaticCompletion, computeContentDigest, entriesToPairs, markConfirmed,
  mergeSubset, needsAutomaticCompletion,
  newRowId, pairsToEntries,
  selectCompletionTargets, type DraftEntry
} from '../src/lib/admin-textbook-draft';

/**
 * 草稿与配对编辑器的映射。
 * 重点：rowId 稳定、归属不丢、空白行不入库、内容变化撤销核对状态。
 */

/** 构造一条草稿条目。 */
function entry(overrides: Partial<DraftEntry> = {}): DraftEntry {
  return {
    rowId: 'row-1', entryId: null, unitId: null, clientUnitId: 'cu1',
    lessonId: null, clientLessonId: 'cl1', text: '山', pinyin: 'shān', meaning: null,
    listTypes: ['writing'], confirmed: true, ...overrides
  };
}

test('语文条目映射到左汉字右拼音', () => {
  const pairs = entriesToPairs([entry()], 'chinese');
  assert.equal(pairs[0].text, '山');
  assert.equal(pairs[0].pinyin, 'shān');
  assert.equal(pairs[0].tokenId, 'row-1');
});

test('英语条目映射到左英文右释义', () => {
  const pairs = entriesToPairs([entry({ text: 'apple', pinyin: null, meaning: '苹果' })], 'english');
  assert.equal(pairs[0].english, 'apple');
  assert.equal(pairs[0].meaning, '苹果');
});

test('往返映射保留 rowId 与归属', () => {
  const original = entry();
  const pairs = entriesToPairs([original], 'chinese');
  const back = pairsToEntries(pairs, [original], 'chinese');
  assert.equal(back.length, 1);
  assert.equal(back[0].rowId, 'row-1');
  assert.equal(back[0].clientUnitId, 'cu1');
  assert.equal(back[0].clientLessonId, 'cl1');
  assert.equal(back[0].listTypes.includes('writing'), true);
});

test('空白行不写进草稿', () => {
  const pairs = [{ tokenId: 'row-1', text: '山', pinyin: 'shān' }, { tokenId: 'row-2', text: '  ', pinyin: '' }];
  const entries = pairsToEntries(pairs, [], 'chinese');
  assert.equal(entries.length, 1);
  assert.equal(entries[0].text, '山');
});

test('内容变化即撤销已核对状态', () => {
  const original = entry({ confirmed: true });
  const changed = pairsToEntries([{ tokenId: 'row-1', text: '水', pinyin: 'shuǐ' }], [original], 'chinese');
  assert.equal(changed[0].confirmed, false, '正文改动后不能沿用旧的已核对状态');
});

test('仅第二栏变化同样撤销已核对状态', () => {
  const original = entry({ confirmed: true });
  const changed = pairsToEntries([{ tokenId: 'row-1', text: '山', pinyin: 'shān2' }], [original], 'chinese');
  assert.equal(changed[0].confirmed, false);
});

test('内容未变时保留已核对状态', () => {
  const original = entry({ confirmed: true });
  const same = pairsToEntries([{ tokenId: 'row-1', text: '山', pinyin: 'shān' }], [original], 'chinese');
  assert.equal(same[0].confirmed, true);
});

test('新行获得稳定行标识', () => {
  assert.equal(newRowId(0), 'row-1');
  assert.equal(newRowId(9), 'row-10');
  const entries = pairsToEntries([{ text: '新词', pinyin: 'xīn cí' }], [], 'chinese');
  assert.equal(entries[0].rowId, 'row-1');
});

test('可见子集合并回全部条目时不影响其他单元', () => {
  const others = [entry({ rowId: 'row-9', clientUnitId: 'cu2', text: '水', pinyin: 'shuǐ' })];
  const all = [entry({ rowId: 'row-1', clientUnitId: 'cu1' }), ...others];
  // 只编辑 cu1 的两行（其中一行是新加的）
  const subset = [
    entry({ rowId: 'row-1', clientUnitId: 'cu1', text: '山', pinyin: 'shān' }),
    entry({ rowId: 'row-2', clientUnitId: 'cu1', text: '川', pinyin: 'chuān' })
  ];
  const merged = mergeSubset(all, ['row-1'], subset);
  assert.equal(merged.length, 3, '其他单元的条目必须保留');
  assert.equal(merged.some(item => item.rowId === 'row-9'), true);
  assert.equal(merged.some(item => item.rowId === 'row-2'), true, '新增行应被追加');
});

test('子集中被删除的行确实从完整列表中移除', () => {
  const all = [entry({ rowId: 'row-1' }), entry({ rowId: 'row-2', text: '水', pinyin: 'shuǐ' })];
  const merged = mergeSubset(all, ['row-1', 'row-2'], [entry({ rowId: 'row-1' })]);
  assert.deepEqual(merged.map(item => item.rowId), ['row-1']);
});

test('合并保持原有顺序', () => {
  const all = [entry({ rowId: 'row-1' }), entry({ rowId: 'row-2', text: '水' }), entry({ rowId: 'row-3', text: '火' })];
  const merged = mergeSubset(all, ['row-2'], [entry({ rowId: 'row-2', text: '水水' })]);
  assert.deepEqual(merged.map(item => item.rowId), ['row-1', 'row-2', 'row-3']);
});

test('未标记过的行需要补全，已标记的跳过', () => {
  assert.equal(needsAutomaticCompletion({ text: '山' }), true);
  assert.equal(needsAutomaticCompletion({ text: '山', automaticInitialized: true }), false);
});

test('发起补全前就标记，因此网络失败也不会导致重复补全', () => {
  const pairs = [{ tokenId: 'row-1', text: '山' }, { tokenId: 'row-2', text: '水', automaticInitialized: true }];
  const began = beginAutomaticCompletion(pairs);
  assert.deepEqual([...began.eligibleIds], ['row-1']);
  assert.equal(began.pairs[0].automaticInitialized, true, '必须先把行标记为已初始化');
  assert.equal(began.pairs[1].automaticInitialized, true);
  // 即使后续补全失败，再次调用也不会有任何行可补
  const again = beginAutomaticCompletion(began.pairs);
  assert.equal(again.eligibleIds.size, 0);
});

test('显式重试时忽略标记，重新补全全部行', () => {
  const pairs = [{ tokenId: 'row-1', text: '山', automaticInitialized: true }];
  const began = beginAutomaticCompletion(pairs, true);
  assert.deepEqual([...began.eligibleIds], ['row-1']);
});

test('用户手动清空后不会被再次自动补全（标记优先于字段是否为空）', () => {
  const pairs = [{ tokenId: 'row-1', text: '山', pinyin: '', automaticInitialized: true }];
  const began = beginAutomaticCompletion(pairs);
  assert.equal(began.eligibleIds.size, 0, '用户的清空操作必须被尊重');
});

test('语文补全只针对缺拼音且允许补全的行', () => {
  const pairs = [
    { tokenId: 'row-1', text: '山' },
    { tokenId: 'row-2', text: '水', automaticInitialized: true },
    { tokenId: 'row-3', text: '  ' }
  ];
  const targets = selectCompletionTargets(pairs, 'chinese', new Set(['row-1', 'row-3']));
  assert.deepEqual(targets.map(item => item.rowId), ['row-1'], '已标记的行与空行都不请求');
});

test('英语只补缺一侧的行，两侧都有或都空都不请求', () => {
  const pairs = [
    { tokenId: 'row-1', english: 'apple' },
    { tokenId: 'row-2', meaning: '香蕉' },
    { tokenId: 'row-3', english: 'cat', meaning: '猫' },
    { tokenId: 'row-4' }
  ];
  const targets = selectCompletionTargets(pairs, 'english', new Set(['row-1', 'row-2', 'row-3', 'row-4']));
  assert.deepEqual(targets.map(item => item.rowId), ['row-1', 'row-2']);
  assert.equal(targets[0].missing, 'meaning');
  assert.equal(targets[1].missing, 'english');
});

test('英语已标记初始化的行不再补全（已编辑输入不被自动覆盖）', () => {
  // eligibleIds 由 beginAutomaticCompletion 计算，它本身就会排除已初始化的行；
  // 因此这里必须走完整流程，而不是手工构造一个自相矛盾的集合。
  const pairs = [
    { tokenId: 'row-1', english: 'apple', automaticInitialized: true },
    { tokenId: 'row-2', english: 'banana' }
  ];
  const began = beginAutomaticCompletion(pairs);
  assert.deepEqual([...began.eligibleIds], ['row-2'], '只有未初始化的行可补');
  const targets = selectCompletionTargets(began.pairs, 'english', began.eligibleIds);
  assert.deepEqual(targets.map(item => item.rowId), ['row-2']);
});

test('迟到回包不覆盖用户在等待期间的输入（B07）', () => {
  // 请求发出时 row-1 只有英文；用户在等待期间手工填了释义。
  const current = [{ tokenId: 'row-1', english: 'apple', meaning: '苹果（我填的）' }];
  const result = applyCompletionResults(current, new Set(['row-1']), [{ rowId: 'row-1', meaning: '苹果' }]);
  assert.equal(result.filledCount, 0, '已有内容时不应写入');
  assert.equal(result.pairs[0].meaning, '苹果（我填的）', '用户输入必须优先于自动结果');
});

test('迟到回包只补仍然为空的那一侧', () => {
  const current = [
    { tokenId: 'row-1', english: 'apple' },              // 仍缺释义 → 可补
    { tokenId: 'row-2', meaning: '香蕉', english: '我填了' } // 已补齐 → 不补
  ];
  // F06 之后结果必须带上"对哪段原文算的"：合并会校验它是否仍然匹配。
  // 这两条的原文分别是 apple 与"我填了"。
  const result = applyCompletionResults(current, new Set(['row-1', 'row-2']), [
    { rowId: 'row-1', sourceText: 'apple', meaning: '苹果' },
    { rowId: 'row-2', sourceText: '我填了', english: 'banana' }
  ]);
  assert.equal(result.filledCount, 1);
  assert.equal(result.pairs[0].meaning, '苹果');
  assert.equal(result.pairs[1].english, '我填了');
});

test('不属于本次补全范围的行即使有回包也不写入', () => {
  const current = [{ tokenId: 'row-1', english: 'apple' }];
  const result = applyCompletionResults(current, new Set(),
    [{ rowId: 'row-1', sourceText: 'apple', meaning: '苹果' }]);
  assert.equal(result.filledCount, 0);
  assert.equal(result.pairs[0].meaning, undefined);
});

test('回包中该侧为空时不写入，也不计入补全条数', () => {
  const current = [{ tokenId: 'row-1', english: 'apple' }];
  const result = applyCompletionResults(current, new Set(['row-1']), [{ rowId: 'row-1', meaning: '   ' }]);
  assert.equal(result.filledCount, 0);
  assert.equal(result.pairs[0].meaning, undefined);
});

test('摘要与服务端算法一致：text \\0 pinyin \\0 meaning 的 SHA-256', async () => {
  // 用 Node 自带 crypto 独立算出期望值，避免"用同一实现验证自己"。
  const { createHash } = await import('node:crypto');
  const expected = createHash('sha256').update('山\u0000shān\u0000', 'utf8').digest('hex');
  const actual = await computeContentDigest({ text: '山', pinyin: 'shān', meaning: null });
  assert.equal(actual, expected);
  assert.equal(actual.length, 64);
});

test('映射保留已核对条目的摘要，撤销核对时一并清空', () => {
  const verified = entry({ confirmed: true, confirmedDigest: 'digest-a' });
  const kept = pairsToEntries([{ tokenId: 'row-1', text: '山', pinyin: 'shān' }], [verified], 'chinese');
  assert.equal(kept[0].confirmed, true);
  assert.equal(kept[0].confirmedDigest, 'digest-a', '内容未变时应保留摘要');

  // 内容改动 → 核对撤销，摘要必须一并清空（否则旧摘要会为新内容背书）。
  const changed = pairsToEntries([{ tokenId: 'row-1', text: '水', pinyin: 'shuǐ' }], [verified], 'chinese');
  assert.equal(changed[0].confirmed, false);
  assert.equal(changed[0].confirmedDigest, null);
});

test('未核对条目不携带摘要', () => {
  const plain = entry({ confirmed: false, confirmedDigest: null });
  const mapped = pairsToEntries([{ tokenId: 'row-1', text: '山', pinyin: 'shān' }], [plain], 'chinese');
  assert.equal(mapped[0].confirmedDigest, null);
});

test('只有拿到摘要的条目才会被标记为已核对', () => {
  const entries = [
    entry({ rowId: 'row-1', confirmed: false, confirmedDigest: null }),
    entry({ rowId: 'row-2', text: '水', confirmed: false, confirmedDigest: null })
  ];
  const confirmed = markConfirmed(entries, new Map([['row-1', 'digest-1']]));
  assert.equal(confirmed[0].confirmed, true);
  assert.equal(confirmed[0].confirmedDigest, 'digest-1');
  // 没有摘要的条目保持未确认：不产生"确认了但服务端无法验证"的中间态。
  assert.equal(confirmed[1].confirmed, false);
  assert.equal(confirmed[1].confirmedDigest, null);
});

test('空摘要不被当作有效凭据', () => {
  const entries = [entry({ rowId: 'row-1', confirmed: false, confirmedDigest: null })];
  const confirmed = markConfirmed(entries, new Map([['row-1', '']]));
  assert.equal(confirmed[0].confirmed, false);
});

test('空行被过滤后，新行不会与既有行标识冲突（B04：空行不得导致配对错位）', () => {
  // 既有 row-1；用户新增一行（无 tokenId）并在其前面留了一个空行。
  const existing = [entry({ rowId: 'row-1', text: '山', pinyin: 'shān' })];
  const pairs = [
    { tokenId: 'row-1', text: '山', pinyin: 'shān' },
    { text: '   ', pinyin: '' },              // 空行，会被过滤
    { text: '水', pinyin: 'shuǐ' }            // 新行，无 tokenId
  ];
  const mapped = pairsToEntries(pairs, existing, 'chinese');
  const rowIds = mapped.map(item => item.rowId);
  assert.equal(new Set(rowIds).size, rowIds.length, `行标识不得重复：${JSON.stringify(rowIds)}`);
  // 新行不能顶掉既有的 row-1。
  assert.equal(mapped[0].rowId, 'row-1');
  assert.equal(mapped[0].text, '山');
});

test('在另一节点新增行时，不得顶掉不可见节点的同名行（B04 的边界）', () => {
  // 不可见的 A 节点已有一条 row-1。
  const hidden = entry({ rowId: 'row-1', clientUnitId: 'cuA', text: '旧内容', pinyin: 'jiù' });
  const all = [hidden];
  // 当前编辑 B 节点，一行新内容（无 tokenId）；过滤后位于下标 0，因此生成 row-1。
  const subset = pairsToEntries([{ text: '新内容', pinyin: 'xīn' }], [], 'chinese');
  const merged = mergeSubset(all, [], subset);
  const rowIds = merged.map(item => item.rowId);
  assert.equal(new Set(rowIds).size, rowIds.length,
    `不同节点的行不得共用标识，否则合并会互相覆盖：${JSON.stringify(merged.map(i => [i.rowId, i.text]))}`);
  // 更关键：新内容必须还在。若因行号与隐藏节点相同而被丢弃，用户会以为"输入没了"。
  assert.equal(merged.some(item => item.text === '新内容'), true,
    `新行不得被静默丢弃：${JSON.stringify(merged.map(i => [i.rowId, i.text]))}`);
  assert.equal(merged.some(item => item.text === '旧内容'), true, '隐藏节点的内容也不得丢失');
});

test('切换学科不抹掉另一学科的字段（切到英语再切回，拼音仍在）', () => {
  const chinese = [entry({ rowId: 'row-1', text: '山', pinyin: 'shān', meaning: null, confirmed: false })];
  // 切到英语后映射一次（模拟"编辑过程中切换学科"）。
  const asEnglish = pairsToEntries(entriesToPairs(chinese, 'english'), chinese, 'english');
  assert.equal(asEnglish[0].pinyin, 'shān', '切到英语不得抹掉拼音');
  // 再切回语文：拼音必须还在。
  const backToChinese = pairsToEntries(entriesToPairs(asEnglish, 'chinese'), asEnglish, 'chinese');
  assert.equal(backToChinese[0].pinyin, 'shān', '切回语文后拼音仍应存在');
  assert.equal(backToChinese[0].text, '山');
});

test('英语条目改释义不会清掉同条目上残留的拼音', () => {
  const original = [entry({ rowId: 'row-1', text: 'apple', pinyin: 'píng guǒ', meaning: '苹果', confirmed: false })];
  const edited = pairsToEntries([{ tokenId: 'row-1', english: 'apple', meaning: '苹果（改）' }], original, 'english');
  assert.equal(edited[0].meaning, '苹果（改）');
  assert.equal(edited[0].pinyin, 'píng guǒ', '另一学科的字段应保留而非清空');
});

test('重新加载草稿后不会对同一行二次自动补全（一条内容只补一次）', () => {
  // 保存前：自动补全过但用户尚未核对，且用户手动清空了拼音。
  const saved = entry({
    rowId: 'row-1', text: '山', pinyin: '', confirmed: false,
    confirmedDigest: null, automaticInitialized: true
  });
  // 重新打开草稿（映射成编辑器行）。
  const pairs = entriesToPairs([saved], 'chinese');
  assert.equal(pairs[0].automaticInitialized, true, '已补全标记必须随草稿保留');
  // 再点自动补全：这一行不应被再次补全。
  const began = beginAutomaticCompletion(pairs);
  assert.equal(began.eligibleIds.size, 0, '重新加载后不得二次补全');
  assert.equal(pairs[0].pinyin, '', '用户清空的拼音不得被重新填上');
});

test('已补全标记不因内容改动而丢失（撤销的是核对，不是补全历史）', () => {
  const previous = entry({ rowId: 'row-1', text: '山', pinyin: 'shān', confirmed: true,
    confirmedDigest: 'digest-a', automaticInitialized: true });
  const changed = pairsToEntries([{ tokenId: 'row-1', text: '水', pinyin: 'shuǐ' }], [previous], 'chinese');
  assert.equal(changed[0].confirmed, false, '内容改动应撤销核对');
  assert.equal(changed[0].automaticInitialized, true, '但"补全过"这一事实不变');
});

test('保留映射 preserved 必须随条目重建往返（否则改一个字就丢光义项、上下文与来源）', () => {
  // **这是数据丢失级的性质**：`pairsToEntries` 在每次输入时重建条目对象，
  // 若不带 `preserved`，服务端草稿里的 sourceRefs／partOfSpeech／senseId／
  // context／acceptableAnswers 会在管理员改一个字之后全部消失。
  const previous = entry({
    rowId: 'row-1', text: '山', pinyin: 'shān', listTypes: ['writing'],
    preserved: {
      partOfSpeech: 'noun',
      senseId: 'sense-1',
      sourceRefs: [{ sourceId: '附录A', locator: '第二列', page: 78 }]
    }
  });
  const rebuilt = pairsToEntries([{ tokenId: 'row-1', text: '水', pinyin: 'shuǐ' }], [previous], 'chinese');

  assert.deepEqual(rebuilt[0].preserved, previous.preserved);
  // 与 preserved 同一个理由：类别也不能因为重建而清空。
  assert.deepEqual(rebuilt[0].listTypes, ['writing']);
  // 新行没有既有条目，因此不该凭空造出 preserved。
  const fresh = pairsToEntries([{ text: '火', pinyin: 'huǒ' }], [previous], 'chinese');
  assert.equal(fresh[0].preserved, undefined);
});

test('保留映射是浅拷贝：改动新条目不得污染原条目', () => {
  const previous = entry({ preserved: { partOfSpeech: 'noun' } });
  const rebuilt = pairsToEntries([{ tokenId: 'row-1', text: '水', pinyin: 'shuǐ' }], [previous], 'chinese');
  assert.notEqual(rebuilt[0].preserved, previous.preserved, '必须是新对象，否则撤销快照会被就地改写');
  assert.deepEqual(previous.preserved, { partOfSpeech: 'noun' });
});
