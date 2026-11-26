import {
  clearSession, confirmUnambiguousTokens, createSession, fillSuggestedPinyin, generate,
  processSession, sessionCanOutput, setMode, setParsePolicy, setProcessing, setRawText
} from '../../../../toolbox-web/src/domain/worksheet-session.ts';
import { projectWorksheet } from '../../../../toolbox-web/src/lib/worksheet.ts';
import { useWorksheetV2Session } from '../../../../toolbox-web/src/composables/useWorksheetV2Session.ts';

/** 独立验收探针：只访问纯函数及内存 localStorage，不访问网络，不修改业务代码或既有证据。 */
async function main(): Promise<void> {
const findings: Array<Record<string, unknown>> = [];
const renderer = async () => ({ generationId: 0, kind: 'student' as const, snapshotHash: 'before', pageCount: 1, sha256: 'fixture' });
let session = setRawText(createSession('chinese'), '春 天').session;
session = setMode(session, 'chinese-pinyin').session;
session = (await generate(session, renderer)).session;

const replaced = setRawText(session, '秋 冬').session;
findings.push({ id: 'same-count-edit', expected: { canOutput: false }, actual: {
  rawText: replaced.document.rawText, artifactStatus: replaced.workflow.artifact?.status, canOutput: sessionCanOutput(replaced)
}, defectReproduced: sessionCanOutput(replaced) });

const changedPolicy = setParsePolicy(session, 'one-per-line').session;
findings.push({ id: 'parse-policy-change', expected: { canOutput: false }, actual: {
  tokens: changedPolicy.document.tokens.map(token => token.text), canOutput: sessionCanOutput(changedPolicy)
}, defectReproduced: sessionCanOutput(changedPolicy) });

const invalid = setRawText(session, '"unclosed');
const invalidResult = await generate(invalid.session, renderer);
findings.push({ id: 'invalid-raw-still-generates', expected: { artifactGenerated: false }, actual: {
  rawText: invalidResult.session.document.rawText,
  parseBlockers: invalid.blockers,
  tokens: invalidResult.session.document.tokens.map(token => token.text),
  artifactGenerated: invalidResult.artifactGenerated,
  canOutput: sessionCanOutput(invalidResult.session)
}, defectReproduced: invalidResult.artifactGenerated });

let resolveRender!: () => void;
const delayed = new Promise<void>(resolve => { resolveRender = resolve; });
let current = session;
const pending = generate(current, async () => { await delayed; return renderer(); });
current = clearSession(current).session;
const cleared = { rawText: current.document.rawText, canOutput: sessionCanOutput(current) };
resolveRender();
// 与 useWorksheetV2Session.generateNow 的行为一致：等待后无条件回写 result.session。
current = (await pending).session;
findings.push({ id: 'late-render-revives-cleared-session', expected: { rawText: '', canOutput: false }, actual: {
  beforeResolve: cleared, afterResolve: { rawText: current.document.rawText, canOutput: sessionCanOutput(current) }
}, defectReproduced: current.document.rawText !== '' && sessionCanOutput(current) });

let readings = setRawText(createSession('chinese'), '行 重').session;
readings = fillSuggestedPinyin(readings, text => ({ segments: [{ grapheme: text, syllable: text === '行' ? 'xíng' : 'zhòng' }] })).session;
const target = readings.document.tokens[0]!.tokenId;
// 与 useWorksheetV2Session.confirmToken 的调用完全一致。
readings = confirmUnambiguousTokens({ ...readings, document: {
  ...readings.document,
  tokens: readings.document.tokens.map(token => token.tokenId === target ? { ...token, pinyinState: 'confirmed' as const } : token)
} }, () => false).session;
findings.push({ id: 'one-confirm-confirms-other-polyphone', expected: { secondState: 'suggested' }, actual: {
  tokens: readings.document.tokens.map(token => ({ text: token.text, state: token.pinyinState }))
}, defectReproduced: readings.document.tokens[1]!.pinyinState === 'confirmed' });

readings = setProcessing(setMode(readings, 'chinese-pinyin').session, { repetitions: 2 }).session;
const processed = processSession(readings).items;
const projected = projectWorksheet(processed.map(token => ({
  id: token.tokenId, text: token.text, pinyin: token.pinyinSegments.map(item => item.syllable).join(' '),
  meaning: '', kind: 'character' as const, unit: 1
})), { mode: 'chinese-pinyin', title: '', grade: 1, gridMm: 12, repetitions: 2, answer: false });
findings.push({ id: 'repetition-expanded-twice', expected: { renderedQuestions: 4 }, actual: {
  inputCount: readings.document.tokens.length, repetitions: 2, processedCount: processed.length, renderedQuestions: projected.length
}, defectReproduced: projected.length !== 4 });

const storage = new Map<string, string>();
const localStorage = {
  getItem: (key: string) => storage.get(key) ?? null,
  setItem: (key: string, value: string) => { storage.set(key, value); },
  removeItem: (key: string) => { storage.delete(key); }
};
Object.defineProperty(globalThis, 'window', { value: { localStorage }, configurable: true });

const source = useWorksheetV2Session('english');
source.applyParsePolicy('one-per-line');
source.applyRawText('ice cream\nGood morning');
source.applyMode('english-dictation');
source.editMeaning(source.session.value.document.tokens[0]!.tokenId, '冰淇淋');
source.editMeaning(source.session.value.document.tokens[1]!.tokenId, '早上好');
source.session.value.layout = { title: 'Saved title', gridMm: 14 };
const saved = source.saveDraft();
const restored = useWorksheetV2Session('english');
restored.loadDraft();
findings.push({ id: 'v2-restore-loses-policy-corrections-layout', expected: {
  parsePolicy: 'one-per-line', tokens: ['ice cream', 'Good morning'], meanings: ['冰淇淋', '早上好'], layout: { title: 'Saved title', gridMm: 14 }
}, actual: {
  saved, rawText: restored.session.value.document.rawText, parsePolicy: restored.session.value.document.parsePolicy,
  tokens: restored.session.value.document.tokens.map(token => token.text),
  meanings: restored.session.value.document.tokens.map(token => token.meaning), layout: restored.session.value.layout
}, defectReproduced: restored.session.value.document.tokens.length !== 2 || restored.session.value.document.tokens[0]!.meaning !== '冰淇淋' });

storage.clear();
localStorage.setItem('yuyutian-toolbox:draft:v1:chinese', JSON.stringify({
  schemaVersion: 1, mode: 'chinese-dictation', customEntries: [{ id: 'legacy-one', text: '春', pinyin: 'chūn', meaning: '', unit: 1, kind: 'character' }],
  selectedIds: [], layout: { gridMm: 14 }, title: 'Legacy title'
}));
const migrated = useWorksheetV2Session('chinese');
migrated.loadDraft();
findings.push({ id: 'v1-restore-loses-mode-corrections', expected: { mode: 'chinese-dictation', pinyin: 'chūn' }, actual: {
  rawText: migrated.session.value.document.rawText, mode: migrated.session.value.processing.mode,
  pinyin: migrated.session.value.document.tokens[0]?.pinyinSegments, layout: migrated.session.value.layout
}, defectReproduced: migrated.session.value.processing.mode !== 'chinese-dictation' });

console.log(JSON.stringify({
  executedAt: new Date().toISOString(), scope: 'Pure session functions and real composable with in-memory storage; no network or real PDF rendering.',
  findingCount: findings.length, reproducedCount: findings.filter(item => item.defectReproduced).length, findings
}, null, 2));
}

void main().catch(error => { console.error(error); process.exitCode = 1; });
