import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createSession, setParsePolicy, setRawText, updateTokenMeaning } from '../src/domain/worksheet-session';
import { editablePairsForSession, importEditablePairsIntoSession, removeEditablePair, updateEditablePair } from '../src/domain/worksheet-editable-pairs';
import { createStoredDraft, restoreDraftSession } from '../src/domain/worksheet-draft-session';
import { useWorksheetV2Session } from '../src/composables/useWorksheetV2Session';
import type { TranslationResult } from '../src/services/public-api';

/** 按行输入方便检查英语短语与原文位置。 */
function englishSession() { return setParsePolicy(createSession('english'), 'one-per-line').session; }

/** 网络边界可控，真实执行配对导入、自动补全和草稿恢复。 */
function harness(subject: 'chinese' | 'english' = 'english') {
  const requests: Array<{ items: ReadonlyArray<{ id: string; text: string }>; resolve: (value: TranslationResult[]) => void }> = [];
  const stored = new Map<string, string>();
  const flow = useWorksheetV2Session(subject, {
    storage: { getItem: key => stored.get(key) ?? null, setItem: (key, value) => { stored.set(key, value); }, removeItem: key => { stored.delete(key); } },
    translate: items => subject === 'chinese' ? Promise.resolve([]) : new Promise(resolve => { requests.push({ items, resolve }); }),
    recordSuccess: () => undefined, recordPrint: () => undefined, release: () => undefined
  });
  flow.applyParsePolicy('one-per-line');
  return { flow, requests };
}

test('OCR 中英配对同批重复词按行绑定，机器字段保持 suggested', () => {
  const result = importEditablePairsIntoSession(englishSession(), [
    { english: 'walk', meaning: '牵着（动物）走，遛' },
    { english: 'walk', meaning: '散步' },
    { english: 'See you tomorrow!', meaning: '明天见！' }
  ], 'replace', 'image');
  assert.deepEqual(result.blockers, []);
  const tokens = result.session.document.tokens;
  assert.deepEqual(tokens.map(token => [token.text, token.meaning, token.meaningState, token.englishState]), [
    ['walk', '牵着（动物）走，遛', 'suggested', 'suggested'], ['walk', '散步', 'suggested', 'suggested'], ['See you tomorrow!', '明天见！', 'suggested', 'suggested']
  ]);
  assert.equal(new Set(tokens.map(token => token.tokenId)).size, 3);
  assert.equal(result.session.document.rawText, 'walk\nwalk\nSee you tomorrow!');
});

test('只修改英文保持本行中文和稳定标识，不借用后一行同形词义项', () => {
  let session = importEditablePairsIntoSession(englishSession(), [{ english: 'Weak', meaning: '周，星期' }, { english: 'week', meaning: '一周' }], 'replace', 'image').session;
  const [first, second] = session.document.tokens;
  session = updateEditablePair(session, first!.tokenId, { english: 'week' }).session;
  assert.deepEqual(session.document.tokens.map(token => [token.tokenId, token.text, token.meaning]), [
    [first!.tokenId, 'week', '周，星期'], [second!.tokenId, 'week', '一周']
  ]);
  assert.equal(session.document.tokens[0]!.englishState, 'confirmed');
  assert.equal(session.document.tokens[0]!.meaningState, 'suggested');
  const edited = setRawText(session, 'week\nweek\nMonday').session;
  assert.deepEqual(edited.document.tokens.slice(0, 2).map(token => token.meaning), ['周，星期', '一周']);
});

test('删除和重排行保留每个重复词自己的校对', () => {
  let session = importEditablePairsIntoSession(englishSession(), [{ english: 'walk', meaning: '散步' }, { english: 'walk', meaning: '牵着走' }]).session;
  const pairs = editablePairsForSession(session);
  session = importEditablePairsIntoSession(session, [...pairs].reverse()).session;
  assert.deepEqual(session.document.tokens.map(token => token.meaning), ['牵着走', '散步']);
  session = removeEditablePair(session, pairs[0]!.tokenId!).session;
  assert.equal(session.document.tokens[0]!.tokenId, pairs[1]!.tokenId);
  assert.equal(session.document.tokens[0]!.meaning, '牵着走');
});

