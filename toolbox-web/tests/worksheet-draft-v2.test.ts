import assert from 'node:assert/strict';
import test from 'node:test';
import {
  DRAFT_PREFIX_V2, commitMigration, containsForbiddenPayload,
  draftKeyV1, draftKeyV2, loadDraftV2, migrateFromV1, saveDraftV2,
  validateStoredDraft, WorksheetDraftError, type DraftStorage, type StoredDraftV2
} from '../src/domain/worksheet-draft-v2';

/** 内存存储；可模拟容量不足。 */
function memoryStorage(seed: Record<string, string> = {}, options: { failOnSet?: boolean } = {}): DraftStorage & { data: Record<string, string> } {
  const data: Record<string, string> = { ...seed };
  return {
    data,
    getItem: (key) => (key in data ? data[key]! : null),
    setItem: (key, value) => {
      if (options.failOnSet) throw new Error('QUOTA_EXCEEDED');
      data[key] = value;
    },
    removeItem: (key) => { delete data[key]; }
  };
}

/** 构造一份完整 v2 负载。 */
function payload(overrides: Partial<Omit<StoredDraftV2, 'schemaVersion' | 'savedAt'>> = {}) {
  return {
    subject: 'chinese' as const,
    rawText: '春天 花朵',
    parsePolicy: 'whitespace' as const,
    sources: [{ sourceId: 'smartedu', kind: 'textbook' as const, editionId: 'e1', contentVersion: '1.0.2', label: '一上 · 识字表' }],
    corrections: [],
    processing: {
      mode: 'chinese-dictation' as const, chineseUnit: 'word' as const, duplicatePolicy: 'merge-equivalent' as const,
      ordering: 'input' as const, selection: 'all' as const, requestedCount: null, seed: null, repetitions: 1 as const
    },
    title: '练习',
    layout: { gridMm: 12 },
    selection: { grade: 1, semester: 'first', editionId: 'e1', selectedIds: ['a'], strategy: 'all', ordering: 'textbook', requestedCount: null, seed: null },
    migratedFromV1: false,
    ...overrides
  };
}

test('保存后可原样读回，键使用 v2 前缀', () => {
  const storage = memoryStorage();
  const saved = saveDraftV2(storage, 'chinese', payload());
  assert.equal(saved.ok, true);
  assert.equal(saved.notice, 'draftSaved');
  assert.ok(draftKeyV2('chinese').startsWith(DRAFT_PREFIX_V2));
  const loaded = loadDraftV2(storage, 'chinese');
  assert.equal(loaded.status, 'ok');
  assert.equal(loaded.payload?.rawText, '春天 花朵');
  assert.equal(loaded.payload?.schemaVersion, 2);
  assert.equal(loaded.payload?.processing.mode, 'chinese-dictation');
});

test('不同学科使用不同键，互不覆盖', () => {
  const storage = memoryStorage();
  saveDraftV2(storage, 'chinese', payload());
  saveDraftV2(storage, 'english', payload({ subject: 'english', rawText: 'apple', processing: { ...payload().processing, mode: 'english-meaning' } }));
  assert.equal(loadDraftV2(storage, 'chinese').payload?.rawText, '春天 花朵');
  assert.equal(loadDraftV2(storage, 'english').payload?.rawText, 'apple');
});

test('optional normalized English fields validate independently, retain missing states and copy corrections', () => {
  const draft: StoredDraftV2 = {
    ...payload({ subject: 'english', rawText: '\u661f\u671f\u4e00', processing: { ...payload().processing, mode: 'english-dictation' } }),
    schemaVersion: 2, savedAt: '2026-10-04T00:00:00.000Z',
    corrections: [{ textFingerprint: '\u661f\u671f\u4e00', tokenIndex: 0, occurrence: 0, pinyin: [], pinyinState: 'missing', meaning: '\u661f\u671f\u4e00', meaningState: 'suggested', englishText: 'Monday', englishState: 'suggested' }]
  };
  const validated = validateStoredDraft('english', draft);
  assert.deepEqual(validated.corrections, draft.corrections);
  assert.notEqual(validated.corrections[0], draft.corrections[0]);
  draft.corrections[0]!.englishText = null;
  draft.corrections[0]!.englishState = 'missing';
  assert.equal(validated.corrections[0]!.englishText, 'Monday');
  assert.equal(validated.corrections[0]!.englishState, 'suggested');
  assert.equal(validateStoredDraft('english', draft).corrections[0]!.englishText, null);
  assert.equal(validateStoredDraft('english', draft).corrections[0]!.englishState, 'missing');
  delete draft.corrections[0]!.englishText;
  delete draft.corrections[0]!.englishState;
  assert.deepEqual(validateStoredDraft('english', draft).corrections, draft.corrections);
});

