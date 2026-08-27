import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { BookSourceSearchResult } from './ReaderModels';

interface RuntimeSearchRequest {
  keyword: string;
  page: number;
  mode: string;
}

/** 后端书源异步搜索任务。 */
export interface BookSourceRuntimeSearchTask {
  taskId: string;
  status: string;
  processedSources: number;
  totalSources: number;
  cachedSources: number;
  pendingSources: number;
  failedSources: number;
  resultCount: number;
  results: BookSourceSearchResult[];
  nextOffset: number;
  hasMore: boolean;
  message: string;
  updatedAt: number;
}

/**
 * 调用MyTools后端的用户级书源规则执行服务。
 */
export class BookSourceRuntimeSearchApi {
  private readonly client: AuthorizedApiClient;

  constructor(baseUrl: string, authManager: AuthSessionManager) {
    this.client = new AuthorizedApiClient(baseUrl, authManager);
  }

  /** 启动全量书源搜索任务。 */
  async start(keyword: string, page: number, mode: string): Promise<BookSourceRuntimeSearchTask> {
    const request: RuntimeSearchRequest = {
      keyword: keyword, page: page, mode: mode === 'EXACT' ? 'EXACT' : 'FUZZY'
    };
    try {
      return this.task((await this.client.postJson('/api/app/v1/reader/source-search', request)).data);
    } catch (error) {
      const message = error instanceof Error ? error.message : '';
      if (/not found/i.test(message)) throw new Error('后端版本尚未支持书源并发搜索，请先更新MyTools服务');
      throw error instanceof Error ? error : new Error('书源搜索请求失败');
    }
  }

  /** 终止仍在执行的搜索任务。 */
  async cancel(taskId: string): Promise<void> {
    if (!/^[0-9a-f-]{36}$/.test(taskId)) return;
    await this.client.delete(`/api/app/v1/reader/source-search/${encodeURIComponent(taskId)}`);
  }

  /** 获取任务新增结果。 */
  async find(taskId: string, offset: number): Promise<BookSourceRuntimeSearchTask> {
    if (!/^[0-9a-f-]{36}$/.test(taskId) || !Number.isSafeInteger(offset) || offset < 0) {
      throw new Error('书源搜索任务参数无效');
    }
    return this.task((await this.client.get(`/api/app/v1/reader/source-search/${encodeURIComponent(taskId)}` +
      `?offset=${offset}&limit=200`)).data);
  }

  private task(value: Object | undefined): BookSourceRuntimeSearchTask {
    if (value === undefined || value === null || Array.isArray(value) || typeof value !== 'object') {
      throw new Error('书源搜索响应无效');
    }
    const data = value as Record<string, Object>;
    const taskId = `${data['taskId'] ?? ''}`;
    const rawStatus = `${data['status'] ?? ''}`;
    const status = rawStatus === 'SUCCEEDED' || rawStatus === 'PARTIAL_FAILED' ? 'COMPLETED' :
      (rawStatus === 'ACCEPTED' || rawStatus === 'QUEUED' ? 'RUNNING' :
        (rawStatus === 'TIMED_OUT' ? 'FAILED' : rawStatus));
    const completedShards = this.safeInteger(data['completedShards']);
    const failedShards = this.safeInteger(data['failedShards']);
    const totalShards = this.safeInteger(data['totalShards']);
    const processedSources = this.safeInteger(data['processedSources']) >= 0 ?
      this.safeInteger(data['processedSources']) : Math.max(0, completedShards) + Math.max(0, failedShards);
    const totalSources = this.safeInteger(data['totalSources']) >= 0 ?
      this.safeInteger(data['totalSources']) : Math.max(0, totalShards);
    // 兼容尚未部署搜索进度扩展字段的旧后端，避免升级期间整页搜索不可用。
    const rawCachedSources = this.safeInteger(data['cachedSources']);
    const rawPendingSources = this.safeInteger(data['pendingSources']);
    const cachedSources = rawCachedSources >= 0 ? rawCachedSources : 0;
    const pendingSources = rawPendingSources >= 0 ? rawPendingSources : totalSources;
    const rawFailedSources = this.safeInteger(data['failedSources']);
    const failedSources = rawFailedSources >= 0 ? rawFailedSources : Math.max(0, failedShards);
    const rawResults = Array.isArray(data['results']) ? data['results'] as Object[] : [];
    const resultCount = this.safeInteger(data['resultCount']) >= 0 ? this.safeInteger(data['resultCount']) : rawResults.length;
    const nextOffset = this.safeInteger(data['nextOffset']) >= 0 ? this.safeInteger(data['nextOffset']) : rawResults.length;
    const parsedUpdatedAt = Date.parse(`${data['updatedAt'] ?? ''}`);
    const updatedAt = this.safeInteger(data['updatedAt']) >= 0 ? this.safeInteger(data['updatedAt']) :
      (Number.isFinite(parsedUpdatedAt) ? Math.floor(parsedUpdatedAt) : Date.now());
    const message = `${data['message'] ?? ''}`.substring(0, 200);
    const hasMore = data['hasMore'] === true;
    if (!/^[0-9a-f-]{36}$/.test(taskId) || !['RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'].includes(status) ||
      processedSources < 0 || totalSources < 0 || processedSources > totalSources || failedSources < 0 ||
      cachedSources < 0 || cachedSources > processedSources || pendingSources < 0 ||
      cachedSources + pendingSources !== totalSources || failedSources > processedSources ||
      resultCount < 0 || nextOffset < 0 || nextOffset > resultCount ||
      updatedAt < 0) {
      throw new Error('书源搜索响应字段无效');
    }
    const results: BookSourceSearchResult[] = [];
    rawResults.forEach((item: Object) => {
      const parsed = this.result(item);
      if (parsed !== undefined) results.push(parsed);
    });
    return { taskId: taskId, status: status, processedSources: processedSources, totalSources: totalSources,
      cachedSources: cachedSources, pendingSources: pendingSources, failedSources: failedSources,
      resultCount: resultCount, results: results, nextOffset: nextOffset,
      hasMore: hasMore, message: message, updatedAt: updatedAt };
  }

  private result(value: Object): BookSourceSearchResult | undefined {
    if (value === null || Array.isArray(value) || typeof value !== 'object') return undefined;
    const data = value as Record<string, Object>;
    const name = `${data['name'] ?? ''}`.trim().substring(0, 300);
    const bookUrl = `${data['bookUrl'] ?? ''}`.trim().substring(0, 4096);
    const sourceUrl = `${data['sourceUrl'] ?? ''}`.trim().substring(0, 4096);
    if (name.length === 0 || bookUrl.length === 0 || sourceUrl.length === 0) return undefined;
    return { name: name, author: `${data['author'] ?? ''}`.trim().substring(0, 200),
      intro: `${data['intro'] ?? ''}`.trim().substring(0, 10000),
      lastChapter: `${data['lastChapter'] ?? ''}`.trim().substring(0, 500),
      coverUrl: `${data['coverUrl'] ?? ''}`.trim().substring(0, 4096), bookUrl: bookUrl,
      sourceUrl: sourceUrl, sourceName: `${data['sourceName'] ?? ''}`.trim().substring(0, 200) };
  }

  private safeInteger(value: Object | undefined): number {
    const parsed = Number(value);
    return Number.isSafeInteger(parsed) ? parsed : -1;
  }
}
