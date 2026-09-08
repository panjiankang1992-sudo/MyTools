/** 有声书生成运行的 App 可见状态。 */
export type AudiobookGenerationStatus = 'ACCEPTED' | 'QUEUED' | 'TEXT_READY' | 'COMPLETED' |
  'FAILED' | 'TIMED_OUT' | 'CANCELLED';

/** 有声书生成范围。 */
export type AudiobookGenerationMode = 'FULL' | 'INCREMENTAL' | 'REPAIR';

/** 创建有声书生成运行的请求。 */
export interface CreateAudiobookGeneration {
  ebookAssetId: string;
  idempotencyKey: string;
  mode: AudiobookGenerationMode;
  rightsConfirmed: boolean;
}

/** 基于完成版本替换一条冻结音色的请求。 */
export interface CreateAudiobookVoiceRevision {
  idempotencyKey: string;
  roleKey: string;
  provider: string;
  voiceType: string;
}

/** 基于完成版本修订一条低置信度说话人归因的请求。 */
export interface CreateAudiobookSpeakerRevision {
  idempotencyKey: string;
  chapterIndex: number;
  sequenceNumber: number;
  speakerKind: 'CHARACTER' | 'NARRATOR' | 'UNKNOWN';
  speakerCanonicalName?: string;
}

/** 基于完成版本修订一条中文词条的冻结读音。 */
export interface CreateAudiobookPronunciationRevision {
  idempotencyKey: string;
  term: string;
  pinyin: string;
}

/** 受管媒体导入状态。 */
export type ManagedEbookImportStatus = 'ACCEPTED' | 'QUEUED' | 'SUCCEEDED' | 'FAILED' |
  'TIMED_OUT' | 'CANCELLED';

/** 从书架中的受管 TXT 或 EPUB 文件创建 Reader 电子书导入。 */
export interface CreateManagedEbookImport {
  mediaItemId: string;
  idempotencyKey: string;
  rightsConfirmed: boolean;
}

/** 将受管媒体映射为 Reader 电子书资产的异步进度。 */
export interface ManagedEbookImport {
  id: string;
  ebookAssetId?: string;
  status: ManagedEbookImportStatus;
  title: string;
  createdAt: number;
  updatedAt: number;
}

/** 有声书生成进度摘要。 */
export interface AudiobookGeneration {
  id: string;
  status: AudiobookGenerationStatus;
  currentStage: string;
  mode: AudiobookGenerationMode;
  generationVersion: number;
  requestedChapterCount: number;
  completedChapterCount: number;
  failedChapterCount: number;
  errorCode?: string;
  createdAt: number;
  updatedAt: number;
}

/** 一个版本化播放清单中的章节。 */
export interface AudiobookChapter {
  index: number;
  title: string;
  availability: 'PENDING' | 'READY' | 'FAILED';
  audioAssetId?: string;
  durationMs?: number;
}

/** 固定版本的章节播放清单。 */
export interface AudiobookPlaybackManifest {
  generationId: string;
  generationVersion: number;
  status: AudiobookGenerationStatus;
  chapters: AudiobookChapter[];
}

/** 系统播放器可使用的短期章节流描述。 */
export interface AudiobookPlaybackDescriptor {
  url: string;
  ticket: string;
  expiresAt: number;
  generationId: string;
  chapterIndex: number;
}

/** 仅用于系统播放器试听音色的短期音频描述。 */
export interface AudiobookVoicePreviewDescriptor {
  url: string;
  ticket: string;
  expiresAt: number;
  generationId: string;
  provider: string;
  voiceType: string;
}

/** 整书归档的异步处理状态。 */
export type AudiobookExportStatus = 'ACCEPTED' | 'QUEUED' | 'COMPLETED' | 'FAILED' | 'TIMED_OUT' | 'CANCELLED';

/** 创建已完成有声书版本 ZIP 归档的请求。 */
export interface CreateAudiobookExport {
  idempotencyKey: string;
  format: 'ZIP';
}

/** 不携带存储位置和下载票据的归档任务摘要。 */
export interface AudiobookExport {
  id: string;
  generationId: string;
  format: 'ZIP';
  status: AudiobookExportStatus;
  currentStage: string;
  chapterCount: number;
  sizeBytes?: number;
  errorCode?: string;
  createdAt: number;
  updatedAt: number;
}

