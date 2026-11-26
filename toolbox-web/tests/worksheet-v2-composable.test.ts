import assert from 'node:assert/strict';
import { test } from 'node:test';
import { useWorksheetV2Session } from '../src/composables/useWorksheetV2Session';
import { projectWorksheet } from '../src/lib/worksheet';
import type { WordEntry, WorksheetArtifact, WorksheetOptions } from '../src/types';
import type { DraftStorage } from '../src/domain/worksheet-draft-v2';

/** 每个测试使用独立内存存储，不写入真实用户草稿。 */
function memoryStorage(): DraftStorage {
  const values = new Map<string, string>();
  return { getItem: key => values.get(key) ?? null, setItem: (key, value) => { values.set(key, value); }, removeItem: key => { values.delete(key); } };
}

/** 用真实投影器检查输入与配置，只有 PDF 字节渲染在竞态测试里替换为可控边界。 */
function harness(subject: 'chinese' | 'english' = 'chinese', options: { delayed?: boolean; storage?: DraftStorage } = {}) {
  const calls: Array<{ entries: WordEntry[]; options: WorksheetOptions; resolve: (value: WorksheetArtifact) => void; reject: (error: Error) => void; artifact: WorksheetArtifact }> = [];
  const released: string[] = [];
  const success: string[] = [];
  const storage = options.storage ?? memoryStorage();
  const flow = useWorksheetV2Session(subject, {
    storage,
    release: value => { released.push(value.url); },
    recordSuccess: (_category, _tool, operation) => { success.push(operation); },
    recordPrint: () => undefined,
    translate: async () => [],
    render: (entries, configuration) => {
      const questions = projectWorksheet(entries, configuration);
      const artifact = { bytes: new TextEncoder().encode(JSON.stringify(questions)), filename: configuration.answer ? 'answer.pdf' : 'student.pdf', url: `blob:test-${calls.length}`, pageCount: 1 };
      let resolve!: (value: WorksheetArtifact) => void;
      let reject!: (error: Error) => void;
      const result = new Promise<WorksheetArtifact>((done, fail) => { resolve = done; reject = fail; });
      calls.push({ entries, options: configuration, resolve, reject, artifact });
      if (!options.delayed) resolve(artifact);
      return result;
    }
  });
  return { flow, calls, released, success, storage };
}

/** 建立有效中文学生卷输入。 */
function enterChinese(flow: ReturnType<typeof useWorksheetV2Session>): void {
  flow.applyRawText('春 天');
  flow.applyMode('chinese-pinyin');
}

test('同数量替换、分项策略与版式变化均阻断旧学生 PDF', async () => {
  const { flow } = harness();
  enterChinese(flow);
  await flow.generateNow();
  assert.equal(flow.canOutput.value, true);
  flow.applyRawText('秋 冬');
  assert.equal(flow.canOutput.value, false);
  assert.equal(flow.artifactStale.value, true);
  await flow.generateNow();
  flow.applyParsePolicy('one-per-line');
  assert.equal(flow.canOutput.value, false);
  await flow.generateNow();
  flow.applyLayout({ gridMm: 14, title: 'New title' });
  assert.equal(flow.canOutput.value, false);
});

test('无效原文不调用渲染器，也不会用旧 tokens 再生成', async () => {
  const { flow, calls } = harness();
  enterChinese(flow);
  await flow.generateNow();
  flow.applyRawText('"unclosed');
  await flow.generateNow();
  assert.equal(calls.length, 1);
  assert.equal(flow.canOutput.value, false);
  assert.equal(flow.session.value.document.rawText, '"unclosed');
  assert.equal(flow.blockers.value[0]?.code, 'WORKSHEET_PARSE_QUOTE');
});

test('等价空白修改保留当前学生产物且不会多计成功', async () => {
  const { flow, success } = harness();
  enterChinese(flow);
  await flow.generateNow();
  flow.applyRawText('  春\n天  ');
  assert.equal(flow.canOutput.value, true);
  await flow.generateNow();
  assert.equal(success.length, 1);
});

