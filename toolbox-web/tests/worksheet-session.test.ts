import assert from 'node:assert/strict';
import test from 'node:test';
import type { CurriculumEntry } from '../src/domain/curriculum';
import {
  appendText, applyEnglishSuggestions, backToEdit, clearSession, createSession, enterOutput, enterProcess,
  confirmUnambiguousTokens, fillSuggestedPinyin, generate, importTextbook, processSession, sessionCanOutput,
  setLayout, setMode, updateTokenEnglish, updateTokenMeaning, updateTokenPinyin,
  setParsePolicy, setProcessing, setRawText, textbookImportRequest
} from '../src/domain/worksheet-session';
import { suggestPinyin } from '../src/lib/pinyin-adapter';

/** 构造教材条目。 */
function entry(text: string, listTypes: CurriculumEntry['listTypes'], index: number): CurriculumEntry {
  return {
    entryId: `e${index}`, entryVersion: '1.0.2', editionId: 'ed-1', unitId: 'u1', lessonId: 'l1',
    order: index, form: text.length > 1 ? 'word' : 'character', listTypes,
    text, pinyinDisplay: null,
    pinyinSegments: [...text].map(grapheme => ({ grapheme, syllable: 'yī' })),
    meaning: null, partOfSpeech: null, senseId: `s${index}`, context: null, promptContext: null,
    acceptableAnswers: [], sourceRefs: [{ sourceId: 's', locator: 'p1', page: 1 }],
    reviewStatus: 'published', blockingIssues: []
  };
}

/** 记录调用参数的渲染器桩。 */
function stubRenderer() {
  const calls: Array<Parameters<Parameters<typeof generate>[1]>[0]> = [];
  const renderer = async (input: Parameters<Parameters<typeof generate>[1]>[0]) => {
    calls.push(input);
    return { generationId: 0, kind: 'student' as const, snapshotHash: 'h', pageCount: 1, sha256: 'sha' };
  };
  return { renderer, calls };
}

test('新建会话是空原文、未选题型，不能输出', () => {
  const session = createSession('chinese');
  assert.equal(session.document.rawText, '');
  assert.equal(session.processing.mode, null);
  assert.equal(sessionCanOutput(session), false);
});

test('先输入原文，再统一选题型；识字表只筛内容不决定题型', async () => {
  let session = createSession('chinese');
  // ① 从识字表导入
  const recognition = [entry('山', ['recognition'], 1), entry('水', ['recognition'], 2)];
  session = importTextbook(session, textbookImportRequest(recognition, 'ed-1', '1.0.2', '识字表', 'replace')).session;
  // 此时仍然没有题型：导入不自动分配。
  assert.equal(session.processing.mode, null, '导入字表不得自动决定题型');
  assert.equal(session.document.rawText, '山 水');

  // ② 再追加写字表：内容变了，题型依旧不变。
  const writing = [entry('田', ['writing'], 3)];
  session = importTextbook(session, textbookImportRequest(writing, 'ed-1', '1.0.2', '写字表', 'append')).session;
  assert.equal(session.processing.mode, null, '增删字表不得改变已选题型');
  assert.equal(session.document.rawText, '山 水 田');

  // ③ 由用户统一选择题型，覆盖全部条目。
  const chosen = setMode(session, 'chinese-dictation');
  assert.deepEqual(chosen.blockers, []);
  session = chosen.session;
  assert.equal(session.processing.mode, 'chinese-dictation');

  // ④ 再看处理结果：三类字表的条目都在同一份题单里，题型是全局的。
  const processed = processSession(session);
  assert.equal(processed.items.length, 3);
  assert.ok(processed.items.every(item => item.text.length >= 1));
});

test('切换字表范围不会改动已选题型', () => {
  let session = createSession('chinese');
  session = importTextbook(session, textbookImportRequest([entry('山', ['recognition'], 1)], 'ed-1', '1.0.2', '识字表', 'replace')).session;
  session = setMode(session, 'chinese-pinyin').session;
  // 换成词语表
  session = importTextbook(session, textbookImportRequest([entry('山水', ['vocabulary'], 2)], 'ed-1', '1.0.2', '词语表', 'replace')).session;
  assert.equal(session.processing.mode, 'chinese-pinyin', '换字表类别不得自动切模式');
});

