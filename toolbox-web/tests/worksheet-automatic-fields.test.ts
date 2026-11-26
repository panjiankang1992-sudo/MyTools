import assert from 'node:assert/strict';
import { test } from 'node:test';
import { useWorksheetV2Session } from '../src/composables/useWorksheetV2Session';
import { projectWorksheet } from '../src/lib/worksheet';
import type { TranslationResult } from '../src/services/public-api';
import type { WordEntry } from '../src/types';
import type { CurriculumEntry } from '../src/domain/curriculum';
import { fillSuggestedPinyin } from '../src/domain/worksheet-session';
import { suggestPinyin } from '../src/lib/pinyin-adapter';

/** 仅翻译网络与 PDF 字节边界可控，执行真实会话、处理和题目投影。 */
function harness(subject: 'chinese' | 'english' = 'english', deferChinese = false) {
  const pending: Array<{ items: ReadonlyArray<{ id: string; text: string }>; signal: AbortSignal;
    resolve: (results: TranslationResult[]) => void; reject: (error: Error) => void }> = [];
  const rendered: WordEntry[][] = [];
  const stored = new Map<string, string>();
  const flow = useWorksheetV2Session(subject, {
    storage: { getItem: key => stored.get(key) ?? null, setItem: (key, value) => { stored.set(key, value); }, removeItem: key => { stored.delete(key); } },
    translate: (items, signal) => subject === 'chinese' && !deferChinese ? Promise.resolve([])
      : new Promise((resolve, reject) => { pending.push({ items, signal, resolve, reject }); }),
    recordSuccess: () => undefined, recordPrint: () => undefined, release: () => undefined,
    render: async (entries, options) => {
      rendered.push(entries);
      return { bytes: new TextEncoder().encode(JSON.stringify(projectWorksheet(entries, options))), filename: 'worksheet.pdf', pageCount: 1, url: 'blob:automatic' };
    }
  });
  return { flow, pending, rendered };
}

/** 创建与当前原文严格对应的网络回包。 */
function translated(item: { id: string; text: string }, englishText: string, meaning: string): TranslationResult {
  return { id: item.id, sourceText: item.text, englishText, meaning, status: 'translated' };
}

/** 构造已选中的教材内容，验证导入来源不影响自动补全。 */
function curriculumEntry(text: string, overrides: Partial<CurriculumEntry> = {}): CurriculumEntry {
  return {
    entryId: `entry-${text}`, entryVersion: '1.0.0', editionId: 'edition', unitId: 'unit', lessonId: null,
    order: 1, form: 'word', listTypes: ['vocabulary'], text, pinyinDisplay: null, pinyinSegments: [],
    meaning: null, partOfSpeech: null, senseId: `sense-${text}`, context: null, promptContext: null,
    acceptableAnswers: [], sourceRefs: [], reviewStatus: 'published', blockingIssues: [], ...overrides
  };
}


test('星期一到星期天自动翻译后直接生成，原文、中文提示和英语答案各归其位', async () => {
  const { flow, pending, rendered } = harness();
  const chinese = ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期天'];
  const english = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
  flow.applyRawText(chinese.join(' '));
  flow.applyMode('english-dictation');
  flow.prepareConfirmation();
  assert.equal(flow.automaticPreparing.value, true);
  assert.equal(pending.length, 1);
  const generating = flow.generateNow();
  pending[0]!.resolve(pending[0]!.items.map((item, index) => translated(item, english[index]!, chinese[index]!)));
  await generating;
  assert.equal(flow.canOutput.value, true);
  assert.equal(flow.automaticError.value, null);
  assert.equal(flow.session.value.document.rawText, chinese.join(' '));
  assert.deepEqual(rendered[0]!.map(item => item.text), english);
  const questions = JSON.parse(new TextDecoder().decode(flow.artifact.value!.bytes));
  assert.deepEqual(questions.map((item: { prompt: string }) => item.prompt), chinese);
  assert.ok(questions.every((item: object) => !Object.hasOwn(item, 'answerText')));
  await flow.generateAnswerNow();
  const answers = JSON.parse(new TextDecoder().decode(flow.answerArtifact.value!.bytes));
  assert.deepEqual(answers.map((item: { answerText: string }) => item.answerText), english);
  assert.equal(pending.length, 1, '完成后重复生成不再次请求');
});

