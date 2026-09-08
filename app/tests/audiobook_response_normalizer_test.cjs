const normalizerModule = require(process.argv[2]);
const progressPolicyModule = require(process.argv[3]);
const policyModule = require(process.argv[4]);
const normalizer = new normalizerModule.AudiobookResponseNormalizer();
const progressPolicy = new progressPolicyModule.AudiobookPlaybackProgressPolicy();
const policy = new policyModule.AudiobookPlaybackPolicy();
const generationId = '123e4567-e89b-12d3-a456-426614174000';

function equal(actual, expected, message) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

function rejects(callback, message) {
  let rejected = false;
  try { callback(); } catch (_) { rejected = true; }
  if (!rejected) throw new Error(`${message}: expected rejection`);
}

const generation = normalizer.generation({ id: generationId, status: 'QUEUED', currentStage: 'SYNTHESIZING',
  mode: 'FULL', generationVersion: 1, requestedChapterCount: 2, completedChapterCount: 0,
  failedChapterCount: 0, errorCode: null, createdAt: '2026-09-06T00:00:00Z',
  updatedAt: '2026-09-06T00:00:01Z' });
equal([generation.id, generation.status, generation.generationVersion], [generationId, 'QUEUED', 1],
  'Generation response normalization');
rejects(() => normalizer.generation({ id: generationId, status: 'INVALID', mode: 'FULL' }),
  'Invalid generation status');

const imported = normalizer.managedImport({ id: generationId, ebookAssetId: generationId,
  status: 'SUCCEEDED', title: '示例书', createdAt: '2026-09-06T00:00:00Z',
  updatedAt: '2026-09-06T00:00:01Z' });
equal([imported.status, imported.ebookAssetId], ['SUCCEEDED', generationId],
  'Managed import response normalization');
rejects(() => normalizer.managedImport({ id: generationId, status: 'SUCCEEDED', title: '示例书',
  createdAt: '2026-09-06T00:00:00Z', updatedAt: '2026-09-06T00:00:01Z' }),
  'Succeeded managed import requires Reader asset');

const voicePlan = normalizer.voicePlan({ generationId, qualityGateAttestationSha256: 'b'.repeat(64), bindings: [
  { roleKey: 'NARRATOR', characterCanonicalName: null, provider: 'VOLCENGINE', voiceType: 'narrator',
    matchScore: 1, matchMethod: 'RULES_V1', rationaleTags: ['configured_catalog'], locked: true,
    reviewStatus: 'AUTO' },
  { roleKey: 'CHARACTER:Lin', characterCanonicalName: 'Lin', provider: 'VOLCENGINE', voiceType: 'female',
    matchScore: 0.9, matchMethod: 'RULES_V1', rationaleTags: ['presentation_exact'], locked: true,
    reviewStatus: 'AUTO' }
] });
equal([voicePlan.bindings[1].voiceType, voicePlan.qualityGateAttestationSha256], ['female', 'b'.repeat(64)],
  'Voice plan response normalization');
rejects(() => normalizer.voicePlan({ generationId, qualityGateAttestationSha256: 'invalid', bindings: [
  { roleKey: 'NARRATOR', characterCanonicalName: null, provider: 'VOLCENGINE', voiceType: 'narrator',
    matchScore: 1, matchMethod: 'RULES_V1', rationaleTags: ['configured_catalog'], locked: true,
    reviewStatus: 'AUTO' }
]}), 'Voice plan validates quality gate attestation digest');
rejects(() => normalizer.voicePlan({ generationId, bindings: [
  { roleKey: 'CHARACTER:Lin', characterCanonicalName: 'Lin', provider: 'VOLCENGINE', voiceType: 'female',
    matchScore: 0.9, matchMethod: 'RULES_V1', rationaleTags: [], locked: true, reviewStatus: 'AUTO' }
] }), 'Voice plan requires narrator binding');

const voiceCatalog = normalizer.voiceCatalog({ generationId, voices: [
  { provider: 'VOLCENGINE', voiceType: 'narrator', language: 'zh-CN', presentation: 'NEUTRAL',
    ageGroup: 'ADULT', styleTags: ['calm'], narratorEligible: true, catalogVersion: 'v1', ssmlSupported: true, previewAvailable: true },
  { provider: 'VOLCENGINE', voiceType: 'female', language: 'zh-CN', presentation: 'FEMININE',
    ageGroup: 'ADULT', styleTags: ['warm'], narratorEligible: false, catalogVersion: 'v1', ssmlSupported: false, previewAvailable: false }
] });
equal([voiceCatalog.voices[1].voiceType, voiceCatalog.voices[0].ssmlSupported, voiceCatalog.voices[0].previewAvailable], ['female', true, true],
  'Voice catalog response normalization');
rejects(() => normalizer.voiceCatalog({ generationId, voices: [
  { provider: 'VOLCENGINE', voiceType: 'female', language: 'zh-CN', presentation: 'UNSAFE',
    ageGroup: 'ADULT', styleTags: [], narratorEligible: false, catalogVersion: 'v1', ssmlSupported: false, previewAvailable: false }
] }), 'Voice catalog validates presentation');

