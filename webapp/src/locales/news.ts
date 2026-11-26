import { computed } from 'vue';
import { getLocale } from './index';

/** 新闻页面文案随现有语言设置切换，业务值保持英文。 */
const enNews = {
  trendReady: 'Seven-day samples available',
  title: 'Daily brief',
  eyebrow: 'YOUR DAILY READING',
  subtitle: 'Technology, world, domestic and local news in one reading list.',
  refresh: 'Refresh lists',
  retry: 'Retry',
  preview: 'Generate preview',
  previewRetry: 'Retry same preview',
  previewHint: 'A preview saves a readable edition. It does not send a message.',
  cutoff: 'Content cutoff',
  subscription: 'My subscription',
  enabled: 'Generate daily',
  zone: 'Time zone',
  time: 'Daily cutoff time',
  save: 'Save subscription',
  saved: 'Subscription saved.',
  reload: 'Reload saved settings',
  scheduleHint: 'Changes apply to the next cutoff. Editing or disabling cancels unfinished runs.',
  scheduleOff: 'Automatic generation is not enabled on the service. Settings can be saved for later.',
  deliveryOff: 'Sending is disabled on the server. Delivery settings and history are available below.',
  next: 'Next cutoff',
  paused: 'Paused',
  currentRegion: 'Current local coverage',
  regionHint:
    'Based on the execution server\u2019s public egress location. Each edition keeps its own region snapshot.',
  regionUnknown: 'Region not yet available',
  node: 'Execution node',
  observed: 'Observed',
  history: 'Recent editions',
  historyLimit: 'Up to 100 saved editions',
  emptyHistory: 'No editions yet. Generate a preview to get started.',
  read: 'Read edition',
  readerEmpty: 'Choose an edition or generate a preview.',
  loading: 'Loading\u2026',
  edition: 'Daily edition',
  frozenRegion: 'Local coverage in this edition',
  complete: 'Coverage target met',
  degraded: 'Coverage incomplete',
  qualityHint: 'Counts describe available coverage, not independent verification of every report.',
  headlines: 'Headlines',
  details: 'Details in top 10',
  candidates: 'Candidates',
  window: 'Coverage window',
  emptySection: 'No eligible stories in this category.',
  excerpt: 'Source excerpt',
  evidence: 'Evidence and sources',
  sourceIds: 'Sources',
  original: 'Read original',
  discussion: 'Discussion',
  published: 'Published',
  trend: 'GitHub trend',
  netStars: 'Net stars over approximately 7 days',
  samples: 'Samples',
  stars: 'Total stars',
  trendHint: 'Total stars are not this week\u2019s growth.',
  runs: 'Generation activity',
  runHint: 'Ready means readable; it does not mean sent or complete.',
  emptyRuns: 'No scheduled runs yet.',
  cancel: 'Cancel generation',
  confirmCancel: 'Cancel this unfinished run?',
  confirm: 'Confirm cancellation',
  keep: 'Keep run',
  taskStatus: 'Scheduler status',
  skipped: 'Earlier dates skipped',
  errorCode: 'Error code',
  partialError: 'Some data could not be loaded. Retry the affected section.',
  unavailable: 'Request failed or the news service is unavailable.',
  conflict: 'Settings changed elsewhere. Reload them before saving again. Your draft has been kept.',
  invalid: 'Enter a valid time zone and a time in HH:mm format.',
  expired: 'This edition has passed its retention period. Its original request remains recorded and will not generate another edition.',
  notFound: 'This edition is unavailable or does not belong to your account.',
  previewUnknown: 'The preview result is unconfirmed. Retry reuses the same request and cutoff.',
  topNote: 'Up to 20 titles per category; details for the top 10 when source evidence is sufficient.'
};

