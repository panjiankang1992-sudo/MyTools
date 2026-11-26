import assert from 'node:assert/strict';
import test from 'node:test';
import { compareEntries, compareLessons, compareUnits, coverageKey, gradesForSubject, hasBlockingIssues, isUsableEdition, type CurriculumEntry, type CurriculumLesson, type CurriculumUnit, type TextbookEdition } from '../src/domain/curriculum';
import { parseEntryRows, parseTable, detectDelimiter } from '../src/lib/csv';
import { sha256Hex, stableHash } from '../src/domain/hash';

/** 构造条目的最小可用实例。 */
function entry(entryId: string, overrides: Partial<CurriculumEntry> = {}): CurriculumEntry {
  return {
    entryId,
    entryVersion: 'v1',
    editionId: 'edition-1',
    unitId: 'unit-1',
    lessonId: null,
    order: 0,
    form: 'word',
    listTypes: ['vocabulary'],
    text: entryId,
    pinyinDisplay: 'pīn',
    pinyinSegments: [],
    meaning: 'meaning',
    partOfSpeech: null,
    senseId: entryId,
    context: null,
    promptContext: null,
    acceptableAnswers: [],
    sourceRefs: [],
    reviewStatus: 'published',
    blockingIssues: [],
    ...overrides
  };
}

/** 构造版本元数据。 */
function edition(overrides: Partial<TextbookEdition> = {}): TextbookEdition {
  return {
    editionId: 'edition-1',
    subject: 'chinese',
    grade: 1,
    semester: 'first',
    regionCodes: ['jiangsu'],
    publisher: 'publisher',
    series: 'series',
    editionLabel: '2024',
    curriculumYear: 2022,
    isbn: null,
    sourceIds: [],
    schemaVersion: 1,
    contentVersion: 'v1',
    contentSha256: 'hash',
    reviewStatus: 'published',
    coverageStatus: 'complete',
    verifiedAt: '2026-01-01T00:00:00Z',
    publishedAt: '2026-01-02T00:00:00Z',
    availableEntryCount: 10,
    expectedEntryCount: 10,
    missingScopes: [],
    ...overrides
  };
}

test('grade ranges follow the subject rules', () => {
  assert.deepEqual(gradesForSubject('chinese'), [1, 2, 3, 4, 5, 6]);
  assert.deepEqual(gradesForSubject('english'), [3, 4, 5, 6]);
});

test('ordering is stable and independent of input order', () => {
  const units: CurriculumUnit[] = [
    { unitId: 'u2', editionId: 'e', order: 2, title: 'b' },
    { unitId: 'u1', editionId: 'e', order: 1, title: 'a' }
  ];
  assert.deepEqual(units.slice().sort(compareUnits).map(item => item.unitId), ['u1', 'u2']);
  const lessons: CurriculumLesson[] = [
    { lessonId: 'l2', unitId: 'u1', order: 2, title: 'b' },
    { lessonId: 'l1', unitId: 'u1', order: 1, title: 'a' }
  ];
  assert.deepEqual(lessons.slice().sort(compareLessons).map(item => item.lessonId), ['l1', 'l2']);
  const entries = [
    entry('c', { unitId: 'u2', order: 0 }),
    entry('a', { unitId: 'u1', order: 1 }),
    entry('b', { unitId: 'u1', order: 0 })
  ];
  assert.deepEqual(entries.slice().sort(compareEntries).map(item => item.entryId), ['b', 'a', 'c']);
});

test('coverage key and usability reflect the published contract', () => {
  assert.equal(coverageKey('chinese', 1, 'first'), 'chinese:1:first');
  assert.equal(isUsableEdition(edition()), true);
  assert.equal(isUsableEdition(edition({ reviewStatus: 'draft' })), false);
  assert.equal(isUsableEdition(edition({ coverageStatus: 'missing' })), false);
  assert.equal(isUsableEdition(edition({ availableEntryCount: 0 })), false);
  assert.equal(hasBlockingIssues(entry('a', { blockingIssues: ['x'] })), true);
  assert.equal(hasBlockingIssues(entry('a')), false);
});

test('delimited parsing handles quotes, embedded delimiters, and newlines', () => {
  const csv = 'apple,"苹果, 平果"\nbook,"书\n本"\n"quote""d","引号"';
  const parsed = parseTable(csv, ',');
  assert.equal(parsed.errorLine, null);
  assert.deepEqual(parsed.rows, [['apple', '苹果, 平果'], ['book', '书\n本'], ['quote"d', '引号']]);
});

test('entry row parsing accepts tab, comma, and pipe separated input', () => {
  assert.deepEqual(parseEntryRows('山水\tshān shuǐ').pairs, [['山水', 'shān shuǐ']]);
  assert.deepEqual(parseEntryRows('apple,苹果').pairs, [['apple', '苹果']]);
  assert.deepEqual(parseEntryRows('apple | 苹果').pairs, [['apple', '苹果']]);
  assert.equal(detectDelimiter('a\tb'), '\t');
  assert.equal(detectDelimiter('a|b'), '|');
});

test('entry row parsing reports unusable lines and unterminated quotes', () => {
  const single = parseEntryRows('只有一列');
  assert.equal(single.pairs.length, 0);
  assert.equal(single.invalidLine, 1);
  const unterminated = parseEntryRows('apple,"苹果');
  assert.equal(unterminated.unterminatedQuote, true);
});

test('stable hash is deterministic and sha256 matches known vectors', async () => {
  assert.equal(stableHash('abc'), stableHash('abc'));
  assert.notEqual(stableHash('abc'), stableHash('abd'));
  const digest = await sha256Hex(new TextEncoder().encode('abc'));
  assert.equal(digest, 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  const empty = await sha256Hex(new Uint8Array());
  assert.equal(empty, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
});