test('英文和短语自动补中文，保留整条短语边界及用户已有释义', async () => {
  const { flow, pending } = harness();
  flow.applyParsePolicy('one-per-line');
  flow.applyRawText('ice cream\nMonday');
  const monday = flow.session.value.document.tokens[1]!;
  flow.editMeaning(monday.tokenId, '周一');
  const task = flow.prepareAutomaticFields();
  assert.deepEqual(pending[0]!.items.map(item => item.text), ['ice cream']);
  pending[0]!.resolve([translated(pending[0]!.items[0]!, 'ice cream', '冰淇淋')]);
  await task;
  assert.deepEqual(flow.session.value.document.tokens.map(item => item.meaning), ['冰淇淋', '周一']);
  assert.equal(flow.session.value.document.tokens[0]!.meaningState, 'suggested');
  assert.equal(flow.session.value.document.tokens[1]!.meaningState, 'confirmed');
});

test('翻译中手工编辑不会被迟到结果覆盖，已生成旧结果失效', async () => {
  const { flow, pending } = harness();
  flow.applyRawText('星期一');
  const task = flow.prepareAutomaticFields();
  const id = flow.session.value.document.tokens[0]!.tokenId;
  flow.editEnglish(id, 'Monday');
  flow.editMeaning(id, '周一');
  assert.equal(pending[0]!.signal.aborted, true);
  pending[0]!.resolve([translated(pending[0]!.items[0]!, 'Tuesday', '星期二')]);
  assert.equal(await task, false);
  assert.equal(flow.session.value.document.tokens[0]!.englishText, 'Monday');
  assert.equal(flow.session.value.document.tokens[0]!.meaning, '周一');
  assert.equal(flow.automaticPreparing.value, false);
});

test('清空、返回与销毁都取消翻译及待生成动作', async () => {
  for (const cancel of ['clear', 'back', 'dispose'] as const) {
    const { flow, pending, rendered } = harness();
    flow.applyRawText('星期一');
    flow.applyMode('english-dictation');
    const task = flow.generateNow();
    if (cancel === 'clear') flow.clearAll();
    else if (cancel === 'back') flow.goBackToEdit();
    else flow.dispose();
    pending[0]!.resolve([translated(pending[0]!.items[0]!, 'Monday', '星期一')]);
    await task;
    assert.equal(pending[0]!.signal.aborted, true);
    assert.equal(rendered.length, 0);
    assert.equal(flow.artifact.value, null);
  }
});

test('旧原文回包晚于新原文，不会污染新翻译', async () => {
  const { flow, pending } = harness();
  flow.applyRawText('星期一');
  const first = flow.prepareAutomaticFields();
  flow.applyRawText('星期二');
  const second = flow.prepareAutomaticFields();
  pending[1]!.resolve([translated(pending[1]!.items[0]!, 'Tuesday', '星期二')]);
  await second;
  pending[0]!.resolve([translated(pending[0]!.items[0]!, 'Monday', '星期一')]);
  await first;
  assert.equal(flow.session.value.document.tokens[0]!.englishText, 'Tuesday');
  assert.equal(flow.session.value.document.rawText, '星期二');
});

test('部分翻译失败不自动重试，显式补全只请求缺失项且保留成功项', async () => {
  const { flow, pending } = harness();
  flow.applyRawText('星期一 星期二');
  const first = flow.prepareAutomaticFields();
  pending[0]!.resolve([translated(pending[0]!.items[0]!, 'Monday', '星期一')]);
  await first;
  await flow.prepareAutomaticFields();
  assert.equal(pending.length, 1);
  assert.equal(flow.session.value.document.tokens[0]!.englishText, 'Monday');
  assert.match(flow.automaticError.value ?? '', /1 项/u);
  const explicit = flow.prepareAutomaticFields(true);
  assert.deepEqual(pending[1]!.items.map(item => item.text), ['星期二']);
  pending[1]!.resolve([translated(pending[1]!.items[0]!, 'Tuesday', '星期二')]);
  await explicit;
  assert.equal(flow.automaticError.value, null);
});

