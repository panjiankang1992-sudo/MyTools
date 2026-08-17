import http from '@ohos.net.http';
import { BookSource, BookSourceExploreCategory, BookSourceHealthSample, BookSourceSearchResult } from './ReaderModels';
import { BookSourceExploreParser } from './BookSourceExploreParser';
import { DeclarativePageTemplate } from './DeclarativePageTemplate';
import { BookSourceHtmlParser } from './BookSourceHtmlParser';
import { SourceUrlPolicy } from '../../shared/network/SourceUrlPolicy';
import { BookSourceCredentialStore } from './BookSourceCredentialStore';
import { DeclarativeJsonPath } from './DeclarativeJsonPath';
import { DeclarativeRequestConfig } from './DeclarativeRequestConfig';
import { SourceUrlResolver } from './SourceUrlResolver';
import { BookSourceRulePolicy } from './BookSourceRulePolicy';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import { SourceHttpRequestLease, sourceHttpRequestCoordinator } from './SourceHttpRequestCoordinator';
import { SourceHttpRetryPolicy } from './SourceHttpRetryPolicy';
import { SourceHttpResponsePolicy } from './SourceHttpResponsePolicy';
import { SourceHttpRequestHeaderPolicy } from './SourceHttpRequestHeaderPolicy';
import { SourceRequestInputPolicy } from './SourceRequestInputPolicy';

interface SourceRequest {
  url: string;
  method: http.RequestMethod;
  body: string;
  headers: Record<string, string>;
}

interface SourceSearchOutcome {
  results: BookSourceSearchResult[];
}

/**
 * 执行不含脚本的开源阅读JSON书源搜索规则。
 */
export class BookSourceSearchEngine {
  private readonly htmlParser = new BookSourceHtmlParser();
  private readonly jsonPath = new DeclarativeJsonPath();
  private readonly rulePolicy = new BookSourceRulePolicy();
  private readonly requestConfig = new DeclarativeRequestConfig();
  private readonly urlResolver = new SourceUrlResolver();
  private readonly urlPolicy = new SourceUrlPolicy();
  private readonly credentialStore: BookSourceCredentialStore;
  private readonly exploreParser = new BookSourceExploreParser();
  private readonly pageTemplate = new DeclarativePageTemplate();
  private readonly retryPolicy = new SourceHttpRetryPolicy();
  private readonly responsePolicy = new SourceHttpResponsePolicy();
  private readonly headerPolicy = new SourceHttpRequestHeaderPolicy();
  private readonly inputPolicy = new SourceRequestInputPolicy();

  constructor(credentialStore: BookSourceCredentialStore) {
    this.credentialStore = credentialStore;
  }

  /**
   * 搜索所有已启用且无需脚本运行时的书源。
   */
  async search(sources: BookSource[], keyword: string, page: number, isCurrent: () => boolean,
    cancellation?: DownloadCancellationToken):
    Promise<BookSourceSearchResult[]> {
    const safeKeyword = this.inputPolicy.keyword(keyword);
    const safePage = this.inputPolicy.page(page);
    const eligible = sources.filter((source: BookSource) => source.enabled && !this.searchContainsScript(source) &&
      source.searchUrl.trim().length > 0);
    const ranked = eligible.slice().sort((left: BookSource, right: BookSource) =>
      this.sourcePriority(left) - this.sourcePriority(right));
    const selected = new Map<string, BookSource>();
    ranked.slice(0, 16).forEach((source: BookSource) => selected.set(source.bookSourceUrl, source));
    eligible.slice().sort((left: BookSource, right: BookSource) =>
      (left.healthCheckedAt ?? 0) - (right.healthCheckedAt ?? 0)).forEach((source: BookSource) => {
      if (selected.size < 20) selected.set(source.bookSourceUrl, source);
    });
    const active = Array.from(selected.values());
    const results: BookSourceSearchResult[] = [];
    for (let offset = 0; offset < active.length; offset += 4) {
      if (!isCurrent()) break;
      const batch = active.slice(offset, offset + 4);
      const outcomes = await Promise.all(batch.map((source: BookSource) =>
        this.searchOneSource(source, safeKeyword, safePage, isCurrent, cancellation)));
      // Promise.all保持输入顺序，避免网络完成先后改变候选来源排序。
      outcomes.forEach((outcome: SourceSearchOutcome) => outcome.results.forEach(
        (value: BookSourceSearchResult) => results.push(value)));
    }
    const unique = new Map<string, BookSourceSearchResult>();
    results.forEach((value: BookSourceSearchResult) => unique.set(`${value.sourceUrl}|${value.bookUrl}`, value));
    return Array.from(unique.values()).slice(0, 100);
  }

