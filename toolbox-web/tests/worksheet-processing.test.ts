import assert from 'node:assert/strict';
import test from 'node:test';
import type { InputSourceRef, InputToken } from '../src/domain/worksheet-input';
import {
  PROCESSING_DEFAULTS, PROCESSING_LIMITS, balancedAvailability, checkGeneratable, dedupeTokens,
  expandToCharacters, equivalentKey, englishTextForToken, isHanCharacter, isValidChineseMeaning,
  isValidEnglishText, isValidPinyinSegments, isValidPinyinSyllable, orderTokens,
  processWorksheetInput, splitGraphemes, type WorksheetProcessingOptions
} from '../src/domain/worksheet-processing';

/** 构造教材来源。 */
function textbookRef(unitId: string, entryId = 'e1'): InputSourceRef {
  return {
    sourceId: 'smartedu', kind: 'textbook', editionId: 'cn-ty-1-1-pep-2024',
    contentVersion: '1.0.2', entryId, unitId, imageId: null, localOverride: false
  };
}

/** 构造自定义来源。 */
function customRef(): InputSourceRef {
  return {
    sourceId: 'local', kind: 'custom', editionId: null, contentVersion: null,
    entryId: null, unitId: null, imageId: null, localOverride: false
  };
}

/** 构造条目。 */
function token(text: string, options: {
  pinyin?: Array<[string, string]>;
  pinyinState?: InputToken['pinyinState'];
  meaning?: string | null;
  senseId?: string | null;
  refs?: InputSourceRef[];
} = {}): InputToken {
  const segments = (options.pinyin ?? []).map(([grapheme, syllable]) => ({ grapheme, syllable }));
  return {
    tokenId: `tok-${text}-${Math.random().toString(36).slice(2, 8)}`,
    text,
    rawStart: 0,
    rawEnd: text.length,
    sourceRefs: options.refs ?? [textbookRef('cn-ty-1-1-pep-2024-u01')],
    pinyinSegments: segments,
    pinyinState: options.pinyinState ?? (segments.length ? 'confirmed' : 'missing'),
    meaning: options.meaning ?? null,
    meaningState: options.meaning ? 'confirmed' : 'missing',
    senseId: options.senseId ?? null,
    acceptableAnswers: segments.map(segment => segment.syllable),
    issues: []
  };
}

/** 覆盖默认参数。 */
function options(overrides: Partial<WorksheetProcessingOptions> = {}): WorksheetProcessingOptions {
  return { ...PROCESSING_DEFAULTS, ...overrides };
}

test('契约默认值：按词、等价去重、全部、输入顺序、重复一次', () => {
  assert.equal(PROCESSING_DEFAULTS.chineseUnit, 'word');
  assert.equal(PROCESSING_DEFAULTS.duplicatePolicy, 'merge-equivalent');
  assert.equal(PROCESSING_DEFAULTS.selection, 'all');
  assert.equal(PROCESSING_DEFAULTS.ordering, 'input');
  assert.equal(PROCESSING_DEFAULTS.repetitions, 1);
  assert.equal(PROCESSING_DEFAULTS.mode, null, '首次新建必须由用户选择题型');
});

test('逐字展开继承词内已确认读音，多音字不用孤字默认读音替换', () => {
  // 「长」在「长大」里读 zhǎng；孤字默认读 cháng，绝不能被替换。
  const word = token('长大', { pinyin: [['长', 'zhǎng'], ['大', 'dà']] });
  const { items } = expandToCharacters([word]);
  assert.deepEqual(items.map(item => item.text), ['长', '大']);
  assert.equal(items[0]!.pinyinSegments[0]!.syllable, 'zhǎng');
  assert.equal(items[0]!.pinyinState, 'confirmed');
  // 另一个词里的同一个字保留它自己的读音，两者不互相覆盖。
  const other = token('长江', { pinyin: [['长', 'cháng'], ['江', 'jiāng']] });
  const second = expandToCharacters([other]);
  assert.equal(second.items[0]!.pinyinSegments[0]!.syllable, 'cháng');
});

test('词语缺少读音时展开项保持未校对并提示复核', () => {
  const word = token('春天', { pinyin: [['春', 'chūn']], pinyinState: 'suggested' });
  const { items, notices } = expandToCharacters([word]);
  assert.equal(items[0]!.pinyinState, 'suggested');
  assert.equal(items[1]!.pinyinState, 'missing', '没有片段的字必须标为缺失而不是猜一个读音');
  assert.equal(items[1]!.pinyinSegments.length, 0);
  assert.ok(notices.some(notice => notice.code === 'WORKSHEET_EXPAND_PINYIN_MISSING'));
});

