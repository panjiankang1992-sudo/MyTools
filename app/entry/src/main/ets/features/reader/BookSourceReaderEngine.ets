import http from '@ohos.net.http';
import common from '@ohos.app.ability.common';
import { BookSource, ReaderChapter, ReaderDocument, SourceChapterContent } from './ReaderModels';
import { BookSourceHtmlParser } from './BookSourceHtmlParser';
import { SourceUrlPolicy } from '../../shared/network/SourceUrlPolicy';
import { BookSourceCredentialStore } from './BookSourceCredentialStore';
import { DeclarativeJsonPath } from './DeclarativeJsonPath';
import { DeclarativeRequestConfig } from './DeclarativeRequestConfig';
import { SourceUrlResolver } from './SourceUrlResolver';
import { NormalizedSourceChapter, SourceCatalogNormalizer, SourceChapterCandidate } from './SourceCatalogNormalizer';
import { BookSourceRulePolicy } from './BookSourceRulePolicy';
import { JsonContentProjector } from './JsonContentProjector';
import { SourceComicPageProjector } from './SourceComicPageProjector';
import { SafeRemoteCoverCache } from '../../shared/network/SafeRemoteCoverCache';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import { SourceHttpRequestLease, sourceHttpRequestCoordinator } from './SourceHttpRequestCoordinator';
import { SourceHttpRetryPolicy } from './SourceHttpRetryPolicy';
import { SourceHttpResponsePolicy } from './SourceHttpResponsePolicy';
import { SourceHttpRequestHeaderPolicy } from './SourceHttpRequestHeaderPolicy';

interface SourceResponse {
  url: string;
  body: string;
  payload?: Object;
}

interface SourceRequest {
  url: string;
  method: http.RequestMethod;
  body: string;
  headers: Record<string, string>;
}

/**
 * 执行无需JavaScript的JSON书源详情、目录和正文规则。
 */
export class BookSourceReaderEngine {
  private readonly htmlParser = new BookSourceHtmlParser();
  private readonly jsonPath = new DeclarativeJsonPath();
  private readonly rulePolicy = new BookSourceRulePolicy();
  private readonly contentProjector = new JsonContentProjector();
  private readonly comicPageProjector = new SourceComicPageProjector();
  private readonly imageCache: SafeRemoteCoverCache;
  private readonly requestConfig = new DeclarativeRequestConfig();
  private readonly urlResolver = new SourceUrlResolver();
  private readonly catalogNormalizer = new SourceCatalogNormalizer();
  private readonly urlPolicy = new SourceUrlPolicy();
  private readonly credentialStore: BookSourceCredentialStore;
  private readonly retryPolicy = new SourceHttpRetryPolicy();
  private readonly responsePolicy = new SourceHttpResponsePolicy();
  private readonly headerPolicy = new SourceHttpRequestHeaderPolicy();

  constructor(imageCache: SafeRemoteCoverCache, credentialStore: BookSourceCredentialStore) {
    this.imageCache = imageCache;
    this.credentialStore = credentialStore;
  }

  /**
   * 从图书详情地址解析章节目录，但不预下载全部正文。
   */
  async loadCatalog(source: BookSource, bookUrl: string,
    cancellation?: DownloadCancellationToken): Promise<ReaderDocument> {
    this.requireDeclarativeRules(source);
    const detail = await this.fetch(source, bookUrl, cancellation);
    const tocRule = detail.payload === undefined ?
      this.rulePolicy.htmlRule(source.ruleBookInfo, 'tocUrl') :
      this.rulePolicy.jsonRule(source.ruleBookInfo, 'tocUrl');
    const rawTocUrl = tocRule.length === 0 ? '' : this.textAtResponse(detail, source.ruleBookInfo, 'tocUrl');
    const toc = rawTocUrl.length === 0 ? detail : await this.fetch(source,
      this.urlResolver.resolveRequest(detail.url, rawTocUrl), cancellation);
    const candidates: SourceChapterCandidate[] = [];
    if (toc.payload !== undefined) {
      const list = this.valueAt(toc.payload, this.rulePolicy.jsonRule(source.ruleToc, 'chapterList'));
      const values = Array.isArray(list) ? list as Object[] : [];
      values.slice(0, 20000).forEach((item: Object) => {
        candidates.push({
          title: this.jsonTextAt(item, source.ruleToc, 'chapterName'),
          url: this.jsonTextAt(item, source.ruleToc, 'chapterUrl')
        });
      });
    } else {
      const fragments = this.htmlParser.selectList(toc.body,
        this.rulePolicy.htmlRule(source.ruleToc, 'chapterList'));
      fragments.slice(0, 20000).forEach((item: string) => {
        candidates.push({
          title: this.htmlTextAt(item, source.ruleToc, 'chapterName'),
          url: this.htmlTextAt(item, source.ruleToc, 'chapterUrl')
        });
      });
    }
    const chapters: ReaderChapter[] = this.catalogNormalizer.normalize(toc.url, candidates)
      .map((value: NormalizedSourceChapter): ReaderChapter => ({
        title: value.title,
        content: '',
        resourceUri: value.requestUrl
      }));
    if (chapters.length === 0) throw new Error('书源目录规则没有返回章节');
    return { kind: 'text', chapters: chapters, imageUris: [] };
  }

