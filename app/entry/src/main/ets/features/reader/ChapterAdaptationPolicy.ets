import { Book } from './ReaderModels';
import { ChapterAdaptationStatus, ChapterAdaptationInput, ChapterAdaptationDetail, ChapterAdaptationSourceCheck,
  AdaptationFeatures, AdaptationConsentInput, AdaptationChapter } from './ChapterAdaptationModels';

/** 改编请求与页面生命周期的纯策略，正文不能作为请求字段。 */
export class ChapterAdaptationPolicy {
  private revision: number = 0;

  /** 每次切换页面、章节或账户时使旧回复失效。 */
  advance(): number { this.revision++; return this.revision; }
  /** 只有当前操作可以更新界面。 */
  current(revision: number): boolean { return revision === this.revision; }
  /** 本地章节行只用于定位；必须从权威目录取得精确匹配的章节身份。 */
  targetChapter(items: AdaptationChapter[], index: number, title: string): AdaptationChapter | undefined {
    if (!Number.isSafeInteger(index) || index < 0 || title === '') throw new Error('ADAPTATION_CHAPTER_NOT_FOUND');
    const matches = items.filter((item: AdaptationChapter) => item.index === index);
    if (matches.length > 1 || (matches.length === 1 && matches[0].title !== title)) throw new Error('ADAPTATION_CATALOG_CHANGED');
    return matches.length === 0 ? undefined : matches[0];
  }
  /** 必须是书架中的非本地文本书籍；服务端仍重新验证真实归属与格式。 */
  eligible(book: Book | undefined, books: Book[]): boolean {
    return book !== undefined && book.origin !== 'local' && !book.id.startsWith('local:') &&
      ['txt', 'epub', 'mobi', 'azw3', 'unknown'].includes(book.format) &&
      books.some((item: Book) => item.id === book.id && item.origin === book.origin &&
        item.sourceId === book.sourceId && item.resourceUri === book.resourceUri);
  }
  /** 服务端 UUID 不能用本地 ID、标题或章节序号替代。 */
  id(value: string): string {
    if (typeof value !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)) throw new Error('ADAPTATION_ID_INVALID');
    return value;
  }
  /** 与 Reader 一致的 ASCII 幂等键。 */
  key(value: string): string {
    if (!/^[!-~]{1,128}$/.test(value)) throw new Error('ADAPTATION_KEY_INVALID'); return value;
  }
  /** 规范化必填意图，以 Unicode 码点计算长度并拒绝损坏的代理对。 */
  intent(value: string): string {
    if (typeof value !== 'string' || value.length > 8192 || /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]/.test(value)) throw new Error('ADAPTATION_INTENT_INVALID');
    const points = Array.from(value);
    if (points.length > 4096 || points.some((point: string) => point.length === 1 && /[\uD800-\uDFFF]/.test(point))) throw new Error('ADAPTATION_INTENT_INVALID');
    // 与 Java 空白规则保持一致，不把零宽字符或 BOM 作为可丢弃空白。
    const normalized = value.replace(/^[\u0009-\u000D\u001C-\u0020\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]+|[\u0009-\u000D\u001C-\u0020\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]+$/g, '');
    if (Array.from(normalized).length < 5 || Array.from(normalized).length > 2000) throw new Error('ADAPTATION_INTENT_INVALID');
    return normalized;
  }
  /** 构造精确字段集合，忽略调用方对象上的额外属性而不序列化它们。 */
  input(value: ChapterAdaptationInput): ChapterAdaptationInput {
    if (!Number.isSafeInteger(value.expectedBindingRevision) || value.expectedBindingRevision < 1 ||
      !Number.isSafeInteger(value.expectedCatalogRevision) || value.expectedCatalogRevision < 1) throw new Error('ADAPTATION_REVISION_INVALID');
    const result: ChapterAdaptationInput = { idempotencyKey: this.key(value.idempotencyKey), intent: this.intent(value.intent),
      expectedBindingRevision: value.expectedBindingRevision, expectedCatalogRevision: value.expectedCatalogRevision };
    if (value.expectedSourceSha256 !== undefined) {
      if (!/^[0-9a-f]{64}$/.test(value.expectedSourceSha256)) throw new Error('ADAPTATION_HASH_INVALID');
      result.expectedSourceSha256 = value.expectedSourceSha256;
    }
    if ((value.templateCode === undefined) !== (value.templateVersion === undefined)) throw new Error('ADAPTATION_TEMPLATE_INVALID');
    if (value.templateCode !== undefined) {
      if (!/^[a-z][a-z0-9-]{0,63}$/.test(value.templateCode) || !Number.isSafeInteger(value.templateVersion) ||
        value.templateVersion === undefined || value.templateVersion < 1 || value.templateVersion > 1000000) throw new Error('ADAPTATION_TEMPLATE_INVALID');
      result.templateCode = value.templateCode; result.templateVersion = value.templateVersion;
    }
    return result;
  }
  /** 终态之后不继续自动轮询。 */
  terminal(status: ChapterAdaptationStatus): boolean { return ['COMPLETED', 'CANCELLED', 'FAILED'].includes(status); }
  /** 只能从当前来源并具有正式采用正文的版本派生。 */
  canDerive(detail: ChapterAdaptationDetail | undefined): boolean {
    return detail !== undefined && detail.sourceRelation === 'CURRENT' && detail.version.status === 'COMPLETED' &&
      detail.result !== undefined && detail.result.viewStatus === 'SELECTED' &&
      detail.result.attemptId === detail.version.selectedAttemptId;
  }
  /** 页面必须取得同版本、未过期的来源收据；旧详情中的 CURRENT 字样不够。 */
  canDeriveVerified(detail: ChapterAdaptationDetail | undefined, proof: ChapterAdaptationSourceCheck | undefined, now: number): boolean {
    return this.canDerive(detail) && proof !== undefined && proof.adaptationId === detail?.version.adaptationId &&
      proof.status === 'CURRENT' && /^[a-f0-9]{64}$/.test(proof.sourceSha256) &&
      Number.isFinite(now) && Date.parse(proof.sourceCheckedAt) <= now && now < Date.parse(proof.validUntil) &&
      Date.parse(proof.validUntil) - Date.parse(proof.sourceCheckedAt) <= 60000;
  }
  /** 新后端不要求额外授权；旧后端仍需兼容其真实能力，不能伪造已同意。 */
  canCreate(features: AdaptationFeatures | undefined): boolean {
    return features !== undefined && features.readEnabled && features.createEnabled &&
      (features.consentStatus === 'NOT_REQUIRED' || (features.consentStatus === 'ACCEPTED' &&
      features.hasActiveConsent && features.consentRevision > 0 && features.disclosure !== undefined));
  }
  /** 两项确认必须本次明确勾选，不能从曾经同意或缓存文字推断。 */
  consentInput(features: AdaptationFeatures | undefined, accepted: boolean, rights: boolean): AdaptationConsentInput {
    if (features === undefined || !features.readEnabled || features.disclosure === undefined || !accepted || !rights ||
      !Number.isSafeInteger(features.consentRevision) || features.consentRevision < 0) throw new Error('ADAPTATION_CONSENT_REQUIRED');
    return { disclosureVersion: features.disclosure.version, disclosureSha256: features.disclosure.disclosureSha256,
      accepted: true, rightsAttested: true, expectedConsentRevision: features.consentRevision };
  }
  /** 只有明确的入口拒绝可解除不确定状态，网络/网关下游错误不能假定请求未生效。 */
  rejected(error: Error): boolean {
    return ['GATEWAY_001', 'GATEWAY_002', 'GATEWAY_003', 'READER_030', 'READER_031', 'READER_032',
      'READER_034', 'READER_035', 'READER_036', 'READER_037', 'READER_038', 'READER_045', 'READER_046', 'READER_052', 'READER_060', 'READER_063'].includes(this.error(error));
  }
  /** 只显示固定错误码，绝不把第三方或网络异常原文输出到界面/日志。 */
  error(error: Error): string {
    const code = error.message.match(/\b(?:READER_[0-9]{3}|GATEWAY_[0-9]{3}|ADAPTATION_[A-Z_]+)\b/);
    return code === null ? 'ADAPTATION_UNAVAILABLE' : code[0];
  }
}