test('学科与题型不匹配时拒绝，避免英语页选中汉语题型', () => {
  const english = createSession('english');
  const rejected = setMode(english, 'chinese-dictation');
  assert.equal(rejected.session.processing.mode, null);
  assert.ok(rejected.blockers.some(item => item.code === 'WORKSHEET_MODE_SUBJECT_MISMATCH'));
  assert.equal(setMode(english, 'english-dictation').session.processing.mode, 'english-dictation');
});

test('原文保留空格、换行与引号，不被规范化结果覆盖', () => {
  let session = createSession('english');
  const raw = 'apple   "ice cream"\ndon\'t';
  session = setRawText(session, raw).session;
  assert.equal(session.document.rawText, raw, '原文必须逐字保留');
  assert.deepEqual(session.document.tokens.map(token => token.text), ['apple', 'ice cream', "don't"]);
});

test('解析出错时保留旧条目并阻断，不丢用户已输入内容', () => {
  let session = createSession('chinese');
  session = setRawText(session, '春天 花朵').session;
  const before = session.document.tokens.length;
  const broken = setRawText(session, '"未闭合');
  assert.ok(broken.blockers.length > 0, '必须报出引号错误');
  assert.equal(broken.session.document.tokens.length, before, '旧条目不得被清空');
});

test('未选题型时不允许生成，并给出明确原因', async () => {
  let session = createSession('chinese');
  session = setRawText(session, '春天').session;
  const { renderer, calls } = stubRenderer();
  const result = await generate(session, renderer);
  assert.equal(result.artifactGenerated, false);
  assert.ok(result.blockers.some(item => item.code === 'WORKSHEET_MODE_REQUIRED'));
  assert.equal(calls.length, 0, '未选题型不得调用渲染器');
});

test('生成成功后进入预览并可输出，渲染器拿到冻结所需的内容快照', async () => {
  let session = createSession('chinese');
  session = setRawText(session, '春天 花朵').session;
  // 汉字字帖学生卷需要已确认读音，先逐项确认。
  for (const token of [...session.document.tokens]) {
    session = updateTokenPinyin(session, token.tokenId, [...token.text].map(() => 'chūn').join(' ')).session;
  }
  session = setMode(session, 'chinese-dictation').session;
  const { renderer, calls } = stubRenderer();
  const result = await generate(session, renderer);
  assert.equal(result.artifactGenerated, true);
  assert.equal(result.session.workflow.step, 'preview');
  assert.equal(sessionCanOutput(result.session), true);
  assert.equal(calls.length, 1);
  assert.equal(calls[0]!.mode, 'chinese-dictation');
  assert.equal(calls[0]!.entries.length, 2);
  assert.ok(calls[0]!.inputDocumentId.length > 0);
});

test('渲染失败时留在处理步骤并保留原文，不留下可用产物', async () => {
  let session = createSession('chinese');
  session = setRawText(session, '春天').session;
  session = updateTokenPinyin(session, session.document.tokens[0]!.tokenId, 'chūn tiān').session;
  session = setMode(session, 'chinese-dictation').session;
  const failing = async () => { throw new Error('FONT_UNAVAILABLE'); };
  const result = await generate(session, failing);
  assert.equal(result.artifactGenerated, false);
  assert.equal(result.session.workflow.step, 'process');
  assert.equal(result.session.workflow.artifact, null);
  assert.equal(result.session.document.rawText, '春天', '失败不得丢失原文');
});