test('追加不会重置旧草稿原文、人工字段或重复项身份', () => {
  let session = setRawText(createSession('english'), '  "ice cream"  Monday  ').session;
  const old = session.document.tokens[0]!;
  session = updateTokenMeaning(session, old.tokenId, '手工冰淇淋').session;
  const result = importEditablePairsIntoSession(session, [{ english: 'ice cream', meaning: '冰淇淋' }], 'append', 'image');
  assert.ok(result.session.document.rawText.startsWith('  "ice cream"  Monday'));
  assert.deepEqual(result.session.document.tokens.map(token => token.text), ['ice cream', 'Monday', 'ice cream']);
  assert.equal(result.session.document.tokens[0]!.tokenId, old.tokenId);
  assert.equal(result.session.document.tokens[0]!.meaning, '手工冰淇淋');
  assert.equal(result.session.document.tokens[0]!.meaningState, 'confirmed');
  assert.equal(result.session.document.tokens[2]!.meaningState, 'suggested');
  assert.notEqual(result.session.document.tokens[2]!.tokenId, old.tokenId);
});

test('空行按组忽略，单边缺项保留在原行，不把下一项中文挪过来', () => {
  const result = importEditablePairsIntoSession(englishSession(), [
    { english: 'dancing', meaning: '' }, {}, { meaning: '狗' }, { english: 'free', meaning: '空闲的' }
  ], 'replace', 'image');
  assert.deepEqual(result.session.document.tokens.map(token => [token.englishText, token.meaning]), [
    ['dancing', null], ['', '狗'], ['free', '空闲的']
  ]);
});

test('替换新来源不复用旧同形词的人工释义', () => {
  const initial = importEditablePairsIntoSession(englishSession(), [{ english: 'walk', meaning: '散步' }]).session;
  const result = importEditablePairsIntoSession(initial, [{ english: 'walk', meaning: '牵着（动物）走' }], 'replace', 'image');
  assert.notEqual(result.session.document.tokens[0]!.tokenId, initial.document.tokens[0]!.tokenId);
  assert.equal(result.session.document.tokens[0]!.meaning, '牵着（动物）走');
  assert.equal(result.session.document.tokens[0]!.meaningState, 'suggested');
});

test('修改汉字保留该行现有读音和身份，不联动清空或重新补音', () => {
  let session = importEditablePairsIntoSession(createSession('chinese'), [{ text: '春天', pinyin: 'chūn tiān' }]).session;
  const id = session.document.tokens[0]!.tokenId;
  session = updateEditablePair(session, id, { text: '秋天' }).session;
  assert.equal(session.document.tokens[0]!.tokenId, id);
  assert.equal(session.document.tokens[0]!.pinyinState, 'confirmed');
  assert.deepEqual(session.document.tokens[0]!.pinyinSegments.map(segment => segment.syllable), ['chūn', 'tiān']);
  assert.equal(session.document.tokens[0]!.automaticInitialized, true);
});

test('无效拼音保留全部输入，不截断后静默当成有效读音', () => {
  const { flow } = harness('chinese');
  assert.equal(flow.importEditablePairs([{ text: '春', pinyin: 'chūn tiān' }]), false);
  assert.equal(flow.editablePairs.value[0]!.pinyin, 'chūn tiān');
  assert.equal(flow.blockers.value[0]!.code, 'WORKSHEET_PINYIN_COUNT_MISMATCH');
  assert.equal(flow.saveDraft(), false);
  assert.equal(flow.updatePair(flow.editablePairs.value[0]!.tokenId!, { pinyin: 'chūn' }), true);
  assert.equal(flow.saveDraft(), true);
});

test('结构错误原子拒绝，已有内容保持不变', () => {
  const initial = importEditablePairsIntoSession(englishSession(), [{ english: 'dog', meaning: '狗' }]).session;
  for (const rows of [[{ english: 'cat\ndog', meaning: '猫' }], [{ tokenId: 'missing', english: 'cat', meaning: '猫' }]]) {
    const result = importEditablePairsIntoSession(initial, rows);
    assert.equal(result.session, initial);
    assert.ok(result.blockers.length);
  }
  const chinese = importEditablePairsIntoSession(createSession('chinese'), [{ pinyin: 'chūn' }]);
  assert.deepEqual(chinese.blockers, []);
  assert.equal(chinese.session.document.rawText, 'chūn');
  assert.equal(chinese.session.document.tokens[0]!.chineseText, '');
});

