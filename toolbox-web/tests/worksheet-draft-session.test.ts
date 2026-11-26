import assert from 'node:assert/strict';
import test from 'node:test';
import { createStoredDraft, restoreDraftSession } from '../src/domain/worksheet-draft-session';
import { commitMigration, containsForbiddenPayload, draftKeyV1, draftKeyV2, loadDraftV2, migrateFromV1, saveDraftV2, validateStoredDraft, WorksheetDraftError, type DraftStorage, type StoredDraftV2 } from '../src/domain/worksheet-draft-v2';
import { createSession, sessionCanOutput, setParsePolicy, setRawText, updateTokenMeaning, updateTokenPinyin } from '../src/domain/worksheet-session';
import type { InputSourceRef } from '../src/domain/worksheet-input';
import { processWorksheetInput } from '../src/domain/worksheet-processing';

/** 内存草稿存储可模拟写入失败，不访问浏览器状态。 */
function storage(seed: Record<string, string> = {}, failed = false): DraftStorage & { values: Record<string, string> } {
  const values = { ...seed };
  return { values, getItem: key => values[key] ?? null, setItem: (key, value) => { if (failed) throw new Error('QUOTA_EXCEEDED'); values[key] = value; }, removeItem: key => { delete values[key]; } };
}

/** 创建保留短语边界的英语会话。 */
function englishSession() {
  let session = setParsePolicy(createSession('english'), 'one-per-line').session;
  session = setRawText(session, 'ice cream\nGood morning').session;
  session = updateTokenMeaning(session, session.document.tokens[0]!.tokenId, '\u51b0\u6dc7\u6dcb').session;
  session = updateTokenMeaning(session, session.document.tokens[1]!.tokenId, '\u65e9\u4e0a\u597d').session;
  session.processing = { mode: 'english-dictation', chineseUnit: 'word', duplicatePolicy: 'keep-occurrences', ordering: 'random', selection: 'random-count', requestedCount: 2, seed: 'draft-seed', repetitions: 2 };
  session.layout = { gridMm: 14, title: '\u65e9\u8bfb\u7ec3\u4e60' };
  return session;
}

/** 构造真实教材身份引用，供来源恢复断言使用。 */
function textbookSource(entryId: string): InputSourceRef {
  return { sourceId: 'catalog-source', kind: 'textbook', editionId: 'edition-3-first', contentVersion: '2.1', entryId, unitId: 'unit-2', imageId: null, localOverride: true };
}

test('explicit save and fresh-session restore retain English phrases, checked meanings, processing, layout and sources', () => {
  const session = englishSession();
  session.document.tokens[0]!.sourceRefs = [textbookSource('ice-cream')];
  session.document.tokens[0]!.senseId = 'food';
  session.document.tokens[0]!.acceptableAnswers = ['\u51b0\u6dc7\u6dcb', '\u51b0\u6fc0\u51cc'];
  session.document.imports = [{ sourceId: 'catalog-source', kind: 'textbook', appliedAtRevision: 1, label: 'Unit 2', sourceVersion: '2.1' }];
  const selection: StoredDraftV2['selection'] = { grade: 3, semester: 'second', editionId: 'edition-3-first', selectedIds: ['ice-cream', 'morning'], strategy: 'random-count', ordering: 'random', requestedCount: 2, seed: 'draft-seed' };
  const before = JSON.stringify(session);
  const draft = createStoredDraft(session, { selection });
  const memory = storage();
  assert.equal(saveDraftV2(memory, 'english', draft).ok, true);
  const loaded = loadDraftV2(memory, 'english');
  assert.equal(loaded.status, 'ok');
  const restored = restoreDraftSession('english', loaded.payload);
  assert.notEqual(restored.document.documentId, session.document.documentId);
  assert.equal(restored.document.rawText, 'ice cream\nGood morning');
  assert.equal(restored.document.parsePolicy, 'one-per-line');
  assert.deepEqual(restored.document.tokens.map(token => [token.text, token.meaning, token.meaningState]), [['ice cream', '\u51b0\u6dc7\u6dcb', 'confirmed'], ['Good morning', '\u65e9\u4e0a\u597d', 'confirmed']]);
  assert.deepEqual(restored.processing, session.processing);
  assert.deepEqual(restored.layout, session.layout);
  assert.deepEqual(restored.document.tokens[0]!.sourceRefs, session.document.tokens[0]!.sourceRefs);
  assert.equal(restored.document.tokens[0]!.senseId, 'food');
  assert.deepEqual(restored.document.tokens[0]!.acceptableAnswers, ['\u51b0\u6dc7\u6dcb', '\u51b0\u6fc0\u51cc']);
  assert.equal(restored.document.imports[0]!.label, 'Unit 2');
  assert.deepEqual(loaded.payload!.selection, selection);
  assert.equal(restored.workflow.artifact, null);
  assert.equal(restored.workflow.busy, false);
  assert.equal(sessionCanOutput(restored), false);
  assert.equal(JSON.stringify(session), before);
  assert.equal(processWorksheetInput(restored.document.tokens, restored.processing).questionCount, 4);
  loaded.payload!.corrections[0]!.meaning = 'changed';
  assert.equal(restored.document.tokens[0]!.meaning, '\u51b0\u6dc7\u6dcb');
});

