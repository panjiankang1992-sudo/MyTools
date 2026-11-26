import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import { curriculumEditionLabel, curriculumScopeLabel, useWorksheetCurriculum, type WorksheetCurriculumApi } from '../src/composables/useWorksheetCurriculum';
import { PublicApiError } from '../src/services/public-api';
import type { CurriculumEntry, TextbookCatalog, TextbookEdition, TextbookVersion } from '../src/domain/curriculum';
import { createSession, importTextbook, setMode, textbookImportRequest } from '../src/domain/worksheet-session';

/** 构造两个年级、两册、同册多个版本及英语目录，覆盖选择路径。 */
function fixtures() {
  const editions: TextbookEdition[] = [
    ['zh-1-up', 'chinese', 1, 'first'], ['zh-1-low', 'chinese', 1, 'second'],
    ['zh-2-up-a', 'chinese', 2, 'first'], ['zh-2-up-b', 'chinese', 2, 'first'], ['en-4-low', 'english', 4, 'second']
  ].map(([editionId, subject, grade, semester]) => ({
    editionId: String(editionId), subject: subject as TextbookEdition['subject'], grade: Number(grade), semester: semester as TextbookEdition['semester'],
    regionCodes: ['CN-JS'], publisher: 'publisher', series: 'series', editionLabel: '2026', curriculumYear: 2026,
    isbn: null, sourceIds: ['source'], schemaVersion: 1, contentVersion: 'v2026', contentSha256: 'sha', reviewStatus: 'published',
    coverageStatus: 'partial', verifiedAt: '2026-10-03', publishedAt: '2026-10-03', availableEntryCount: 3, expectedEntryCount: 5, missingScopes: ['appendix']
  }));
  const versions = new Map(editions.map(edition => [edition.editionId, {
    edition,
    units: [{ unitId: 'u1', editionId: edition.editionId, order: 1, title: 'Unit 1' }, { unitId: 'u2', editionId: edition.editionId, order: 2, title: 'Unit 2' }],
    lessons: [{ lessonId: 'l1', unitId: 'u1', order: 1, title: 'Lesson 1' }, { lessonId: 'l2', unitId: 'u2', order: 1, title: 'Lesson 2' }],
    listTypes: ['recognition', 'writing', 'vocabulary'], entryCount: 3, sha256: 'sha'
  } as TextbookVersion]));
  const records = new Map(editions.map(edition => [edition.editionId, ['山', '水', '山水'].map((text, index) => ({
    entryId: `${edition.editionId}-${index}`, entryVersion: 'v1', editionId: edition.editionId, unitId: index === 2 ? 'u2' : 'u1', lessonId: index === 2 ? 'l2' : 'l1',
    order: index, form: index === 2 ? 'word' : 'character', listTypes: [(['recognition', 'writing', 'vocabulary'] as const)[index]!],
    text, pinyinDisplay: null, pinyinSegments: [...text].map(grapheme => ({ grapheme, syllable: 'shān' })), meaning: null, partOfSpeech: null,
    senseId: `s${index}`, context: null, promptContext: null, acceptableAnswers: [], sourceRefs: [], reviewStatus: 'published', blockingIssues: []
  } as CurriculumEntry))]));
  const catalog: TextbookCatalog = { catalogVersion: 'catalog1', editions,
    coverage: editions.map(edition => ({ subject: edition.subject, grade: edition.grade, semester: edition.semester, coverageStatus: 'partial', editionIds: [edition.editionId] })) };
  const requests: string[] = [];
  const api: WorksheetCurriculumApi = {
    catalog: async () => catalog,
    version: async id => { requests.push(id); return versions.get(id)!; },
    entries: async id => ({ entries: records.get(id)!, total: 3, contentVersion: 'v2026' })
  };
  return { api, versions, requests, catalog, records };
}