test('同形异音字按字素位置继承读音，派生身份互不重复', () => {
  const word = token('长长', { pinyin: [['长', 'zhǎng'], ['长', 'cháng']] });
  const expanded = expandToCharacters([word]);
  assert.deepEqual(expanded.items.map(item => item.pinyinSegments[0]?.syllable), ['zhǎng', 'cháng']);
  assert.equal(new Set(expanded.items.map(item => item.tokenId)).size, 2);
  const processed = processWorksheetInput([word], options({ chineseUnit: 'character', repetitions: 2 }));
  assert.equal(processed.questionCount, 4, '不同读音不应错误去重成一个字');
  assert.equal(new Set(processed.items.map(item => item.tokenId)).size, 4);
});

test('位置不匹配的拼音片段不能按字形跨位填入', () => {
  const word = token('春天', { pinyin: [['天', 'tiān'], ['春', 'chūn']] });
  const { items, notices } = expandToCharacters([word]);
  assert.ok(items.every(item => item.pinyinState === 'missing' && item.pinyinSegments.length === 0));
  assert.ok(notices.some(item => item.code === 'WORKSHEET_EXPAND_PINYIN_MISSING'));
});

test('按字素切分，补充平面字符不被切半', () => {
  assert.deepEqual(splitGraphemes('𠀀山'), ['𠀀', '山']);
  assert.equal(isHanCharacter('山'), true);
  assert.equal(isHanCharacter('A'), false);
  const { items } = expandToCharacters([token('𠀀山')]);
  assert.deepEqual(items.map(item => item.text), ['𠀀', '山']);
});

test('等价去重：同音同字合并来源并保留首次出现位置', () => {
  const first = token('山', { pinyin: [['山', 'shān']], refs: [textbookRef('u1', 'entry-recognition')] });
  const second = token('山', { pinyin: [['山', 'shān']], refs: [textbookRef('u1', 'entry-writing')] });
  const third = token('水', { pinyin: [['水', 'shuǐ']] });
  const result = dedupeTokens([first, second, third], 'merge-equivalent');
  assert.equal(result.before, 3);
  assert.equal(result.after, 2);
  assert.equal(result.mergedGroups, 1);
  assert.equal(result.items[0]!.tokenId, first.tokenId, '保留第一次出现的身份');
  assert.equal(result.items[0]!.sourceRefs.length, 2, '合并全部来源');
  assert.deepEqual(result.items.map(item => item.text), ['山', '水']);
});

test('等价去重：不同读音或不同义项不合并', () => {
  const chang = token('长', { pinyin: [['长', 'cháng']] });
  const zhang = token('长', { pinyin: [['长', 'zhǎng']] });
  assert.notEqual(equivalentKey(chang), equivalentKey(zhang));
  assert.equal(dedupeTokens([chang, zhang], 'merge-equivalent').after, 2);

  const senseA = token('bank', { meaning: '银行', senseId: 'a' });
  const senseB = token('bank', { meaning: '河岸', senseId: 'b' });
  const kept = dedupeTokens([senseA, senseB], 'merge-equivalent');
  assert.equal(kept.after, 2, '不同义项必须同时保留');
});

test('保留每次出现时不合并', () => {
  const a = token('山', { pinyin: [['山', 'shān']] });
  const b = token('山', { pinyin: [['山', 'shān']] });
  const result = dedupeTokens([a, b], 'keep-occurrences');
  assert.equal(result.after, 2);
  assert.equal(result.mergedGroups, 0);
});

test('均衡选题：混入自定义条目时禁用并说明原因，且不伪造教材单元', () => {
  const mixed = [
    token('山', { pinyin: [['山', 'shān']] }),
    token('hello', { meaning: '你好', refs: [customRef()] })
  ];
  const availability = balancedAvailability(mixed);
  assert.equal(availability.available, false);
  assert.equal(availability.reason, 'WORKSHEET_BALANCED_NEEDS_TEXTBOOK_UNITS');
  assert.equal(availability.nonTextbookCount, 1);

  const selected = processWorksheetInput(mixed, options({ selection: 'balanced-unit', requestedCount: 1 }));
  assert.ok(selected.notices.some(notice => notice.code === 'WORKSHEET_BALANCED_NEEDS_TEXTBOOK_UNITS'));
  // 退化时按输入顺序取前 N 项，不能给自定义条目假塞一个教材单元。
  assert.deepEqual(selected.items.map(item => item.text), ['山']);
  assert.equal(selected.items[0]!.sourceRefs.some(ref => ref.kind === 'custom'), false);
});