  private async searchOneSource(source: BookSource, keyword: string, page: number, isCurrent: () => boolean,
    cancellation?: DownloadCancellationToken):
    Promise<SourceSearchOutcome> {
    const startedAt = Date.now();
    try {
      const values = await this.searchSource(source, keyword, page, cancellation);
      if (isCurrent()) this.setHealth(source, values.length > 0 ? 'available' : 'partial', Date.now() - startedAt,
        values.length > 0 ? `搜索返回${values.length}项` : '请求成功但当前关键词无结果');
      return { results: values };
    } catch (error) {
      if (cancellation?.isCancelled()) return { results: [] };
      if (isCurrent()) this.setHealth(source, 'unavailable', Date.now() - startedAt,
        error instanceof Error ? error.message : '搜索请求失败');
      // 单个书源失败返回空结果，不拒绝整个批次。
      return { results: [] };
    }
  }

  /**
   * 使用单个书源的无脚本发现规则加载图书。
   */
  async explore(source: BookSource, exploreUrl: string, page: number = 1,
    cancellation?: DownloadCancellationToken): Promise<BookSourceSearchResult[]> {
    if (!source.enabled || !source.enabledExplore || source.exploreUrl.trim().length === 0) {
      throw new Error('该书源未启用发现或缺少发现地址');
    }
    if (this.exploreContainsScript(source)) throw new Error('该书源发现规则需要受限脚本运行时');
    return this.requestSource(source, exploreUrl, source.ruleExplore, '', this.inputPolicy.page(page), cancellation);
  }

  /**
   * 解析单个书源可由用户选择的有界发现分类。
   */
  exploreCategories(source: BookSource): BookSourceExploreCategory[] {
    return this.exploreParser.parse(source.exploreUrl);
  }

  /**
   * 使用真实搜索请求检查单个书源的可用性。
   */
  async checkHealth(source: BookSource, keyword: string,
    cancellation?: DownloadCancellationToken): Promise<void> {
    if (!source.enabled || source.searchUrl.trim().length === 0 || this.searchContainsScript(source)) {
      this.setHealth(source, 'unsupported', 0, !source.enabled ? '书源未启用' : '需要脚本运行时或缺少搜索地址');
      return;
    }
    const startedAt = Date.now();
    try {
      const values = await this.searchSource(source, this.inputPolicy.keyword(keyword.trim().length > 0 ?
        keyword : '测试'), 1,
        cancellation);
      if (cancellation?.isCancelled()) return;
      this.setHealth(source, values.length > 0 ? 'available' : 'partial', Date.now() - startedAt,
        values.length > 0 ? `探测返回${values.length}项` : '请求成功但探测词无结果');
    } catch (error) {
      if (cancellation?.isCancelled()) return;
      this.setHealth(source, 'unavailable', Date.now() - startedAt,
        error instanceof Error ? error.message : '书源探测失败');
    }
  }

  private setHealth(source: BookSource, status: 'available' | 'partial' | 'unavailable' | 'unsupported',
    latencyMs: number, message: string): void {
    source.healthStatus = status;
    source.healthLatencyMs = Math.max(0, latencyMs);
    source.healthCheckedAt = Date.now();
    source.healthMessage = message.substring(0, 200);
    const sample: BookSourceHealthSample = {
      status: status,
      latencyMs: Math.max(0, latencyMs),
      checkedAt: source.healthCheckedAt
    };
    source.healthHistory = (source.healthHistory ?? []).concat(sample).slice(-20);
    source.healthConsecutiveFailures = status === 'unavailable' ?
      (source.healthConsecutiveFailures ?? 0) + 1 : (status === 'unsupported' ?
        (source.healthConsecutiveFailures ?? 0) : 0);
    const supported = source.healthHistory.filter((value: BookSourceHealthSample) => value.status !== 'unsupported');
    const successful = supported.filter((value: BookSourceHealthSample) =>
      value.status === 'available' || value.status === 'partial');
    source.healthSuccessRate = supported.length === 0 ? 0 : successful.length / supported.length;
    source.healthAverageLatencyMs = successful.length === 0 ? 0 : Math.round(successful.reduce(
      (total: number, value: BookSourceHealthSample) => total + value.latencyMs, 0) / successful.length);
    source.healthDegraded = (source.healthConsecutiveFailures ?? 0) >= 3 ||
      (supported.length >= 5 && (source.healthSuccessRate ?? 0) < 0.4);
  }