test('R06: 年级、上下册及同册各版本均可选，版本身份随导入快照保留', async () => {
  const { api, requests } = fixtures();
  const picker = useWorksheetCurriculum('chinese', api);
  await picker.loadCatalog();
  assert.equal(picker.editionId.value, 'zh-1-up');
  await picker.chooseSemester('second');
  picker.selectAllScopes();
  assert.equal(picker.getImport()?.editionId, 'zh-1-low');
  await picker.chooseGrade(2);
  assert.equal(picker.editionId.value, null, '缺失册次不能保留上一册内容');
  assert.equal(picker.getImport(), null);
  await picker.chooseSemester('first');
  assert.equal(picker.editions.value.length, 2);
  await picker.chooseEdition('zh-2-up-b');
  picker.selectAllScopes();
  const imported = picker.getImport()!;
  assert.equal(imported.editionId, 'zh-2-up-b');
  assert.equal(imported.contentVersion, 'v2026');
  assert.equal(imported.grade, 2);
  assert.equal(imported.semester, 'first');
  assert.ok(imported.label.includes('二年级上册'));
  assert.deepEqual(requests, ['zh-1-up', 'zh-1-low', 'zh-2-up-a', 'zh-2-up-b']);
});

test('R06: 字表混选与课文范围只过滤内容，追加导入不改变全局题型', async () => {
  const picker = useWorksheetCurriculum('chinese', fixtures().api);
  await picker.loadCatalog();
  picker.listTypes.value = ['recognition', 'writing'];
  picker.scopes.value = ['lesson:l1'];
  assert.deepEqual(picker.selectedEntries.value.map(entry => entry.text), ['山', '水']);
  let session = setMode(createSession('chinese'), 'chinese-pinyin').session;
  const selected = picker.getImport()!;
  session = importTextbook(session, textbookImportRequest(selected.entries, selected.editionId, selected.contentVersion, selected.label, 'append')).session;
  assert.equal(session.processing.mode, 'chinese-pinyin');
  assert.equal(session.document.rawText, '山 水');
  assert.ok(session.document.tokens.every(token => token.sourceRefs.every(source => source.editionId === selected.editionId && source.contentVersion === selected.contentVersion)));
  picker.listTypes.value = [];
  assert.equal(picker.getImport(), null, '取消全部字表应为空范围，不能误选整册');
  picker.listTypes.value = ['vocabulary'];
  picker.scopes.value = ['unit:u2'];
  assert.deepEqual(picker.selectedEntries.value.map(entry => entry.text), ['山水']);
  picker.scopes.value = [];
  assert.equal(picker.getImport(), null, '清空范围不能默认为全部');
});

test('R06: 英语其他年级与下册可达，缺失槽位诚实显示空态', async () => {
  const picker = useWorksheetCurriculum('english', fixtures().api);
  await picker.loadCatalog();
  assert.equal(picker.editionId.value, null);
  assert.deepEqual(picker.gradeOptions.map(option => option.value), [3, 4, 5, 6]);
  await picker.chooseGrade(4);
  await picker.chooseSemester('second');
  assert.deepEqual(picker.scopes.value, []);
  assert.equal(picker.getImport(), null);
  picker.selectAllScopes();
  assert.equal(picker.getImport()?.editionId, 'en-4-low');
});

test('R06: 快速切册，迟到响应不得覆盖所选新版本', async () => {
  const { api, versions } = fixtures();
  let release: (value: TextbookVersion) => void = () => {};
  api.version = id => id === 'zh-1-up'
    ? new Promise(resolve => { release = resolve; }) : Promise.resolve(versions.get(id)!);
  const picker = useWorksheetCurriculum('chinese', api);
  const firstLoad = picker.loadCatalog();
  await Promise.resolve();
  await picker.chooseSemester('second');
  assert.deepEqual(picker.scopes.value, []);
  picker.scopes.value = ['lesson:l1'];
  assert.equal(picker.getImport()?.editionId, 'zh-1-low');
  release(versions.get('zh-1-up')!);
  await firstLoad;
  assert.equal(picker.getImport()?.editionId, 'zh-1-low');
  assert.deepEqual(picker.scopes.value, ['lesson:l1'], '迟到响应不能覆盖用户主动选择的课文');
  assert.ok(picker.selectedEntries.value.every(entry => entry.editionId === 'zh-1-low'));
});