test('均衡选题：全部条目都有教材单元时可用并给出提示', () => {
  const items = [
    token('山', { refs: [textbookRef('u1')] }),
    token('水', { refs: [textbookRef('u1')] }),
    token('天', { refs: [textbookRef('u2')] }),
    token('地', { refs: [textbookRef('u2')] })
  ];
  assert.equal(balancedAvailability(items).available, true);
  const result = processWorksheetInput(items, options({ selection: 'balanced-unit', requestedCount: 4, seed: 'fixed' }));
  assert.ok(result.notices.some(notice => notice.code === 'WORKSHEET_BALANCED_APPLIED'));
  assert.equal(result.items.length, 4);
  // 两个单元都要有代表，不能被一个单元占满。
  const units = new Set(result.items.map(item => item.sourceRefs[0]!.unitId));
  assert.equal(units.size, 2);
});

test('随机选题与排序同 seed 可复现，且不会被再次按教材顺序重排', () => {
  const items = ['一', '二', '三', '四', '五', '六'].map(text => token(text));
  const first = processWorksheetInput(items, options({ selection: 'random-count', requestedCount: 3, seed: 's1' }));
  const second = processWorksheetInput(items, options({ selection: 'random-count', requestedCount: 3, seed: 's1' }));
  assert.deepEqual(first.items.map(item => item.text), second.items.map(item => item.text), '同 seed 必须一致');

  const random = orderTokens(items, 'random', 's1');
  assert.notDeepEqual(random.map(item => item.text), items.map(item => item.text), '随机顺序应当与输入顺序不同');
  // 排序结果必须原样保留，不能事后按输入顺序再排一次。
  assert.deepEqual(
    processWorksheetInput(items, options({ ordering: 'random', seed: 's1' })).items.map(item => item.text),
    random.map(item => item.text)
  );
  assert.deepEqual(orderTokens(items, 'input', 's1').map(item => item.text), items.map(item => item.text));
});

test('重复展开保留首轮身份并生成新身份，数量正确', () => {
  const items = [token('山'), token('水')];
  const once = processWorksheetInput(items, options({ repetitions: 1 }));
  assert.equal(once.questionCount, 2);
  assert.deepEqual(once.items.map(item => item.tokenId), items.map(item => item.tokenId));
  const thrice = processWorksheetInput(items, options({ repetitions: 3 }));
  assert.equal(thrice.questionCount, 6);
  assert.notEqual(thrice.items[2]!.tokenId, thrice.items[0]!.tokenId, '重复轮次必须有不同的身份');
});

test('规模上限：选入超过 200 项阻断，重复后超过 500 题阻断', () => {
  const many = Array.from({ length: PROCESSING_LIMITS.maxSelected + 1 }, (_item, index) => token(`字${index}`));
  const overSelected = processWorksheetInput(many, options({ selection: 'random-count', requestedCount: PROCESSING_LIMITS.maxSelected + 1 }));
  assert.equal(overSelected.blocked, true);
  assert.ok(overSelected.blockers.some(blocker => blocker.code === 'WORKSHEET_LIMIT_SELECTED'));

  const twoHundred = Array.from({ length: PROCESSING_LIMITS.maxSelected }, (_item, index) => token(`字${index}`));
  const overQuestions = processWorksheetInput(twoHundred, options({ repetitions: 3 }));
  assert.equal(overQuestions.questionCount, 600);
  assert.equal(overQuestions.blocked, true);
  assert.ok(overQuestions.blockers.some(blocker => blocker.code === 'WORKSHEET_LIMIT_QUESTIONS'));

  const within = processWorksheetInput(twoHundred, options({ repetitions: 2 }));
  assert.equal(within.questionCount, 400);
  assert.equal(within.blocked, false);
});