  private sourcePriority(source: BookSource): number {
    const degradedPenalty = source.healthDegraded === true ? 1000000 : 0;
    const failurePenalty = (source.healthConsecutiveFailures ?? 0) * 100000;
    const successPenalty = Math.round((1 - (source.healthSuccessRate ?? 0.5)) * 10000);
    const latency = source.healthAverageLatencyMs ?? source.healthLatencyMs ?? 9999;
    return degradedPenalty + failurePenalty + successPenalty + Math.min(9999, Math.max(0, latency));
  }

  private async searchSource(source: BookSource, keyword: string, page: number,
    cancellation?: DownloadCancellationToken): Promise<BookSourceSearchResult[]> {
    return this.requestSource(source, source.searchUrl, source.ruleSearch, keyword, page, cancellation);
  }

  private async requestSource(source: BookSource, urlTemplate: string, rules: Record<string, Object>, keyword: string,
    page: number, cancellation?: DownloadCancellationToken): Promise<BookSourceSearchResult[]> {
    if (cancellation?.isCancelled()) throw new Error('书源搜索已取消');
    this.validateRestrictedScripts(rules);
    const requestSpec = this.buildRequest(source, urlTemplate, keyword, page);
    await this.urlPolicy.assertSafe(requestSpec.url);
    if (cancellation?.isCancelled()) throw new Error('书源搜索已取消');
    const credentialRevision = this.credentialStore.currentScopeRevision();
    const sensitiveHeaders = await this.credentialStore.headersFor(source.bookSourceUrl, requestSpec.url);
    if (!this.credentialStore.scopeRevisionCurrent(credentialRevision)) throw new Error('书源账户作用域已切换');
    if (cancellation?.isCancelled()) throw new Error('书源搜索已取消');
    Object.keys(sensitiveHeaders).forEach((key: string) => requestSpec.headers[key] = sensitiveHeaders[key]);
    const lease: SourceHttpRequestLease = await sourceHttpRequestCoordinator.acquire(source.bookSourceUrl,
      cancellation);
    try {
      const text = await this.requestText(source, requestSpec, credentialRevision, cancellation);
      const bodyFailure = this.responsePolicy.bodyError(text);
      if (bodyFailure.length > 0) throw new Error(bodyFailure);
      const trimmed = text.trim();
      const results = trimmed.startsWith('{') || trimmed.startsWith('[') ?
        this.parseJsonResults(source, requestSpec.url, JSON.parse(trimmed) as Object, rules) :
        this.parseHtmlResults(source, requestSpec.url, text, rules);
      await this.sanitizeCoverUrls(results, cancellation);
      return results;
    } catch (error) {
      if (cancellation?.isCancelled()) throw new Error('书源搜索已取消');
      if (error instanceof Error) throw error;
      throw new Error(`${source.bookSourceName}搜索失败`);
    } finally {
      sourceHttpRequestCoordinator.release(lease);
    }
  }