test('教材进入确认页自动补缺失中文及英文，并保留教材已有释义', async () => {
  const { flow, pending } = harness();
  flow.importCurriculumEntries([
    curriculumEntry('Monday'), curriculumEntry('星期二'), curriculumEntry('week', { meaning: '周，星期' })
  ], 'edition', '1.0.0', '教材词表');
  flow.prepareConfirmation();
  assert.deepEqual(pending[0]!.items.map(item => item.text), ['Monday', '星期二']);
  pending[0]!.resolve([
    translated(pending[0]!.items[0]!, 'Monday', '星期一'), translated(pending[0]!.items[1]!, 'Tuesday', '星期二')
  ]);
  await flow.prepareAutomaticFields();
  assert.deepEqual(flow.editablePairs.value.map(row => [row.meaning, row.english]), [
    ['星期一', 'Monday'], ['星期二', 'Tuesday'], ['周，星期', 'week']
  ]);
  assert.equal(flow.session.value.document.tokens[2]!.meaningState, 'confirmed');
  flow.goBackToEdit();
  flow.prepareConfirmation();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(pending.length, 1, '返回确认页后不重复翻译已有完整字段');
});

test('教材缺失拼音自动补全，已有教材和人工读音都保留', async () => {
  const { flow, pending } = harness('chinese');
  flow.importCurriculumEntries([
    curriculumEntry('长大'), curriculumEntry('银行', { pinyinSegments: [
      { grapheme: '银', syllable: 'yín' }, { grapheme: '行', syllable: 'háng' }
    ] })
  ], 'edition', '1.0.0', '教材词语');
  flow.prepareConfirmation();
  await flow.prepareAutomaticFields();
  assert.deepEqual(flow.editablePairs.value.map(row => row.pinyin), ['zhǎng dà', 'yín háng']);
  assert.equal(flow.session.value.document.tokens[1]!.pinyinState, 'confirmed');
  flow.editPinyin(flow.session.value.document.tokens[0]!.tokenId, 'cháng dà');
  flow.goBackToEdit();
  flow.prepareConfirmation();
  await flow.prepareAutomaticFields();
  assert.deepEqual(flow.editablePairs.value.map(row => row.pinyin), ['cháng dà', 'yín háng']);
  assert.equal(pending.length, 0);
});

test('教材拼音只补对齐数组中的空音节，不覆盖已有或无效读音', async () => {
  const { flow } = harness('chinese');
  flow.importCurriculumEntries([
    curriculumEntry('长大', { pinyinSegments: [{ grapheme: '长', syllable: 'cháng' }, { grapheme: '大', syllable: '' }] }),
    curriculumEntry('银行', { pinyinSegments: [{ grapheme: '银', syllable: 'bad-value' }, { grapheme: '行', syllable: '' }] }),
    curriculumEntry('中国', { pinyinSegments: [{ grapheme: '中', syllable: 'zhōng' }] }),
    curriculumEntry('天空', { pinyinSegments: [{ grapheme: '空', syllable: '' }, { grapheme: '天', syllable: 'tiān' }] })
  ], 'edition', '1.0.0', '教材词语');
  flow.prepareConfirmation();
  await flow.prepareAutomaticFields();
  assert.deepEqual(flow.session.value.document.tokens.map(token => token.pinyinSegments.map(segment => segment.syllable)), [
    ['cháng', 'dà'], ['bad-value', 'háng'], ['zhōng'], ['', 'tiān']
  ]);
  const result = fillSuggestedPinyin(flow.session.value, text => suggestPinyin(text).suggestion);
  assert.equal(result.filled, 0, '无法安全对齐的数组保持不变');
});

test('永久单项失败只尝试一次，保留可编辑条目并显示未完成数量', async () => {
  const { flow, pending } = harness();
  flow.applyRawText('apple');
  const task = flow.prepareAutomaticFields();
  pending[0]!.resolve([]);
  await task;
  await flow.prepareAutomaticFields();
  assert.equal(pending.length, 1);
  assert.equal(flow.automaticPreparing.value, false);
  assert.match(flow.automaticError.value ?? '', /1 项.*手动填写/u);
  flow.editMeaning(flow.session.value.document.tokens[0]!.tokenId, '苹果');
  assert.equal(flow.editablePairs.value[0]!.meaning, '苹果');
  assert.equal(flow.automaticError.value, null);
});