/** 仅用于立即交给系统下载器的短期 ZIP 下载描述。 */
export interface AudiobookExportDownloadDescriptor {
  url: string;
  ticket: string;
  expiresAt: number;
  generationId: string;
  exportId: string;
}

/** 一代有声书中可供人工审核的全书人物与关系摘要。 */
export interface AudiobookBookAnalysis {
  generationId: string;
  analysisModelVersion: string;
  analysisRuleVersion: string;
  characters: AudiobookCharacterProfile[];
  relationships: AudiobookCharacterRelationship[];
  speechSegmentCount: number;
  speechSegments: AudiobookSpeechSegment[];
}

/** 一条可审核的冻结说话人归因片段，低置信度时可含受限的冻结正文摘录。 */
export interface AudiobookSpeechSegment {
  chapterIndex: number;
  sequenceNumber: number;
  textStartCodepoint: number;
  textEndCodepoint: number;
  speakerKind: 'CHARACTER' | 'NARRATOR' | 'UNKNOWN';
  speakerCanonicalName?: string;
  deliveryTags: string[];
  confidence: number;
  reviewStatus: string;
  evidenceExcerpt?: string;
}

/** 一名已归并人物的展示档案。 */
export interface AudiobookCharacterProfile {
  canonicalName: string;
  displayName: string;
  presentation: string;
  characterType: string;
  traits: string[];
  firstChapterIndex: number;
  occurrenceCount: number;
  confidence: number;
  reviewStatus: string;
  aliases: AudiobookCharacterAlias[];
}

/** 人物别名及其原文证据定位。 */
export interface AudiobookCharacterAlias {
  alias: string;
  aliasType: string;
  evidenceChapterIndex?: number;
  evidenceStartCodepoint?: number;
  evidenceEndCodepoint?: number;
  confidence: number;
}

/** 人物或关系的可选原文证据区间。 */
export interface AudiobookAnalysisEvidence {
  chapterIndex?: number;
  startCodepoint?: number;
  endCodepoint?: number;
}

/** 一条可审核的人物关系。 */
export interface AudiobookCharacterRelationship {
  sourceCanonicalName: string;
  targetCanonicalName: string;
  relationshipType: string;
  direction: string;
  evidenceChapterIndex?: number;
  evidenceStartCodepoint?: number;
  evidenceEndCodepoint?: number;
  confidence: number;
  reviewStatus: string;
}

/** 一代有声书中可审核的冻结音色计划。 */
export interface AudiobookVoicePlan {
  generationId: string;
  qualityGateAttestationSha256?: string;
  bindings: AudiobookVoiceBinding[];
}

/** 一条已选择的旁白或角色音色绑定。 */
export interface AudiobookVoiceBinding {
  roleKey: string;
  characterCanonicalName?: string;
  provider: string;
  voiceType: string;
  matchScore: number;
  matchMethod: string;
  rationaleTags: string[];
  locked: boolean;
  reviewStatus: string;
}

/** 可供审核页选择的已启用音色目录。 */
export interface AudiobookVoiceCatalog {
  generationId: string;
  voices: AudiobookVoiceCandidate[];
}

/** 一代有声书中可审核的冻结读音词典。 */
export interface AudiobookPronunciationDictionary {
  generationId: string;
  fingerprintSha256: string;
  entries: AudiobookPronunciationEntry[];
}

/** 一条中文词条的带声调数字拼音读法。 */
export interface AudiobookPronunciationEntry {
  term: string;
  pinyin: string;
}

/** 一条不携带任何认证信息的音色目录项。 */
export interface AudiobookVoiceCandidate {
  provider: string;
  voiceType: string;
  language: string;
  presentation: string;
  ageGroup: string;
  styleTags: string[];
  narratorEligible: boolean;
  catalogVersion: string;
  ssmlSupported: boolean;
  previewAvailable: boolean;
}

/** 当前章节结束后的连续播放决策。 */
export interface AudiobookAutoNextDecision {
  kind: 'PLAY' | 'WAIT' | 'FAILED' | 'COMPLETED';
  chapter?: AudiobookChapter;
}