test('清空时废弃在途生成，迟到结果只释放资源', async () => {
  const { flow, calls, released, success } = harness('chinese', { delayed: true });
  enterChinese(flow);
  const pending = flow.generateNow();
  assert.equal(flow.generating.value, true);
  flow.clearAll();
  calls[0]!.resolve(calls[0]!.artifact);
  await pending;
  assert.equal(flow.session.value.document.rawText, '');
  assert.equal(flow.artifact.value, null);
  assert.equal(flow.canOutput.value, false);
  assert.equal(flow.generating.value, false);
  assert.deepEqual(released, ['blob:test-0']);
  assert.equal(success.length, 0);
});

test('先发后至生成不覆盖后续编辑及最新产物', async () => {
  const { flow, calls, released } = harness('chinese', { delayed: true });
  enterChinese(flow);
  const first = flow.generateNow();
  flow.applyRawText('秋 冬');
  flow.applyLayout({ gridMm: 14, title: 'Second' });
  const second = flow.generateNow();
  assert.equal(calls[0]!.options.gridMm, 12);
  assert.equal(calls[0]!.options.title, '');
  calls[1]!.resolve(calls[1]!.artifact);
  await second;
  calls[0]!.resolve(calls[0]!.artifact);
  await first;
  assert.equal(flow.session.value.document.rawText, '秋 冬');
  assert.equal(flow.artifact.value?.url, 'blob:test-1');
  assert.equal(flow.canOutput.value, true);
  assert.deepEqual(released, ['blob:test-0']);
});

test('生成失败保留修改后的原文，旧卷不能冒充当前卷', async () => {
  const { flow, calls } = harness('chinese', { delayed: true });
  enterChinese(flow);
  let pending = flow.generateNow();
  calls[0]!.resolve(calls[0]!.artifact);
  await pending;
  flow.applyRawText('秋 冬');
  pending = flow.generateNow();
  calls[1]!.reject(new Error('FONT_UNAVAILABLE'));
  await pending;
  assert.equal(flow.session.value.document.rawText, '秋 冬');
  assert.equal(flow.canOutput.value, false);
  assert.match(flow.errorMessage.value, /字体/);
  assert.equal(flow.generating.value, false);
});

test('单项确认不确认其他多音字，空读音不能确认', async () => {
  const { flow } = harness();
  flow.applyRawText('行 重');
  flow.fillReadings();
  await flow.prepareAutomaticFields();
  const [first, second] = flow.session.value.document.tokens;
  flow.confirmToken(first!.tokenId);
  assert.equal(flow.session.value.document.tokens[0]!.pinyinState, 'confirmed');
  assert.equal(flow.session.value.document.tokens[1]!.pinyinState, 'suggested');
  assert.equal(flow.session.value.document.tokens[1]!.tokenId, second!.tokenId);
  flow.applyRawText('山');
  flow.confirmToken(flow.session.value.document.tokens[0]!.tokenId);
  assert.notEqual(flow.session.value.document.tokens[0]!.pinyinState, 'confirmed');
});

test('重复只展开一次且保留处理层确定的题序', async () => {
  const { flow, calls } = harness();
  enterChinese(flow);
  flow.applyProcessing({ repetitions: 2 });
  await flow.generateNow();
  const rendered = JSON.parse(new TextDecoder().decode(flow.artifact.value!.bytes)) as Array<{ prompt: string }>;
  assert.deepEqual(rendered.map(item => item.prompt), ['春', '天', '春', '天']);
  assert.equal(calls[0]!.options.expandedQuestions, true);
  assert.equal(calls[0]!.options.repetitions, 1);
});

test('200 项重复两次能够输出 400 题，不被渲染层误判超 200 项', async () => {
  const { flow } = harness('english');
  flow.applyRawText(Array.from({ length: 200 }, (_, i) => `word${i}`).join(' '));
  flow.applyMode('english-meaning');
  flow.applyProcessing({ repetitions: 2 });
  await flow.generateNow();
  assert.equal(flow.canOutput.value, true);
  assert.equal(JSON.parse(new TextDecoder().decode(flow.artifact.value!.bytes)).length, 400);
  flow.applyProcessing({ repetitions: 3 });
  await flow.generateNow();
  assert.equal(flow.canOutput.value, false);
});