test('网络异常不自动重试或请求后续批次，显式补全成功后清除错误', async () => {
  const recovered = harness();
  recovered.flow.applyRawText('apple');
  const task = recovered.flow.prepareAutomaticFields();
  recovered.pending[0]!.reject(new Error('offline'));
  await task;
  await recovered.flow.prepareAutomaticFields();
  assert.equal(recovered.pending.length, 1);
  const explicit = recovered.flow.prepareAutomaticFields(true);
  recovered.pending[1]!.resolve([translated(recovered.pending[1]!.items[0]!, 'apple', '苹果')]);
  await explicit;
  assert.equal(recovered.flow.automaticError.value, null);

  const failed = harness();
  failed.flow.applyRawText(Array.from({ length: 21 }, () => 'apple').join(' '));
  const failedTask = failed.flow.prepareAutomaticFields();
  assert.equal(failed.pending[0]!.items.length, 20);
  failed.pending[0]!.reject(new Error('offline'));
  await failedTask;
  await failed.flow.prepareAutomaticFields();
  assert.equal(failed.pending.length, 1);
  assert.ok(failed.flow.session.value.document.tokens.every(token => token.automaticInitialized));
  assert.match(failed.flow.automaticError.value ?? '', /21 项/u);
});

test('显式重试中人工修改会取消请求，迟到结果不能覆盖输入', async () => {
  const { flow, pending } = harness();
  flow.applyRawText('apple');
  const first = flow.prepareAutomaticFields();
  pending[0]!.resolve([]);
  await first;
  const retry = flow.prepareAutomaticFields(true);
  flow.editMeaning(flow.session.value.document.tokens[0]!.tokenId, '苹果，苹果树');
  assert.equal(pending[1]!.signal.aborted, true);
  pending[1]!.resolve([translated(pending[1]!.items[0]!, 'apple', '苹果')]);
  assert.equal(await retry, false);
  assert.equal(flow.editablePairs.value[0]!.meaning, '苹果，苹果树');
  await flow.prepareAutomaticFields();
  assert.equal(pending.length, 2);
});

test('进入中文确认页自动补上下文拼音，无需逐条确认便可生成汉字默写', async () => {
  const { flow, pending, rendered } = harness('chinese');
  flow.applyRawText('长大 银行');
  flow.prepareConfirmation();
  await flow.prepareAutomaticFields();
  assert.equal(pending.length, 0);
  flow.applyMode('chinese-dictation');
  await flow.generateNow();
  assert.equal(flow.canOutput.value, true);
  assert.deepEqual(rendered[0]!.map(item => item.pinyin), ['zhǎng dà', 'yín háng']);
  const id = flow.session.value.document.tokens[0]!.tokenId;
  flow.editPinyin(id, 'cháng dà');
  await flow.prepareAutomaticFields();
  assert.equal(flow.session.value.document.tokens[0]!.pinyinSegments[0]!.syllable, 'cháng');
});

test('未知汉字不能以原字假装拼音生成', async () => {
  const { flow, rendered } = harness('chinese');
  flow.applyRawText('𱁬');
  flow.applyMode('chinese-dictation');
  await flow.generateNow();
  assert.equal(rendered.length, 0);
  assert.equal(flow.canOutput.value, false);
});

test('校正英文不自动翻译，显式补全按校正值补译中文并保留原始输入', async () => {
  const { flow, pending } = harness();
  flow.applyRawText('appl');
  flow.editEnglish(flow.session.value.document.tokens[0]!.tokenId, 'apple');
  await flow.prepareAutomaticFields();
  assert.equal(pending.length, 0);
  const task = flow.prepareAutomaticFields(true);
  assert.equal(pending[0]!.items[0]!.text, 'apple');
  pending[0]!.resolve([translated(pending[0]!.items[0]!, 'apple', '苹果')]);
  await task;
  assert.equal(flow.session.value.document.tokens[0]!.meaning, '苹果');
  assert.equal(flow.session.value.document.rawText, 'appl');
});

test('混合中英原文不直接成为含答案的中文题面', async () => {
  const { flow, pending } = harness();
  flow.applyParsePolicy('one-per-line');
  flow.applyRawText('星期一 Monday');
  flow.applyMode('english-dictation');
  const task = flow.generateNow();
  assert.equal(flow.session.value.document.tokens[0]!.meaning, null);
  pending[0]!.resolve([translated(pending[0]!.items[0]!, 'Monday', '星期一')]);
  await task;
  const questions = JSON.parse(new TextDecoder().decode(flow.artifact.value!.bytes));
  assert.equal(questions[0].prompt, '星期一');
  assert.equal(flow.session.value.document.rawText, '星期一 Monday');
});

