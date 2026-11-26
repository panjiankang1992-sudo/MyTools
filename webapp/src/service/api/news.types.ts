/** 新闻用户接口只承载内容和状态，不接收所有者或投递凭据。 */
export type NewsCategory = 'TECH' | 'WORLD' | 'DOMESTIC' | 'LOCAL';
export type NewsQuality = 'COMPLETE' | 'DEGRADED';
export interface NewsCapabilities {
  latestCutoff: string;
  rankingVersion: string;
  headlineLimit: number;
  detailLimit: number;
  detailMethod: string;
  regionStatus: string;
  deliveryEnabled: boolean;
}
export interface NewsRegion {
  status: string;
  regionCode: string | null;
  nodeId: string | null;
  resolvedAt: string | null;
  details: {
    cityName: string | null;
    precision: string;
    sourcePackLabel: string | null;
    sourcePackVersion: number | null;
    observedAt: string | null;
    expiresAt: string | null;
    cached: boolean;
  } | null;
}
export interface NewsCitation {
  articleId: string;
  url: string;
  sourceId: string;
  contentHash: string;
  fetchedAt: string;
}
export interface NewsItem {
  rank: number;
  eventId: string;
  title: string;
  url: string;
  publishedAt: string | null;
  sourceIds: string[];
  detailStatus: string;
  detail: { text: string; method: string; citations: NewsCitation[] } | null;
  technical: {
    discussionUrl: string | null;
    evidenceUrl?: string | null;
    github: {
      status: string;
      stars: number;
      netStars: number | null;
      sampleCount: number;
      coverageSeconds: number;
      observedAt: string;
    } | null;
  } | null;
}
export interface NewsSection {
  category: NewsCategory;
  windowStart: string;
  windowEnd: string;
  candidateCount: number;
  headlineCount: number;
  detailCount: number;
  quality: NewsQuality;
  gaps: string[];
  items: NewsItem[];
}
export interface NewsEditionHeader {
  id: string;
  cutoff: string;
  createdAt: string;
  quality: NewsQuality;
}
export interface NewsEdition extends NewsEditionHeader {
  zone: string;
  rankingVersion: string;
  region: NewsRegion;
  sections: Record<NewsCategory, NewsSection>;
}
export interface NewsSubscription {
  id: string;
  revision: number;
  enabled: boolean;
  zoneId: string;
  cutoffTime: string;
  nextDate: string | null;
  nextDueAt: string | null;
  updatedAt: string;
}
export interface NewsSubscriptionChange {
  expectedRevision: number;
  enabled: boolean;
  zoneId: string;
  cutoffTime: string;
}
export interface NewsSubscriptionState {
  subscription: NewsSubscription | null;
  schedulingEnabled: boolean;
  deliveryEnabled: boolean;
}
export interface NewsRun {
  id: string;
  businessDate: string;
  zoneId: string;
  cutoff: string;
  status: string;
  taskStatus: string | null;
  editionId: string | null;
  errorCode: string | null;
  skippedDays: number;
}
export interface NewsPreview {
  idempotencyKey: string;
  cutoff: string;
}
/** 可注入的用户接口便于验证网络竞争和未知结果恢复。 */
export interface NewsApi {
  capabilities: () => Promise<NewsCapabilities>;
  region: () => Promise<NewsRegion>;
  subscription: () => Promise<NewsSubscriptionState>;
  saveSubscription: (change: NewsSubscriptionChange) => Promise<NewsSubscription>;
  history: () => Promise<NewsEditionHeader[]>;
  edition: (id: string) => Promise<NewsEdition>;
  runs: () => Promise<NewsRun[]>;
  preview: (body: NewsPreview) => Promise<NewsEdition>;
  cancel: (id: string) => Promise<NewsRun>;
}
