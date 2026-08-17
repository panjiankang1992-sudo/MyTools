import { http } from '@kit.NetworkKit';
import { SourceUrlPolicy } from '../../shared/network/SourceUrlPolicy';
import { OpdsBookEntry, OpdsCatalog, OpdsNavigationEntry } from './OpdsModels';
import { OpdsAtomCatalogNormalizer } from './OpdsAtomCatalogNormalizer';
import { OpdsJsonCatalogNormalizer } from './OpdsJsonCatalogNormalizer';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import { OpdsNetworkResponsePolicy } from './OpdsNetworkResponsePolicy';
import { SourceHttpRetryPolicy } from './SourceHttpRetryPolicy';
import { OpdsRequestInputPolicy } from './OpdsRequestInputPolicy';

export { OpdsBookEntry, OpdsCatalog, OpdsNavigationEntry } from './OpdsModels';

/**
 * 读取公开或用户已授权的HTTPS OPDS 1 Atom与OPDS 2 JSON书库。
 */
export class OpdsProvider {
  private readonly urlPolicy: SourceUrlPolicy = new SourceUrlPolicy();
  private readonly atomNormalizer: OpdsAtomCatalogNormalizer = new OpdsAtomCatalogNormalizer();
  private readonly jsonNormalizer: OpdsJsonCatalogNormalizer = new OpdsJsonCatalogNormalizer();
  private readonly responsePolicy: OpdsNetworkResponsePolicy = new OpdsNetworkResponsePolicy();
  private readonly retryPolicy: SourceHttpRetryPolicy = new SourceHttpRetryPolicy();
  private readonly inputPolicy: OpdsRequestInputPolicy = new OpdsRequestInputPolicy();

  /**
   * 加载指定OPDS目录，不预下载图书正文。
   */
  async loadCatalog(url: string, authorization?: string,
    cancellation?: DownloadCancellationToken): Promise<OpdsCatalog> {
    if (cancellation?.isCancelled()) throw new Error('OPDS目录加载已取消');
    const requestedUrl = this.inputPolicy.url(url);
    const requestedAuthorization = this.inputPolicy.authorization(authorization);
    await this.urlPolicy.assertSafe(requestedUrl);
    if (cancellation?.isCancelled()) throw new Error('OPDS目录加载已取消');
    try {
      const headers: Record<string, string> = {
        'Accept': 'application/opds+json, application/atom+xml, application/xml;q=0.9'
      };
      if (requestedAuthorization !== undefined) headers.Authorization = requestedAuthorization;
      const text = await this.requestText(requestedUrl, headers, cancellation);
      const bodyError = this.responsePolicy.bodyError(text);
      if (bodyError.length > 0) throw new Error(bodyError);
      const catalog = text.trimStart().startsWith('{') ? this.jsonNormalizer.normalize(requestedUrl, text) :
        this.atomNormalizer.normalize(requestedUrl, text);
      await this.sanitizeCoverUrls(catalog.books, cancellation);
      return catalog;
    } catch (error) {
      if (cancellation?.isCancelled()) throw new Error('OPDS目录加载已取消');
      if (error instanceof Error) throw error;
      throw new Error('无法读取OPDS目录');
    }
  }

  private async requestText(url: string, headers: Record<string, string>,
    cancellation?: DownloadCancellationToken): Promise<string> {
    for (let attempt = 0; attempt < 2; attempt++) {
      if (cancellation?.isCancelled()) throw new Error('OPDS目录加载已取消');
      const request = http.createHttp();
      let responseError = '';
      let responseStatus: number | undefined = undefined;
      const cancelHandler = (): void => request.destroy();
      try {
        cancellation?.bind(cancelHandler);
        request.on('headersReceive', (receivedHeaders: Object) => {
          responseError = this.responsePolicy.headerError(receivedHeaders);
          if (responseError.length > 0) request.destroy();
        });
        const response = await request.request(url, {
          method: http.RequestMethod.GET,
          header: headers,
          connectTimeout: 10000,
          readTimeout: 20000
        });
        if (cancellation?.isCancelled()) throw new Error('OPDS目录加载已取消');
        if (responseError.length > 0) throw new Error(responseError);
        responseStatus = response.responseCode;
        if (response.responseCode >= 200 && response.responseCode < 300) {
          return typeof response.result === 'string' ? response.result : `${response.result}`;
        }
        if (!this.retryPolicy.shouldRetry(true, attempt, response.responseCode)) {
          throw new Error(`OPDS目录请求失败（${response.responseCode}）`);
        }
      } catch (error) {
        if (cancellation?.isCancelled()) throw new Error('OPDS目录加载已取消');
        if (responseError.length > 0) throw new Error(responseError);
        if (!this.retryPolicy.shouldRetry(true, attempt, responseStatus)) {
          if (error instanceof Error) throw error;
          throw new Error('无法读取OPDS目录');
        }
      } finally {
        cancellation?.unbind(cancelHandler);
        request.off('headersReceive');
        request.destroy();
      }
      await this.retryPolicy.wait(cancellation);
    }
    throw new Error('无法读取OPDS目录');
  }

  private async sanitizeCoverUrls(books: OpdsBookEntry[], cancellation?: DownloadCancellationToken): Promise<void> {
    const decisions = new Map<string, boolean>();
    for (let index = 0; index < books.length; index++) {
      if (cancellation?.isCancelled()) throw new Error('OPDS目录加载已取消');
      const url = books[index].coverUrl;
      if (url.length === 0) continue;
      let safe = decisions.get(url);
      if (safe === undefined) {
        try {
          await this.urlPolicy.assertSafe(url);
          if (cancellation?.isCancelled()) throw new Error('OPDS目录加载已取消');
          safe = true;
        } catch (_) {
          safe = false;
        }
        decisions.set(url, safe);
      }
      if (!safe) books[index].coverUrl = '';
    }
  }

}