test('全部选入同样限制 200 项，不静默截取且可显式抽取解除阻断', () => {
  const many = Array.from({ length: 201 }, (_item, index) => token(`word${index}`));
  const all = processWorksheetInput(many, options());
  assert.equal(all.selectedCount, 201, '保留真实规模用于提示，不能截取前 200 项');
  assert.equal(all.blocked, true);
  assert.ok(all.blockers.some(item => item.code === 'WORKSHEET_LIMIT_SELECTED'));
  const selected = processWorksheetInput(many, options({ selection: 'random-count', requestedCount: 200, repetitions: 2, seed: 'selected' }));
  assert.equal(selected.selectedCount, 200);
  assert.equal(selected.questionCount, 400);
  assert.equal(selected.blocked, false);
});

test('处理不改变词边界：逐字展开前后原条目数量一致且原数组未被改写', () => {
  const items = [token('春天', { pinyin: [['春', 'chūn'], ['天', 'tiān']] }), token('花朵')];
  const snapshot = JSON.stringify(items);
  processWorksheetInput(items, options({ chineseUnit: 'character' }));
  assert.equal(JSON.stringify(items), snapshot, '处理不得就地修改输入条目');
  // 从逐字切回按词，仍然得到原来的两个词。
  const backToWord = processWorksheetInput(items, options({ chineseUnit: 'word' }));
  assert.deepEqual(backToWord.items.map(item => item.text), ['春天', '花朵']);
});

test('去重数量在结果中如实给出，便于界面显示前后对比', () => {
  const items = [
    token('山', { pinyin: [['山', 'shān']] }),
    token('山', { pinyin: [['山', 'shān']] }),
    token('水', { pinyin: [['水', 'shuǐ']] })
  ];
  const result = processWorksheetInput(items, options());
  assert.equal(result.tokenCountBefore, 3);
  assert.equal(result.tokenCountAfterDedupe, 2);
  assert.equal(result.selectedCount, 2);
  assert.equal(result.questionCount, 2);
  assert.ok(result.notices.some(notice => notice.code === 'WORKSHEET_DEDUPE_MERGED'));
});

test('空输入不阻断但也没有题目', () => {
  const result = processWorksheetInput([], options());
  assert.equal(result.questionCount, 0);
  assert.equal(result.blocked, false);
  assert.equal(balancedAvailability([]).reason, 'WORKSHEET_BALANCED_EMPTY');
});

// ---------------------------------------------------------------- 学生卷与答案卷分别判定
test('缺拼音时：汉字字帖学生卷和答案卷都缺少题面', () => {
  // 学生卷与答案卷都需要真实拼音题面，答案卷还需要汉字答案。
  const items = [token('山'), token('水')];
  const student = checkGeneratable(items, 'chinese-dictation', 'student');
  assert.equal(student.ok, false, '学生卷缺拼音应被阻断');
  assert.deepEqual(student.blockers, ['WORKSHEET_MISSING_PINYIN']);
  const answer = checkGeneratable(items, 'chinese-dictation', 'answer');
  assert.equal(answer.ok, false, '答案卷仍需拼音题面');
});

test('缺拼音时：拼音字帖学生卷可生成，答案卷被阻断', () => {
  // 与上一条正好相反：同一批内容，两种题型的学生卷/答案卷要求不同。
  const items = [token('山'), token('水')];
  assert.equal(checkGeneratable(items, 'chinese-pinyin', 'student').ok, true, '学生卷只显示汉字');
  const answer = checkGeneratable(items, 'chinese-pinyin', 'answer');
  assert.equal(answer.ok, false, '答案卷需要每字拼音');
  assert.deepEqual(answer.blockers, ['WORKSHEET_MISSING_PINYIN']);
});

test('有效自动建议读音可直接输出，同时保留机器来源状态', () => {
  const suggested = { ...token('山'), pinyinSegments: [{ grapheme: '山', syllable: 'shān' }], pinyinState: 'suggested' as const };
  const check = checkGeneratable([suggested], 'chinese-dictation', 'student');
  assert.equal(check.ok, true, '完整的自动读音不再要求用户逐项确认');
  assert.equal(check.missingPinyin, 0);
  assert.equal(suggested.pinyinState, 'suggested', '允许输出不等于冒充人工确认');
  const confirmed = { ...suggested, pinyinState: 'confirmed' as const };
  assert.equal(checkGeneratable([confirmed], 'chinese-dictation', 'student').ok, true);
});