  /**
   * 按需读取单个章节正文。
   */
  async loadChapter(source: BookSource, chapter: ReaderChapter,
    cancellation?: DownloadCancellationToken): Promise<SourceChapterContent> {
    if (chapter.resourceUri === undefined || chapter.resourceUri.length === 0) throw new Error('章节地址为空');
    this.requireDeclarativeRules(source);
    const response = await this.fetch(source, chapter.resourceUri, cancellation);
    if (source.bookSourceType === 2) {
      const rawPages: Object | undefined = response.payload === undefined ?
        this.htmlParser.selectTexts(response.body, this.rulePolicy.htmlRule(source.ruleContent, 'content'))
          .map((value: string) => this.rulePolicy.transform(source.ruleContent, 'content', value)) :
        this.transformValue(source.ruleContent, 'content',
          this.valueAt(response.payload, this.rulePolicy.jsonRule(source.ruleContent, 'content')));
      const candidates = this.comicPageProjector.project(response.url, rawPages);
      if (candidates.length === 0) throw new Error('书源漫画规则没有返回图片页');
      return { kind: 'comic', text: '', imageUris: [], remoteImageUrls: candidates };
    }
    const content = response.payload === undefined ?
      this.contentProjector.multiline(this.htmlParser.selectTexts(response.body,
        this.rulePolicy.htmlRule(source.ruleContent, 'content')).map((value: string) =>
        this.rulePolicy.transform(source.ruleContent, 'content', value))) :
      this.contentProjector.multiline(this.transformValue(source.ruleContent, 'content',
        this.valueAt(response.payload, this.rulePolicy.jsonRule(source.ruleContent, 'content'))));
    if (content.length === 0) throw new Error('书源正文规则没有返回内容');
    return {
      kind: 'text',
      text: response.payload === undefined ? this.htmlParser.htmlToText(content) : this.htmlToText(content),
      imageUris: [],
      remoteImageUrls: []
    };
  }

  /**
   * 以最多4路并发把指定漫画页候选缓存为本地URI。
   */
  async cacheComicPages(context: common.UIAbilityContext, source: BookSource, urls: string[],
    cancellation?: DownloadCancellationToken): Promise<string[]> {
    if (urls.length > 8) throw new Error('单次漫画预取超过8页');
    const imageUris: string[] = [];
    for (let offset = 0; offset < urls.length; offset += 4) {
      const tasks: Promise<string>[] = [];
      if (cancellation?.isCancelled()) throw new Error('书源漫画页加载已取消');
      urls.slice(offset, offset + 4).forEach((url: string) =>
        tasks.push(this.cacheComicPage(context, source, url, cancellation)));
      const cached = await Promise.all(tasks);
      cached.forEach((uri: string) => imageUris.push(uri));
    }
    return imageUris;
  }

  private async cacheComicPage(context: common.UIAbilityContext, source: BookSource, url: string,
    cancellation?: DownloadCancellationToken): Promise<string> {
    const credentialRevision = this.credentialStore.currentScopeRevision();
    const headers = this.headerPolicy.parse(source.header);
    const sensitiveHeaders = await this.credentialStore.headersFor(source.bookSourceUrl, url);
    if (!this.credentialStore.scopeRevisionCurrent(credentialRevision)) throw new Error('书源账户作用域已切换');
    Object.keys(sensitiveHeaders).forEach((key: string) => headers[key] = sensitiveHeaders[key]);
    return this.imageCache.cacheReaderPage(context, url, headers, cancellation);
  }