test('R06: 教材加载失败或身份不匹配不能导入旧内容', async () => {
  const { api, versions } = fixtures();
  const picker = useWorksheetCurriculum('chinese', api);
  await picker.loadCatalog();
  picker.selectAllScopes();
  api.version = async () => versions.get('zh-1-up')!;
  await picker.chooseSemester('second');
  assert.equal(picker.getImport(), null);
  assert.ok(picker.error.value);
  assert.equal(picker.selectedEntries.value.length, 0);
});


test('目录显示真实教材、年级、单元课文名称，缺失名称不暴露内部ID', async () => {
  const { api, versions } = fixtures();
  versions.get('zh-1-up')!.units[0]!.title = '识字第一单元';
  versions.get('zh-1-up')!.lessons[0]!.title = '天地人';
  const picker = useWorksheetCurriculum('chinese', api);
  await picker.loadCatalog();
  assert.equal(picker.scopeTree.value[0]?.label, '识字第一单元');
  assert.equal(picker.scopeTree.value[0]?.children?.[0]?.label, '天地人');
  assert.equal(curriculumScopeLabel('cn-ty-1-up-u01-l01', 'cn-ty-1-up-u01-l01', 1, 'lesson'), '第 1 课');
  assert.equal(curriculumScopeLabel('', 'en-3-up-u02', 2, 'unit'), '第 2 单元');
  assert.equal(curriculumEditionLabel(versions.get('en-4-low')!.edition), 'series · 四年级下册 · 2026');
});


test('真实一年级1.0.2已发布教材内reviewed条目整册318项均可选，拒绝草稿及阻断项', async () => {
  const book = JSON.parse(await readFile(new URL('../content/textbooks/cn-ty-1-1-pep-2024/1.0.2/book.json', import.meta.url), 'utf8')) as {
    edition: TextbookEdition; units: TextbookVersion['units']; lessons: TextbookVersion['lessons']; entries: CurriculumEntry[];
  };
  assert.equal(book.edition.reviewStatus, 'published');
  assert.equal(book.entries.length, 318);
  assert.ok(book.entries.every(entry => entry.reviewStatus === 'reviewed'));
  const listTypes = [...new Set(book.entries.flatMap(entry => entry.listTypes))];
  const api: WorksheetCurriculumApi = {
    catalog: async () => ({ catalogVersion: 'local-fixture', editions: [book.edition], coverage: [{ subject: 'chinese', grade: 1, semester: 'first', coverageStatus: 'partial', editionIds: [book.edition.editionId] }] }),
    version: async () => ({ edition: book.edition, units: book.units, lessons: book.lessons, listTypes, entryCount: book.entries.length, sha256: '' }),
    entries: async () => ({ entries: [
      ...book.entries,
      { ...book.entries[0]!, entryId: 'draft-entry', reviewStatus: 'draft' },
      { ...book.entries[0]!, entryId: 'blocked-entry', blockingIssues: ['UNREVIEWED_CONTENT'] }
    ], total: 320, contentVersion: book.edition.contentVersion })
  };
  const picker = useWorksheetCurriculum('chinese', api);
  await picker.loadCatalog();
  assert.equal(picker.selectedEntries.value.length, 0);
  assert.equal(picker.getImport(), null);
  picker.selectAllScopes();
  assert.equal(picker.selectedEntries.value.length, 318);
  const imported = picker.getImport()!;
  assert.equal(imported.entries.length, 318);
  assert.equal(imported.contentVersion, '1.0.2');
  assert.ok(!imported.entries.some(entry => entry.entryId === 'draft-entry' || entry.entryId === 'blocked-entry'));
  assert.equal(picker.scopeTree.value[0]?.label, '第一单元·识字');
});

