import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { after, before, test } from 'node:test';
import { getDocument } from 'pdfjs-dist/legacy/build/pdf.mjs';
import { createSession, generate, processSession, setMode, setProcessing } from '../src/domain/worksheet-session';
import { editablePairsForSession, importEditablePairsIntoSession, updateEditablePair, type EditableWorksheetPair } from '../src/domain/worksheet-editable-pairs';
import { availableWorksheetModes, checkGeneratable, chineseTextForToken, expandToCharacters, isValidPinyinForToken } from '../src/domain/worksheet-processing';
import { createStoredDraft, restoreDraftSession } from '../src/domain/worksheet-draft-session';
import { generateWorksheet, layoutWorksheet, projectWorksheet } from '../src/lib/worksheet';
import type { WordEntry, WorksheetArtifact, WorksheetMode, WorksheetOptions } from '../src/types';

const fixtures: Array<{ mode: WorksheetMode; row: EditableWorksheetPair; prompt: string; cells: number }> = [
  { mode: 'chinese-pinyin', row: { text: '你好' }, prompt: '你好', cells: 2 },
  { mode: 'chinese-dictation', row: { pinyin: 'nǐ hǎo' }, prompt: 'nǐ hǎo', cells: 2 },
  { mode: 'english-dictation', row: { meaning: '星期一' }, prompt: '星期一', cells: 0 },
  { mode: 'english-meaning', row: { english: 'Monday' }, prompt: 'Monday', cells: 0 }
];
const originalFetch = globalThis.fetch;
before(async () => {
  const font = new Uint8Array(await readFile(new URL('../public/fonts/NotoSansCJKsc-Regular.otf', import.meta.url)));
  globalThis.fetch = async input => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    assert.equal(url, '/fonts/NotoSansCJKsc-Regular.otf', 'Single-sided PDF generation must remain local');
    return new Response(font, { headers: { 'content-type': 'font/otf' } });
  };
});
after(() => { globalThis.fetch = originalFetch; });

for (const fixture of fixtures) {
  test(`single-sided ${fixture.mode}: retains input, only compatible mode and real printable PDF`, async () => {
    const subject = fixture.mode.startsWith('chinese') ? 'chinese' : 'english';
    const imported = importEditablePairsIntoSession(createSession(subject), [fixture.row]);
    assert.deepEqual(imported.blockers, []);
    assert.equal(imported.session.document.tokens.length, 1);
    assert.deepEqual(availableWorksheetModes(imported.session.document.tokens, subject), [fixture.mode]);
    const configured = setMode(imported.session, fixture.mode).session;
    const restored = restoreDraftSession(subject, createStoredDraft(configured));
    const originalPair = editablePairsForSession(configured)[0]!;
    const restoredPair = editablePairsForSession(restored)[0]!;
    for (const field of ['text', 'pinyin', 'meaning', 'english'] as const) assert.equal(restoredPair[field], originalPair[field]);
    assert.deepEqual(availableWorksheetModes(restored.document.tokens, subject), [fixture.mode]);
    assert.equal(checkGeneratable(processSession(configured).items, fixture.mode, 'answer').ok, false);
    let artifact: WorksheetArtifact | undefined;
    const result = await generate(restored, async input => {
      const entries: WordEntry[] = input.entries.map(token => ({ id: token.tokenId, text: token.text,
        pinyin: token.pinyinSegments.map(segment => segment.syllable).join(' '), meaning: token.meaning ?? '', unit: 1, kind: 'word' }));
      const options: WorksheetOptions = { mode: input.mode, title: 'Single sided', grade: 1, gridMm: 12, repetitions: 1, answer: false, expandedQuestions: true };
      const projected = projectWorksheet(entries, options);
      assert.equal(projected[0]!.prompt, fixture.prompt);
      assert.equal(projected[0]!.cells, fixture.cells);
      assert.equal(Object.hasOwn(projected[0]!, 'answerText'), false);
      if (fixture.mode === 'chinese-dictation') assert.deepEqual(projected[0]!.segments, ['nǐ', 'hǎo']);
      if (fixture.mode === 'english-dictation') {
        const geometry = layoutWorksheet(projected, options, (text, size) => text.length * size / 2);
        assert.ok(geometry[0]!.questions[0]!.width >= 40 * 72 / 25.4, 'Unknown English answer still gets a usable writing area');
      }
      artifact = await generateWorksheet(entries, options);
      return { generationId: 1, kind: 'student', snapshotHash: 'single', pageCount: artifact.pageCount, sha256: 'single' };
    });
    assert.equal(result.artifactGenerated, true, JSON.stringify(result.blockers));
    assert.ok(artifact);
    const pdf = await getDocument({ data: artifact.bytes.slice(), useWorkerFetch: false, isEvalSupported: false, useSystemFonts: false }).promise;
    try {
      assert.equal(pdf.numPages, 1);
      const page = await pdf.getPage(1);
      const content = await page.getTextContent();
      const text = content.items.map(item => 'str' in item ? item.str : '').join('').replace(/\s/gu, '').normalize('NFC');
      assert.ok(text.includes(fixture.prompt.replace(/\s/gu, '')), text);
      assert.equal(text.includes('undefined'), false);
      assert.equal(text.includes('null'), false);
      if (fixture.mode === 'chinese-dictation') assert.equal(text.includes('你好'), false, 'Pinyin-only input must never invent Hanzi answers');
      if (fixture.mode === 'english-dictation') assert.equal(text.includes('Monday'), false, 'Chinese-only input must never invent English answers');
      if (fixture.mode === 'chinese-pinyin') assert.equal(text.includes('nǐhǎo'), false);
      if (fixture.mode === 'english-meaning') assert.equal(text.includes('星期一'), false);
      assert.equal(await pdf.getAttachments(), null);
      const viewport = page.getViewport({ scale: 1 });
      assert.ok(Math.abs(viewport.width - 210 * 72 / 25.4) < 0.5);
      assert.ok(Math.abs(viewport.height - 297 * 72 / 25.4) < 0.5);
    } finally { await pdf.destroy(); URL.revokeObjectURL(artifact.url); }
    let answerRendererCalled = false;
    const answer = await generate(restored, async () => { answerRendererCalled = true; throw new Error('must not render'); }, 'answer');
    assert.equal(answer.artifactGenerated, false);
    assert.equal(answerRendererCalled, false);
  });
}