  private requireDeclarativeRules(source: BookSource): void {
    // 仅校验阅读链路实际使用的字段；书源中的wordCount等可选脚本元数据不会被执行。
    this.rulePolicy.jsonRule(source.ruleBookInfo, 'tocUrl');
    this.rulePolicy.jsonRule(source.ruleToc, 'chapterList');
    this.rulePolicy.jsonRule(source.ruleToc, 'chapterName');
    this.rulePolicy.jsonRule(source.ruleToc, 'chapterUrl');
    this.rulePolicy.jsonRule(source.ruleContent, 'content');
  }

  private async fetch(source: BookSource, value: string,
    cancellation?: DownloadCancellationToken): Promise<SourceResponse> {
    if (cancellation?.isCancelled()) throw new Error('书源章节加载已取消');
    const spec = this.parseRequest(source.bookSourceUrl, value);
    await this.urlPolicy.assertSafe(spec.url);
    if (cancellation?.isCancelled()) throw new Error('书源章节加载已取消');
    const lease: SourceHttpRequestLease = await sourceHttpRequestCoordinator.acquire(source.bookSourceUrl,
      cancellation);
    try {
      const headers = this.headerPolicy.merge(this.headerPolicy.parse(source.header), spec.headers);
      const credentialRevision = this.credentialStore.currentScopeRevision();
      const sensitiveHeaders = await this.credentialStore.headersFor(source.bookSourceUrl, spec.url);
      if (cancellation?.isCancelled()) throw new Error('书源章节加载已取消');
      if (!this.credentialStore.scopeRevisionCurrent(credentialRevision)) throw new Error('书源账户作用域已切换');
      Object.keys(sensitiveHeaders).forEach((key: string) => headers[key] = sensitiveHeaders[key]);
      if (spec.method === http.RequestMethod.POST && !Object.keys(headers).some((key: string) =>
        key.toLowerCase() === 'content-type')) {
        headers['Content-Type'] = 'application/json';
      }
      const text = await this.requestText(source, spec, headers, credentialRevision, cancellation);
      const bodyFailure = this.responsePolicy.bodyError(text);
      if (bodyFailure.length > 0) throw new Error(bodyFailure);
      const trimmed = text.trim();
      if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
        return { url: spec.url, body: text, payload: JSON.parse(trimmed) as Object };
      }
      return { url: spec.url, body: text };
    } catch (error) {
      if (cancellation?.isCancelled()) throw new Error('书源章节加载已取消');
      if (error instanceof Error) throw error;
      throw new Error('书源网络请求失败');
    } finally {
      sourceHttpRequestCoordinator.release(lease);
    }
  }

  private async requestText(source: BookSource, spec: SourceRequest, headers: Record<string, string>,
    credentialRevision: number, cancellation?: DownloadCancellationToken): Promise<string> {
    for (let attempt = 0; attempt < 2; attempt++) {
      if (cancellation?.isCancelled()) throw new Error('书源章节加载已取消');
      const request = http.createHttp();
      let redirectBlocked = false;
      let headerFailure = '';
      let responseStatus: number | undefined = undefined;
      const cancelHandler = (): void => request.destroy();
      try {
        cancellation?.bind(cancelHandler);
        request.on('headersReceive', (receivedHeaders: Object) => {
          headerFailure = this.responsePolicy.headerError(receivedHeaders);
          if (headerFailure.length > 0) {
            redirectBlocked = headerFailure.includes('重定向');
            request.destroy();
          }
        });
        const response = await request.request(spec.url, {
          method: spec.method,
          header: headers,
          extraData: spec.method === http.RequestMethod.POST ? spec.body : undefined,
          expectDataType: http.HttpDataType.STRING,
          connectTimeout: 10000,
          readTimeout: 30000
        });
        if (cancellation?.isCancelled()) throw new Error('书源章节加载已取消');
        if (headerFailure.length > 0) throw new Error(headerFailure);
        if (redirectBlocked) throw new Error('书源重定向已阻止，请配置最终公开地址');
        responseStatus = response.responseCode;
        if (response.responseCode >= 200 && response.responseCode < 300) {
          this.credentialStore.captureSessionCookies(source.bookSourceUrl, spec.url, response.cookies,
            credentialRevision);
          return typeof response.result === 'string' ? response.result : `${response.result}`;
        }
        if (!this.retryPolicy.shouldRetry(spec.method === http.RequestMethod.GET, attempt, response.responseCode)) {
          throw new Error(`书源请求返回HTTP ${response.responseCode}`);
        }
      } catch (error) {
        if (cancellation?.isCancelled()) throw new Error('书源章节加载已取消');
        if (headerFailure.length > 0) throw new Error(headerFailure);
        if (redirectBlocked) throw new Error('书源重定向已阻止，请配置最终公开地址');
        if (!this.retryPolicy.shouldRetry(spec.method === http.RequestMethod.GET, attempt, responseStatus)) {
          if (error instanceof Error) throw error;
          throw new Error('书源网络请求失败');
        }
      } finally {
        cancellation?.unbind(cancelHandler);
        request.off('headersReceive');
        request.destroy();
      }
      await this.retryPolicy.wait(cancellation);
    }
    throw new Error('书源网络请求失败');
  }

  private textAtResponse(response: SourceResponse, rules: Record<string, Object>, key: string): string {
    const value = response.payload === undefined ?
      this.htmlParser.selectText(response.body, this.rulePolicy.htmlRule(rules, key)) :
      this.textAt(response.payload, this.rulePolicy.jsonRule(rules, key));
    return this.rulePolicy.transform(rules, key, value);
  }

  private parseRequest(baseUrl: string, value: string): SourceRequest {
    const marker = value.indexOf(',{');
    const url = this.absoluteUrl(baseUrl, marker < 0 ? value : value.substring(0, marker));
    if (marker < 0) return { url: url, method: http.RequestMethod.GET, body: '', headers: {} };
    const config = this.requestConfig.parse(value.substring(marker + 1));
    const method = config.method === 'POST' ?
      http.RequestMethod.POST : http.RequestMethod.GET;
    return { url: url, method: method, body: config.body, headers: config.headers };
  }

  private valueAt(root: Object, rule: string): Object | undefined {
    return this.jsonPath.evaluate(root, rule);
  }

  private textAt(root: Object, rule: string): string {
    if (rule.length === 0) return '';
    const value = this.valueAt(root, rule);
    if (value === undefined || value === null || typeof value === 'object') return '';
    return `${value}`.trim();
  }

  private jsonTextAt(root: Object, rules: Record<string, Object>, key: string): string {
    return this.rulePolicy.transform(rules, key, this.textAt(root, this.rulePolicy.jsonRule(rules, key)));
  }

  private htmlTextAt(html: string, rules: Record<string, Object>, key: string): string {
    const value = this.htmlParser.selectText(html, this.rulePolicy.htmlRule(rules, key));
    return this.rulePolicy.transform(rules, key, value);
  }

  private transformValue(rules: Record<string, Object>, key: string, value: Object | undefined): Object | undefined {
    if (Array.isArray(value)) {
      return value.map((item: Object): Object => typeof item === 'string' || typeof item === 'number' ||
        typeof item === 'boolean' ? this.rulePolicy.transform(rules, key, `${item}`) : item);
    }
    if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
      return this.rulePolicy.transform(rules, key, `${value}`);
    }
    return value;
  }

  private absoluteUrl(baseUrl: string, value: string): string {
    return this.urlResolver.resolve(baseUrl, value);
  }

  private htmlToText(value: string): string {
    return value.replace(/<script[\s\S]*?<\/script>/gi, '').replace(/<style[\s\S]*?<\/style>/gi, '')
      .replace(/<(br|\/p|\/div|\/h[1-6]|\/li)>/gi, '\n').replace(/<[^>]+>/g, '')
      .replace(/&nbsp;/gi, ' ').replace(/&amp;/gi, '&').replace(/&lt;/gi, '<').replace(/&gt;/gi, '>')
      .replace(/&quot;/gi, '"').replace(/&#39;/gi, "'").replace(/\r/g, '').replace(/\n{3,}/g, '\n\n').trim();
  }
}