test('学生卷与答案独立生成、独立校验，缺答案不破坏可用学生卷', async () => {
  const { flow, calls } = harness('english');
  flow.applyRawText('apple');
  flow.applyMode('english-meaning');
  await flow.generateNow();
  await flow.generateAnswerNow();
  assert.equal(calls.length, 1);
  assert.equal(flow.canOutput.value, true);
  assert.equal(flow.canOutputAnswer.value, false);
  flow.editMeaning(flow.session.value.document.tokens[0]!.tokenId, '苹果');
  await flow.generateNow();
  const studentUrl = flow.artifact.value!.url;
  await flow.generateAnswerNow();
  assert.equal(flow.canOutputAnswer.value, true);
  assert.equal(flow.artifact.value!.url, studentUrl);
  const student = JSON.parse(new TextDecoder().decode(flow.artifact.value!.bytes));
  const answer = JSON.parse(new TextDecoder().decode(flow.answerArtifact.value!.bytes));
  assert.equal(student[0].answerText, undefined);
  assert.equal(answer[0].answerText, '苹果');
  flow.applyRawText('pear');
  assert.equal(flow.canOutput.value, false);
  assert.equal(flow.canOutputAnswer.value, false);
});

test('真实 composable 保存后在新会话恢复短语边界、校对及版式', () => {
  const source = harness('english');
  source.flow.applyParsePolicy('one-per-line');
  source.flow.applyRawText('ice cream\nGood morning');
  source.flow.applyMode('english-dictation');
  source.flow.editMeaning(source.flow.session.value.document.tokens[0]!.tokenId, '冰淇淋');
  source.flow.editMeaning(source.flow.session.value.document.tokens[1]!.tokenId, '早上好');
  source.flow.applyLayout({ title: 'Saved title', gridMm: 14 });
  assert.equal(source.flow.saveDraft(), true);
  const restored = harness('english', { storage: source.storage }).flow;
  restored.loadDraft();
  assert.equal(restored.session.value.document.rawText, 'ice cream\nGood morning');
  assert.equal(restored.session.value.document.parsePolicy, 'one-per-line');
  assert.deepEqual(restored.session.value.document.tokens.map(token => [token.text, token.meaning]), [['ice cream', '冰淇淋'], ['Good morning', '早上好']]);
  assert.equal(restored.session.value.layout.title, 'Saved title');
  assert.equal(restored.session.value.layout.gridMm, 14);
  assert.equal(restored.session.value.processing.mode, 'english-dictation');
  assert.equal(restored.artifact.value, null);
});

test('v1 恢复保留题型与配对字段，旧存储字节不改变', () => {
  const storage = memoryStorage();
  const old = JSON.stringify({ schemaVersion: 1, mode: 'chinese-dictation', title: 'Legacy', customEntries: [{ id: 'one', text: '春', pinyin: 'chūn', meaning: '', unit: 1, kind: 'character' }], selectedIds: [], layout: { gridMm: 14 } });
  storage.setItem('yuyutian-toolbox:draft:v1:chinese', old);
  const { flow } = harness('chinese', { storage });
  flow.loadDraft();
  assert.equal(flow.session.value.processing.mode, 'chinese-dictation');
  assert.equal(flow.session.value.document.tokens[0]!.pinyinSegments[0]!.syllable, 'chūn');
  assert.equal(flow.session.value.layout.gridMm, 14);
  assert.equal(storage.getItem('yuyutian-toolbox:draft:v1:chinese'), old);
});

test('损坏草稿恢复不清空现有编辑或产物', async () => {
  const { flow, storage } = harness();
  enterChinese(flow);
  await flow.generateNow();
  storage.setItem('yuyutian-toolbox:draft:v2:chinese', JSON.stringify({ schemaVersion: 2, subject: 'chinese', rawText: 'bad', corrections: null }));
  flow.loadDraft();
  assert.equal(flow.session.value.document.rawText, '春 天');
  assert.equal(flow.canOutput.value, true);
});

