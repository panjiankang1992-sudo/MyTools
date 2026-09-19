export type ChapterAdaptationKind = 'INITIAL' | 'OPTIMIZE' | 'REGENERATE';
export type ChapterAdaptationStatus = 'PENDING_DISPATCH' | 'QUEUED' | 'CONTEXT_FREEZING' | 'ANALYZING' |
  'GENERATING' | 'VALIDATING' | 'REPAIRING' | 'PERSISTING' | 'COMPLETED' | 'CANCEL_REQUESTED' | 'CANCELLED' | 'FAILED';

export interface ChapterAdaptationInput {
  idempotencyKey: string;
  intent: string;
  expectedBindingRevision: number;
  expectedCatalogRevision: number;
  expectedSourceSha256?: string;
  templateCode?: string;
  templateVersion?: number;
}
export interface AdaptationStyleTemplate {
  code: string;
  version: number;
  name: string;
  description: string;
  promptSha256: string;
}
export interface ChapterAdaptationCapability {
  shelfBookId: string;
  status: string;
  bindingRevision: number;
  catalogRevision: number;
  catalogSha256: string;
  reasonCode: string;
  pollAfterMs: number;
}
export interface AdaptationChapter {
  chapterId: string;
  index: number;
  title: string;
  contentKind: string;
  sourceSha256: string;
  eligible: boolean;
  ineligibleReason: string;
}
export interface AdaptationCatalog {
  shelfBookId: string;
  bindingRevision: number;
  catalogRevision: number;
  catalogSha256: string;
  items: AdaptationChapter[];
  nextCursor: string;
}
export interface ChapterAdaptationAccepted {
  adaptationId: string;
  chapterId: string;
  revisionNumber: number;
  requestKind: ChapterAdaptationKind;
  status: ChapterAdaptationStatus;
  currentStage: string;
  pollAfterMs: number;
  createdAt: string;
}
export interface ChapterAdaptationProgress {
  adaptationId: string;
  status: ChapterAdaptationStatus;
  currentStage: string;
  version: number;
  candidateCount: number;
  lastErrorCode: string;
  pollAfterMs: number;
  createdAt: string;
  startedAt: string;
  finishedAt: string;
}
export interface ChapterAdaptationLineage {
  childAdaptationId: string;
  rootAdaptationId: string;
  parentAdaptationId: string;
  triggerAdaptationId: string;
}
export interface ChapterAdaptationHistoryItem {
  styleTemplate?: AdaptationStyleTemplate;
  adaptationId: string;
  shelfBookId: string;
  chapterId: string;
  chapterTitle: string;
  revisionNumber: number;
  requestKind: ChapterAdaptationKind;
  lineage: ChapterAdaptationLineage;
  intent: string;
  status: ChapterAdaptationStatus;
  currentStage: string;
  selectedAttemptId: string;
  attemptCount: number;
  modelId: string;
  lastErrorCode: string;
  createdAt: string;
  finishedAt: string;
}
export interface ChapterAdaptationHistory {
  items: ChapterAdaptationHistoryItem[];
  nextCursor: string;
}
export interface ChapterAdaptationOutput {
  attemptId: string;
  content: string;
  contentSha256: string;
  viewStatus: string;
}
export interface ChapterAdaptationCandidate {
  attemptId: string;
  attemptNo: number;
  callKind: string;
  callStatus: string;
  disposition: string;
  selected: boolean;
  hasViewableOutput: boolean;
}
export interface ChapterAdaptationDetail {
  version: ChapterAdaptationHistoryItem;
  sourceRelation: string;
  sourceCheckedAt: string;
  result?: ChapterAdaptationOutput;
  attempts: ChapterAdaptationCandidate[];
}
export interface ChapterAdaptationSourceCheck {
  adaptationId: string;
  status: string;
  sourceCheckedAt: string;
  validUntil: string;
  pollAfterMs: number;
  reasonCode: string;
  bindingRevision: number;
  catalogRevision: number;
  sourceSha256: string;
}
export interface AdaptationDisclosurePayload {
  schemaVersion: string;
  providerCode: string;
  contractSha256: string;
  providerName: string;
  providerOrigin: string;
  dataUseNotice: string;
  retentionNotice: string;
  rightsNotice: string;
}
export interface AdaptationDisclosure {
  version: string;
  disclosureSha256: string;
  rightsAttestationVersion: string;
  payload: AdaptationDisclosurePayload;
}
export interface AdaptationFeatures {
  readEnabled: boolean;
  createEnabled: boolean;
  consentStatus: string;
  consentRevision: number;
  hasActiveConsent: boolean;
  acceptedAt: string;
  reasonCode: string;
  disclosure?: AdaptationDisclosure;
}
export interface AdaptationConsentInput {
  disclosureVersion: string;
  disclosureSha256: string;
  accepted: boolean;
  rightsAttested: boolean;
  expectedConsentRevision: number;
}
export interface AdaptationComparisonHunk {
  kind: string;
  original: string;
  adapted: string;
}
export interface AdaptationComparison {
  adaptationId: string;
  attemptId: string;
  mode: string;
  original: string;
  adapted: string;
  hunks: AdaptationComparisonHunk[];
}
