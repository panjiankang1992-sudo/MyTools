import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import { RssResponseNormalizer } from './RssResponseNormalizer';
import { RssGroup, RssSubscription, RssEntry, RssEntryPage, RssCounts, RssCapabilities, RssDiscovery,
  RssImportItem, RssStateChange, RssCreateSubscription, RssUpdateSubscription, RssGroupChange, RssFilter } from './RssModels';

interface RssUrlRequest { url: string; }
interface RssOpmlRequest { xml: string; }
interface RssMarkReadRequest { subscriptionId: string; groupId: string; q: string; }

/** RSS 请求只经过现有认证 Gateway，写入不在网络失败时自动切换端点重试。 */
export class RssApi {
  private readonly client: AuthorizedApiClient;
  private readonly normalizer: RssResponseNormalizer = new RssResponseNormalizer();
  private readonly prefix: string = '/api/app/v1/rss';
  /** 绑定本次页面账户和服务端点。 */
  constructor(base: string, auth: AuthSessionManager) { this.client = new AuthorizedApiClient(base, auth, false, 30000); }
  /** 查询后端能力。 */
  async capabilities(cancel?: DownloadCancellationToken): Promise<RssCapabilities> { return this.normalizer.capabilities((await this.client.get(this.prefix + '/capabilities', cancel)).data); }
  /** 查询订阅。 */
  async subscriptions(cancel?: DownloadCancellationToken): Promise<RssSubscription[]> { return this.normalizer.subscriptions((await this.client.get(this.prefix + '/subscriptions', cancel)).data); }
  /** 查询分组。 */
  async groups(cancel?: DownloadCancellationToken): Promise<RssGroup[]> { return this.normalizer.groups((await this.client.get(this.prefix + '/groups', cancel)).data); }
  /** 查询统计。 */
  async counts(cancel?: DownloadCancellationToken): Promise<RssCounts> { return this.normalizer.counts((await this.client.get(this.prefix + '/counts', cancel)).data); }
  /** 绑定筛选和游标请求文章。 */
  async entries(filter: RssFilter, cursor: string, cancel?: DownloadCancellationToken): Promise<RssEntryPage> {
    const query = `?view=${encodeURIComponent(filter.view)}&subscriptionId=${encodeURIComponent(filter.subscriptionId)}&groupId=${encodeURIComponent(filter.groupId)}&q=${encodeURIComponent(filter.q)}&cursor=${encodeURIComponent(cursor)}`;
    return this.normalizer.page((await this.client.get(this.prefix + '/entries' + query, cancel)).data);
  }
  /** 读取正文不自动修改已读。 */
  async entry(id: string, cancel?: DownloadCancellationToken): Promise<RssEntry> { return this.normalizer.entry((await this.client.get(this.prefix + '/entries/' + this.entryId(id), cancel)).data); }
  /** 设置已读与收藏，保留版本冲突。 */
  async state(id: string, body: RssStateChange, cancel?: DownloadCancellationToken): Promise<RssEntry> { return this.normalizer.entry((await this.client.putJson(this.prefix + '/entries/' + this.entryId(id) + '/state', body, cancel)).data); }
  /** 检测来源，用户选择候选后再次调用此方法。 */
  async discover(url: string, cancel?: DownloadCancellationToken): Promise<RssDiscovery> {
    const body: RssUrlRequest = { url: url };
    return this.normalizer.discovery((await this.client.postJson(this.prefix + '/discover', body, cancel)).data);
  }
  /** 新增来源，重复提交由服务端唯一约束归并。 */
  async add(body: RssCreateSubscription, cancel?: DownloadCancellationToken): Promise<RssSubscription> { return this.normalizer.subscription((await this.client.postJson(this.prefix + '/subscriptions', body, cancel)).data); }
  /** 更新来源设置。 */
  async update(id: string, body: RssUpdateSubscription, cancel?: DownloadCancellationToken): Promise<RssSubscription> { return this.normalizer.subscription((await this.client.putJson(this.prefix + '/subscriptions/' + this.uuid(id), body, cancel)).data); }
  /** 取消来源，收藏由服务端保留。 */
  async remove(id: string, cancel?: DownloadCancellationToken): Promise<void> { await this.client.delete(this.prefix + '/subscriptions/' + this.uuid(id), cancel); }
  /** 请求后台刷新。 */
  async refresh(id: string, cancel?: DownloadCancellationToken): Promise<RssSubscription> { return this.normalizer.subscription((await this.client.post(this.prefix + '/subscriptions/' + this.uuid(id) + '/refresh', cancel)).data); }
  /** 创建分组。 */
  async addGroup(name: string, cancel?: DownloadCancellationToken): Promise<void> {
    const body: RssGroupChange = { name: name, sortOrder: 0 };
    await this.client.postJson(this.prefix + '/groups', body, cancel);
  }
  /** 删除分组，来源转入未分组。 */
  async removeGroup(id: string, cancel?: DownloadCancellationToken): Promise<void> { await this.client.delete(this.prefix + '/groups/' + this.uuid(id), cancel); }
  /** 仅对当前范围批量已读，不自动重试。 */
  async markRead(filter: RssFilter, cancel?: DownloadCancellationToken): Promise<void> {
    const body: RssMarkReadRequest = { subscriptionId: filter.subscriptionId, groupId: filter.groupId, q: filter.q };
    await this.client.postJson(this.prefix + '/entries/mark-read', body, cancel);
  }
  /** 预览 OPML，暂不新增来源。 */
  async previewOpml(xml: string, cancel?: DownloadCancellationToken): Promise<RssImportItem[]> {
    const body: RssOpmlRequest = { xml: xml };
    return this.normalizer.imports((await this.client.postJson(this.prefix + '/opml/preview', body, cancel)).data);
  }
  /** 按预览导入，展示逐项结果。 */
  async importOpml(xml: string, cancel?: DownloadCancellationToken): Promise<RssImportItem[]> {
    const body: RssOpmlRequest = { xml: xml };
    return this.normalizer.imports((await this.client.postJson(this.prefix + '/opml/import', body, cancel)).data);
  }
  /** 导出本人订阅。 */
  async exportOpml(cancel?: DownloadCancellationToken): Promise<string> { return this.normalizer.opml((await this.client.get(this.prefix + '/opml/export', cancel)).data); }
  private uuid(id: string): string {
    if (!/^[0-9a-f-]{36}$/i.test(id)) throw new Error('RSS_001');
    return id;
  }
  private entryId(id: string): string {
    if (!/^[1-9][0-9]{0,18}$/.test(id)) throw new Error('RSS_001');
    return id;
  }
}