  private async requestText(source: BookSource, spec: SourceRequest, credentialRevision: number,
    cancellation?: DownloadCancellationToken): Promise<string> {
    for (let attempt = 0; attempt < 2; attempt++) {
      if (cancellation?.isCancelled()) throw new Error('书源搜索已取消');
      const request = http.createHttp();
      let redirectBlocked = false;
      let headerFailure = '';
      let responseStatus: number | undefined = undefined;
      const cancelHandler = (): void => request.destroy();
      try {
        cancellation?.bind(cancelHandler);
        request.on('headersReceive', (headers: Object) => {
          headerFailure = this.responsePolicy.headerError(headers);
          if (headerFailure.length > 0) {
            redirectBlocked = headerFailure.includes('重定向');
            request.destroy();
          }
        });
        const response = await request.request(spec.url, {
          method: spec.method,
          header: spec.headers,
          extraData: spec.method === http.RequestMethod.POST ? spec.body : undefined,
          expectDataType: http.HttpDataType.STRING,
          connectTimeout: 10000,
          readTimeout: 20000
        });
        if (cancellation?.isCancelled()) throw new Error('书源搜索已取消');
        if (headerFailure.length > 0) throw new Error(headerFailure);
        if (redirectBlocked) throw new Error('书源重定向已阻止，请配置最终公开地址');
        responseStatus = response.responseCode;
        if (response.responseCode >= 200 && response.responseCode < 300) {
          this.credentialStore.captureSessionCookies(source.bookSourceUrl, spec.url, response.cookies,
            credentialRevision);
          return typeof response.result === 'string' ? response.result : `${response.result}`;
        }
        if (!this.retryPolicy.shouldRetry(spec.method === http.RequestMethod.GET, attempt, response.responseCode)) {
          throw new Error(`${source.bookSourceName}返回HTTP ${response.responseCode}`);
        }
      } catch (error) {
        if (cancellation?.isCancelled()) throw new Error('书源搜索已取消');
        if (headerFailure.length > 0) throw new Error(headerFailure);
        if (redirectBlocked) throw new Error('书源重定向已阻止，请配置最终公开地址');
        if (!this.retryPolicy.shouldRetry(spec.method === http.RequestMethod.GET, attempt, responseStatus)) {
          if (error instanceof Error) throw error;
          throw new Error(`${source.bookSourceName}搜索失败`);
        }
      } finally {
        cancellation?.unbind(cancelHandler);
        request.off('headersReceive');
        request.destroy();
      }
      await this.retryPolicy.wait(cancellation);
    }
    throw new Error(`${source.bookSourceName}搜索失败`);
  }

  private validateRestrictedScripts(rules: Record<string, Object>): void {
    Object.keys(rules).forEach((key: string) => this.rulePolicy.jsonRule(rules, key));
  }

  private async sanitizeCoverUrls(results: BookSourceSearchResult[],
    cancellation?: DownloadCancellationToken): Promise<void> {
    const checked = new Map<string, boolean>();
    for (let index = 0; index < results.length; index++) {
      if (cancellation?.isCancelled()) throw new Error('书源搜索已取消');
      const coverUrl = results[index].coverUrl;
      if (coverUrl.length === 0) continue;
      let safe = checked.get(coverUrl);
      if (safe === undefined) {
        try {
          await this.urlPolicy.assertSafe(coverUrl);
          if (cancellation?.isCancelled()) throw new Error('书源搜索已取消');
          safe = true;
        } catch (_) {
          safe = false;
        }
        checked.set(coverUrl, safe);
      }
      if (!safe) results[index].coverUrl = '';
    }
  }

  private buildRequest(source: BookSource, urlTemplate: string, keyword: string, page: number): SourceRequest {
    const marker = urlTemplate.indexOf(',{');
    const rawUrl = marker < 0 ? urlTemplate : urlTemplate.substring(0, marker);
    const encodedKeyword = encodeURIComponent(keyword);
    const urlText = this.pageTemplate.expand(
      rawUrl.replace(/\{\{key\}\}|\{\{searchKey\}\}|searchKey/g, encodedKeyword), page);
    const url = this.absoluteUrl(source.bookSourceUrl, urlText);
    let headers = this.headerPolicy.parse(source.header);
    let method = http.RequestMethod.GET;
    let body = '';
    if (marker >= 0) {
      const escapedKeyword = keyword.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
      const configText = this.pageTemplate.expand(urlTemplate.substring(marker + 1)
        .replace(/\{\{key\}\}|\{\{searchKey\}\}|searchKey/g, escapedKeyword), page);
      const config = this.requestConfig.parse(configText);
      method = config.method === 'POST' ? http.RequestMethod.POST : http.RequestMethod.GET;
      body = config.body;
      headers = this.headerPolicy.merge(headers, config.headers);
    }
    headers = this.headerPolicy.merge(headers, {});
    if (method === http.RequestMethod.POST && !Object.keys(headers).some((key: string) =>
      key.toLowerCase() === 'content-type')) {
      headers['Content-Type'] = 'application/json';
    }
    return { url: url, method: method, body: body, headers: headers };
  }

