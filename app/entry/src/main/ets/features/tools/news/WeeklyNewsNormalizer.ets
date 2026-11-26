import { WeeklyJob, WeeklyReport, WeeklySection, WeeklyArticle, WeeklyHistory, WeeklyCapabilities, WeeklyAsset,
  WeeklyImage, WeeklyFailure, WEEKLY_CATEGORIES } from './WeeklyNewsModels';

/** 所有网络字段先校验边界，坏响应不进入详情或图片组件。 */
export class WeeklyNewsNormalizer {
  /** 校验能力声明及用于预览的服务器时刻。 */
  capabilities(value: Object | undefined): WeeklyCapabilities {
    const row = this.object(value);
    return { manualWeeklyEnabled: row['manualWeeklyEnabled'] === true, serverTime: this.time(row['serverTime']) };
  }
  /** 首次使用允许没有活动任务。 */
  active(value: Object | undefined): WeeklyJob | undefined {
    const job = this.object(value)['job'];
    return job === null || job === undefined ? undefined : this.job(job);
  }
  /** 校验持久任务，进度只显示已完成数。 */
  job(value: Object | undefined): WeeklyJob {
    const row = this.object(value), progress = this.object(row['progress']);
    const status = this.text(row['status'], 16);
    if (!['QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED'].includes(status)) this.invalid();
    const stage = this.text(progress['stage'], 16);
    if (!['QUEUED', 'COLLECTING', 'ENRICHING', 'MERGING', 'ANALYZING', 'SAVING'].includes(stage)) this.invalid();
    return { id: this.uuid(row['id']), status: status, attempt: this.integer(row['attempt'], 10), revision: this.integer(row['revision'], 1000),
      windowStart: this.time(row['windowStart']), windowEnd: this.time(row['windowEnd']), createdAt: this.time(row['createdAt']),
      updatedAt: this.time(row['updatedAt']), reportRevision: this.integer(row['reportRevision'], 10),
      errorCode: this.text(row['errorCode'], 80), reused: row['reused'] === true,
      progress: { stage: stage, category: this.text(progress['category'], 20), completedCategories: this.integer(progress['completedCategories'], 10) } };
  }
  /** 历史页采用不透明 UUID 游标。 */
  history(value: Object | undefined): WeeklyHistory {
    const row = this.object(value), cursor = this.text(row['nextCursor'], 36);
    if (cursor) this.uuid(cursor);
    return { items: this.rows(row['items'], 100).map((item: Object) => this.job(item)), nextCursor: cursor };
  }
  /** 元数据不携带全部正文。 */
  report(value: Object | undefined): WeeklyReport {
    const row = this.object(value);
    const region = row['region'] ? this.object(row['region']) : undefined;
    return { id: this.uuid(row['id']), revision: this.integer(row['revision'], 10), status: this.text(row['status'], 16),
      windowStart: this.time(row['windowStart']), windowEnd: this.time(row['windowEnd']), generatedAt: this.time(row['generatedAt']),
      articleCount: this.integer(row['articleCount'], 200), sections: this.rows(row['sections'], 10).map((item: Object) => this.section(item)),
      failedSections: this.rows(row['failedSections'], 10).map((item: Object): WeeklyFailure => {
        const failure = this.object(item); return { category: this.category(failure['category']), code: this.text(failure['code'], 80) };
      }), regionCode: region ? this.text(region['regionCode'], 80) : '' };
  }
  /** 每类最多二十条，不把超量结果静默截断。 */
  section(value: Object | undefined): WeeklySection {
    const row = this.object(value);
    return { category: this.category(row['category']), count: this.integer(row['count'], 20), shortfall: this.integer(row['shortfall'], 20),
      items: row['items'] === undefined ? [] : this.rows(row['items'], 20).map((item: Object) => this.article(item)) };
  }
  /** 列表和详情共用有界文章结构，不渲染服务端 HTML。 */
  article(value: Object | undefined): WeeklyArticle {
    const row = this.object(value);
    const event = this.text(row['eventId'], 128);
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(event)) this.invalid();
    let picture: WeeklyImage | undefined = undefined;
    if (row['image']) {
      const image = this.object(row['image']), id = this.text(image['assetId'], 64);
      if (!/^[a-f0-9]{64}$/.test(id)) this.invalid();
      picture = { assetId: id, caption: this.text(image['caption'], 800), sourceUrl: this.url(image['sourceUrl']) };
    }
    return { rank: this.integer(row['rank'], 20), eventId: event, category: this.category(row['category']), title: this.text(row['title'], 400),
      summary: this.text(row['summary'], 1800), points: this.strings(row['points'], 5, 500), limitation: this.text(row['limitation'], 600),
      rankingReason: this.text(row['rankingReason'], 600), publishedAt: this.time(row['publishedAt'] || row['activityAt']),
      sources: this.strings(row['sources'], 32, 160), sourceLinks: this.strings(row['sourceLinks'], 32, 2048).map((link: string) => this.url(link)), image: picture };
  }
  /** 原图只能来自本人报告，不允许响应改写为其他网络图片。 */
  asset(value: Object | undefined): WeeklyAsset {
    const row = this.object(value), id = this.text(row['assetId'], 64), mime = this.text(row['mime'], 20);
    const base64 = this.text(row['base64'], 1398104);
    if (!/^[a-f0-9]{64}$/.test(id) || !['image/jpeg', 'image/png'].includes(mime) || !/^[A-Za-z0-9+/]+=*$/.test(base64)) this.invalid();
    return { assetId: id, mime: mime, base64: base64 };
  }
  private object(value: Object | undefined): Record<string, Object> {
    if (!value || typeof value !== 'object' || Array.isArray(value)) this.invalid();
    return value as Record<string, Object>;
  }
  private rows(value: Object | undefined, max: number): Object[] {
    if (!Array.isArray(value) || (value as Object[]).length > max) this.invalid();
    return value as Object[];
  }
  private text(value: Object | undefined, max: number): string {
    if (value === undefined || value === null) return '';
    if (typeof value !== 'string' || (value as string).length > max) this.invalid();
    return value as string;
  }
  private strings(value: Object | undefined, max: number, length: number): string[] {
    return value === undefined ? [] : this.rows(value, max).map((item: Object) => this.text(item, length));
  }
  private integer(value: Object | undefined, max: number): number {
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || Number(value) < 0 || Number(value) > max) this.invalid();
    return value as number;
  }
  private time(value: Object | undefined): string {
    const result = this.text(value, 40); if (!result || !Number.isFinite(Date.parse(result))) this.invalid(); return result;
  }
  private uuid(value: Object | undefined): string {
    const result = this.text(value, 36); if (!/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(result)) this.invalid(); return result;
  }
  private category(value: Object | undefined): string {
    const result = this.text(value, 20); if (!WEEKLY_CATEGORIES.includes(result)) this.invalid(); return result;
  }
  private url(value: Object | undefined): string {
    const result = this.text(value, 2048); if (!/^https:\/\/[^\s/@?#]+(?:[/?#][^\s]*)?$/i.test(result)) this.invalid(); return result;
  }
  private invalid(): never { throw new Error('NEWS_INVALID_RESPONSE'); }
}