test('拼音校验拒绝汉字回传、非法字母串、错位和多重声调', () => {
  for (const syllable of ['𱁬', '山', 'hello', 'abc', 'chūń', '', 'chūn tiān']) assert.equal(isValidPinyinSyllable(syllable), false, syllable);
  for (const syllable of ['chūn', 'tiān', 'nǚ', 'lüè', 'zi', 'ń', 'ê']) assert.equal(isValidPinyinSyllable(syllable), true, syllable);
  assert.equal(isValidPinyinSegments('春天', [{ grapheme: '天', syllable: 'tiān' }, { grapheme: '春', syllable: 'chūn' }]), false);
  assert.equal(isValidPinyinSegments('A', [{ grapheme: 'A', syllable: 'a' }]), false, '不能用字母绕过汉字读音校验');
  const invalid = token('山', { pinyin: [['山', '山']], pinyinState: 'confirmed' });
  assert.equal(checkGeneratable([invalid], 'chinese-dictation', 'student').ok, false, '人工状态也不能绕过实际音节校验');
});

test('英文与中文释义分别校验，不能把中文原文或英文回传当作翻译', () => {
  for (const value of ['Monday', 'ice cream', "New Year’s Day", 'Good morning!', 'word1']) assert.equal(isValidEnglishText(value), true);
  for (const value of ['星期一', 'Monday星期一', '123', '?!', 'Monдay']) assert.equal(isValidEnglishText(value), false);
  assert.equal(isValidChineseMeaning('星期一'), true);
  assert.equal(isValidChineseMeaning('Monday'), false);
  assert.equal(englishTextForToken(token('星期一')), '');
  assert.equal(englishTextForToken({ ...token('星期一'), englishText: 'Monday', englishState: 'suggested' }), 'Monday');
  assert.equal(englishTextForToken({ ...token('Monday'), englishText: '', englishState: 'missing' }), '', '显式清空不能回退旧原文');
  assert.equal(checkGeneratable([token('星期一', { meaning: '星期一' })], 'english-dictation', 'student').ok, true, '中文单边可以生成默写学生卷');
  const translated = { ...token('Monday', { meaning: '星期一' }), meaningState: 'suggested' as const };
  assert.equal(checkGeneratable([translated], 'english-dictation', 'student').ok, true);
  assert.equal(checkGeneratable([{ ...translated, meaning: 'Monday' }], 'english-dictation', 'student').ok, false);
});

test('英语默写学生卷和答案卷都需要中文题面', () => {
  const noMeaning = [token('apple')];
  assert.equal(checkGeneratable(noMeaning, 'english-dictation', 'student').ok, false, '学生卷给中文填英文，缺义项应阻断');
  assert.deepEqual(checkGeneratable(noMeaning, 'english-dictation', 'student').blockers, ['WORKSHEET_MISSING_MEANING']);
  assert.equal(checkGeneratable(noMeaning, 'english-dictation', 'answer').ok, false, '答案卷还需中文题面');
});

test('中文释义练习答案卷需要义项，学生卷不需要', () => {
  const noMeaning = [token('apple')];
  assert.equal(checkGeneratable(noMeaning, 'english-meaning', 'student').ok, true);
  const answer = checkGeneratable(noMeaning, 'english-meaning', 'answer');
  assert.equal(answer.ok, false);
  assert.deepEqual(answer.blockers, ['WORKSHEET_MISSING_MEANING']);
});

test('读音数量与字数不一致也不算可输出', () => {
  const misaligned = { ...token('山水'), pinyinSegments: [{ grapheme: '山', syllable: 'shān' }], pinyinState: 'confirmed' as const };
  const check = checkGeneratable([misaligned], 'chinese-pinyin', 'answer');
  assert.equal(check.ok, false, '片段数量与字数不符时不得当作已对齐');
  assert.equal(check.missingPinyin, 1);
});

test('字段齐备时两种输出都可生成', () => {
  const complete = { ...token('山'), pinyinSegments: [{ grapheme: '山', syllable: 'shān' }], pinyinState: 'confirmed' as const, meaning: '山', meaningState: 'confirmed' as const };
  for (const mode of ['chinese-pinyin', 'chinese-dictation']) {
    assert.equal(checkGeneratable([complete], mode, 'student').ok, true, `${mode} 学生卷`);
    assert.equal(checkGeneratable([complete], mode, 'answer').ok, true, `${mode} 答案卷`);
  }
});