test('英文改变后保留已有释义，生成预览不再翻译，主动清空也不会自动补回', async () => {
  const { flow, pending, rendered } = harness();
  flow.applyRawText('apple');
  flow.applyMode('english-dictation');
  const initial = flow.prepareAutomaticFields();
  pending[0]!.resolve([translated(pending[0]!.items[0]!, 'apple', '苹果')]);
  await initial;
  flow.editEnglish(flow.session.value.document.tokens[0]!.tokenId, 'banana');
  assert.equal(flow.session.value.document.tokens[0]!.meaning, '苹果');
  await flow.generateNow();
  assert.equal(pending.length, 1);
  assert.equal(rendered[0]![0]!.text, 'banana');
  assert.equal(rendered[0]![0]!.meaning, '苹果');
  flow.editMeaning(flow.session.value.document.tokens[0]!.tokenId, '');
  await flow.generateNow();
  assert.equal(pending.length, 1);
  assert.equal(flow.session.value.document.tokens[0]!.meaning, null);
});

test('完整配对首次进入即初始化，清空后返回、切题型、预览或答案均不自动请求', async () => {
  const { flow, pending } = harness();
  flow.importEditablePairs([{ english: 'apple', meaning: '苹果' }]);
  flow.prepareConfirmation();
  assert.equal(flow.session.value.document.tokens[0]!.automaticInitialized, true);
  const id = flow.session.value.document.tokens[0]!.tokenId;
  flow.updatePair(id, { meaning: '' });
  flow.goBackToEdit();
  flow.prepareConfirmation();
  flow.applyMode('english-dictation');
  await flow.generateNow();
  await flow.generateAnswerNow();
  flow.applyMode('english-meaning');
  await flow.generateNow();
  await flow.generateAnswerNow();
  await flow.prepareAutomaticFields();
  assert.equal(pending.length, 0);
  assert.equal(flow.editablePairs.value[0]!.meaning, '');
  const explicit = flow.prepareAutomaticFields(true);
  assert.equal(pending.length, 1);
  pending[0]!.resolve([translated(pending[0]!.items[0]!, 'apple', '苹果')]);
  await explicit;
  assert.equal(flow.editablePairs.value[0]!.meaning, '苹果');
});

test('清空拼音保留空栏，改汉字和往返预览不再补音；显式补全仍可使用', async () => {
  const { flow } = harness('chinese');
  flow.importEditablePairs([{ text: '春天' }]);
  flow.prepareConfirmation();
  await flow.prepareAutomaticFields();
  const id = flow.session.value.document.tokens[0]!.tokenId;
  assert.equal(flow.editablePairs.value[0]!.pinyin, 'chūn tiān');
  flow.updatePair(id, { text: '夏天' });
  assert.equal(flow.editablePairs.value[0]!.pinyin, 'chūn tiān');
  flow.editPinyin(id, '');
  flow.goBackToEdit();
  flow.prepareConfirmation();
  await flow.prepareAutomaticFields();
  flow.applyMode('chinese-dictation');
  await flow.generateNow();
  await flow.generateAnswerNow();
  flow.prepareStudentReadings();
  assert.equal(flow.editablePairs.value[0]!.pinyin, '');
  assert.equal(flow.canOutput.value, false);
  await flow.prepareAutomaticFields(true);
  assert.equal(flow.editablePairs.value[0]!.pinyin, 'xià tiān');
});

test('首次请求取消后保留初始化标记，再次确认和输出不会偷偷重发', async () => {
  const { flow, pending } = harness();
  flow.importEditablePairs([{ english: 'apple' }]);
  const task = flow.prepareAutomaticFields();
  assert.equal(flow.editablePairs.value[0]!.automaticInitialized, true);
  flow.goBackToEdit();
  pending[0]!.resolve([translated(pending[0]!.items[0]!, 'apple', '苹果')]);
  assert.equal(await task, false);
  flow.prepareConfirmation();
  await flow.prepareAutomaticFields();
  flow.applyMode('english-dictation');
  await flow.generateNow();
  assert.equal(pending.length, 1);
  assert.equal(flow.editablePairs.value[0]!.meaning, '');
});