test('pinyin-only updates keep row identity and standalone syllables across drafts', () => {
  const imported = importEditablePairsIntoSession(createSession('chinese'), [{ text: '你好', pinyin: 'nǐ hǎo' }]).session;
  const id = imported.document.tokens[0]!.tokenId;
  const cleared = updateEditablePair(imported, id, { text: '' });
  assert.deepEqual(cleared.blockers, []);
  const token = cleared.session.document.tokens[0]!;
  assert.equal(token.tokenId, id);
  assert.equal(chineseTextForToken(token), '');
  assert.deepEqual(token.pinyinSegments, [{ grapheme: '', syllable: 'nǐ' }, { grapheme: '', syllable: 'hǎo' }]);
  assert.equal(isValidPinyinForToken(token), true);
  const restored = restoreDraftSession('chinese', createStoredDraft(cleared.session));
  assert.equal(restored.document.tokens[0]!.chineseText, '');
  assert.equal(editablePairsForSession(restored)[0]!.text, '');
  assert.equal(restored.document.tokens[0]!.automaticInitialized, true);
  const expanded = expandToCharacters(restored.document.tokens).items;
  assert.equal(expanded.length, 2);
  assert.deepEqual(expanded.map(chineseTextForToken), ['', '']);
  assert.deepEqual(expanded.map(item => item.text), ['', ''], '派生题目正文不能把音节伪装为汉字');
  assert.deepEqual(expanded.map(item => item.pinyinSegments[0]!.syllable), ['nǐ', 'hǎo']);
  assert.ok(expanded.every(isValidPinyinForToken));
});

test('invalid standalone pinyin cannot produce fake grids, and genuine Hanzi still require alignment', () => {
  for (const pinyin of ['hello', 'abc', 'nǐ invalid']) {
    const imported = importEditablePairsIntoSession(createSession('chinese'), [{ pinyin }]);
    assert.ok(imported.blockers.some(blocker => blocker.code === 'WORKSHEET_PINYIN_INVALID'));
    assert.deepEqual(availableWorksheetModes(imported.session.document.tokens, 'chinese'), []);
    assert.equal(editablePairsForSession(imported.session)[0]!.pinyin, pinyin);
  }
  const misaligned = importEditablePairsIntoSession(createSession('chinese'), [{ text: '你好', pinyin: 'nǐ' }]);
  assert.ok(misaligned.blockers.some(blocker => blocker.code === 'WORKSHEET_PINYIN_COUNT_MISMATCH'));
  assert.equal(isValidPinyinForToken(misaligned.session.document.tokens[0]!), false);
});

test('mixed one-sided rows require a common template and are never hidden by random selection', async () => {
  for (const subject of ['chinese', 'english'] as const) {
    const rows = subject === 'chinese' ? [{ text: '你好' }, { pinyin: 'nǐ hǎo' }] : [{ meaning: '星期一' }, { english: 'Monday' }];
    let session = importEditablePairsIntoSession(createSession(subject), rows).session;
    assert.equal(session.document.tokens.length, 2);
    assert.deepEqual(availableWorksheetModes(session.document.tokens, subject), []);
    session = setMode(session, subject === 'chinese' ? 'chinese-pinyin' : 'english-meaning').session;
    session = setProcessing(session, { selection: 'random-count', requestedCount: 1, seed: 'mixed' }).session;
    const result = await generate(session, async () => { throw new Error('mixed rows must not reach renderer'); });
    assert.equal(result.artifactGenerated, false);
    assert.ok(result.blockers.some(blocker => blocker.code === (subject === 'chinese' ? 'WORKSHEET_MISSING_TEXT' : 'WORKSHEET_MISSING_ENGLISH')));
    assert.equal(result.session.document.tokens.length, 2);
  }
});

test('answer generation cannot hide missing paired fields through random selection', async () => {
  let session = importEditablePairsIntoSession(createSession('english'), [{ english: 'Monday', meaning: '星期一' }, { english: 'Tuesday' }]).session;
  session = setMode(session, 'english-meaning').session;
  // 固定选中完整行，确认不是碰巧抽到缺字段才被拒绝。
  for (let seed = 0; seed < 20; seed += 1) {
    session = setProcessing(session, { selection: 'random-count', requestedCount: 1, seed: String(seed) }).session;
    if (processSession(session).items[0]?.meaning) break;
  }
  assert.equal(processSession(session).items[0]?.meaning, '星期一');
  const result = await generate(session, async () => { throw new Error('partial answers must not reach renderer'); }, 'answer');
  assert.equal(result.artifactGenerated, false);
  assert.ok(result.blockers.some(blocker => blocker.code === 'WORKSHEET_MISSING_MEANING'));
});