test('draft round trip preserves Chinese raw input alongside suggested and manually confirmed English', () => {
  const rawText = '\u661f\u671f\u4e00\n\u65e9\u4e0a\u597d';
  let session = setParsePolicy(createSession('english'), 'one-per-line').session;
  session = setRawText(session, rawText).session;
  Object.assign(session.document.tokens[0]!, { englishText: 'Monday', englishState: 'suggested', meaning: '\u661f\u671f\u4e00', meaningState: 'suggested' });
  Object.assign(session.document.tokens[1]!, { englishText: 'Good morning', englishState: 'confirmed', meaning: '\u65e9\u4e0a\u597d', meaningState: 'confirmed' });
  const before = JSON.stringify(session);
  const draft = createStoredDraft(session);
  const memory = storage();
  assert.equal(saveDraftV2(memory, 'english', draft).ok, true);
  const loaded = loadDraftV2(memory, 'english');
  assert.equal(loaded.status, 'ok');
  const restored = restoreDraftSession('english', loaded.payload);
  assert.equal(restored.document.rawText, rawText);
  assert.deepEqual(restored.document.tokens.map(token => [token.text, token.englishText, token.englishState, token.meaning, token.meaningState]), [
    ['\u661f\u671f\u4e00', 'Monday', 'suggested', '\u661f\u671f\u4e00', 'suggested'],
    ['\u65e9\u4e0a\u597d', 'Good morning', 'confirmed', '\u65e9\u4e0a\u597d', 'confirmed']
  ]);
  assert.equal(JSON.stringify(session), before);
  loaded.payload!.corrections[0]!.englishText = 'changed';
  loaded.payload!.corrections[1]!.englishState = 'missing';
  assert.equal(restored.document.tokens[0]!.englishText, 'Monday');
  assert.equal(restored.document.tokens[1]!.englishState, 'confirmed');
  assert.equal(session.document.tokens[0]!.englishText, 'Monday');
});

test('legacy drafts without normalized English fields remain restorable with their original text and meanings', () => {
  const draft = createStoredDraft(englishSession());
  for (const correction of draft.corrections) {
    delete correction.englishText;
    delete correction.englishState;
  }
  const memory = storage({ [draftKeyV2('english')]: JSON.stringify(draft) });
  const loaded = loadDraftV2(memory, 'english');
  assert.equal(loaded.status, 'ok');
  assert.ok(loaded.payload!.corrections.every(correction => !Object.hasOwn(correction, 'englishText') && !Object.hasOwn(correction, 'englishState')));
  const restored = restoreDraftSession('english', loaded.payload);
  assert.equal(restored.document.rawText, 'ice cream\nGood morning');
  assert.deepEqual(restored.document.tokens.map(token => [token.text, token.meaning, token.meaningState]), [
    ['ice cream', '\u51b0\u6dc7\u6dcb', 'confirmed'], ['Good morning', '\u65e9\u4e0a\u597d', 'confirmed']
  ]);
});

test('identical Chinese spellings retain different checked and suggested readings by position and occurrence', () => {
  let session = setRawText(createSession('chinese'), '\u884c \u884c \u91cd \u91cd').session;
  const readings = ['x\u00edng', 'h\u00e1ng', 'ch\u00f3ng', 'zh\u00f2ng'];
  session.document.tokens.forEach((token, index) => {
    session = updateTokenPinyin(session, token.tokenId, readings[index]!).session;
  });
  session.document.tokens[2]!.pinyinState = 'suggested';
  session.document.tokens[2]!.meaning = '\u518d\u4e00\u6b21';
  session.document.tokens[2]!.meaningState = 'suggested';
  session.processing.mode = 'chinese-dictation';
  const draft = createStoredDraft(session);
  assert.deepEqual(draft.corrections.map(item => [item.tokenIndex, item.occurrence]), [[0, 0], [1, 1], [2, 0], [3, 1]]);
  const restored = restoreDraftSession('chinese', JSON.parse(JSON.stringify(draft)));
  assert.deepEqual(restored.document.tokens.map(token => token.pinyinSegments[0]!.syllable), readings);
  assert.deepEqual(restored.document.tokens.map(token => token.pinyinState), ['confirmed', 'confirmed', 'suggested', 'confirmed']);
  assert.equal(restored.document.tokens[2]!.meaningState, 'suggested');
  assert.equal(restored.document.tokens[2]!.meaning, '\u518d\u4e00\u6b21');
  assert.equal(new Set(restored.document.tokens.map(token => token.tokenId)).size, 4);
});

