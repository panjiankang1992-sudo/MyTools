import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import { ChapterAdaptationPolicy } from './ChapterAdaptationPolicy';
import { ChapterAdaptationResponseNormalizer } from './ChapterAdaptationResponseNormalizer';
import { AdaptationCatalog, ChapterAdaptationCapability, ChapterAdaptationInput, ChapterAdaptationKind,
  ChapterAdaptationAccepted, ChapterAdaptationProgress, ChapterAdaptationHistory, ChapterAdaptationDetail,
  ChapterAdaptationOutput, ChapterAdaptationSourceCheck, AdaptationFeatures, AdaptationComparison, AdaptationStyleTemplate } from './ChapterAdaptationModels';

import { AdaptationConsentPort } from './AdaptationConsentController';

interface EnsureAdaptationRequest { idempotencyKey: string; }

/** 改编只访问认证的固定业务路由；不会调用 Provider 或携带正文。 */
export class ChapterAdaptationApi implements AdaptationConsentPort {
  private readonly client: AuthorizedApiClient;
  private readonly policy: ChapterAdaptationPolicy = new ChapterAdaptationPolicy();
  private readonly normalize: ChapterAdaptationResponseNormalizer = new ChapterAdaptationResponseNormalizer();
  constructor(baseUrl: string, auth: AuthSessionManager) { this.client = new AuthorizedApiClient(baseUrl, auth, false); }
  /** 当前账户的告知和授权，读取不会自动同意。 */
  async features(cancellation: DownloadCancellationToken): Promise<AdaptationFeatures> {
    const reply = await this.client.get('/api/app/v1/features/reader-adaptation', cancellation);
    return this.normalize.features(reply.data);
  }
  /** 仅发送用户本次明确确认的版本、摘要和修订，不携带告知文案。 */
  async acceptConsent(features: AdaptationFeatures, accepted: boolean, rights: boolean,
    cancellation: DownloadCancellationToken): Promise<AdaptationFeatures> {
    const reply = await this.client.postJson('/api/app/v1/features/reader-adaptation/consent', this.policy.consentInput(features, accepted, rights), cancellation);
    return this.normalize.features(reply.data);
  }
  /** 撤销失败或响应不确定时先查询状态，不自动用新修订重发。 */
  async revokeConsent(revision: number, cancellation: DownloadCancellationToken): Promise<AdaptationFeatures> {
    if (!Number.isSafeInteger(revision) || revision < 0) throw new Error('ADAPTATION_REVISION_INVALID');
    const reply = await this.client.delete(`/api/app/v1/features/reader-adaptation/consent?expectedConsentRevision=${revision}`, cancellation);
    return this.normalize.features(reply.data);
  }
  /** 读取后端风格目录，APP 不提供模板发布能力。 */
  async styles(cancellation: DownloadCancellationToken): Promise<AdaptationStyleTemplate[]> {
    const reply = await this.client.get('/api/app/v1/reader/adaptation-style-templates', cancellation);
    return this.normalize.styles(reply.data);
  }
  /** 只从当前账户已同步书架解析稳定书籍 ID。 */
  async shelf(bookId: string, cancellation: DownloadCancellationToken): Promise<string> {
    const reply = await this.client.get('/api/app/v1/reader/shelves', cancellation);
    return this.normalize.shelf(reply.data, bookId);
  }
  /** 查询准备状态。 */
  async capability(shelf: string, cancellation: DownloadCancellationToken): Promise<ChapterAdaptationCapability> {
    const reply = await this.client.get(`${this.shelfPath(shelf)}/chapter-adaptation-capability`, cancellation);
    return this.normalize.capability(reply.data, shelf);
  }
  /** 准备沿用同一个幂等键重试，不发送任意书源。 */
  async ensure(shelf: string, key: string, cancellation: DownloadCancellationToken): Promise<ChapterAdaptationCapability> {
    const body: EnsureAdaptationRequest = { idempotencyKey: this.policy.key(key) };
    const reply = await this.client.postJson(`${this.shelfPath(shelf)}/chapter-adaptation-capability/ensure`, body, cancellation);
    return this.normalize.capability(reply.data, shelf);
  }
  /** 读取权威目录，不把本地标题或序号当作章节 ID。 */
  async catalog(shelf: string, cursor: string, cancellation: DownloadCancellationToken): Promise<AdaptationCatalog> {
    const reply = await this.client.get(`${this.shelfPath(shelf)}/chapters?limit=200${this.cursor(cursor)}`, cancellation);
    return this.normalize.catalog(reply.data, shelf);
  }
  /** 三种操作每次产生新业务版本；不确定请求必须复用调用方保留的输入与幂等键。 */
  async create(shelf: string, chapter: string, kind: ChapterAdaptationKind, trigger: string,
    input: ChapterAdaptationInput, cancellation: DownloadCancellationToken): Promise<ChapterAdaptationAccepted> {
    const target = kind === 'INITIAL' ? `${this.shelfPath(shelf)}/chapters/${this.policy.id(chapter)}/adaptations` :
      `${this.adaptationPath(trigger)}/${kind === 'OPTIMIZE' ? 'optimize' : 'regenerate'}`;
    const reply = await this.client.postJson(target, this.policy.input(input), cancellation);
    return this.normalize.accepted(reply.data, chapter, kind);
  }
  /** 查询轻量状态。 */
  async progress(id: string, cancellation: DownloadCancellationToken): Promise<ChapterAdaptationProgress> {
    const reply = await this.client.get(`${this.adaptationPath(id)}/status`, cancellation);
    return this.normalize.progress(reply.data, id);
  }
  /** 读取本章历史，不自动下载所有历史正文。 */
  async history(shelf: string, chapter: string, cursor: string, cancellation: DownloadCancellationToken): Promise<ChapterAdaptationHistory> {
    const reply = await this.client.get(`${this.shelfPath(shelf)}/chapters/${this.policy.id(chapter)}/adaptations?limit=20${this.cursor(cursor)}`, cancellation);
    return this.normalize.history(reply.data, shelf, chapter);
  }
  /** 详情再次检查书籍和章节，防止异步切换污染。 */
  async detail(id: string, shelf: string, chapter: string, cancellation: DownloadCancellationToken): Promise<ChapterAdaptationDetail> {
    const reply = await this.client.get(this.adaptationPath(id), cancellation);
    return this.normalize.detail(reply.data, id, shelf, chapter);
  }
  /** 读取冻结原章与当前采用结果的差异，不重新下载书源正文。 */
  async comparison(id: string, output: ChapterAdaptationOutput, cancellation: DownloadCancellationToken): Promise<AdaptationComparison> {
    const reply = await this.client.get(`${this.adaptationPath(id)}/comparison`, cancellation);
    return this.normalize.comparison(reply.data, id, output);
  }
  /** 显式请求或轮询来源核验，不发送正文、摘要或客户端核验结论。 */
  async sourceCheck(id: string, request: boolean, cancellation: DownloadCancellationToken): Promise<ChapterAdaptationSourceCheck> {
    const path = `${this.adaptationPath(id)}/source-check`;
    const reply = request ? await this.client.post(path, cancellation) : await this.client.get(path, cancellation);
    return this.normalize.sourceCheck(reply.data, id);
  }
  /** 查看允许展示的单个未采用候选。 */
  async attempt(id: string, attempt: string, cancellation: DownloadCancellationToken): Promise<ChapterAdaptationOutput> {
    const reply = await this.client.get(`${this.adaptationPath(id)}/attempts/${this.policy.id(attempt)}`, cancellation);
    return this.normalize.output(reply.data, attempt);
  }
  /** 取消只提交无正文命令，不创建新版本。 */
  async cancel(id: string, cancellation: DownloadCancellationToken): Promise<ChapterAdaptationProgress> {
    const reply = await this.client.post(`${this.adaptationPath(id)}/cancel`, cancellation);
    return this.normalize.progress(reply.data, id);
  }
  private shelfPath(id: string): string { return `/api/app/v1/reader/shelves/${this.policy.id(id)}`; }
  private adaptationPath(id: string): string { return `/api/app/v1/reader/chapter-adaptations/${this.policy.id(id)}`; }
  private cursor(cursor: string): string { if (cursor.length > 4096 || (cursor !== '' && !/^[A-Za-z0-9_.-]+$/.test(cursor))) throw new Error('ADAPTATION_CURSOR_INVALID'); return cursor === '' ? '' : `&cursor=${encodeURIComponent(cursor)}`; }
}