test('教材默认不勾选，全选和清除仅改变练习范围并保留字表筛选', async () => {
  const picker = useWorksheetCurriculum('chinese', fixtures().api);
  await picker.loadCatalog();
  assert.ok(picker.scopeTree.value.length, '目录加载后可供用户自主选择');
  assert.deepEqual(picker.scopes.value, []);
  assert.equal(picker.selectedEntries.value.length, 0);
  assert.equal(picker.getImport(), null, '未选择内容不能进入导入流程');
  picker.selectAllScopes();
  assert.deepEqual(picker.selectedEntries.value.map(entry => entry.text), ['山', '水', '山水']);
  picker.listTypes.value = ['writing'];
  picker.scopes.value = [];
  assert.equal(picker.getImport(), null);
  assert.deepEqual(picker.listTypes.value, ['writing']);
  picker.selectAllScopes();
  assert.deepEqual(picker.selectedEntries.value.map(entry => entry.text), ['水']);
  picker.scopes.value = [];
  picker.scopes.value = ['lesson:l1'];
  assert.deepEqual(picker.selectedEntries.value.map(entry => entry.text), ['水']);
});

test('切换年级、册次或教材版本后保持未选择，不沿用上一教材的勾选', async () => {
  const picker = useWorksheetCurriculum('chinese', fixtures().api);
  await picker.loadCatalog();
  const changes = [
    () => picker.chooseGrade(2),
    () => picker.chooseEdition('zh-2-up-b'),
    () => picker.chooseGrade(1),
    () => picker.chooseSemester('second')
  ];
  for (const change of changes) {
    picker.selectAllScopes();
    assert.equal(picker.selectedEntries.value.length, 3);
    await change();
    assert.ok(picker.version.value);
    assert.deepEqual(picker.scopes.value, []);
    assert.equal(picker.selectedEntries.value.length, 0);
    assert.equal(picker.getImport(), null);
  }
});