test('页面销毁作废任务且释放已有学生与答案资源', async () => {
  const { flow, calls, released } = harness('chinese', { delayed: true });
  enterChinese(flow);
  const pending = flow.generateNow();
  flow.dispose();
  calls[0]!.resolve(calls[0]!.artifact);
  await pending;
  assert.equal(flow.artifact.value, null);
  assert.equal(flow.canOutput.value, false);
  assert.deepEqual(released, ['blob:test-0']);
});

test('学生先完成时仍可接纳同时生成的相同内容答案', async () => {
  const { flow, calls, released } = harness('english', { delayed: true });
  flow.applyRawText('apple');
  flow.applyMode('english-meaning');
  flow.editMeaning(flow.session.value.document.tokens[0]!.tokenId, '苹果');
  const answer = flow.generateAnswerNow();
  const student = flow.generateNow();
  calls[1]!.resolve(calls[1]!.artifact);
  await student;
  calls[0]!.resolve(calls[0]!.artifact);
  await answer;
  assert.equal(flow.canOutput.value, true);
  assert.equal(flow.canOutputAnswer.value, true);
  assert.equal(released.length, 0);
});

test('读音编辑校验失败不能借旧读音再次生成，也不确认旧值', async () => {
  const { flow, calls } = harness();
  flow.importPairedRows('春天,chūn tiān');
  flow.applyMode('chinese-dictation');
  await flow.generateNow();
  const tokenId = flow.session.value.document.tokens[0]!.tokenId;
  flow.editPinyin(tokenId, 'chūn');
  flow.confirmToken(tokenId);
  await flow.generateNow();
  assert.equal(flow.canOutput.value, false);
  assert.equal(calls.length, 1);
  assert.equal(flow.saveDraft(), false);
  flow.editPinyin(tokenId, 'chūn tiān');
  await flow.generateNow();
  assert.equal(flow.canOutput.value, true);
});

for (const subject of ['chinese', 'english'] as const) {
  test(`${subject} 有效输入确认时默认题型可直接生成学生卷且不包含答案`, async () => {
    const { flow, calls } = harness(subject);
    const raw = subject === 'chinese' ? '春 天' : 'apple pear';
    flow.applyRawText(raw);
    assert.equal(flow.selectedMode.value, null, '输入动作本身不决定题型');
    flow.prepareConfirmation();
    assert.equal(flow.selectedMode.value, subject === 'chinese' ? 'chinese-pinyin' : 'english-meaning');
    assert.equal(flow.session.value.workflow.step, 'process');
    assert.equal(flow.session.value.document.rawText, raw);
    await flow.generateNow();
    assert.equal(flow.canOutput.value, true, '默认学生题型不依赖尚未提供的读音或释义');
    assert.equal(calls.length, 1);
    assert.equal(calls[0]!.options.answer, false);
    const questions = JSON.parse(new TextDecoder().decode(flow.artifact.value!.bytes)) as Array<Record<string, unknown>>;
    assert.ok(questions.every(question => !Object.hasOwn(question, 'answerText')));
    assert.equal(flow.answerArtifact.value, null);
  });
}

test('默认学生卷即使已有读音或释义也不泄露答案', async () => {
  for (const subject of ['chinese', 'english'] as const) {
    const { flow } = harness(subject);
    const answer = subject === 'chinese' ? 'chūn' : '苹果';
    flow.importPairedRows(subject === 'chinese' ? `春,${answer}` : `apple,${answer}`);
    flow.prepareConfirmation();
    await flow.generateNow();
    const projected = new TextDecoder().decode(flow.artifact.value!.bytes);
    assert.equal(projected.includes(answer), false);
  }
});

test('已选的兼容题型在确认、切换及返回编辑后保持，原文与既有配置不改变', () => {
  const { flow } = harness();
  const raw = ' 春\n  天 ';
  flow.applyRawText(raw);
  flow.editPinyin(flow.session.value.document.tokens[0]!.tokenId, 'chūn');
  flow.editPinyin(flow.session.value.document.tokens[1]!.tokenId, 'tiān');
  flow.applyLayout({ title: 'Preserved', gridMm: 14 });
  flow.applyProcessing({ repetitions: 2 });
  flow.applyMode('chinese-dictation');
  flow.prepareConfirmation();
  assert.equal(flow.selectedMode.value, 'chinese-dictation');
  flow.applyMode('chinese-pinyin');
  flow.goBackToEdit();
  flow.prepareConfirmation();
  assert.equal(flow.selectedMode.value, 'chinese-pinyin');
  assert.equal(flow.session.value.document.rawText, raw);
  assert.equal(flow.session.value.layout.title, 'Preserved');
  assert.equal(flow.session.value.layout.gridMm, 14);
  assert.equal(flow.session.value.processing.repetitions, 2);
});