test('legacy v2 restores unique and fully recorded duplicate corrections without mixing homographs', () => {
  const session = setRawText(createSession('chinese'), '\u884c \u884c \u5c71').session;
  const draft = createStoredDraft(session);
  draft.corrections = draft.corrections.map((item, index) => {
    const { tokenIndex: _index, occurrence: _occurrence, ...legacy } = item;
    return { ...legacy, pinyin: [{ grapheme: item.textFingerprint, syllable: ['x\u00edng', 'h\u00e1ng', 'sh\u0101n'][index]! }], pinyinState: 'confirmed' };
  });
  assert.deepEqual(restoreDraftSession('chinese', draft).document.tokens.map(token => token.pinyinSegments[0]!.syllable), ['x\u00edng', 'h\u00e1ng', 'sh\u0101n']);
  draft.corrections = [draft.corrections[1]!, draft.corrections[2]!];
  const ambiguous = restoreDraftSession('chinese', draft);
  assert.deepEqual(ambiguous.document.tokens.slice(0, 2).map(token => token.pinyinState), ['missing', 'missing']);
  assert.ok(ambiguous.document.tokens.slice(0, 2).every(token => token.issues.some(issue => issue.code === 'WORKSHEET_DRAFT_AMBIGUOUS_CORRECTION')));
  assert.equal(ambiguous.document.tokens[2]!.pinyinSegments[0]!.syllable, 'sh\u0101n');
});

test('wrong position or fingerprint and malformed nested fields reject restoration without changing the existing session', () => {
  const session = englishSession();
  const before = JSON.stringify(session);
  const base = createStoredDraft(session);
  const mutations: Array<[(string | number)[], unknown]> = [
    [['parsePolicy'], 'split-on-every-letter'], [['processing', 'repetitions'], 99],
    [['processing', 'mode'], 'chinese-pinyin'], [['layout', 'gridMm'], -14],
    [['corrections', 0, 'tokenIndex'], 1], [['corrections', 0, 'occurrence'], 1],
    [['corrections', 0, 'textFingerprint'], 'another phrase'], [['corrections', 0, 'pinyin'], 'wrong-type'],
    [['corrections', 0, 'meaningState'], 'trusted'], [['sources'], [null]],
    [['corrections', 0, 'englishText'], 42], [['corrections', 0, 'englishText'], { text: 'Monday' }],
    [['corrections', 0, 'englishState'], 'automatic'], [['corrections', 0, 'englishState'], null],
    [['selection', 'selectedIds'], 'all'], [['subject'], 'chinese'],
  ];
  for (const [path, value] of mutations) {
    const malformed = JSON.parse(JSON.stringify(base)) as Record<string, unknown>;
    let target = malformed;
    for (const key of path.slice(0, -1)) target = target[key] as Record<string, unknown>;
    target[path[path.length - 1]!] = value;
    assert.throws(() => restoreDraftSession('english', malformed), WorksheetDraftError);
    assert.equal(loadDraftV2(storage({ [draftKeyV2('english')]: JSON.stringify(malformed) }), 'english').status, 'invalid');
    assert.equal(JSON.stringify(session), before);
  }
  for (const malformed of [null, [], { schemaVersion: 2, subject: 'english', rawText: '' }]) assert.throws(() => restoreDraftSession('english', malformed), WorksheetDraftError);
});