test('OCR 中英已完整时不调用翻译，教材原义保持原样', async () => {
  const { flow, requests } = harness();
  assert.equal(flow.importEditablePairs([{ english: 'walk', meaning: '牵着（动物）走，遛' }], 'replace', 'image'), true);
  await flow.prepareAutomaticFields();
  assert.equal(requests.length, 0);
  assert.equal(flow.editablePairs.value[0]!.meaning, '牵着（动物）走，遛');
  assert.equal(flow.editablePairs.value[0]!.meaningState, 'suggested');
});

test('中文和英文单边输入仅补缺字段，用户已有两侧不被改写', async () => {
  const { flow, requests } = harness();
  flow.importEditablePairs([{ meaning: '星期一' }, { english: 'dog' }, { english: 'walk', meaning: '牵着走' }]);
  const task = flow.prepareAutomaticFields();
  assert.deepEqual(requests[0]!.items.map(item => item.text), ['星期一', 'dog']);
  requests[0]!.resolve(requests[0]!.items.map((item, index) => ({ ...item, sourceText: item.text, englishText: index ? 'dog' : 'Monday', meaning: index ? '狗' : '周一', status: 'translated' })));
  await task;
  assert.deepEqual(flow.editablePairs.value.map(row => [row.meaning, row.english]), [['星期一', 'Monday'], ['狗', 'dog'], ['牵着走', 'walk']]);
});

test('人工编辑配对字段会取消迟到翻译，不覆盖用户改动', async () => {
  const { flow, requests } = harness();
  flow.importEditablePairs([{ meaning: '星期一' }]);
  const task = flow.prepareAutomaticFields();
  const id = flow.editablePairs.value[0]!.tokenId!;
  flow.updatePair(id, { english: 'Monday' });
  const item = requests[0]!.items[0]!;
  requests[0]!.resolve([{ id: item.id, sourceText: item.text, englishText: 'Tuesday', meaning: '周二', status: 'translated' }]);
  assert.equal(await task, false);
  assert.equal(flow.editablePairs.value[0]!.english, 'Monday');
  assert.equal(flow.editablePairs.value[0]!.englishState, 'confirmed');
});

test('未改配对字段的重复提交不取消正在补全的任务', async () => {
  const { flow, requests } = harness();
  flow.importEditablePairs([{ meaning: '星期一' }]);
  const task = flow.prepareAutomaticFields();
  const before = flow.session.value;
  flow.importEditablePairs(flow.editablePairs.value);
  assert.equal(flow.session.value, before);
  assert.equal(flow.automaticPreparing.value, true);
  const item = requests[0]!.items[0]!;
  requests[0]!.resolve([{ id: item.id, sourceText: item.text, englishText: 'Monday', meaning: '周一', status: 'translated' }]);
  assert.equal(await task, true);
  assert.equal(flow.editablePairs.value[0]!.english, 'Monday');
});

test('自动拼音不会覆盖手填拼音，确认页提交不提升未改机器字段状态', async () => {
  const { flow } = harness('chinese');
  flow.importEditablePairs([{ text: '春天', pinyin: 'chūn tiān' }, { text: '秋天' }]);
  await flow.prepareAutomaticFields();
  assert.deepEqual(flow.editablePairs.value.map(row => row.pinyinState), ['confirmed', 'suggested']);
  flow.importEditablePairs(flow.editablePairs.value);
  assert.deepEqual(flow.editablePairs.value.map(row => row.pinyinState), ['confirmed', 'suggested']);
});

test('保存恢复与返回原文后机器配对及重复词校对均存在', () => {
  const session = importEditablePairsIntoSession(englishSession(), [{ english: 'walk', meaning: '散步' }, { english: 'walk', meaning: '牵着走' }], 'replace', 'image').session;
  const restored = restoreDraftSession('english', createStoredDraft(session));
  assert.deepEqual(restored.document.tokens.map(token => [token.text, token.meaning, token.meaningState]), [['walk', '散步', 'suggested'], ['walk', '牵着走', 'suggested']]);
  assert.ok(restored.document.tokens.every(token => token.sourceRefs[0]!.kind === 'image'));
});