const enNewsStatus: Record<string, string> = {
  COMPLETE: 'Coverage target met',
  DEGRADED: 'Coverage incomplete',
  QUEUED: 'Queued',
  DISPATCHED: 'Scheduled',
  READY: 'Ready to read',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
  VERIFIED: 'City coverage verified',
  PROVINCE_FALLBACK: 'Province coverage',
  CACHED: 'Cached region',
  UNRESOLVED: 'Not resolved',
  STALE: 'Observation expired',
  EGRESS_UNAVAILABLE: 'Egress unavailable',
  LOOKUP_FAILED: 'Location lookup failed',
  COUNTRY_ONLY: 'Country precision only',
  SOURCE_PACK_MISSING: 'No local source pack',
  NO_SOURCE_PACK: 'No local source pack',
  EVIDENCE_EXPIRED: 'Source evidence retention expired',
  EVIDENCE_TOO_SHORT: 'Not enough source text for details',
  NOT_AUTHORIZED: 'Source permits headlines only',
  HEADLINE_ONLY: 'Headline only',
  WARMING_UP: 'Accumulating seven days of samples',
  MISSING_BASELINE: 'Missing comparison sample',
  GAPPED_HISTORY: 'Gaps in sampling',
  HEADLINE_SHORTFALL: 'Fewer than 20 eligible titles',
  DETAIL_SHORTFALL: 'Details unavailable for some top-10 items',
  REGION_UNRESOLVED: 'Local region or sources unavailable'
};