test('返回编辑不改值时产物仍可输出；改了就过期', async () => {
  let session = createSession('chinese');
  session = setRawText(session, '春天').session;
  session = updateTokenPinyin(session, session.document.tokens[0]!.tokenId, 'chūn tiān').session;
  session = setMode(session, 'chinese-dictation').session;
  const { renderer } = stubRenderer();
  session = (await generate(session, renderer)).session;

  const back = backToEdit(session).session;
  assert.equal(sessionCanOutput(back), true, '未改动时旧产物仍可沿用');
  assert.equal(enterOutput(back).session.workflow.step, 'output');

  const edited = setRawText(back, '春天 花朵').session;
  assert.equal(sessionCanOutput(edited), false, '改了原文后产物必须过期');
  assert.equal(enterOutput(edited).session.workflow.step, 'input', '过期时不得进入输出步骤');
});

test('只改版式只影响排版指纹，不改内容身份', async () => {
  let session = createSession('chinese');
  session = setRawText(session, '春天').session;
  session = setMode(session, 'chinese-dictation').session;
  const { renderer } = stubRenderer();
  session = (await generate(session, renderer)).session;
  const operationId = session.workflow.operationId;
  const restyled = setLayout(session, { gridMm: 14 }).session;
  assert.equal(restyled.workflow.operationId, operationId, '纯版式变化不新建操作');
  assert.equal(restyled.workflow.renderRevision, session.workflow.renderRevision + 1);
  assert.equal(restyled.document.rawText, '春天', '版式变化不得动原文');
});

test('清空后原文与产物都清掉，且迟到结果不得复活', async () => {
  let session = createSession('chinese');
  session = setRawText(session, '春天').session;
  session = setMode(session, 'chinese-dictation').session;
  const { renderer } = stubRenderer();
  session = (await generate(session, renderer)).session;
  const cleared = clearSession(session).session;
  assert.equal(cleared.document.rawText, '');
  assert.equal(cleared.workflow.artifact, null);
  assert.equal(sessionCanOutput(cleared), false);
});

test('追加保留已有原文，追加空串不改动', () => {
  let session = createSession('chinese');
  session = setRawText(session, '春天').session;
  session = appendText(session, '花朵').session;
  assert.equal(session.document.rawText, '春天 花朵');
  assert.equal(appendText(session, '   ').session.document.rawText, '春天 花朵');
});

test('切换分项策略重新解析但不改原文', () => {
  let session = createSession('english');
  session = setRawText(session, 'ice cream\napple pie').session;
  const before = session.document.rawText;
  const switched = setParsePolicy(session, 'one-per-line').session;
  assert.equal(switched.document.rawText, before, '原文不得被改写');
  assert.deepEqual(switched.document.tokens.map(token => token.text), ['ice cream', 'apple pie']);
});

test('处理参数可调，且会递增处理修订号', () => {
  const session = createSession('chinese');
  const changed = setProcessing(session, { repetitions: 3, duplicatePolicy: 'keep-occurrences' }).session;
  assert.equal(changed.processing.repetitions, 3);
  assert.equal(changed.processing.duplicatePolicy, 'keep-occurrences');
  assert.equal(changed.workflow.processingRevision, session.workflow.processingRevision + 1);
});

test('进入处理步骤不改动内容', () => {
  const session = setRawText(createSession('chinese'), '春天').session;
  const processing = enterProcess(session).session;
  assert.equal(processing.workflow.step, 'process');
  assert.equal(processing.document.rawText, '春天');
});

test('教材导入必须保留拼音与义项，否则汉字字帖无法生成', () => {
  let session = createSession('chinese');
  const entries: CurriculumEntry[] = [
    { ...entry('山水', ['vocabulary'], 1), meaning: '山水风景' },
    { ...entry('风云', ['vocabulary'], 2), meaning: '风云变幻' }
  ];
  session = importTextbook(session, textbookImportRequest(entries, 'ed-1', '1.0.2', '词语表', 'replace')).session;
  assert.equal(session.document.tokens.length, 2);
  // 拼音与义项都要跟着条目一起进来，否则后续校验会因为缺字段而阻断。
  for (const token of session.document.tokens) {
    assert.ok(token.pinyinSegments.length > 0, `${token.text} 丢失了拼音`);
    assert.equal(token.pinyinState, 'confirmed');
    assert.ok(token.meaning, `${token.text} 丢失了义项`);
    assert.equal(token.meaningState, 'confirmed');
  }
});

