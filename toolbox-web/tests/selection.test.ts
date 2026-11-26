import assert from 'node:assert/strict';
import test from 'node:test';
import { balancedQuota, createSeededRandom, deriveSeed, normalizeText, shuffleWith } from '../src/domain/random';
import { entryDedupeKey, expandRepetitions, filterCandidates, normalizeCandidates, planBalancedQuota, selectForWorksheet } from '../src/domain/selection';
import type { CurriculumEntry } from '../src/domain/curriculum';

/** 构造可控的教材条目，便于验证确定性选题。 */
function entry(overrides: Partial<CurriculumEntry> & { entryId: string; text: string }): CurriculumEntry {
  return {
    entryVersion: 'v1',
    editionId: 'edition-1',
    unitId: 'unit-1',
    lessonId: null,
    order: 0,
    form: 'word',
    listTypes: ['vocabulary'],
    pinyinDisplay: 'pīn yīn',
    pinyinSegments: [],
    meaning: 'meaning',
    partOfSpeech: null,
    senseId: `${overrides.entryId}:sense`,
    context: null,
    promptContext: null,
    acceptableAnswers: [],
    sourceRefs: [],
    reviewStatus: 'published',
    blockingIssues: [],
    ...overrides
  };
}

test('balanced quota releases capacity from small groups and redistributes in order', () => {
  // 契约示例：容量 2/10/10，请求 9，预期 2/4/3。
  assert.deepEqual(balancedQuota([2, 10, 10], 9), [2, 4, 3]);
  // 候选不足时返回全部可用数量，不重复补题。
  assert.deepEqual(balancedQuota([5], 20), [5]);
  assert.deepEqual(balancedQuota([0, 0], 5), [0, 0]);
  assert.deepEqual(balancedQuota([3, 3], 0), [0, 0]);
});

test('seeded shuffle is reproducible and derives independent group seeds', () => {
  const items = ['a', 'b', 'c', 'd', 'e', 'f'];
  const first = shuffleWith(items, createSeededRandom('seed-1'));
  const second = shuffleWith(items, createSeededRandom('seed-1'));
  const other = shuffleWith(items, createSeededRandom('seed-2'));
  assert.deepEqual(first, second);
  assert.notDeepEqual(first, other);
  assert.deepEqual(items, ['a', 'b', 'c', 'd', 'e', 'f'], 'shuffle must not mutate the input');
  assert.notEqual(deriveSeed('seed-1', 'unit-a'), deriveSeed('seed-1', 'unit-b'));
});

test('normalization unifies whitespace and Unicode but keeps letter case', () => {
  assert.equal(normalizeText('  Hello   World '), 'Hello World');
  assert.equal(normalizeText('cafe\u0301'), 'café');
  assert.notEqual(normalizeText('Apple'), normalizeText('apple'));
});

test('deduplication keeps distinct senses and drops exact repeats', () => {
  const pool = [
    entry({ entryId: 'a', text: '苹果', meaning: 'apple', senseId: 'apple:fruit' }),
    entry({ entryId: 'b', text: '苹果', meaning: 'apple', senseId: 'apple:fruit' }),
    entry({ entryId: 'c', text: '苹果', meaning: 'fruit', senseId: 'c:fruit' })
  ];
  const { entries } = normalizeCandidates(pool);
  assert.equal(entries.length, 2);
  assert.notEqual(entryDedupeKey(pool[0]!), entryDedupeKey(pool[2]!));
});

test('blocking entries are excluded and reported instead of being generated', () => {
  const pool = [
    entry({ entryId: 'ok', text: '山水' }),
    entry({ entryId: 'bad', text: '待校对', blockingIssues: ['unverifiedPinyin'] }),
    entry({ entryId: 'nopinyin', text: '缺音', pinyinDisplay: '   ' })
  ];
  const result = selectForWorksheet({ candidates: pool, strategy: 'all', requestedCount: 0, seed: null, ordering: 'textbook' }, { needsPinyin: true, needsMeaning: false });
  assert.deepEqual(result.entries.map(item => item.entryId), ['ok']);
  assert.deepEqual(result.blocked.map(item => item.entryId), ['bad', 'nopinyin']);
});