  private parseJsonResults(source: BookSource, responseUrl: string, payload: Object,
    rules: Record<string, Object>): BookSourceSearchResult[] {
    const listRule = this.rulePolicy.jsonRule(rules, 'bookList');
    const listValue = this.valueAt(payload, listRule);
    const list = Array.isArray(listValue) ? listValue as Object[] : [];
    const results: BookSourceSearchResult[] = [];
    list.slice(0, 100).forEach((item: Object) => {
      const name = this.jsonTextAt(item, rules, 'name', ['name', 'title', 'bookName']);
      const bookUrlRaw = this.jsonTextAt(item, rules, 'bookUrl', ['bookUrl', 'url', 'id']);
      if (name.length === 0 || bookUrlRaw.length === 0) return;
      results.push({
        name: name,
        author: this.jsonTextAt(item, rules, 'author', ['author', 'writer']),
        intro: this.jsonTextAt(item, rules, 'intro', ['intro', 'description', 'desc']),
        lastChapter: this.jsonTextAt(item, rules, 'lastChapter', ['lastChapter', 'latestChapter']),
        coverUrl: this.absoluteUrl(responseUrl,
          this.jsonTextAt(item, rules, 'coverUrl', ['coverUrl', 'cover'])),
        bookUrl: this.urlResolver.resolveRequest(responseUrl, bookUrlRaw),
        sourceUrl: source.bookSourceUrl,
        sourceName: source.bookSourceName
      });
    });
    return results;
  }

  private parseHtmlResults(source: BookSource, responseUrl: string, html: string,
    rules: Record<string, Object>): BookSourceSearchResult[] {
    const listRule = this.rulePolicy.htmlRule(rules, 'bookList');
    if (listRule.length === 0) throw new Error('HTML书源缺少bookList规则');
    const fragments = this.htmlParser.selectList(html, listRule);
    const results: BookSourceSearchResult[] = [];
    fragments.slice(0, 100).forEach((fragment: string) => {
      const name = this.htmlTextAt(fragment, rules, 'name');
      const bookUrlRaw = this.htmlTextAt(fragment, rules, 'bookUrl');
      if (name.length === 0 || bookUrlRaw.length === 0) return;
      results.push({
        name: name,
        author: this.htmlTextAt(fragment, rules, 'author'),
        intro: this.htmlTextAt(fragment, rules, 'intro'),
        lastChapter: this.htmlTextAt(fragment, rules, 'lastChapter'),
        coverUrl: this.absoluteUrl(responseUrl,
          this.htmlTextAt(fragment, rules, 'coverUrl')),
        bookUrl: this.urlResolver.resolveRequest(responseUrl, bookUrlRaw),
        sourceUrl: source.bookSourceUrl,
        sourceName: source.bookSourceName
      });
    });
    return results;
  }

  private valueAt(root: Object, rule: string): Object | undefined {
    return this.jsonPath.evaluate(root, rule);
  }

  private textAt(item: Object, rule: string, fallbackKeys: string[]): string {
    let value: Object | undefined = rule.length === 0 ? undefined : this.valueAt(item, rule);
    if (rule.length === 0) {
      for (let index = 0; index < fallbackKeys.length && value === undefined; index++) {
        value = this.valueAt(item, fallbackKeys[index]);
      }
    }
    if (value === undefined || value === null || typeof value === 'object') return '';
    return `${value}`.trim();
  }

  private jsonTextAt(item: Object, rules: Record<string, Object>, key: string,
    fallbackKeys: string[]): string {
    const value = this.textAt(item, this.rulePolicy.jsonRule(rules, key), fallbackKeys);
    return this.rulePolicy.transform(rules, key, value);
  }

  private htmlTextAt(fragment: string, rules: Record<string, Object>, key: string): string {
    const value = this.htmlParser.selectText(fragment, this.rulePolicy.htmlRule(rules, key));
    return this.rulePolicy.transform(rules, key, value);
  }

  private absoluteUrl(baseUrl: string, value: string): string {
    return value.length === 0 ? '' : this.urlResolver.resolve(baseUrl, value);
  }

  private searchContainsScript(source: BookSource): boolean {
    const text = source.searchUrl.toLowerCase();
    const withoutKnownPlaceholders = text.replace(/\{\{(key|searchkey)\}\}/gi, '');
    return text.includes('@js:') || text.includes('<js>') || text.includes('javascript:') ||
      this.pageTemplate.hasUnsupportedExpression(withoutKnownPlaceholders);
  }

  private exploreContainsScript(source: BookSource): boolean {
    const text = source.exploreUrl.toLowerCase();
    return text.includes('@js:') || text.includes('<js>') || text.includes('javascript:') ||
      this.pageTemplate.hasUnsupportedExpression(text);
  }
}