test('导入的条目落到原文区间内，区间外的既有内容不受影响', () => {
  let session = createSession('chinese');
  session = setRawText(session, '自定义内容').session;
  session = importTextbook(session, textbookImportRequest([entry('山水', ['vocabulary'], 1)], 'ed-1', '1.0.2', '词语表', 'append')).session;
  assert.equal(session.document.rawText, '自定义内容 山水');
  const custom = session.document.tokens[0]!;
  const imported = session.document.tokens[1]!;
  assert.equal(custom.text, '自定义内容');
  assert.equal(custom.pinyinSegments.length, 0, '自定义内容不应被塞入教材读音');
  assert.ok(imported.pinyinSegments.length > 0, '导入条目必须带读音');
  assert.equal(imported.sourceRefs[0]!.kind, 'textbook');
});

test('自动补全读音只补缺失项，保留机器状态且不覆盖教材读音', () => {
  let session = createSession('chinese');
  session = importTextbook(session, textbookImportRequest([entry('山水', ['vocabulary'], 1)], 'ed-1', '1.0.2', '词语表', 'replace')).session;
  session = appendText(session, '自定义词').session;
  const textbookToken = session.document.tokens[0]!;
  const customToken = session.document.tokens[1]!;
  assert.ok(textbookToken.pinyinSegments.length > 0);
  assert.equal(customToken.pinyinSegments.length, 0);

  const result = fillSuggestedPinyin(session, text => ({
    segments: [...text].map(grapheme => ({ grapheme, syllable: 'cāi' }))
  }));
  assert.equal(result.filled, 1, '只补缺失读音的那一项');
  const filledTextbook = result.session.document.tokens[0]!;
  const filledCustom = result.session.document.tokens[1]!;
  // 本用例的教材条目夹具统一使用 yī，关键是它必须原样保留而不是被自动候选覆盖。
  assert.equal(filledTextbook.pinyinSegments[0]!.syllable, 'yī', '教材已确认读音不得被覆盖');
  assert.equal(filledTextbook.pinyinState, 'confirmed');
  assert.equal(filledCustom.pinyinState, 'suggested', '本机候选必须保留机器来源');
  assert.deepEqual(filledCustom.pinyinSegments.map(segment => segment.syllable), ['cāi', 'cāi', 'cāi', 'cāi']);
});

test('没有缺失读音时不产生改动，也不产生成功提示', () => {
  let session = createSession('chinese');
  session = setRawText(session, '春天').session;
  session = fillSuggestedPinyin(session, text => ({ segments: [...text].map(g => ({ grapheme: g, syllable: 'chūn' })) })).session;
  const again = fillSuggestedPinyin(session, text => ({ segments: [...text].map(g => ({ grapheme: g, syllable: 'x' })) }));
  assert.equal(again.filled, 0);
  assert.equal(again.session.document.tokens[0]!.pinyinSegments[0]!.syllable, 'chūn', '已有读音不得被再次改写');
});

test('手工改读音即为已确认，且音节数不匹配时拒绝硬对齐', () => {
  let session = createSession('chinese');
  session = setRawText(session, '春天 山水').session;
  const first = session.document.tokens[0]!;
  const mismatch = updateTokenPinyin(session, first.tokenId, 'chūn');
  assert.ok(mismatch.blockers.some(item => item.code === 'WORKSHEET_PINYIN_COUNT_MISMATCH'));
  assert.equal(mismatch.session.document.tokens[0]!.pinyinSegments.length, 0, '不匹配时不得写入半截读音');

  const applied = updateTokenPinyin(session, first.tokenId, 'chūn tiān');
  assert.deepEqual(applied.blockers, []);
  assert.deepEqual(applied.session.document.tokens[0]!.pinyinSegments.map(s => s.syllable), ['chūn', 'tiān']);
  assert.equal(applied.session.document.tokens[0]!.pinyinState, 'confirmed', '用户手工输入即为最高优先级');
});