test('malformed normalized English fields reject loading instead of replacing existing storage', () => {
  const draft: StoredDraftV2 = {
    ...payload({ subject: 'english', rawText: '\u661f\u671f\u4e00', processing: { ...payload().processing, mode: 'english-dictation' } }),
    schemaVersion: 2, savedAt: '2026-10-04T00:00:00.000Z',
    corrections: [{ textFingerprint: '\u661f\u671f\u4e00', pinyin: [], pinyinState: 'missing', meaning: '\u661f\u671f\u4e00', meaningState: 'suggested' }]
  };
  const mutations = [
    { englishText: 1 }, { englishText: [] }, { englishText: false },
    { englishState: 'auto' }, { englishState: 1 }, { englishState: null }
  ];
  for (const mutation of mutations) {
    const malformed = { ...draft, corrections: [{ ...draft.corrections[0]!, ...mutation }] };
    assert.throws(() => validateStoredDraft('english', malformed), WorksheetDraftError);
    const encoded = JSON.stringify(malformed);
    const storage = memoryStorage({ [draftKeyV2('english')]: encoded });
    assert.equal(loadDraftV2(storage, 'english').status, 'invalid');
    assert.equal(storage.data[draftKeyV2('english')], encoded);
  }
});

test('没有任何草稿时返回 empty，坏数据返回 invalid 而不是抛错', () => {
  assert.equal(loadDraftV2(memoryStorage(), 'chinese').status, 'empty');
  assert.equal(loadDraftV2(memoryStorage({ [draftKeyV2('chinese')]: '{not json' }), 'chinese').status, 'invalid');
  assert.equal(loadDraftV2(memoryStorage({ [draftKeyV2('chinese')]: JSON.stringify({ schemaVersion: 1 }) }), 'chinese').status, 'invalid');
});

test('存储不可写时保存失败但不抛错，原文仍在调用方手里', () => {
  const storage = memoryStorage({}, { failOnSet: true });
  const saved = saveDraftV2(storage, 'chinese', payload());
  assert.equal(saved.ok, false);
  assert.equal(saved.notice, 'draftSaveFailed');
});

test('拒绝把 Blob / base64 / 临时 URL 写进草稿', () => {
  assert.equal(containsForbiddenPayload({ a: 1, b: 'ok' }), false);
  assert.equal(containsForbiddenPayload({ raw: 'data:image/png;base64,AAAA' }), true);
  assert.equal(containsForbiddenPayload({ url: 'blob:http://localhost/abc' }), true);
  assert.equal(containsForbiddenPayload({ image: new Blob(['x']) }), true);
  assert.equal(containsForbiddenPayload({ nested: { bytes: new Uint8Array([1, 2]) } }), true);

  const storage = memoryStorage();
  const saved = saveDraftV2(storage, 'chinese', payload({ rawText: 'data:image/png;base64,AAAA' }));
  assert.equal(saved.ok, false);
  assert.equal(saved.notice, 'draftNotSerializable');
  assert.equal(loadDraftV2(storage, 'chinese').status, 'empty', '被拒绝的内容不得落盘');
});

test('v1 迁移从旧词条生成等价规范化文本，并明确提示原始格式未保存', () => {
  const v1 = {
    schemaVersion: 1, mode: 'chinese-dictation', title: '旧练习', repetitions: 1,
    layout: { gridMm: 14 }, selectedIds: ['a', 'b'],
    customEntries: [
      { id: '1', text: '春天', pinyin: 'chūn tiān', meaning: '', unit: 1, kind: 'word' },
      { id: '2', text: 'ice cream', pinyin: '', meaning: '冰淇淋', unit: 1, kind: 'phrase' }
    ],
    strategy: 'all', ordering: 'textbook', requestedCount: null, seed: null,
    grade: 2, semester: 'first', editionId: 'e1'
  };
  const storage = memoryStorage({ [draftKeyV1('chinese')]: JSON.stringify(v1) });
  const migration = migrateFromV1(storage, 'chinese');
  assert.equal(migration.status, 'ok');
  assert.equal(migration.rawText, '春天 "ice cream"', '含空格短语必须按引号规则序列化');
  assert.equal(migration.notice, 'draftMigratedFromV1', '必须提示原始输入格式未保存');
  assert.equal(migration.payload?.migratedFromV1, true);
  assert.equal(migration.payload?.layout?.gridMm, 14, '保留旧格距');
  assert.equal(migration.requiresOnlineSelection, true, '教材条目只保存了选择，必须联网取回');
});