test('英语教材按原书单词表导入，保留表内句型和词组，不混入表外表达', async () => {
  const book = JSON.parse(await readFile(new URL('../content/textbooks/en-yl-3-1-yilin-2024/1.0.1/book.json', import.meta.url), 'utf8')) as {
    edition: TextbookEdition; units: TextbookVersion['units']; lessons: TextbookVersion['lessons']; entries: CurriculumEntry[];
  };
  const vocabulary = book.entries.filter(entry => entry.listTypes.includes('vocabulary'));
  const queries: unknown[] = [];
  const api: WorksheetCurriculumApi = {
    catalog: async () => ({ catalogVersion: 'local-fixture', editions: [book.edition], coverage: [] }),
    version: async () => ({ edition: book.edition, units: book.units, lessons: book.lessons, listTypes: ['vocabulary', 'expression'], entryCount: book.entries.length, sha256: '' }),
    // 故意混入不属于词表的表达，不能因为同属一个单元而被导入。
    entries: async (_id, _version, query) => {
      queries.push(query);
      return { entries: [...book.entries, { ...book.entries[0]!, entryId: 'outside-word-list', text: 'Outside word list.', listTypes: ['expression'] }],
        total: book.entries.length + 1, contentVersion: book.edition.contentVersion };
    }
  };
  const picker = useWorksheetCurriculum('english', api);
  await picker.loadCatalog();
  assert.deepEqual(queries, [{ listTypes: ['vocabulary'] }]);
  assert.deepEqual(picker.listTypes.value, ['vocabulary']);
  assert.deepEqual(picker.scopes.value, []);
  assert.equal(picker.getImport(), null);
  assert.equal(picker.scopeTree.value.length, 9);
  assert.ok(picker.scopeTree.value.every(unit => !unit.children), '单词表按单元展示，不再套用课文层级');
  picker.selectAllScopes();
  assert.equal(vocabulary.length, 140);
  assert.equal(picker.selectedEntries.value.length, vocabulary.length);
  assert.deepEqual(new Set(picker.selectedEntries.value.map(entry => entry.entryId)), new Set(vocabulary.map(entry => entry.entryId)));
  assert.ok(!picker.selectedEntries.value.some(entry => entry.entryId === 'outside-word-list'));
  for (const text of ['Good morning.', 'Nice to meet you!', 'Can I help you?']) {
    assert.ok(picker.selectedEntries.value.some(entry => entry.text === text), '教材单词表原有的句型必须保留');
  }
  // 旧组件状态即使残留表达类勾选，也不能改变英语教材的单词表边界。
  picker.listTypes.value = ['expression'];
  assert.equal(picker.selectedEntries.value.length, vocabulary.length);
  const selected = picker.getImport()!;
  const imported = importTextbook(createSession('english'), textbookImportRequest(selected.entries, selected.editionId, selected.contentVersion, selected.label, 'append'));
  assert.deepEqual(imported.blockers, []);
  assert.equal(imported.session.document.tokens.length, 140);
  for (const phrase of ['excuse me', 'take pictures', 'get together', "New Year's Day"]) {
    const token = imported.session.document.tokens.find(item => item.text === phrase);
    assert.ok(token, `${phrase} 作为单词表中的完整一项保留`);
    assert.equal(token.meaning, vocabulary.find(entry => entry.text === phrase)!.meaning);
    assert.equal(token.meaningState, 'confirmed');
  }
  picker.scopes.value = [`unit:${book.units[0]!.unitId}`];
  assert.equal(picker.selectedEntries.value.length, 13);
  assert.ok(picker.selectedEntries.value.every(entry => entry.unitId === book.units[0]!.unitId));
  assert.ok(!picker.selectedEntries.value.some(entry => ['Bobby', 'Sam'].includes(entry.text)), '附录人名不得推算并入单元');
  const counts = book.units.map(unit => book.entries.filter(entry => entry.unitId === unit.unitId).length);
  assert.deepEqual(counts, [13, 11, 17, 9, 18, 12, 31, 18, 11]);
  assert.equal(book.units[8]!.title, '专有名词（附录）');
  picker.scopes.value = [`unit:${book.units[8]!.unitId}`];
  assert.equal(picker.selectedEntries.value.length, 11);
  assert.ok(picker.selectedEntries.value.some(entry => entry.text === "New Year's Day"));
  assert.ok(book.entries.every(entry => entry.sourceRefs.every(source => source.page && [78, 79, 80, 82].includes(source.page))));
  assert.equal(book.entries.filter(entry => entry.context?.includes('非单元核心词')).length, 13);
  for (const [text, alternative] of [['Mr', 'Mr.'], ['mum', 'mom'], ['grandfather', 'grandpa'], ['grandmother', 'grandma']]) {
    const entry = book.entries.find(item => item.text === text)!;
    assert.ok(entry.context?.includes(alternative!));
    assert.ok(entry.acceptableAnswers.includes(alternative!));
  }
  const child = book.entries.find(entry => entry.text === 'child')!;
  assert.ok(child.context?.includes('children'));
  assert.ok(!child.acceptableAnswers.includes('children'), '复数说明不能当成单数的同义答案');
  picker.scopes.value = [];
  assert.equal(picker.getImport(), null);
});

test('英语教材没有单词表条目时保持空态，不拿表达句替代', async () => {
  const { api, records, versions } = fixtures();
  const edition = versions.get('en-4-low')!;
  edition.listTypes = ['expression'];
  records.set(edition.edition.editionId, records.get(edition.edition.editionId)!.map(entry => ({ ...entry, listTypes: ['expression'] })));
  const picker = useWorksheetCurriculum('english', api);
  await picker.loadCatalog();
  await picker.chooseGrade(4);
  await picker.chooseSemester('second');
  picker.selectAllScopes();
  assert.equal(picker.selectedEntries.value.length, 0);
  assert.equal(picker.getImport(), null);
});


test('真实三四年级损坏版本标签只显示可靠年份版次，保持教材名及册次不变', async () => {
  for (const [grade, gradeName] of [[3, '三'], [4, '四']] as const) {
    const book = JSON.parse(await readFile(new URL(`../content/textbooks/cn-ty-${grade}-1-pep-2024/1.0.0/book.json`, import.meta.url), 'utf8')) as { edition: TextbookEdition };
    const originalLabel = book.edition.editionLabel;
    assert.ok(originalLabel.includes('\uFFFD'), '使用现存损坏元数据验证安全降级');
    const label = curriculumEditionLabel(book.edition);
    assert.equal(label, `统编语文 · ${gradeName}年级上册 · 2024 年版`);
    assert.ok(!label.includes('\uFFFD'));
    assert.ok(!label.includes(book.edition.editionId));
    assert.equal(book.edition.editionLabel, originalLabel, '界面格式化不改写已发布教材数据');
    assert.equal(curriculumEditionLabel({ ...book.edition, editionLabel: '损坏\uFFFD标签' }), `统编语文 · ${gradeName}年级上册`);
    assert.equal(curriculumEditionLabel({ ...book.edition, editionLabel: book.edition.editionId }), `统编语文 · ${gradeName}年级上册`);
  }
});