test('非法、纯空白和空引号原文不默认题型，也不离开输入步骤', () => {
  for (const raw of ['', ' \n\t ', '""', '"unclosed', '春\u0000天']) {
    const { flow } = harness();
    flow.applyRawText('春 天');
    flow.applyRawText(raw);
    flow.prepareConfirmation();
    assert.equal(flow.selectedMode.value, null);
    assert.equal(flow.session.value.workflow.step, 'input');
    assert.equal(flow.session.value.document.rawText, raw);
    assert.ok(flow.blockers.value.length > 0);
  }
});

test('确认允许大批量有效原文进入选题设置，不因后续选入上限丢失默认题型', () => {
  const { flow } = harness('english');
  flow.applyRawText(Array.from({ length: 201 }, (_, index) => `word${index}`).join(' '));
  flow.prepareConfirmation();
  assert.equal(flow.selectedMode.value, 'english-meaning');
  assert.equal(flow.session.value.workflow.step, 'process');
  assert.equal(flow.session.value.document.tokens.length, 201);
});

test('完整自动拼音可直接生成，多音字保留可修改状态且不覆盖已校对内容', async () => {
  const { flow, calls } = harness();
  flow.applyRawText('行 重 山');
  const existingId = flow.session.value.document.tokens[2]!.tokenId;
  flow.editPinyin(existingId, 'shān');
  flow.applyMode('chinese-dictation');
  const existing = JSON.stringify(flow.session.value.document.tokens[2]);
  flow.prepareStudentReadings();
  await flow.prepareAutomaticFields();
  const [first, second] = flow.session.value.document.tokens;
  assert.ok(first!.pinyinSegments.length > 0);
  assert.ok(second!.pinyinSegments.length > 0);
  assert.equal(first!.pinyinState, 'suggested');
  assert.equal(second!.pinyinState, 'suggested');
  assert.equal(JSON.stringify(flow.session.value.document.tokens[2]), existing);
  const revision = flow.session.value.document.revision;
  flow.prepareStudentReadings();
  assert.equal(flow.session.value.document.revision, revision, '重复准备不改已有建议或已确认项');
  await flow.generateNow();
  assert.equal(calls.length, 1, '完整上下文读音无需重复逐项确认');
  assert.equal(flow.canOutput.value, true);
});

test('不需拼音的题型或无效原文不会触发读音准备副作用', () => {
  for (const subject of ['chinese', 'english'] as const) {
    const { flow } = harness(subject);
    flow.applyRawText(subject === 'chinese' ? '春 天' : 'apple pear');
    flow.prepareConfirmation();
    const before = JSON.stringify(flow.session.value);
    flow.prepareStudentReadings();
    assert.equal(JSON.stringify(flow.session.value), before);
  }
  const { flow } = harness();
  flow.applyRawText('行 重');
  flow.applyMode('chinese-dictation');
  flow.applyRawText('"unclosed');
  const before = JSON.stringify(flow.session.value);
  flow.prepareStudentReadings();
  assert.equal(JSON.stringify(flow.session.value), before, '解析失败保留的旧词条不能被补音');
});

test('未选择题型不生成，选择汉字默写后生成自动补齐拼音', async () => {
  const { flow, calls } = harness();
  flow.applyRawText('春 天');
  flow.goProcess();
  assert.equal(flow.selectedMode.value, null);
  await flow.generateNow();
  assert.equal(flow.selectedMode.value, null);
  assert.equal(calls.length, 0);
  flow.applyMode('chinese-dictation');
  await flow.generateNow();
  assert.ok(flow.session.value.document.tokens.every(token => token.pinyinSegments.length > 0));
  assert.equal(calls.length, 1);
});