test('迁移不写回 v2，v1 键保留；显式保存后才写 v2', () => {
  const v1 = { schemaVersion: 1, title: 'x', repetitions: 1, layout: { gridMm: 12 }, selectedIds: [], customEntries: [], strategy: 'all', ordering: 'textbook', requestedCount: null, seed: null, grade: null, semester: 'first', editionId: null, mode: null };
  const storage = memoryStorage({ [draftKeyV1('chinese')]: JSON.stringify(v1) });
  const migration = migrateFromV1(storage, 'chinese');
  assert.equal(loadDraftV2(storage, 'chinese').status, 'empty', '迁移本身不得写 v2');
  assert.ok(storage.data[draftKeyV1('chinese')], 'v1 键必须保留');
  const committed = commitMigration(storage, 'chinese', migration.payload!);
  assert.equal(committed.ok, true);
  assert.equal(loadDraftV2(storage, 'chinese').status, 'ok');
  assert.ok(storage.data[draftKeyV1('chinese')], '写回 v2 后 v1 键仍在');
});

test('没有 v1 草稿时迁移返回 none，不产生任何写入', () => {
  const storage = memoryStorage();
  const migration = migrateFromV1(storage, 'chinese');
  assert.equal(migration.status, 'none');
  assert.equal(migration.notice, null);
  assert.deepEqual(Object.keys(storage.data), []);
});

test('v1 数据损坏时只影响这一份草稿，不清理其它键', () => {
  const storage = memoryStorage({
    [draftKeyV1('chinese')]: '{broken',
    'unrelated-key': 'keep-me'
  });
  const migration = migrateFromV1(storage, 'chinese');
  assert.equal(migration.status, 'invalid');
  assert.equal(migration.notice, 'draftMigrationFailed');
  assert.equal(storage.data['unrelated-key'], 'keep-me', '迁移异常不得 reset 整个 localStorage');
});

test('迁移只保存选择信息，不在本地编造教材条目正文', () => {
  const v1 = { schemaVersion: 1, title: 'x', repetitions: 1, layout: { gridMm: 12 }, selectedIds: ['id-1', 'id-2'], customEntries: [{ id: 'c1', text: '自定义', pinyin: '', meaning: '', unit: 1, kind: 'word' }], strategy: 'all', ordering: 'textbook', requestedCount: null, seed: null, grade: 1, semester: 'first', editionId: 'e1', mode: null };
  const storage = memoryStorage({ [draftKeyV1('chinese')]: JSON.stringify(v1) });
  const migration = migrateFromV1(storage, 'chinese');
  assert.deepEqual(migration.payload?.selection?.selectedIds, ['id-1', 'id-2']);
  assert.equal(migration.rawText, '自定义', '只有自定义文本可离线恢复');
  assert.deepEqual(migration.payload?.corrections.map(correction => [correction.textFingerprint, correction.pinyin, correction.meaning]), [['自定义', [], null]], '只保留真实自定义条目，不得凭旧 ID 编造读音或义项');
});

test('v1 迁移必须保留旧全局题型，不能丢掉用户已做的选择', () => {
  const key = 'yuyutian-toolbox:draft:v1:chinese';
  const v1 = {
    schemaVersion: 1, mode: 'chinese-dictation', title: '', repetitions: 3, layout: { gridMm: 14 },
    selectedIds: [], customEntries: [{ id: 'c', text: '山水', pinyin: '', meaning: '', unit: 1, kind: 'word' }],
    strategy: 'all', ordering: 'input', requestedCount: null, seed: null, grade: 1, semester: 'first', editionId: 'e'
  };
  const storage = { getItem: (k: string) => (k === key ? JSON.stringify(v1) : null), setItem: () => undefined, removeItem: () => undefined };
  const migration = migrateFromV1(storage, 'chinese');
  assert.equal(migration.status, 'ok');
  const payload = migration.payload;
  assert.ok(payload, '迁移应产出 payload');
  const processing = payload.processing;
  assert.ok(processing, '迁移应产出处理配置');
  assert.equal(processing.mode, 'chinese-dictation', '旧全局题型必须保留');
  assert.equal(processing.repetitions, 3, '旧重复次数必须保留');
  assert.equal(processing.ordering, 'input', '旧顺序设置必须保留');
});