/** 模拟页面持有旧目录，而旧内容版本已从公开接口下线。 */
function staleCatalogFixture() {
  const data = fixtures();
  const current = structuredClone(data.catalog);
  current.editions[0]!.contentVersion = 'v2027';
  const latest = { ...data.versions.get('zh-1-up')!, edition: current.editions[0]! };
  const calls: string[] = [];
  let catalogCalls = 0;
  data.api.catalog = async () => { catalogCalls += 1; return catalogCalls === 1 ? data.catalog : current; };
  data.api.version = async (id, version) => {
    calls.push(`${id}@${version}`);
    if (id === 'zh-1-up' && version === 'v2026') throw new PublicApiError('TOOLBOX_003', 'resource.notFound', {}, undefined, 404);
    return id === 'zh-1-up' ? latest : data.versions.get(id)!;
  };
  data.api.entries = async (id, version) => ({ entries: data.records.get(id)!, total: 3, contentVersion: version });
  return { ...data, current, calls, catalogCalls: () => catalogCalls };
}

test('stale textbook version refreshes the catalog and loads the latest edition once', async () => {
  const data = staleCatalogFixture();
  const picker = useWorksheetCurriculum('chinese', data.api);
  await picker.loadCatalog();
  assert.equal(picker.error.value, '');
  assert.equal(picker.loading.value, false);
  assert.equal(data.catalogCalls(), 2);
  assert.deepEqual(data.calls, ['zh-1-up@v2026', 'zh-1-up@v2027']);
  assert.deepEqual(picker.scopes.value, []);
  picker.selectAllScopes();
  assert.equal(picker.getImport()?.contentVersion, 'v2027');
  assert.equal(picker.selectedEntries.value.length, 3);
});

test('failed recovery is bounded and never exposes stale entries', async () => {
  const data = staleCatalogFixture();
  data.api.entries = async () => { throw new PublicApiError('TOOLBOX_003', 'resource.notFound', {}, undefined, 404); };
  const picker = useWorksheetCurriculum('chinese', data.api);
  await picker.loadCatalog();
  assert.equal(data.catalogCalls(), 2);
  assert.ok(picker.error.value);
  assert.equal(picker.getImport(), null);
  assert.equal(picker.loading.value, false);
});

test('late recovery cannot overwrite a newly selected semester', async () => {
  const data = staleCatalogFixture();
  let release!: (value: TextbookCatalog) => void;
  let started!: () => void;
  const refreshing = new Promise<void>(resolve => { started = resolve; });
  let count = 0;
  data.api.catalog = async () => {
    if (++count === 1) return data.catalog;
    started();
    return new Promise(resolve => { release = resolve; });
  };
  const picker = useWorksheetCurriculum('chinese', data.api);
  const pending = picker.loadCatalog();
  await refreshing;
  await picker.chooseSemester('second');
  picker.selectAllScopes();
  release(data.current);
  await pending;
  assert.equal(picker.editionId.value, 'zh-1-low');
  assert.equal(picker.getImport()?.editionId, 'zh-1-low');
  assert.equal(picker.error.value, '');
});

test('manual retry preserves the chosen edition within the same grade and semester', async () => {
  const data = fixtures();
  const picker = useWorksheetCurriculum('chinese', data.api);
  await picker.loadCatalog();
  await picker.chooseGrade(2);
  await picker.chooseEdition('zh-2-up-b');
  await picker.loadCatalog();
  assert.equal(picker.editionId.value, 'zh-2-up-b');
});