const pronunciationDictionary = normalizer.pronunciationDictionary({ generationId,
  fingerprintSha256: 'a'.repeat(64), entries: [{ term: '银行', pinyin: 'yin2 hang2' }] });
equal([pronunciationDictionary.generationId, pronunciationDictionary.entries[0].term,
  pronunciationDictionary.entries[0].pinyin], [generationId, '银行', 'yin2 hang2'],
  'Pronunciation dictionary response normalization');
rejects(() => normalizer.pronunciationDictionary({ generationId, fingerprintSha256: 'a'.repeat(64), entries: [
  { term: '银行', pinyin: 'yin2 hang2' }, { term: '银行', pinyin: 'yin2 hang2' }
] }), 'Pronunciation dictionary rejects duplicate terms');
rejects(() => normalizer.pronunciationDictionary({ generationId, fingerprintSha256: 'invalid', entries: [
  { term: '银行', pinyin: 'yin2 hang2' }
] }), 'Pronunciation dictionary requires a fingerprint');

const analysis = normalizer.bookAnalysis({ generationId, analysisModelVersion: 'gpt-5.6-mini',
  analysisRuleVersion: 'BOOK_ANALYSIS_RULES_V2', characters: [
  { canonicalName: 'Lin', displayName: '林', presentation: 'FEMININE', characterType: 'HUMAN',
    traits: ['warm'], firstChapterIndex: 0, occurrenceCount: 4, confidence: 0.91, reviewStatus: 'AUTO',
    aliases: [{ alias: '小林', aliasType: 'SHORT_NAME', evidenceChapterIndex: 0,
      evidenceStartCodepoint: 2, evidenceEndCodepoint: 4, confidence: 0.81 }] }
], relationships: [
  { sourceCanonicalName: 'Lin', targetCanonicalName: 'Chen', relationshipType: 'FRIEND', direction: 'OUTBOUND',
    evidenceChapterIndex: 0, evidenceStartCodepoint: 4, evidenceEndCodepoint: 6, confidence: 0.86,
    reviewStatus: 'AUTO' }
], speechSegments: [{ chapterIndex: 0, sequenceNumber: 0, textStartCodepoint: 0, textEndCodepoint: 2,
  speakerKind: 'CHARACTER', speakerCanonicalName: 'Lin', deliveryTags: [], confidence: 0.45,
  reviewStatus: 'AUTO', evidenceExcerpt: '“你好。” 林说道。' }] });
equal([analysis.characters[0].canonicalName, analysis.relationships.length, analysis.speechSegmentCount,
  analysis.speechSegments[0].speakerCanonicalName, analysis.speechSegments[0].evidenceExcerpt,
  analysis.analysisModelVersion, analysis.analysisRuleVersion],
  ['Lin', 1, 1, 'Lin', '“你好。” 林说道。', 'gpt-5.6-mini', 'BOOK_ANALYSIS_RULES_V2'],
  'Book analysis response normalization');
rejects(() => normalizer.bookAnalysis({ generationId, analysisModelVersion: 'model',
  analysisRuleVersion: 'RULES', characters: [], relationships: [], speechSegments: [{
  chapterIndex: 0, sequenceNumber: 0, textStartCodepoint: 2, textEndCodepoint: 2, speakerKind: 'CHARACTER',
  speakerCanonicalName: 'Lin', deliveryTags: [], confidence: 0.4, reviewStatus: 'AUTO'
}] }), 'Book analysis rejects empty speaker interval');
rejects(() => normalizer.bookAnalysis({ generationId, analysisModelVersion: 'model',
  analysisRuleVersion: 'RULES', characters: [], relationships: [], speechSegments: [{
  chapterIndex: 0, sequenceNumber: 0, textStartCodepoint: 0, textEndCodepoint: 2, speakerKind: 'UNKNOWN',
  deliveryTags: [], confidence: 0.4, reviewStatus: 'AUTO', evidenceExcerpt: 'bad\nexcerpt'
}] }), 'Book analysis rejects unsafe evidence excerpt');
rejects(() => normalizer.bookAnalysis({ generationId, analysisModelVersion: 'model',
  analysisRuleVersion: 'RULES', characters: [
  { canonicalName: 'Lin', displayName: '林', presentation: 'FEMININE', characterType: 'HUMAN', traits: [],
    firstChapterIndex: 0, occurrenceCount: 1, confidence: 1.2, reviewStatus: 'AUTO', aliases: [] }
], relationships: [], speechSegments: [] }), 'Book analysis validates confidence');

const accountScope = `account:v2:${'a'.repeat(64)}`;
const progress = progressPolicy.normalize({ owner: accountScope, mediaItemId: generationId, mediaVersion: 2,
  title: '示例书', generationId, generationVersion: 2, chapterIndex: 3, positionMs: 24000,
  updatedAt: 1760000000000 });
equal([progressPolicy.matches(progress, accountScope, generationId, 2), progressPolicy.position(progress, 60000)],
  [true, 24000], 'Audiobook progress isolates media revision and keeps safe position');
