import { ChapterAdaptationPolicy } from './ChapterAdaptationPolicy';
import { AdaptationCatalog, AdaptationChapter, ChapterAdaptationCapability, ChapterAdaptationAccepted,
  ChapterAdaptationProgress, ChapterAdaptationHistoryItem, ChapterAdaptationHistory, ChapterAdaptationDetail,
  ChapterAdaptationOutput, ChapterAdaptationCandidate, ChapterAdaptationKind, ChapterAdaptationStatus, ChapterAdaptationSourceCheck,
  AdaptationFeatures, AdaptationComparison, AdaptationComparisonHunk, AdaptationStyleTemplate } from './ChapterAdaptationModels';

/** 严格投影公共回复；损坏条目不能静默跳过后错误绑定另一章节。 */
export class ChapterAdaptationResponseNormalizer {
  private readonly policy: ChapterAdaptationPolicy = new ChapterAdaptationPolicy();
  /** 目录拒绝重复模板或损坏修订，不能默认选中错误提示词。 */
  styles(value: Object | undefined): AdaptationStyleTemplate[] {
    const codes: string[] = [];
    return this.array(this.record(value)['items'], 1000).map((raw: Object): AdaptationStyleTemplate => {
      const item = this.style(raw);
      if (codes.includes(item.code)) throw this.invalid(); codes.push(item.code); return item;
    });
  }
  private style(value: Object): AdaptationStyleTemplate {
    const row = this.record(value); const code = this.text(row['code'], 64);
    if (!/^[a-z][a-z0-9-]{0,63}$/.test(code)) throw this.invalid();
    const version = this.positive(row['version']); if (version > 1000000) throw this.invalid();
    return { code: code, version: version, name: this.text(row['name'], 160),
      description: this.text(row['description'], 1000), promptSha256: this.hash(row['promptSha256']) };
  }

  /** 告知只按固定纯文本字段展示，缺失或损坏的发布不能成为同意依据。 */
  features(value: Object | undefined): AdaptationFeatures {
    const row = this.record(value);
    const result: AdaptationFeatures = { readEnabled: this.bool(row['readEnabled']), createEnabled: this.bool(row['createEnabled']),
      consentStatus: this.one(row['consentStatus'], ['ACCEPTED', 'REQUIRED', 'UNAVAILABLE', 'NOT_REQUIRED']), consentRevision: this.integer(row['consentRevision']),
      hasActiveConsent: this.bool(row['hasActiveConsent']), acceptedAt: this.instant(row['acceptedAt'], true), reasonCode: this.code(row['reasonCode']) };
    if (result.createEnabled && !result.readEnabled) throw this.invalid();
    if (result.consentStatus === 'NOT_REQUIRED' && (result.hasActiveConsent || result.acceptedAt !== '' ||
      result.consentRevision !== 0 || (row['disclosure'] !== undefined && row['disclosure'] !== null))) throw this.invalid();
    if (row['disclosure'] !== undefined && row['disclosure'] !== null) {
      const item = this.record(row['disclosure']); const payload = this.record(item['payload']);
      result.disclosure = { version: this.identifier(item['version'], 64), disclosureSha256: this.hash(item['disclosureSha256']),
        rightsAttestationVersion: this.identifier(item['rightsAttestationVersion'], 64), payload: {
          schemaVersion: this.one(payload['schemaVersion'], ['adaptation-disclosure-v1']), providerCode: this.identifier(payload['providerCode'], 32),
          contractSha256: this.hash(payload['contractSha256']), providerName: this.text(payload['providerName'], 240),
          providerOrigin: this.one(payload['providerOrigin'], ['https://api.sillytraven.dev']), dataUseNotice: this.text(payload['dataUseNotice'], 4000),
          retentionNotice: this.text(payload['retentionNotice'], 4000), rightsNotice: this.text(payload['rightsNotice'], 4000) } };
    }
    if (result.consentStatus === 'UNAVAILABLE' && (result.disclosure !== undefined || result.createEnabled)) throw this.invalid();
    if (result.consentStatus !== 'UNAVAILABLE' && result.consentStatus !== 'NOT_REQUIRED' && result.disclosure === undefined) throw this.invalid();
    if (result.consentStatus === 'ACCEPTED' && (!result.hasActiveConsent || result.acceptedAt === '' || result.consentRevision < 1)) throw this.invalid();
    return result;
  }