test('v1 actual CurriculumEntry shape retains meanings, mode, selection and all existing paper styles without writing old keys', () => {
  const layout = { paper: 'A4', orientation: 'portrait', marginMm: 15, gridMm: 14, gridMmUserSet: true, chineseGrid: 'square', pinyinArea: 'underline', englishLineGapMm: 3, showNameBlank: false, showDateBlank: true, showClassBlank: true };
  const v1 = { schemaVersion: 1, mode: 'english-meaning', title: 'Legacy phrases', repetitions: 3, layout, grade: 4, semester: 'second', editionId: 'older-edition', selectedIds: ['remote-id'], strategy: 'random', ordering: 'manual', requestedCount: 2, seed: 'old-seed', customEntries: [
    { entryId: 'custom-1', text: 'ice cream', pinyinDisplay: null, pinyinSegments: [], meaning: '\u51b0\u6dc7\u6dcb', senseId: 'food', acceptableAnswers: ['\u51b0\u6dc7\u6dcb'] },
    { entryId: 'custom-2', text: 'Good morning', pinyinDisplay: null, pinyinSegments: [], meaning: '\u65e9\u4e0a\u597d', senseId: 'greeting', acceptableAnswers: ['\u65e9\u4e0a\u597d'] }
  ] };
  const encoded = JSON.stringify(v1);
  const memory = storage({ [draftKeyV1('english')]: encoded, unrelated: 'keep' });
  const migration = migrateFromV1(memory, 'english');
  assert.equal(migration.status, 'ok');
  assert.equal(migration.requiresOnlineSelection, true);
  assert.equal(memory.values[draftKeyV2('english')], undefined);
  const restored = restoreDraftSession('english', migration.payload);
  assert.equal(restored.processing.mode, 'english-meaning');
  assert.equal(restored.processing.selection, 'random-count');
  assert.equal(restored.processing.ordering, 'input');
  assert.equal(restored.processing.repetitions, 3);
  assert.equal(restored.processing.seed, 'old-seed');
  assert.deepEqual(restored.document.tokens.map(token => [token.text, token.meaning]), [['ice cream', '\u51b0\u6dc7\u6dcb'], ['Good morning', '\u65e9\u4e0a\u597d']]);
  assert.deepEqual(restored.layout, { ...layout, title: 'Legacy phrases' });
  assert.deepEqual(createStoredDraft(restored).layout, layout);
  assert.deepEqual(migration.payload!.selection.selectedIds, ['remote-id']);
  assert.equal(migration.payload!.selection.ordering, 'manual');
  assert.equal(restored.document.tokens.some(token => token.text.includes('remote-id')), false);
  assert.equal(commitMigration(memory, 'english', migration.payload!).ok, true);
  assert.equal(memory.values[draftKeyV1('english')], encoded);
  assert.equal(memory.values.unrelated, 'keep');
  assert.equal(loadDraftV2(memory, 'english').status, 'ok');
});

test('v1 WordEntry readings and actual CurriculumEntry pinyinSegments both survive migration of homographs', () => {
  const legacy = { schemaVersion: 1, mode: 'chinese-dictation', title: 'Readings', layout: { gridMm: 14 }, customEntries: [
    { id: 'one', text: '\u884c', pinyin: 'x\u00edng', meaning: '\u884c\u8d70' },
    { entryId: 'two', text: '\u884c', pinyinDisplay: 'h\u00e1ng', pinyinSegments: [{ grapheme: '\u884c', syllable: 'h\u00e1ng' }], meaning: '\u884c\u4e1a' }
  ] };
  const memory = storage({ [draftKeyV1('chinese')]: JSON.stringify(legacy) });
  const migration = migrateFromV1(memory, 'chinese');
  assert.equal(migration.status, 'ok');
  const restored = restoreDraftSession('chinese', migration.payload);
  assert.deepEqual(restored.document.tokens.map(token => [token.pinyinSegments[0]!.syllable, token.meaning, token.pinyinState]), [['x\u00edng', '\u884c\u8d70', 'confirmed'], ['h\u00e1ng', '\u884c\u4e1a', 'confirmed']]);
});

test('blocked raw input remains an editable draft without stale token corrections or artifacts', () => {
  const session = setRawText(englishSession(), '"unclosed').session;
  const draft = createStoredDraft(session);
  assert.equal(draft.rawText, '"unclosed');
  assert.deepEqual(draft.corrections, []);
  const restored = restoreDraftSession('english', draft);
  assert.equal(restored.document.rawText, '"unclosed');
  assert.equal(restored.document.tokens.length, 0);
  assert.equal(sessionCanOutput(restored), false);
});

test('storage failure, cyclic objects and invalid v1 payloads preserve both the working draft and existing keys', () => {
  const session = englishSession();
  const before = JSON.stringify(session);
  const memory = storage({ [draftKeyV2('english')]: 'previous' }, true);
  assert.equal(saveDraftV2(memory, 'english', createStoredDraft(session)).ok, false);
  assert.equal(memory.values[draftKeyV2('english')], 'previous');
  assert.equal(JSON.stringify(session), before);
  const cyclic: Record<string, unknown> = {}; cyclic.self = cyclic;
  assert.equal(containsForbiddenPayload(cyclic), true);
  for (const malformed of [{ schemaVersion: 1, customEntries: [null] }, { schemaVersion: 1, customEntries: null }, { schemaVersion: 1, layout: null }, { schemaVersion: 1, customEntries: [{ text: 99 }] }, { schemaVersion: 1, mode: 'chinese-pinyin', customEntries: [] }]) {
    const encoded = JSON.stringify(malformed);
    const legacy = storage({ [draftKeyV1('english')]: encoded });
    assert.equal(migrateFromV1(legacy, 'english').status, 'invalid');
    assert.equal(legacy.values[draftKeyV1('english')], encoded);
    assert.equal(legacy.values[draftKeyV2('english')], undefined);
  }
  assert.throws(() => validateStoredDraft('english', cyclic), WorksheetDraftError);
});