test('手工填释义标为已确认，清空则回到缺失', () => {
  let session = createSession('english');
  session = setRawText(session, 'apple').session;
  const id = session.document.tokens[0]!.tokenId;
  session = updateTokenMeaning(session, id, '苹果').session;
  assert.equal(session.document.tokens[0]!.meaning, '苹果');
  assert.equal(session.document.tokens[0]!.meaningState, 'confirmed');
  session = updateTokenMeaning(session, id, '   ').session;
  assert.equal(session.document.tokens[0]!.meaning, null);
  assert.equal(session.document.tokens[0]!.meaningState, 'missing');
});

test('批量确认跳过缺读音与多音字条目', () => {
  let session = createSession('chinese');
  session = setRawText(session, '山水 长大 花朵').session;
  // 先用本机候选把三项都标成「待确认」，再批量确认。
  session = fillSuggestedPinyin(session, text => ({
    segments: [...text].map(grapheme => ({ grapheme, syllable: 'cāi' }))
  })).session;
  assert.ok(session.document.tokens.every(token => token.pinyinState === 'suggested'));

  const result = confirmUnambiguousTokens(session, grapheme => grapheme === '长');
  assert.equal(result.confirmed, 2, '只有「山水」「花朵」可被批量确认');
  assert.equal(result.session.document.tokens[0]!.pinyinState, 'confirmed');
  assert.equal(result.session.document.tokens[1]!.pinyinState, 'suggested', '含多音字「长」不得被批量确认');
  assert.equal(result.session.document.tokens[2]!.pinyinState, 'confirmed');
});

test('缺读音的条目不会被批量确认', () => {
  let session = createSession('chinese');
  session = setRawText(session, '春天').session;
  const result = confirmUnambiguousTokens(session, () => false);
  assert.equal(result.confirmed, 0);
  assert.equal(result.session.document.tokens[0]!.pinyinState, 'missing');
});

test('批量确认对含多音字的条目保持未确认', () => {
  let session = createSession('chinese');
  session = setRawText(session, '长大').session;
  session = updateTokenPinyin(session, session.document.tokens[0]!.tokenId, 'zhǎng dà').session;
  const result = confirmUnambiguousTokens(session, grapheme => grapheme === '长');
  assert.equal(result.confirmed, 0, '多音字条目必须由用户单独确认');
  assert.equal(result.session.document.tokens[0]!.pinyinState, 'confirmed', '手工输入过的仍保持已确认');
});

test('同数量正文替换、顺序变化及解析策略变化都使旧产物过期', async () => {
  let session = setMode(setRawText(createSession('chinese'), '春 天').session, 'chinese-pinyin').session;
  session = (await generate(session, stubRenderer().renderer)).session;
  assert.equal(sessionCanOutput(session), true);
  for (const changed of [
    setRawText(session, '秋 冬').session,
    setRawText(session, '天 春').session,
    setParsePolicy(session, 'one-per-line').session
  ]) {
    assert.equal(changed.workflow.artifact?.status, 'stale');
    assert.equal(sessionCanOutput(changed), false, '同数量不能作为内容等价的判据');
    assert.notEqual(enterOutput(changed).session.workflow.step, 'output');
  }
});

test('仅空白、同值回写或等价分项策略变化可以沿用产物与操作身份', async () => {
  let session = setMode(setRawText(createSession('chinese'), '春\n天').session, 'chinese-pinyin').session;
  session = (await generate(session, stubRenderer().renderer)).session;
  const operationId = session.workflow.operationId;
  for (const changed of [
    setRawText(session, '春\n天').session,
    setRawText(session, '  春   \n\n天  ').session,
    setParsePolicy(session, 'one-per-line').session
  ]) {
    assert.equal(sessionCanOutput(changed), true);
    const regenerated = await generate(changed, stubRenderer().renderer);
    assert.equal(regenerated.session.workflow.operationId, operationId, '等价输入不产生新内容操作');
  }
});

