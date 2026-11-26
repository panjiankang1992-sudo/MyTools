import { AuthSessionManager } from '../../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../../shared/network/AuthorizedApiClient';
import { DownloadCancellationToken } from '../../../shared/network/DownloadCancellationToken';
import { WeeklyNewsNormalizer } from './WeeklyNewsNormalizer';
import { WeeklyJob, WeeklyReport, WeeklySection, WeeklyArticle, WeeklyHistory, WeeklyCapabilities, WeeklyAsset,
  WeeklyCreate, WeeklyRetry, WEEKLY_CATEGORIES } from './WeeklyNewsModels';

/** 手动采集和私有阅读均走认证网关，写请求不自动换线路重放。 */
export class WeeklyNewsApi {
  private readonly client: AuthorizedApiClient;
  private readonly normalizer: WeeklyNewsNormalizer = new WeeklyNewsNormalizer();
  private readonly prefix: string = '/api/app/v1/news';
  /** 绑定当前账号及服务地址。 */
  constructor(base: string, auth: AuthSessionManager) { this.client = new AuthorizedApiClient(base, auth, false, 30000); }
  /** 获取服务能力，不触发更新。 */
  async capabilities(cancel?: DownloadCancellationToken): Promise<WeeklyCapabilities> { return this.normalizer.capabilities((await this.client.get(this.prefix + '/weekly-capabilities', cancel)).data); }
  /** 恢复当前后台任务。 */
  async active(cancel?: DownloadCancellationToken): Promise<WeeklyJob | undefined> { return this.normalizer.active((await this.client.get(this.prefix + '/weekly-jobs/active', cancel)).data); }
  /** 点击时仅提交幂等键，窗口由服务端确定。 */
  async create(key: string, cancel?: DownloadCancellationToken): Promise<WeeklyJob> {
    const body: WeeklyCreate = { idempotencyKey: key };
    return this.normalizer.job((await this.client.postJson(this.prefix + '/weekly-jobs', body, cancel)).data);
  }
  /** 失败分类重试保持原窗口及预期修订。 */
  async retry(id: string, key: string, revision: number, cancel?: DownloadCancellationToken): Promise<WeeklyJob> {
    const body: WeeklyRetry = { idempotencyKey: key, expectedRevision: revision };
    return this.normalizer.job((await this.client.postJson(this.prefix + '/weekly-jobs/' + this.uuid(id) + '/retry', body, cancel)).data);
  }
  /** 只读取本人任务状态。 */
  async job(id: string, cancel?: DownloadCancellationToken): Promise<WeeklyJob> { return this.normalizer.job((await this.client.get(this.prefix + '/weekly-jobs/' + this.uuid(id), cancel)).data); }
  /** 获取一页历史记录。 */
  async history(cursor: string, cancel?: DownloadCancellationToken): Promise<WeeklyHistory> {
    return this.normalizer.history((await this.client.get(this.prefix + '/weekly-reports?limit=20' + (cursor ? '&cursor=' + this.uuid(cursor) : ''), cancel)).data);
  }
  /** 读取报告元数据。 */
  async report(id: string, cancel?: DownloadCancellationToken): Promise<WeeklyReport> { return this.normalizer.report((await this.client.get(this.reportPath(id), cancel)).data); }
  /** 分类列表只加载二十条摘要。 */
  async section(id: string, category: string, cancel?: DownloadCancellationToken): Promise<WeeklySection> {
    if (!WEEKLY_CATEGORIES.includes(category)) throw new Error('NEWS_INVALID_RESPONSE');
    return this.normalizer.section((await this.client.get(this.reportPath(id) + '/categories/' + category, cancel)).data);
  }
  /** 单篇详情采用服务端固定事件标识。 */
  async article(id: string, event: string, cancel?: DownloadCancellationToken): Promise<WeeklyArticle> {
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(event)) throw new Error('NEWS_INVALID_RESPONSE');
    return this.normalizer.article((await this.client.get(this.reportPath(id) + '/articles/' + event, cancel)).data);
  }
  /** 图片字节仍需通过登录及报告归属校验。 */
  async asset(id: string, asset: string, cancel?: DownloadCancellationToken): Promise<WeeklyAsset> {
    if (!/^[a-f0-9]{64}$/.test(asset)) throw new Error('NEWS_INVALID_RESPONSE');
    return this.normalizer.asset((await this.client.get(this.reportPath(id) + '/assets/' + asset, cancel)).data);
  }
  private reportPath(id: string): string { return this.prefix + '/weekly-reports/' + this.uuid(id); }
  private uuid(id: string): string {
    if (!/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(id)) throw new Error('NEWS_INVALID_RESPONSE'); return id;
  }
}