  /** 从现有本人书架列表精确解析服务端身份，不按书名、地址或位置猜测。 */
  shelf(value: Object | undefined, bookId: string): string {
    if (!Array.isArray(value) || value.length > 10000 || bookId.startsWith('local:')) throw this.invalid();
    const matches: string[] = [];
    value.forEach((raw: Object) => {
      const row = this.record(raw); const metadata = this.record(row['metadata']);
      if (metadata['bookId'] !== bookId || row['deleted'] !== false) return;
      if (metadata['origin'] !== 'source' && metadata['origin'] !== 'remote') throw this.invalid();
      matches.push(this.uuid(row['id']));
    });
    if (matches.length > 1) throw new Error('ADAPTATION_SHELF_AMBIGUOUS');
    if (matches.length === 0) throw new Error('ADAPTATION_SHELF_NOT_SYNCED'); return matches[0];
  }
  /** 准备状态仅用于选择准备或读取目录，不作为模型创建授权。 */
  capability(value: Object | undefined, shelf: string): ChapterAdaptationCapability {
    const row = this.record(value); this.same(row['shelfBookId'], shelf);
    return { shelfBookId: shelf, status: this.one(row['status'], ['READY', 'PREPARING', 'BROKEN', 'UNAVAILABLE']),
      bindingRevision: this.integer(row['bindingRevision']), catalogRevision: this.integer(row['catalogRevision']),
      catalogSha256: this.hash(row['catalogSha256'], true), reasonCode: this.code(row['reasonCode']),
      pollAfterMs: this.poll(row['pollAfterMs']) };
  }
  /** 目录中每项身份唯一、顺序递增，任何条目损坏即拒绝整页。 */
  catalog(value: Object | undefined, shelf: string): AdaptationCatalog {
    const row = this.record(value); this.same(row['shelfBookId'], shelf);
    const items: AdaptationChapter[] = []; const ids: string[] = [];
    this.array(row['items'], 500).forEach((raw: Object) => {
      const item = this.record(raw); const id = this.uuid(item['chapterId']); const index = this.integer(item['index']);
      if (ids.includes(id) || (items.length > 0 && index <= items[items.length - 1].index)) throw this.invalid(); ids.push(id);
      items.push({ chapterId: id, index, title: this.text(item['title'], 1000), contentKind: this.text(item['contentKind'], 32),
        sourceSha256: this.hash(item['sourceSha256'], true), eligible: this.bool(item['eligible']),
        ineligibleReason: this.code(item['ineligibleReason']) });
    });
    return { shelfBookId: shelf, bindingRevision: this.positive(row['bindingRevision']), catalogRevision: this.positive(row['catalogRevision']),
      catalogSha256: this.hash(row['catalogSha256']), items, nextCursor: this.cursor(row['nextCursor']) };
  }
  /** 解析稳定创建收据。 */
  accepted(value: Object | undefined, chapter: string, kind: ChapterAdaptationKind): ChapterAdaptationAccepted {
    const row = this.record(value); this.same(row['chapterId'], chapter);
    if (this.kind(row['requestKind']) !== kind) throw this.invalid();
    return { adaptationId: this.uuid(row['adaptationId']), chapterId: chapter, revisionNumber: this.positive(row['revisionNumber']),
      requestKind: kind, status: this.status(row['status']), currentStage: this.text(row['currentStage'], 64),
      pollAfterMs: this.poll(row['pollAfterMs']), createdAt: this.instant(row['createdAt']) };
  }
  /** 轮询同时校验请求资源，迟到回复另由页面 revision 控制。 */
  progress(value: Object | undefined, id: string): ChapterAdaptationProgress {
    const row = this.record(value); this.same(row['adaptationId'], id);
    return { adaptationId: id, status: this.status(row['status']), currentStage: this.text(row['currentStage'], 64),
      version: this.integer(row['version']), candidateCount: this.integer(row['candidateCount'], 5), lastErrorCode: this.code(row['lastErrorCode']),
      pollAfterMs: this.poll(row['pollAfterMs']), createdAt: this.instant(row['createdAt']), startedAt: this.instant(row['startedAt'], true),
      finishedAt: this.instant(row['finishedAt'], true) };
  }
  /** 只接受同一版本的有限核验窗口，缺失摘要或过长有效期不能开启派生。 */
  sourceCheck(value: Object | undefined, id: string): ChapterAdaptationSourceCheck {
    const row = this.record(value); this.same(row['adaptationId'], id);
    const status = this.one(row['status'], ['QUEUED', 'CHECKING', 'CURRENT', 'STALE', 'UNKNOWN']);
    const checked = this.instant(row['sourceCheckedAt'], status !== 'CURRENT');
    const valid = this.instant(row['validUntil'], status !== 'CURRENT');
    const hash = this.hash(row['sourceSha256'], status !== 'CURRENT');
    if (status === 'CURRENT' && (Date.parse(valid) <= Date.parse(checked) || Date.parse(valid) - Date.parse(checked) > 60000)) throw this.invalid();
    if (status !== 'CURRENT' && (valid !== '' || hash !== '')) throw this.invalid();
    return { adaptationId: id, status, sourceCheckedAt: checked, validUntil: valid, sourceSha256: hash,
      reasonCode: this.code(row['reasonCode']), pollAfterMs: this.poll(row['pollAfterMs']),
      bindingRevision: this.positive(row['bindingRevision']), catalogRevision: this.positive(row['catalogRevision']) };
  }
  /** 历史只包含同书同章版本，顺序必须严格倒序。 */
  history(value: Object | undefined, shelf: string, chapter: string): ChapterAdaptationHistory {
    const row = this.record(value); const items: ChapterAdaptationHistoryItem[] = []; const ids: string[] = [];
    this.array(row['items'], 50).forEach((raw: Object) => {
      const item = this.historyItem(raw); if (item.shelfBookId !== shelf || item.chapterId !== chapter || ids.includes(item.adaptationId) ||
        (items.length > 0 && item.revisionNumber >= items[items.length - 1].revisionNumber)) throw this.invalid();
      ids.push(item.adaptationId); items.push(item);
    });
    return { items, nextCursor: this.cursor(row['nextCursor']) };
  }
  /** 详情正文仅作为纯文本展示，不能变成后续请求的输入字段。 */
  detail(value: Object | undefined, id: string, shelf: string, chapter: string): ChapterAdaptationDetail {
    const row = this.record(value); const version = this.historyItem(row['version']);
    if (version.adaptationId !== id || version.shelfBookId !== shelf || version.chapterId !== chapter) throw this.invalid();
    const attempts: ChapterAdaptationCandidate[] = [];
    this.array(row['attempts'], 5).forEach((raw: Object) => {
      const item = this.record(raw); const candidate: ChapterAdaptationCandidate = { attemptId: this.uuid(item['attemptId']),
        attemptNo: this.positive(item['attemptNo'], 5), callKind: this.one(item['callKind'], ['GENERATE', 'REPAIR']),
        callStatus: this.text(item['callStatus'], 40), disposition: this.text(item['disposition'], 40),
        selected: this.bool(item['selected']), hasViewableOutput: this.bool(item['hasViewableOutput']) };
      if (attempts.some((existing: ChapterAdaptationCandidate) => existing.attemptId === candidate.attemptId)) throw this.invalid();
      attempts.push(candidate);
    });
    const result: ChapterAdaptationDetail = { version, sourceRelation: this.one(row['sourceRelation'], ['CURRENT', 'STALE', 'UNKNOWN']),
      sourceCheckedAt: this.instant(row['sourceCheckedAt'], true), attempts };
    if (row['result'] !== null && row['result'] !== undefined) {
      result.result = this.output(row['result'], version.selectedAttemptId);
      if (version.status !== 'COMPLETED' || result.result.viewStatus !== 'SELECTED') throw this.invalid();
    }
    return result;
  }
  /** 按需读取候选必须显式保留是否被采用或约束拒绝的标记。 */
  output(value: Object | undefined, attempt: string): ChapterAdaptationOutput {
    const row = this.record(value); this.same(row['attemptId'], attempt);
    return { attemptId: attempt, content: this.text(row['content'], 240000), contentSha256: this.hash(row['contentSha256']),
      viewStatus: this.one(row['viewStatus'], ['SELECTED', 'NOT_SELECTED', 'REJECTED_BY_CONSTRAINTS']) };
  }
  /** 段落索引连续，重建的新正文必须等于当前已采用结果，不能跨版本展示差异。 */
  comparison(value: Object | undefined, id: string, output: ChapterAdaptationOutput): AdaptationComparison {
    const row = this.record(value); this.same(row['adaptationId'], id); this.same(row['attemptId'], output.attemptId);
    this.hash(row['originalSha256']); if (this.hash(row['resultSha256']) !== output.contentSha256) throw this.invalid();
    const mode = this.one(row['mode'], ['HUNKS', 'SIDE_BY_SIDE']);
    const hunks: AdaptationComparisonHunk[] = []; let original = ''; let adapted = ''; let left = 0; let right = 0;
    const rawHunks = this.array(row['hunks'], 1000);
    if (mode === 'SIDE_BY_SIDE') {
      if (rawHunks.length !== 0) throw this.invalid();
      original = this.text(row['original'], 240000); adapted = this.text(row['adapted'], 240000);
    } else {
      if (rawHunks.length === 0 || row['original'] != null || row['adapted'] != null) throw this.invalid();
      rawHunks.forEach((raw: Object) => {
        const hunk = this.record(raw); const kind = this.one(hunk['kind'], ['EQUAL', 'INSERT', 'DELETE', 'REPLACE']);
        if (this.integer(hunk['originalStart'], 500) !== left || this.integer(hunk['adaptedStart'], 500) !== right) throw this.invalid();
        const a = this.array(hunk['original'], 500).map((part: Object) => this.text(part, 240000));
        const b = this.array(hunk['adapted'], 500).map((part: Object) => this.text(part, 240000));
        if ((kind === 'EQUAL' || kind === 'DELETE') ? a.length === 0 || b.length !== 0 :
          kind === 'INSERT' ? a.length !== 0 || b.length === 0 : a.length === 0 || b.length === 0) throw this.invalid();
        const oldText = a.join(''); const newText = kind === 'EQUAL' ? oldText : b.join('');
        original += oldText; adapted += newText; left += a.length; right += kind === 'EQUAL' ? a.length : b.length;
        if (left > 500 || right > 500 || original.length > 240000 || adapted.length > 240000) throw this.invalid();
        hunks.push({ kind: kind, original: oldText, adapted: newText });
      });
    }
    if (adapted !== output.content || Array.from(original).length > 120000) throw this.invalid();
    return { adaptationId: id, attemptId: output.attemptId, mode: mode, original: original, adapted: adapted, hunks: hunks };
  }

