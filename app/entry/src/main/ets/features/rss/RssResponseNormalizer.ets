import { RssGroup, RssSubscription, RssEntry, RssEntryPage, RssCounts, RssCapabilities, RssDiscovery,
  RssPreview, RssPreviewItem, RssCandidate, RssImportItem } from './RssModels';

/** 对网络响应执行边界校验，不让无效身份或大正文进入界面。 */
export class RssResponseNormalizer {
  /** 解析分组清单。 */
  groups(value: Object | undefined): RssGroup[] {
    return this.rows(value, 100).map((row: Record<string, Object>): RssGroup => {
      return { id: this.uuid(row['id']), name: this.text(row['name'], 80), sortOrder: this.integer(row['sortOrder']), revision: this.integer(row['revision']) };
    });
  }
  /** 解析订阅清单。 */
  subscriptions(value: Object | undefined): RssSubscription[] { return this.rows(value, 100).map((row: Record<string, Object>) => this.subscription(row)); }
  /** 解析单个订阅状态。 */
  subscription(value: Object | undefined): RssSubscription {
    const row = this.object(value);
    const status = this.text(row['status'], 16);
    if (!['ACTIVE', 'PAUSED', 'GONE'].includes(status)) throw new Error('RSS_INVALID_RESPONSE');
    const group = this.text(row['groupId'], 36);
    if (group) this.uuid(group);
    return { id: this.uuid(row['id']), groupId: group, feedUrl: this.text(row['feedUrl'], 2048),
      title: this.text(row['title'], 512), customTitle: this.text(row['customTitle'], 512), siteUrl: this.text(row['siteUrl'], 2048),
      description: this.text(row['description'], 2000), status: status, intervalMinutes: this.integer(row['intervalMinutes']),
      initialized: this.boolean(row['initialized']), lastAttemptAt: this.time(row['lastAttemptAt']),
      lastSuccessAt: this.time(row['lastSuccessAt']), nextFetchAt: this.time(row['nextFetchAt']),
      lastErrorCode: this.text(row['lastErrorCode'], 32), syncing: this.boolean(row['syncing']),
      revision: this.integer(row['revision']), unreadCount: this.integer(row['unreadCount']) };
  }
  /** 解析文章，数字 ID 拒绝隐式浮点转换。 */
  entry(value: Object | undefined): RssEntry {
    const row = this.object(value);
    const id = this.text(row['id'], 19);
    if (!/^[1-9][0-9]{0,18}$/.test(id)) throw new Error('RSS_INVALID_RESPONSE');
    return { id: id, subscriptionId: this.uuid(row['subscriptionId']), sourceTitle: this.text(row['sourceTitle'], 512),
      title: this.text(row['title'], 2000), author: this.text(row['author'], 512), url: this.text(row['url'], 2048),
      summary: this.text(row['summary'], 2400), contentHtml: this.text(row['contentHtml'], 400000),
      imageUrl: this.text(row['imageUrl'], 2048), publishedAt: this.time(row['publishedAt']),
      sourceUpdatedAt: this.time(row['sourceUpdatedAt']), discoveredAt: this.time(row['discoveredAt']),
      read: this.boolean(row['read']), starred: this.boolean(row['starred']), revision: this.integer(row['revision']) };
  }
  /** 解析游标文章页。 */
  page(value: Object | undefined): RssEntryPage {
    const row = this.object(value);
    return { items: this.rows(row['items'], 100).map((item: Record<string, Object>) => this.entry(item)), nextCursor: this.text(row['nextCursor'], 160) };
  }
  /** 解析首页计数。 */
  counts(value: Object | undefined): RssCounts {
    const row = this.object(value);
    return { subscriptions: this.integer(row['subscriptions']), unread: this.integer(row['unread']), starred: this.integer(row['starred']) };
  }
  /** 只展示服务实际开启的能力。 */
  capabilities(value: Object | undefined): RssCapabilities {
    const row = this.object(value);
    return { pollingEnabled: this.boolean(row['pollingEnabled']), opml: this.boolean(row['opml']), maxSubscriptions: this.integer(row['maxSubscriptions']) };
  }
  /** 解析 URL 发现及有限条目预览。 */
  discovery(value: Object | undefined): RssDiscovery {
    const row = this.object(value);
    const candidates = this.rows(row['candidates'], 10).map((item: Record<string, Object>): RssCandidate => {
      return { url: this.text(item['url'], 2048), title: this.text(item['title'], 512) };
    });
    let preview: RssPreview | undefined = undefined;
    if (row['preview'] !== undefined && row['preview'] !== null) {
      const data = this.object(row['preview']);
      const items = this.rows(data['entries'], 5).map((item: Record<string, Object>): RssPreviewItem => {
        return { title: this.text(item['title'], 2000), summary: this.text(item['summary'], 2400), publishedAt: this.text(item['publishedAt'], 64) };
      });
      preview = { url: this.text(data['url'], 2048), title: this.text(data['title'], 512), siteUrl: this.text(data['siteUrl'], 2048),
        description: this.text(data['description'], 2000), entries: items };
    }
    return { candidates: candidates, preview: preview };
  }
  /** 解析部分成功的 OPML 结果。 */
  imports(value: Object | undefined): RssImportItem[] {
    return this.rows(value, 200).map((row: Record<string, Object>): RssImportItem => {
      return { url: this.text(row['url'], 4096), title: this.text(row['title'], 512), group: this.text(row['group'], 160),
        status: this.text(row['status'], 32), subscriptionId: this.text(row['subscriptionId'], 36), errorCode: this.text(row['errorCode'], 32) };
    });
  }
  /** 解析有界 OPML 导出文本。 */
  opml(value: Object | undefined): string { return this.text(this.object(value)['xml'], 2097152); }
  private object(value: Object | undefined): Record<string, Object> {
    if (value === null || value === undefined || typeof value !== 'object' || Array.isArray(value)) throw new Error('RSS_INVALID_RESPONSE');
    return value as Record<string, Object>;
  }
  private rows(value: Object | undefined, limit: number): Record<string, Object>[] {
    if (!Array.isArray(value) || value.length > limit) throw new Error('RSS_INVALID_RESPONSE');
    return (value as Object[]).map((row: Object) => this.object(row));
  }
  private text(value: Object | undefined, limit: number): string {
    if (value === undefined || value === null) return '';
    if (typeof value !== 'string' || value.length > limit) throw new Error('RSS_INVALID_RESPONSE');
    return value;
  }
  private integer(value: Object | undefined): number {
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error('RSS_INVALID_RESPONSE');
    return value;
  }
  private time(value: Object | undefined): number { return value === null || value === undefined ? 0 : this.integer(value); }
  private boolean(value: Object | undefined): boolean {
    if (typeof value !== 'boolean') throw new Error('RSS_INVALID_RESPONSE');
    return value;
  }
  private uuid(value: Object | undefined): string {
    const text = this.text(value, 36);
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(text)) throw new Error('RSS_INVALID_RESPONSE');
    return text;
  }
}