const zhNews = {
  trendReady: '\u4e03\u65e5\u91c7\u6837\u53ef\u7528',
  title: '\u6bcf\u65e5\u65b0\u95fb',
  eyebrow: '\u6bcf\u65e5\u901f\u62a5',
  subtitle:
    '\u6280\u672f\u3001\u56fd\u9645\u3001\u56fd\u5185\u4e0e\u672c\u5730\uff0c\u6309\u5206\u7c7b\u67e5\u770b\u6bcf\u65e5\u70ed\u70b9\u4e0e\u6765\u6e90\u3002',
  refresh: '\u5237\u65b0\u5217\u8868',
  retry: '\u91cd\u8bd5',
  preview: '\u751f\u6210\u9884\u89c8',
  previewRetry: '\u91cd\u8bd5\u672c\u6b21\u9884\u89c8',
  previewHint:
    '\u9884\u89c8\u4f1a\u4fdd\u5b58\u4e00\u4efd\u53ef\u9605\u8bfb\u65e5\u62a5\uff0c\u4e0d\u4f1a\u53d1\u9001\u6d88\u606f\u3002',
  cutoff: '\u5185\u5bb9\u622a\u6b62',
  subscription: '\u6211\u7684\u8ba2\u9605',
  enabled: '\u6bcf\u65e5\u751f\u6210',
  zone: '\u65f6\u533a',
  time: '\u6bcf\u65e5\u5185\u5bb9\u622a\u6b62\u65f6\u95f4',
  save: '\u4fdd\u5b58\u8ba2\u9605',
  saved: '\u8ba2\u9605\u5df2\u4fdd\u5b58\u3002',
  reload: '\u91cd\u65b0\u8f7d\u5165\u5df2\u4fdd\u5b58\u8bbe\u7f6e',
  scheduleHint:
    '\u4fee\u6539\u4ece\u4e4b\u540e\u7684\u622a\u6b62\u65f6\u523b\u751f\u6548\uff1b\u4fee\u6539\u6216\u505c\u7528\u4f1a\u53d6\u6d88\u5c1a\u672a\u5b8c\u6210\u7684\u751f\u6210\u3002',
  scheduleOff:
    '\u670d\u52a1\u5c1a\u672a\u5f00\u542f\u81ea\u52a8\u751f\u6210\uff1b\u53ef\u4ee5\u5148\u4fdd\u5b58\u8ba2\u9605\u8bbe\u7f6e\u3002',
  deliveryOff:
    "\u670d\u52a1\u7aef\u5c1a\u672a\u542f\u7528\u53d1\u9001\u3002\u63a5\u6536\u914d\u7f6e\u548c\u6295\u9012\u8bb0\u5f55\u89c1\u4e0b\u65b9\u6d88\u606f\u6295\u9012\u9762\u677f\u3002",
  next: '\u4e0b\u6b21\u5185\u5bb9\u622a\u6b62',
  paused: '\u5df2\u505c\u7528',
  currentRegion: '\u5f53\u524d\u672c\u5730\u8303\u56f4',
  regionHint:
    '\u6309\u6267\u884c\u4efb\u52a1\u670d\u52a1\u5668\u7684\u516c\u7f51\u51fa\u53e3\u6240\u5728\u5730\u786e\u5b9a\uff1b\u6bcf\u4efd\u65e5\u62a5\u4fdd\u7559\u751f\u6210\u65f6\u7684\u5730\u533a\u5feb\u7167\u3002',
  regionUnknown: '\u5730\u533a\u5c1a\u672a\u5c31\u7eea',
  node: '\u6267\u884c\u8282\u70b9',
  observed: '\u89c2\u6d4b\u65f6\u95f4',
  history: '\u5386\u53f2\u65e5\u62a5',
  historyLimit: '\u6700\u8fd1 100 \u4efd\u5df2\u4fdd\u5b58\u65e5\u62a5',
  emptyHistory: '\u8fd8\u6ca1\u6709\u65e5\u62a5\uff0c\u53ef\u4ee5\u5148\u751f\u6210\u9884\u89c8\u3002',
  read: '\u9605\u8bfb\u65e5\u62a5',
  readerEmpty:
    '\u9009\u62e9\u4e00\u4efd\u5386\u53f2\u65e5\u62a5\uff0c\u6216\u751f\u6210\u9884\u89c8\u5f00\u59cb\u9605\u8bfb\u3002',
  loading: '\u6b63\u5728\u52a0\u8f7d\u2026',
  edition: '\u65e5\u62a5\u9605\u8bfb',
  frozenRegion: '\u672c\u671f\u672c\u5730\u8303\u56f4',
  complete: '\u6570\u91cf\u8fbe\u6807',
  degraded: '\u5185\u5bb9\u4e0d\u8db3',
  qualityHint:
    '\u6570\u91cf\u53cd\u6620\u5f53\u524d\u6765\u6e90\u8986\u76d6\u60c5\u51b5\uff0c\u4e0d\u4ee3\u8868\u6bcf\u6761\u62a5\u9053\u5747\u5df2\u5b8c\u6210\u4e8b\u5b9e\u6838\u9a8c\u3002',
  headlines: '\u6807\u9898',
  details: '\u524d\u5341\u8be6\u60c5',
  candidates: '\u5019\u9009',
  window: '\u5185\u5bb9\u65f6\u95f4\u7a97',
  emptySection:
    '\u8be5\u5206\u7c7b\u6682\u65e0\u7b26\u5408\u65f6\u95f4\u7a97\u4e0e\u6765\u6e90\u8981\u6c42\u7684\u65b0\u95fb\u3002',
  excerpt: '\u6765\u6e90\u539f\u6587\u8282\u9009',
  evidence: '\u5f15\u7528\u4e0e\u6765\u6e90',
  sourceIds: '\u6765\u6e90\u6807\u8bc6',
  original: '\u67e5\u770b\u539f\u6587',
  discussion: '\u76f8\u5173\u8ba8\u8bba',
  published: '\u539f\u59cb\u53d1\u5e03\u65f6\u95f4',
  trend: 'GitHub \u8d8b\u52bf',
  netStars: '\u8fd1\u4e03\u65e5\u51c0\u661f\u6570',
  samples: '\u91c7\u6837\u6b21\u6570',
  stars: '\u7d2f\u8ba1\u661f\u6570',
  trendHint: '\u7d2f\u8ba1\u661f\u6570\u4e0d\u7b49\u4e8e\u672c\u5468\u589e\u957f\u3002',
  runs: '\u751f\u6210\u8bb0\u5f55',
  runHint:
    '\u5df2\u751f\u6210\u8868\u793a\u65e5\u62a5\u53ef\u9605\u8bfb\uff0c\u4e0d\u8868\u793a\u5185\u5bb9\u5b8c\u6574\u6216\u6d88\u606f\u5df2\u53d1\u9001\u3002',
  emptyRuns: '\u6682\u65e0\u5b9a\u65f6\u751f\u6210\u8bb0\u5f55\u3002',
  cancel: '\u53d6\u6d88\u751f\u6210',
  confirmCancel: '\u53d6\u6d88\u8fd9\u6b21\u5c1a\u672a\u5b8c\u6210\u7684\u751f\u6210\uff1f',
  confirm: '\u786e\u8ba4\u53d6\u6d88',
  keep: '\u4fdd\u7559\u4efb\u52a1',
  taskStatus: '\u8c03\u5ea6\u72b6\u6001',
  skipped: '\u8df3\u8fc7\u7684\u66f4\u65e9\u671f\u6570',
  errorCode: '\u9519\u8bef\u7801',
  partialError:
    '\u90e8\u5206\u6570\u636e\u52a0\u8f7d\u5931\u8d25\uff0c\u8bf7\u5728\u5bf9\u5e94\u533a\u57df\u91cd\u8bd5\u3002',
  unavailable: '\u8bf7\u6c42\u5931\u8d25\uff0c\u6216\u65b0\u95fb\u670d\u52a1\u5c1a\u4e0d\u53ef\u7528\u3002',
  conflict:
    '\u8bbe\u7f6e\u5df2\u5728\u5176\u4ed6\u4f4d\u7f6e\u53d8\u66f4\u3002\u8bf7\u91cd\u65b0\u8f7d\u5165\u540e\u518d\u4fdd\u5b58\uff1b\u5f53\u524d\u8349\u7a3f\u5df2\u4fdd\u7559\u3002',
  invalid: '\u8bf7\u8f93\u5165\u6709\u6548\u65f6\u533a\u4e0e HH:mm \u683c\u5f0f\u7684\u622a\u6b62\u65f6\u95f4\u3002',
  expired: "\u8fd9\u4efd\u65e5\u62a5\u5df2\u8d85\u8fc7\u5185\u5bb9\u4fdd\u7559\u671f\u3002\u539f\u8bf7\u6c42\u8bb0\u5f55\u4ecd\u4fdd\u7559\uff0c\u91cd\u590d\u8bf7\u6c42\u4e0d\u4f1a\u91cd\u65b0\u751f\u6210\u3002",
  notFound:
    '\u8fd9\u4efd\u65e5\u62a5\u4e0d\u53ef\u8bbf\u95ee\uff0c\u6216\u4e0d\u5c5e\u4e8e\u5f53\u524d\u8d26\u6237\u3002',
  previewUnknown:
    '\u9884\u89c8\u7ed3\u679c\u5c1a\u672a\u786e\u8ba4\uff1b\u91cd\u8bd5\u4f1a\u6cbf\u7528\u672c\u6b21\u8bf7\u6c42\u4e0e\u622a\u6b62\u65f6\u95f4\u3002',
  topNote:
    '\u6bcf\u7c7b\u6700\u591a 20 \u6761\uff1b\u524d 10 \u6761\u5728\u6765\u6e90\u6750\u6599\u8db3\u591f\u65f6\u63d0\u4f9b\u8be6\u60c5\u3002'
};

