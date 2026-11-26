/** RSS 用户分组。 */
export interface RssGroup { id: string; name: string; sortOrder: number; revision: number; }
/** 个人订阅及服务器同步状态。 */
export interface RssSubscription {
  id: string; groupId: string; feedUrl: string; title: string; customTitle: string; siteUrl: string;
  description: string; status: string; intervalMinutes: number; initialized: boolean;
  lastAttemptAt: number; lastSuccessAt: number; nextFetchAt: number; lastErrorCode: string;
  syncing: boolean; revision: number; unreadCount: number;
}
/** 文章身份和用户阅读状态，ID 始终是字符串。 */
export interface RssEntry {
  id: string; subscriptionId: string; sourceTitle: string; title: string; author: string; url: string;
  summary: string; contentHtml: string; imageUrl: string; publishedAt: number; sourceUpdatedAt: number;
  discoveredAt: number; read: boolean; starred: boolean; revision: number;
}
/** 绑定筛选条件的文章页。 */
export interface RssEntryPage { items: RssEntry[]; nextCursor: string; }
/** 个人汇总数量。 */
export interface RssCounts { subscriptions: number; unread: number; starred: number; }
/** 实际后端能力。 */
export interface RssCapabilities { pollingEnabled: boolean; opml: boolean; maxSubscriptions: number; }
/** 网站声明的订阅候选。 */
export interface RssCandidate { url: string; title: string; }
/** 添加页最近文章预览。 */
export interface RssPreviewItem { title: string; summary: string; publishedAt: string; }
/** 添加页已识别来源。 */
export interface RssPreview { url: string; title: string; siteUrl: string; description: string; entries: RssPreviewItem[]; }
/** 直接 Feed 或候选列表，二者取一。 */
export interface RssDiscovery { candidates: RssCandidate[]; preview?: RssPreview; }
/** 单项导入结果。 */
export interface RssImportItem { url: string; title: string; group: string; status: string; subscriptionId: string; errorCode: string; }
/** 显式设置值的状态变更。 */
export interface RssStateChange { expectedRevision: number; read: boolean; starred: boolean; }
/** 新增订阅策略。 */
export interface RssCreateSubscription { url: string; title: string; groupId: string; initialReadAll: boolean; }
/** 完整订阅设置更新。 */
export interface RssUpdateSubscription { expectedRevision: number; title: string; groupId: string; status: string; intervalMinutes: number; }
/** 分组编辑请求。 */
export interface RssGroupChange { name: string; sortOrder: number; expectedRevision?: number; }
/** 当前筛选范围。 */
export interface RssFilter { subscriptionId: string; groupId: string; view: string; q: string; }
/** 清空详情状态时创建独立对象。 */
export function emptyRssEntry(): RssEntry {
  return { id: '', subscriptionId: '', sourceTitle: '', title: '', author: '', url: '', summary: '', contentHtml: '',
    imageUrl: '', publishedAt: 0, sourceUpdatedAt: 0, discoveredAt: 0, read: false, starred: false, revision: 0 };
}