test('解析失败进入会话状态，处理与生成均不能借旧条目绕过错误', async () => {
  let session = setMode(setRawText(createSession('chinese'), '春 天').session, 'chinese-pinyin').session;
  session = (await generate(session, stubRenderer().renderer)).session;
  const invalid = setRawText(session, '"unclosed').session;
  assert.equal(invalid.document.revision, session.document.revision + 1);
  assert.equal(invalid.document.parseIssues[0]?.code, 'WORKSHEET_PARSE_QUOTE');
  assert.deepEqual(invalid.document.tokens.map(token => token.text), ['春', '天'], '保留旧条目供用户修复原文');
  assert.deepEqual(processSession(invalid).items, [], '旧条目不得作为当前有效处理结果');
  const stub = stubRenderer();
  const rejected = await generate(invalid, stub.renderer);
  assert.equal(rejected.artifactGenerated, false);
  assert.equal(stub.calls.length, 0);
  assert.equal(rejected.blockers[0]?.code, 'WORKSHEET_PARSE_QUOTE');
  assert.equal(sessionCanOutput(rejected.session), false);
  const repaired = setRawText(invalid, '"秋 冬"').session;
  assert.deepEqual(repaired.document.parseIssues, []);
  const generated = await generate(repaired, stub.renderer);
  assert.equal(generated.artifactGenerated, true);
  assert.deepEqual(stub.calls[0]!.entries.map(token => token.text), ['秋 冬']);
});

test('输出入口同时核对内容指纹和版式版本，不能仅用 current 标志放行', async () => {
  let session = setMode(setRawText(createSession('chinese'), '春 天').session, 'chinese-pinyin').session;
  session = (await generate(session, stubRenderer().renderer)).session;
  const changed = setRawText(session, '秋 冬').session;
  changed.workflow.artifact = { ...changed.workflow.artifact!, status: 'current' };
  assert.equal(sessionCanOutput(changed), false, '伪恢复 current 不能越过修订号核验');
  const mutated = { ...session, layout: { ...session.layout, gridMm: 14 } };
  assert.equal(sessionCanOutput(mutated), false, '即使漏增修订号，指纹也必须发现版式变化');
  assert.notEqual(enterOutput(mutated).session.workflow.step, 'output');
});

test('异步渲染拿到深复制且冻结的嵌套内容与版式快照', async () => {
  let session = importTextbook(createSession('chinese'), textbookImportRequest([entry('春', ['writing'], 1)], 'ed-1', '1', 'test', 'replace')).session;
  session = setMode(session, 'chinese-dictation').session;
  let snapshot: Parameters<Parameters<typeof generate>[1]>[0] | null = null;
  let finish!: () => void;
  const pending = new Promise<void>(resolve => { finish = resolve; });
  const generation = generate(session, async input => {
    snapshot = input;
    await pending;
    return { generationId: 0, kind: 'student', snapshotHash: 'h', pageCount: 1, sha256: 'sha' };
  });
  assert.ok(snapshot);
  const captured = snapshot as Parameters<Parameters<typeof generate>[1]>[0];
  session.document.tokens[0]!.pinyinSegments[0]!.syllable = 'qiū';
  session.document.tokens[0]!.sourceRefs[0]!.editionId = 'changed';
  session.document.tokens[0]!.acceptableAnswers.push('changed');
  session.processing.repetitions = 2;
  session.layout.title = 'changed';
  assert.equal(captured.entries[0]!.pinyinSegments[0]!.syllable, 'yī');
  assert.equal(captured.entries[0]!.sourceRefs[0]!.editionId, 'ed-1');
  assert.deepEqual(captured.entries[0]!.acceptableAnswers, []);
  assert.equal(captured.processing.repetitions, 1);
  assert.equal(captured.layout.title, '');
  assert.equal(Object.isFrozen(captured.entries[0]!.pinyinSegments[0]), true);
  assert.equal(Object.isFrozen(captured.processing), true);
  assert.equal(Object.isFrozen(captured.layout), true);
  finish();
  const completed = await generation;
  assert.equal(sessionCanOutput(completed.session), false, '在途修改不能使旧快照变为当前有效产物');
});