  private historyItem(value: Object): ChapterAdaptationHistoryItem {
    const row = this.record(value); const lineage = this.record(row['lineage']); const id = this.uuid(row['adaptationId']);
    this.same(lineage['childAdaptationId'], id);
    const parent = this.optionalUuid(lineage['parentAdaptationId']); const trigger = this.optionalUuid(lineage['triggerAdaptationId']);
    const root = this.uuid(lineage['rootAdaptationId']); const kind = this.kind(row['requestKind']);
    if ((kind === 'INITIAL' && (root !== id || parent !== '' || trigger !== '')) ||
      (kind !== 'INITIAL' && (root === id || trigger === '' || trigger === id)) ||
      (kind === 'OPTIMIZE' && parent !== trigger) || (kind === 'REGENERATE' && parent !== '')) throw this.invalid();
    return { adaptationId: id, shelfBookId: this.uuid(row['shelfBookId']), chapterId: this.uuid(row['chapterId']),
      chapterTitle: this.text(row['chapterTitle'], 1000), revisionNumber: this.positive(row['revisionNumber']), requestKind: kind,
      lineage: { childAdaptationId: id, rootAdaptationId: root, parentAdaptationId: parent, triggerAdaptationId: trigger },
      intent: this.text(row['intent'], 4000), status: this.status(row['status']), currentStage: this.text(row['currentStage'], 64),
      selectedAttemptId: this.optionalUuid(row['selectedAttemptId']), attemptCount: this.integer(row['attemptCount'], 5),
      modelId: this.text(row['modelId'], 255, true), lastErrorCode: this.code(row['lastErrorCode']),
      createdAt: this.instant(row['createdAt']), finishedAt: this.instant(row['finishedAt'], true),
      styleTemplate: row['styleTemplate'] === undefined || row['styleTemplate'] === null ? undefined : this.style(row['styleTemplate']) };
  }
  private status(value: Object): ChapterAdaptationStatus {
    return this.one(value, ['PENDING_DISPATCH', 'QUEUED', 'CONTEXT_FREEZING', 'ANALYZING', 'GENERATING', 'VALIDATING',
      'REPAIRING', 'PERSISTING', 'COMPLETED', 'CANCEL_REQUESTED', 'CANCELLED', 'FAILED']) as ChapterAdaptationStatus;
  }
  private kind(value: Object): ChapterAdaptationKind { return this.one(value, ['INITIAL', 'OPTIMIZE', 'REGENERATE']) as ChapterAdaptationKind; }
  private identifier(value: Object, max: number): string { const text = this.text(value, max); if (!/^[A-Za-z0-9_.:-]+$/.test(text)) throw this.invalid(); return text; }
  private record(value: Object | undefined): Record<string, Object> {
    if (value === undefined || value === null || typeof value !== 'object' || Array.isArray(value)) throw this.invalid();
    return value as Record<string, Object>;
  }
  private array(value: Object, maximum: number): Object[] { if (!Array.isArray(value) || value.length > maximum) throw this.invalid(); return value as Object[]; }
  private text(value: Object, maximum: number, optional: boolean = false): string {
    if (optional && (value === null || value === undefined)) return '';
    if (typeof value !== 'string' || value.length > maximum || (!optional && value.length === 0) ||
      /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]/.test(value) ||
      Array.from(value).some((point: string) => point.length === 1 && /[\uD800-\uDFFF]/.test(point))) throw this.invalid(); return value;
  }
  private integer(value: Object, maximum: number = Number.MAX_SAFE_INTEGER): number { if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0 || value > maximum) throw this.invalid(); return value; }
  private positive(value: Object, maximum: number = Number.MAX_SAFE_INTEGER): number { const result = this.integer(value, maximum); if (result < 1) throw this.invalid(); return result; }
  private uuid(value: Object): string { return this.policy.id(this.text(value, 36)); }
  private optionalUuid(value: Object): string { return value === null || value === undefined ? '' : this.uuid(value); }
  private same(value: Object, id: string): void { if (this.uuid(value) !== this.policy.id(id)) throw this.invalid(); }
  private bool(value: Object): boolean { if (typeof value !== 'boolean') throw this.invalid(); return value; }
  private one(value: Object, allowed: string[]): string { if (typeof value !== 'string' || !allowed.includes(value)) throw this.invalid(); return value; }
  private hash(value: Object, optional: boolean = false): string { if (optional && (value === null || value === undefined)) return ''; const result = this.text(value, 64); if (!/^[0-9a-f]{64}$/.test(result)) throw this.invalid(); return result; }
  private code(value: Object): string { const result = this.text(value, 32, true); if (result !== '' && !/^READER_[0-9]{3}$/.test(result)) throw this.invalid(); return result; }
  private cursor(value: Object): string { const result = this.text(value, 4096, true); if (result !== '' && !/^[A-Za-z0-9_.-]+$/.test(result)) throw this.invalid(); return result; }
  private poll(value: Object): number { return value === null || value === undefined ? 1500 : Math.max(1000, Math.min(10000, this.integer(value, 60000))); }
  private instant(value: Object, optional: boolean = false): string { const result = this.text(value, 64, optional); if (result === '' && optional) return ''; if (!/^\d{4}-\d{2}-\d{2}T.*Z$/.test(result) || !Number.isFinite(Date.parse(result))) throw this.invalid(); return result; }
  private invalid(): Error { return new Error('ADAPTATION_RESPONSE_INVALID'); }
}