test('追加教材只初始化新增内容，先前人工清空的条目不再自动补', async () => {
  const { flow, pending } = harness();
  flow.importCurriculumEntries([curriculumEntry('Monday', { meaning: '星期一' })], 'edition', '1.0.0', '教材词表');
  flow.prepareConfirmation();
  flow.editMeaning(flow.session.value.document.tokens[0]!.tokenId, '');
  flow.importCurriculumEntries([curriculumEntry('Tuesday')], 'edition', '1.0.0', '教材词表');
  flow.prepareConfirmation();
  assert.deepEqual(pending[0]!.items.map(item => item.text), ['Tuesday']);
  pending[0]!.resolve([translated(pending[0]!.items[0]!, 'Tuesday', '星期二')]);
  await flow.prepareAutomaticFields();
  assert.deepEqual(flow.editablePairs.value.map(row => row.meaning), ['', '星期二']);
  assert.ok(flow.editablePairs.value.every(row => row.automaticInitialized));
});

test('已初始化的手动空栏经草稿保存恢复后不重新补，旧草稿缺标记也保留原样', async () => {
  const { flow, pending } = harness();
  flow.importEditablePairs([{ english: 'apple', meaning: '苹果' }]);
  flow.prepareConfirmation();
  flow.editMeaning(flow.session.value.document.tokens[0]!.tokenId, '');
  assert.equal(flow.saveDraft(), true);
  flow.clearAll();
  flow.loadDraft();
  flow.prepareConfirmation();
  await flow.prepareAutomaticFields();
  assert.equal(flow.editablePairs.value[0]!.meaning, '');
  assert.equal(flow.editablePairs.value[0]!.automaticInitialized, true);
  assert.equal(pending.length, 0);
});

test('回包除请求代次外还核对原文和两栏快照，直接改动缓冲也不能被补回', async () => {
  const { flow, pending } = harness();
  flow.importEditablePairs([{ english: 'apple' }]);
  const task = flow.prepareAutomaticFields();
  flow.session.value.document.tokens[0]!.englishText = 'banana';
  pending[0]!.resolve([translated(pending[0]!.items[0]!, 'apple', '苹果')]);
  await task;
  assert.equal(flow.editablePairs.value[0]!.meaning, '');
  await flow.prepareAutomaticFields();
  assert.equal(pending.length, 1);
});

test('中文确认页等待教材读音后才生成，优先教材读音且只查询首次', async () => {
  const { flow, pending, rendered } = harness('chinese', true);
  flow.importEditablePairs([{ text: '长大' }]);
  flow.applyMode('chinese-dictation');
  flow.prepareConfirmation();
  assert.equal(flow.automaticPreparing.value, true);
  const task = flow.generateNow();
  assert.equal(rendered.length, 0);
  assert.equal(pending.length, 1);
  const item = pending[0]!.items[0]!;
  pending[0]!.resolve([{ id: item.id, sourceText: item.text, englishText: null, meaning: null,
    status: 'translated', pinyinSegments: [{ grapheme: '长', syllable: 'cháng' }, { grapheme: '大', syllable: 'dà' }] }]);
  await task;
  assert.equal(rendered[0]![0]!.pinyin, 'cháng dà');
  assert.equal(flow.canOutput.value, true);
  flow.goBackToEdit();
  flow.prepareConfirmation();
  await flow.generateNow();
  assert.equal(pending.length, 1);
});

test('中文教材回包等待期间手工改读音或直接改缓冲，均不覆盖且不自动重查', async () => {
  for (const change of ['manual', 'direct', 'cancel'] as const) {
    const { flow, pending, rendered } = harness('chinese', true);
    flow.importEditablePairs([{ text: '长大' }]);
    flow.applyMode('chinese-dictation');
    const task = flow.generateNow();
    const item = pending[0]!.items[0]!;
    if (change === 'manual') flow.editPinyin(item.id, 'zhǎng dà');
    else if (change === 'direct') flow.session.value.document.tokens[0]!.text = '春天';
    else flow.goBackToEdit();
    pending[0]!.resolve([{ id: item.id, sourceText: item.text, englishText: null, meaning: null,
      status: 'translated', pinyinSegments: [{ grapheme: '长', syllable: 'cháng' }, { grapheme: '大', syllable: 'dà' }] }]);
    await task;
    assert.equal(rendered.length, 0);
    assert.equal(flow.editablePairs.value[0]!.pinyin, change === 'manual' ? 'zhǎng dà' : '');
    await flow.prepareAutomaticFields();
    assert.equal(pending.length, 1);
  }
});