rejects(() => {
  const invalid = progressPolicy.normalize({ owner: accountScope, mediaItemId: generationId, mediaVersion: 0,
    title: '示例书', generationId, generationVersion: 2, chapterIndex: 0, positionMs: 0, updatedAt: 1 });
  if (invalid === undefined) throw new Error('invalid progress');
}, 'Audiobook progress rejects invalid media revision');

const manifest = normalizer.manifest({ generationId, generationVersion: 1, status: 'QUEUED', chapters: [
  { index: 0, title: '第一章', availability: 'READY', audioAssetId: generationId, durationMs: 1200 },
  { index: 1, title: '第二章', availability: 'PENDING', audioAssetId: null, durationMs: null }
] });
equal(policy.next(manifest, 0).kind, 'WAIT', 'Immediate pending chapter waits instead of skipping');
manifest.chapters[1].availability = 'READY';
manifest.chapters[1].audioAssetId = generationId;
manifest.chapters[1].durationMs = 1300;
equal(policy.next(manifest, 0).chapter.index, 1, 'Ready immediate chapter plays next');
equal(policy.next(manifest, 1).kind, 'COMPLETED', 'Last chapter completes session');
rejects(() => normalizer.manifest({ generationId, generationVersion: 1, status: 'COMPLETED', chapters: [
  { index: 2, title: 'wrong index', availability: 'PENDING' }
] }), 'Manifest chapter ordering');

const ticket = '0123456789abcdef0123456789abcdef';
const descriptor = normalizer.playbackTicket({ ticket,
  streamPath: `/api/app/v1/audiobook-playback/tickets/${ticket}`,
  expiresAt: '2026-09-06T12:00:00Z' }, 'https://mytools.example', generationId, 0,
  Date.parse('2026-09-06T11:00:00Z'));
equal(descriptor.url, `https://mytools.example/api/app/v1/audiobook-playback/tickets/${ticket}`,
  'Audiobook HTTPS ticket descriptor');
rejects(() => normalizer.playbackTicket({ ticket, streamPath: '/api/app/v1/media/tickets/' + ticket,
  expiresAt: '2026-09-06T12:00:00Z' }, 'https://mytools.example', generationId, 0,
  Date.parse('2026-09-06T11:00:00Z')), 'Media ticket cannot substitute audiobook ticket');

const previewDescriptor = normalizer.voicePreviewTicket({ ticket,
  streamPath: `/api/app/v1/audiobook-voice-preview/tickets/${ticket}`,
  expiresAt: '2026-09-06T11:20:00Z' }, 'https://mytools.example', generationId, 'VOLCENGINE', 'narrator',
  Date.parse('2026-09-06T11:00:00Z'));
equal(previewDescriptor.url, `https://mytools.example/api/app/v1/audiobook-voice-preview/tickets/${ticket}`,
  'Audiobook voice preview HTTPS ticket descriptor');
rejects(() => normalizer.voicePreviewTicket({ ticket,
  streamPath: `/api/app/v1/audiobook-playback/tickets/${ticket}`,
  expiresAt: '2026-09-06T11:20:00Z' }, 'https://mytools.example', generationId, 'VOLCENGINE', 'narrator',
  Date.parse('2026-09-06T11:00:00Z')), 'Chapter ticket cannot substitute voice preview ticket');

const exportId = '123e4567-e89b-12d3-a456-426614174001';
const exportJob = normalizer.audiobookExport({ id: exportId, generationId, format: 'ZIP', status: 'COMPLETED',
  currentStage: 'COMPLETED', chapterCount: 2, sizeBytes: 2048, errorCode: null,
  createdAt: '2026-09-06T00:00:00Z', updatedAt: '2026-09-06T00:01:00Z' });
equal([exportJob.id, exportJob.generationId, exportJob.sizeBytes], [exportId, generationId, 2048],
  'Audiobook ZIP export response normalization');
rejects(() => normalizer.audiobookExport({ id: exportId, generationId, format: 'ZIP', status: 'COMPLETED',
  currentStage: 'COMPLETED', chapterCount: 2, sizeBytes: null,
  createdAt: '2026-09-06T00:00:00Z', updatedAt: '2026-09-06T00:01:00Z' }),
  'Completed ZIP export requires archive size');

const exportDescriptor = normalizer.exportDownloadTicket({ ticket,
  downloadPath: `/api/app/v1/audiobook-export/tickets/${ticket}`,
  expiresAt: '2026-09-06T12:00:00Z' }, 'https://mytools.example', generationId, exportId,
  Date.parse('2026-09-06T11:00:00Z'));
equal(exportDescriptor.url, `https://mytools.example/api/app/v1/audiobook-export/tickets/${ticket}`,
  'Audiobook ZIP HTTPS ticket descriptor');
rejects(() => normalizer.exportDownloadTicket({ ticket,
  downloadPath: `/api/app/v1/audiobook-playback/tickets/${ticket}`,
  expiresAt: '2026-09-06T12:00:00Z' }, 'https://mytools.example', generationId, exportId,
  Date.parse('2026-09-06T11:00:00Z')), 'Playback ticket cannot substitute ZIP ticket');

console.log('Audiobook response normalizer tests passed');
