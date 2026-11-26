import { WeeklyNewsPage } from '../features/tools/news/WeeklyNewsPage';
import { WeeklyNewsApi } from '../features/tools/news/WeeklyNewsApi';
import { WeeklyJob, WeeklyReport, WeeklySection, WeeklyArticle, WeeklyHistory, WeeklyCapabilities, WEEKLY_CATEGORIES } from '../features/tools/news/WeeklyNewsModels';
import { AuthSessionManager } from '../features/auth/AuthSessionManager';

/** 模拟器验收使用明确标注的离线数据，禁止连接生产接口。 */
class WeeklyAcceptanceApi extends WeeklyNewsApi {
  private currentJob?: WeeklyJob = undefined;
  private polls: number = 0;
  /** 本地假数据适配器仍复用生产页面的导航和任务恢复。 */
  constructor() { super('https://example.org', new AuthSessionManager()); }
  /** 声明可点击能力，绝不发起网络请求。 */
  async capabilities(): Promise<WeeklyCapabilities> { return { manualWeeklyEnabled: true, serverTime: '2026-11-22T08:00:00Z' }; }
  /** 首次为空，点击后保留模拟任务。 */
  async active(): Promise<WeeklyJob | undefined> { return this.currentJob?.status === 'RUNNING' ? this.currentJob : undefined; }
  /** 模拟快速受理，再通过真实页面轮询进入完成态。 */
  async create(): Promise<WeeklyJob> {
    this.currentJob = { id: '11111111-1111-4111-8111-111111111111', status: 'RUNNING', attempt: 1, revision: 1,
      windowStart: '2026-11-15T08:00:00Z', windowEnd: '2026-11-22T08:00:00Z', createdAt: '2026-11-22T08:00:00Z',
      updatedAt: '2026-11-22T08:00:00Z', progress: { stage: 'COLLECTING', category: '', completedCategories: 0 }, reportRevision: 0, errorCode: '', reused: false };
    return this.currentJob;
  }
  /** 两次轮询覆盖收集、分类分析、部分完成三个状态。 */
  async job(): Promise<WeeklyJob> {
    if (!this.currentJob) throw new Error('FIXTURE_JOB_MISSING');
    this.polls++;
    if (this.polls > 1) { this.currentJob.status = 'PARTIAL'; this.currentJob.reportRevision = 1; this.currentJob.revision = 2; }
    this.currentJob.progress = { stage: 'ANALYZING', category: 'TECH', completedCategories: this.polls > 1 ? 9 : 3 };
    return this.currentJob;
  }
  /** 完成任务才能出现在历史列表。 */
  async history(): Promise<WeeklyHistory> { return { items: this.currentJob?.reportRevision ? [this.currentJob] : [], nextCursor: '' }; }
  /** 失败分类显示真实短缺，不把未完成冒充为零条。 */
  async report(): Promise<WeeklyReport> {
    return { id: this.currentJob!.id, revision: 1, status: 'PARTIAL', windowStart: this.currentJob!.windowStart,
      windowEnd: this.currentJob!.windowEnd, generatedAt: this.currentJob!.windowEnd, articleCount: 24,
      sections: WEEKLY_CATEGORIES.filter((category: string) => category !== 'MILITARY').map((category: string): WeeklySection => {
        return { category: category, count: this.fixtureCount(category), shortfall: 20 - this.fixtureCount(category), items: [] };
      }), failedSections: [{ category: 'MILITARY', code: 'FIXTURE_TIMEOUT' }], regionCode: 'CN-JS' };
  }
  /** 长列表和第二个分类用于验证详情返回时的阅读位置与分类保留。 */
  async section(id: string, category: string): Promise<WeeklySection> {
    const items: WeeklyArticle[] = [];
    for (let rank = 1; rank <= this.fixtureCount(category); rank++) items.push(this.fixture(rank, category));
    return { category: category, count: items.length, shortfall: 20 - items.length, items: items };
  }
  /** 详情展示真实组件，内容明确声明为测试。 */
  async article(id: string, event: string): Promise<WeeklyArticle> { return this.fixture(Number(event.split('-')[1]), event.split('-')[0]); }
  /** 重试不会增加历史条数。 */
  async retry(): Promise<WeeklyJob> { this.currentJob!.status = 'SUCCEEDED'; this.currentJob!.revision++; return this.currentJob!; }
  private fixtureCount(category: string): number { return category === 'TECH' ? 20 : category === 'WORLD' ? 4 : 0; }
  private fixture(rank: number, category: string): WeeklyArticle {
    return { rank: rank, eventId: `${category}-${rank}`, category: category, title: `\u3010\u9a8c\u6536\u6837\u4f8b\u3011\u5f00\u6e90\u5de5\u5177\u7684\u672c\u5468\u8fdb\u5c55 ${rank}`,
      summary: '\u8fd9\u662f\u6a21\u62df\u5668\u9875\u9762\u9a8c\u6536\u6570\u636e\uff0c\u4e0d\u662f\u771f\u5b9e\u65b0\u95fb\u3002\u7528\u4e8e\u68c0\u67e5\u5206\u7c7b\u5207\u6362\u3001\u540e\u53f0\u8fdb\u5ea6\u3001\u6b63\u6587\u9605\u8bfb\u548c\u5386\u53f2\u5bfc\u822a\u3002',
      points: ['\u65b0\u95fb\u4f7f\u7528\u56fa\u5b9a\u7684\u6700\u8fd1\u4e03\u5929\u8303\u56f4\u3002', '\u7f3a\u5c11\u6765\u6e90\u539f\u56fe\u65f6\u4fdd\u7559\u7eaf\u6587\u672c\u3002'], limitation: '',
      rankingReason: '\u4ec5\u7528\u4e8e\u9875\u9762\u9a8c\u6536\u3002', publishedAt: '2026-11-20T09:30:00Z',
      sources: ['Offline fixture'], sourceLinks: ['https://example.org/fixture'] };
  }
}

/** 临时验收入口不进入正式页面清单。 */
@Entry
@Component
struct WeeklyNewsAcceptance {
  private api: WeeklyAcceptanceApi = new WeeklyAcceptanceApi();
  private back: () => void = () => {};
  /** 使用生产组件自己的返回逻辑。 */
  onBackPress(): boolean { this.back(); return true; }
  build() {
    Column() {
      WeeklyNewsPage({ baseUrl: 'https://example.org', accountKey: 'weekly-ui-fixture', apiFactory: () => this.api,
        onBackReady: (handler: () => void): void => { this.back = handler; } })
    }.width('100%').height('100%')
  }
}