const zhNewsStatus: Record<string, string> = {
  COMPLETE: '\u6570\u91cf\u8fbe\u6807',
  DEGRADED: '\u5185\u5bb9\u4e0d\u8db3',
  QUEUED: '\u7b49\u5f85\u751f\u6210',
  DISPATCHED: '\u5df2\u6d3e\u53d1',
  READY: '\u5df2\u751f\u6210',
  FAILED: '\u5931\u8d25',
  CANCELLED: '\u5df2\u53d6\u6d88',
  EXPIRED: '\u5df2\u8fc7\u671f',
  VERIFIED: '\u57ce\u5e02\u8303\u56f4\u5df2\u786e\u8ba4',
  PROVINCE_FALLBACK: '\u4f7f\u7528\u7701\u7ea7\u8303\u56f4',
  CACHED: '\u4f7f\u7528\u7f13\u5b58\u5730\u533a',
  UNRESOLVED: '\u5c1a\u672a\u89e3\u6790',
  STALE: '\u89c2\u6d4b\u5df2\u8fc7\u671f',
  EGRESS_UNAVAILABLE: '\u51fa\u53e3\u63a2\u6d4b\u5931\u8d25',
  LOOKUP_FAILED: '\u5730\u533a\u5b9a\u4f4d\u5931\u8d25',
  COUNTRY_ONLY: '\u4ec5\u6709\u56fd\u5bb6\u7ea7\u7cbe\u5ea6',
  SOURCE_PACK_MISSING: '\u7f3a\u5c11\u672c\u5730\u6765\u6e90\u5305',
  NO_SOURCE_PACK: '\u7f3a\u5c11\u672c\u5730\u6765\u6e90\u5305',
  EVIDENCE_EXPIRED: "\u539f\u6587\u8bc1\u636e\u5df2\u8d85\u8fc7\u4fdd\u7559\u671f",
  EVIDENCE_TOO_SHORT: '\u539f\u6587\u6750\u6599\u4e0d\u8db3\uff0c\u65e0\u6cd5\u63d0\u4f9b\u8be6\u60c5',
  NOT_AUTHORIZED: '\u6765\u6e90\u4ec5\u5141\u8bb8\u5c55\u793a\u6807\u9898',
  HEADLINE_ONLY: '\u4ec5\u6807\u9898',
  WARMING_UP: '\u6b63\u5728\u79ef\u7d2f\u4e03\u65e5\u91c7\u6837',
  MISSING_BASELINE: '\u7f3a\u5c11\u5bf9\u7167\u91c7\u6837',
  GAPPED_HISTORY: '\u91c7\u6837\u5b58\u5728\u7f3a\u53e3',
  HEADLINE_SHORTFALL: '\u7b26\u5408\u6761\u4ef6\u7684\u6807\u9898\u4e0d\u8db3 20 \u6761',
  DETAIL_SHORTFALL: '\u90e8\u5206\u524d\u5341\u6761\u76ee\u7f3a\u5c11\u53ef\u7528\u8be6\u60c5',
  REGION_UNRESOLVED: '\u672c\u5730\u5730\u533a\u6216\u6765\u6e90\u5c1a\u672a\u5c31\u7eea'
};

/** 从现有国际化上下文获取响应式新闻文案。 */
export function useNewsText() {
  const locale = computed(() => (getLocale() === 'zh-CN' ? 'zh-CN' : 'en-US'));
  const text = computed(() => (locale.value === 'zh-CN' ? zhNews : enNews));
  const status = (value: string, context?: 'trend') => {
    if (context === 'trend' && value === 'READY') return text.value.trendReady;
    const messages = locale.value === 'zh-CN' ? zhNewsStatus : enNewsStatus;
    return messages[value.replace(/^GITHUB_TREND_/, '')] || value;
  };
  const error = (code: string) => {
    if (code === 'NEWS_014') return text.value.conflict;
    if (code === 'INVALID_SCHEDULE') return text.value.invalid;
    if (code === 'NEWS_026') return text.value.expired;
    if (code === 'NEWS_003') return text.value.notFound;
    return text.value.unavailable;
  };
  return { text, status, error, locale };
}
