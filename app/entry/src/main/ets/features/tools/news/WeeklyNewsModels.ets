/** 手动新闻只使用服务端冻结的时间窗与身份。 */
export interface WeeklyProgress { stage: string; category: string; completedCategories: number; }
export interface WeeklyJob {
  id: string; status: string; attempt: number; revision: number; windowStart: string; windowEnd: string;
  createdAt: string; updatedAt: string; progress: WeeklyProgress; reportRevision: number; errorCode: string; reused: boolean;
}
export interface WeeklyImage { assetId: string; caption: string; sourceUrl: string; }
export interface WeeklyArticle {
  rank: number; eventId: string; category: string; title: string; summary: string; points: string[];
  limitation: string; rankingReason: string; publishedAt: string; sources: string[]; sourceLinks: string[];
  image?: WeeklyImage;
}
export interface WeeklySection { category: string; count: number; shortfall: number; items: WeeklyArticle[]; }
export interface WeeklyFailure { category: string; code: string; }
export interface WeeklyReport {
  id: string; revision: number; status: string; windowStart: string; windowEnd: string; generatedAt: string;
  articleCount: number; sections: WeeklySection[]; failedSections: WeeklyFailure[]; regionCode: string;
}
export interface WeeklyHistory { items: WeeklyJob[]; nextCursor: string; }
export interface WeeklyCapabilities { manualWeeklyEnabled: boolean; serverTime: string; }
export interface WeeklyAsset { assetId: string; mime: string; base64: string; }
export interface WeeklyCreate { idempotencyKey: string; }
export interface WeeklyRetry { idempotencyKey: string; expectedRevision: number; }

/** 分类顺序与服务端固定集合一致，不让响应插入未知入口。 */
export const WEEKLY_CATEGORIES: string[] = ['TECH', 'WORLD', 'DOMESTIC', 'LOCAL', 'TRENDING', 'MILITARY', 'FINANCE', 'SOCIETY', 'SPORTS', 'ENTERTAINMENT'];