test('filters intersect across dimensions and union inside one dimension', () => {
  const pool = [
    entry({ entryId: 'a', text: 'a', unitId: 'u1', lessonId: 'l1', listTypes: ['writing'] }),
    entry({ entryId: 'b', text: 'b', unitId: 'u2', lessonId: 'l2', listTypes: ['recognition'] }),
    entry({ entryId: 'c', text: 'c', unitId: 'u2', lessonId: 'l2', listTypes: ['writing'] })
  ];
  const base = { candidates: pool, strategy: 'all' as const, requestedCount: 0, seed: null, ordering: 'textbook' as const };
  assert.deepEqual(filterCandidates(pool, { ...base, unitIds: ['u1', 'u2'] }).map(item => item.entryId), ['a', 'b', 'c']);
  assert.deepEqual(filterCandidates(pool, { ...base, unitIds: ['u2'], listTypes: ['writing'] }).map(item => item.entryId), ['c']);
  assert.deepEqual(filterCandidates(pool, { ...base, lessonIds: ['l1'] }).map(item => item.entryId), ['a']);
});

test('random selection is reproducible for the same seed and reports shortage', () => {
  const pool = Array.from({ length: 12 }, (_, index) => entry({ entryId: `e${index}`, text: `w${index}`, order: index }));
  const request = { candidates: pool, strategy: 'random' as const, requestedCount: 5, seed: 'fixed', ordering: 'textbook' as const };
  const first = selectForWorksheet(request, { needsPinyin: true, needsMeaning: false });
  const second = selectForWorksheet(request, { needsPinyin: true, needsMeaning: false });
  assert.deepEqual(first.entries.map(item => item.entryId), second.entries.map(item => item.entryId));
  assert.equal(first.entries.length, 5);
  assert.equal(first.shortage, false);
  const short = selectForWorksheet({ ...request, requestedCount: 30 }, { needsPinyin: true, needsMeaning: false });
  assert.equal(short.entries.length, 12);
  assert.equal(short.available, 12);
  assert.equal(short.shortage, true);
});

test('balanced selection distributes across units and never repeats an entry', () => {
  const pool = [
    ...Array.from({ length: 2 }, (_, index) => entry({ entryId: `a${index}`, text: `a${index}`, unitId: 'unit-a', order: index })),
    ...Array.from({ length: 10 }, (_, index) => entry({ entryId: `b${index}`, text: `b${index}`, unitId: 'unit-b', order: index })),
    ...Array.from({ length: 10 }, (_, index) => entry({ entryId: `c${index}`, text: `c${index}`, unitId: 'unit-c', order: index }))
  ];
  const result = selectForWorksheet({ candidates: pool, strategy: 'balanced', requestedCount: 9, seed: 'seed', ordering: 'textbook' }, { needsPinyin: true, needsMeaning: false });
  assert.deepEqual(result.quotaByUnit.map(item => item.quota), [2, 4, 3]);
  assert.equal(result.entries.length, 9);
  assert.equal(new Set(result.entries.map(item => item.entryId)).size, 9);
  const repeated = selectForWorksheet({ candidates: pool, strategy: 'balanced', requestedCount: 9, seed: 'seed', ordering: 'textbook' }, { needsPinyin: true, needsMeaning: false });
  assert.deepEqual(result.entries.map(item => item.entryId), repeated.entries.map(item => item.entryId));
});

test('unit plans sort groups by unit id and repetition expands in order', () => {
  const pool = [
    entry({ entryId: 'x', text: 'x', unitId: 'unit-b', order: 0 }),
    entry({ entryId: 'y', text: 'y', unitId: 'unit-a', order: 0 })
  ];
  assert.deepEqual(planBalancedQuota(pool, 2).map(item => item.unitId), ['unit-a', 'unit-b']);
  assert.deepEqual(expandRepetitions(['p', 'q'], 2), ['p', 'q', 'p', 'q']);
  assert.deepEqual(expandRepetitions(['p'], 1), ['p']);
});