test('可选输出种类按学生卷与答案卷各自要求校验', async () => {
  let session = setMode(setRawText(createSession('chinese'), '春').session, 'chinese-pinyin').session;
  const stub = stubRenderer();
  assert.equal((await generate(session, stub.renderer)).artifactGenerated, true);
  const missingAnswer = await generate(session, stub.renderer, 'answer');
  assert.equal(missingAnswer.artifactGenerated, false);
  assert.ok(missingAnswer.blockers.some(item => item.code === 'WORKSHEET_MISSING_PINYIN'));
  session = updateTokenPinyin(session, session.document.tokens[0]!.tokenId, 'chūn').session;
  const answer = await generate(session, stub.renderer, 'answer');
  assert.equal(answer.artifactGenerated, true);
  assert.equal(answer.session.workflow.artifact?.kind, 'answer');
  assert.equal(sessionCanOutput(answer.session), true);
});

test('中文星期原文转成独立英文题面，保留原中文释义与原文格式', async () => {
  const raw = ' 星期一\n星期二 星期三 星期四 星期五 星期六 星期天 ';
  let session = setRawText(createSession('english'), raw).session;
  session = setMode(session, 'english-dictation').session;
  assert.deepEqual(session.document.tokens.map(token => token.meaning), ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期天']);
  const stub = stubRenderer();
  assert.equal((await generate(session, stub.renderer)).artifactGenerated, true, '仅有中文题面也可以生成默写学生卷');
  assert.ok(stub.calls[0]!.entries.every(token => token.text === ''), '中文原文不能冒充英文答案');
  stub.calls.length = 0;
  const english = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
  const filled = applyEnglishSuggestions(session, session.document.tokens.map((token, index) => ({ tokenId: token.tokenId, sourceText: token.text, englishText: english[index]!, meaning: '不能覆盖的释义' })));
  session = filled.session;
  assert.equal(filled.filled, 7);
  assert.equal(session.document.rawText, raw);
  assert.deepEqual(session.document.tokens.map(token => token.text), ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期天']);
  assert.ok(session.document.tokens.every(token => token.englishState === 'suggested' && token.meaning === token.text));
  assert.equal((await generate(session, stub.renderer)).artifactGenerated, true);
  assert.deepEqual(stub.calls[0]!.entries.map(token => token.text), english);
  assert.deepEqual(stub.calls[0]!.entries.map(token => token.meaning), session.document.tokens.map(token => token.text));
});

test('英文自动补中文释义，可直接生成且不改变英文短语边界', async () => {
  let session = setRawText(createSession('english'), '"ice cream" Monday').session;
  session = setMode(session, 'english-dictation').session;
  session = applyEnglishSuggestions(session, session.document.tokens.map((token, index) => ({ tokenId: token.tokenId, sourceText: token.text, englishText: 'do not replace source', meaning: ['冰淇淋', '星期一'][index]! }))).session;
  assert.deepEqual(session.document.tokens.map(token => [token.text, token.englishText, token.meaning, token.meaningState]), [['ice cream', undefined, '冰淇淋', 'suggested'], ['Monday', undefined, '星期一', 'suggested']]);
  const stub = stubRenderer();
  assert.equal((await generate(session, stub.renderer)).artifactGenerated, true);
  assert.deepEqual(stub.calls[0]!.entries.map(token => token.text), ['ice cream', 'Monday']);
});

test('机器翻译逐字段补缺失，不覆盖人工、教材或既有机器结果，过时原文不回填', () => {
  let session = setRawText(createSession('english'), '星期一 apple pear').session;
  const [chinese, apple, pear] = session.document.tokens;
  session = updateTokenEnglish(session, chinese!.tokenId, 'Monday').session;
  session = updateTokenMeaning(session, apple!.tokenId, '人工苹果').session;
  const originalRevision = session.document.revision;
  const result = applyEnglishSuggestions(session, [
    { tokenId: chinese!.tokenId, sourceText: chinese!.text, englishText: 'Tuesday', meaning: '星期二' },
    { tokenId: apple!.tokenId, sourceText: apple!.text, englishText: 'orange', meaning: '自动苹果' },
    { tokenId: pear!.tokenId, sourceText: '旧原词', meaning: '梨' }
  ]);
  assert.equal(result.filled, 0);
  assert.equal(result.session.document.revision, originalRevision);
  assert.equal(result.session.document.tokens[0]!.englishText, 'Monday');
  assert.equal(result.session.document.tokens[1]!.meaning, '人工苹果');
  assert.equal(result.session.document.tokens[2]!.meaning, null);
  session = applyEnglishSuggestions(session, [{ tokenId: pear!.tokenId, sourceText: pear!.text, meaning: '梨' }]).session;
  assert.equal(applyEnglishSuggestions(session, [{ tokenId: pear!.tokenId, sourceText: pear!.text, meaning: '覆盖梨' }]).filled, 0);
});

test('非法翻译和混合输入不生成，英文手工修改不改原文且使产物失效', async () => {
  let session = setMode(setRawText(createSession('english'), '星期一 Monday星期二').session, 'english-meaning').session;
  const [first, second] = session.document.tokens;
  const ignored = applyEnglishSuggestions(session, [{ tokenId: first!.tokenId, sourceText: first!.text, englishText: '星期一', meaning: 'Monday' }]);
  assert.equal(ignored.filled, 0);
  const stub = stubRenderer();
  assert.equal((await generate(ignored.session, stub.renderer)).artifactGenerated, false);
  session = updateTokenEnglish(session, first!.tokenId, 'Monday').session;
  session = updateTokenEnglish(session, second!.tokenId, 'Tuesday').session;
  session = (await generate(session, stub.renderer)).session;
  assert.equal(sessionCanOutput(session), true);
  const edited = updateTokenEnglish(session, first!.tokenId, 'Monday星期一');
  assert.equal(edited.session.document.rawText, '星期一 Monday星期二');
  assert.equal(edited.session.document.tokens[0]!.englishText, 'Monday星期一', '保留暂时非法的编辑，控件不跳回旧英文');
  assert.ok(edited.blockers.some(issue => issue.code === 'WORKSHEET_INVALID_ENGLISH'));
  assert.equal(sessionCanOutput(edited.session), false);
  assert.equal((await generate(edited.session, stub.renderer)).artifactGenerated, false);
});

test('正文变更清理旧译文，等价空白编辑保留规范英文与释义', () => {
  let session = setRawText(createSession('english'), '星期一').session;
  const token = session.document.tokens[0]!;
  session = applyEnglishSuggestions(session, [{ tokenId: token.tokenId, sourceText: token.text, englishText: 'Monday' }]).session;
  const padded = setRawText(session, ' 星期一\n').session;
  assert.equal(padded.document.tokens[0]!.englishText, 'Monday');
  const changed = setRawText(session, '星期二').session;
  assert.equal(changed.document.tokens[0]!.englishText, undefined);
  assert.equal(changed.document.tokens[0]!.meaning, '星期二');
});

test('完整自动拼音包括多音词可直接生成，未知字和错位仍阻断', async () => {
  let session = setMode(setRawText(createSession('chinese'), '春天 长大 重庆 行').session, 'chinese-dictation').session;
  session = fillSuggestedPinyin(session, text => suggestPinyin(text).suggestion).session;
  const stub = stubRenderer();
  assert.equal((await generate(session, stub.renderer)).artifactGenerated, true);
  assert.ok(session.document.tokens.every(token => token.pinyinState === 'suggested'));
  session = setRawText(session, '𱁬山').session;
  session = fillSuggestedPinyin(session, text => suggestPinyin(text).suggestion).session;
  assert.deepEqual(session.document.tokens[0]!.pinyinSegments, [{ grapheme: '𱁬', syllable: '' }, { grapheme: '山', syllable: 'shān' }]);
  assert.equal((await generate(session, stub.renderer)).artifactGenerated, false);
});
