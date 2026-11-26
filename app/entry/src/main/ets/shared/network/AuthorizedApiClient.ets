import { StrictHomeHttpClient } from '../../features/connectivity/StrictHomeHttpClient';
import { AuthApi } from '../../features/auth/AuthApi';
import { HomeChannelOrigin } from '../../features/connectivity/HomeChannelOrigin';
import { http } from '@kit.NetworkKit';
import { BusinessError } from '@ohos.base';
import fs from '@ohos.file.fs';
import util from '@ohos.util';
import { AuthSessionManager } from '../../features/auth/AuthSessionManager';
import { ApiEnvelope, ApiResponse } from './AuthorizedApiModels';
import { AuthorizedApiPolicy } from './AuthorizedApiPolicy';
import { DownloadCancellationToken } from './DownloadCancellationToken';
import { DownloadStreamIntegrityPolicy } from './DownloadStreamIntegrityPolicy';
import { BusinessResponseError, BusinessTransportError } from './BusinessTransportError';

interface StreamDownloadResult {
  status: number;
  bytes: number;
}

interface RequestRoute {
  sessionRevision: number;
  endpointRevision: number;
  effectiveBaseUrl: string;
  wasActive: boolean;
  usedChannel: boolean;
}

/**
 * 自动附加令牌并在未授权时刷新一次的 API 客户端。
 */
export class AuthorizedApiClient {
  private static readonly MAX_DOWNLOAD_BYTES: number = 2 * 1024 * 1024 * 1024;
  /** 视频素材上传与成片下载的硬上限，与视频服务实测口径一致。 */
  private static readonly MAX_VIDEO_BYTES: number = 200 * 1024 * 1024;
  private readonly baseUrl: string;
  private readonly authManager: AuthSessionManager;
  private readonly allowPublicFallback: boolean;
  private readonly jsonReadTimeout: number;
  private readonly pinEndpoint: boolean;
  private readonly policy: AuthorizedApiPolicy = new AuthorizedApiPolicy();
  private readonly downloadIntegrityPolicy: DownloadStreamIntegrityPolicy = new DownloadStreamIntegrityPolicy();

  constructor(baseUrl: string, authManager: AuthSessionManager, allowPublicFallback: boolean = true,
    jsonReadTimeout: number = 20000, pinEndpoint: boolean = false) {
    this.baseUrl = authManager.normalizeBusinessBaseUrl(baseUrl);
    this.authManager = authManager;
    this.allowPublicFallback = allowPublicFallback;
    this.jsonReadTimeout = Math.max(1000, Math.min(30000, jsonReadTimeout));
    this.pinEndpoint = pinEndpoint;
  }

  /**
   * 发起 JSON GET 请求。
   */
  async get(path: string, cancellation?: DownloadCancellationToken): Promise<ApiEnvelope> {
    return this.execute(path, http.RequestMethod.GET, undefined, cancellation);
  }

  /**
   * 发起无请求体的 JSON POST 请求。
   */
  async post(path: string, cancellation?: DownloadCancellationToken): Promise<ApiEnvelope> {
    return this.execute(path, http.RequestMethod.POST, undefined, cancellation);
  }

  /**
   * 发起带 JSON 请求体的 POST 请求。
   */
  async postJson(path: string, body: Object,
    cancellation?: DownloadCancellationToken): Promise<ApiEnvelope> {
    return this.execute(path, http.RequestMethod.POST, JSON.stringify(body), cancellation);
  }

  /**
   * 发起带 JSON 请求体的 PUT 请求。
   */
  async putJson(path: string, body: Object, cancellation?: DownloadCancellationToken): Promise<ApiEnvelope> {
    return this.execute(path, http.RequestMethod.PUT, JSON.stringify(body), cancellation);
  }

  /**
   * 以原始字节发起受认证的 POST 上传，未授权时刷新并完整重试一次。
   * 上传不接受压缩、转码或 base64，服务端按原始字节重新实测素材。
   */
  async postBinary(path: string, bytes: ArrayBuffer, contentType: string): Promise<ApiEnvelope> {
    if (!Number.isSafeInteger(bytes.byteLength) || bytes.byteLength <= 0 ||
      bytes.byteLength > AuthorizedApiClient.MAX_VIDEO_BYTES) {
      throw new Error('上传内容为空或超过 200 MB 限制');
    }
    // 请求头只允许固定素材类型，避免把任意头部带进认证网关。
    if (contentType !== 'image/png' && contentType !== 'image/jpeg' && contentType !== 'video/mp4') {
      throw new Error('上传内容类型不受支持');
    }
    const safePath = this.policy.path(path);
    let accessToken = await this.authManager.validAccessToken();
    let response = await this.binaryRequest(safePath, accessToken, bytes, contentType);
    if (response.status === 401) {
      accessToken = await this.authManager.forceRefresh(accessToken);
      response = await this.binaryRequest(safePath, accessToken, bytes, contentType);
    }
    if (response.status < 200 || response.status >= 300 || response.envelope.code !== '0000') {
      const message = typeof response.envelope.message === 'string' ? response.envelope.message : '';
      throw new Error(message.length > 0 ? message : `上传失败：HTTP ${response.status}`);
    }
    return response.envelope;
  }

  /**
   * 发起无请求体的 JSON DELETE 请求。
   */
  async delete(path: string, cancellation?: DownloadCancellationToken): Promise<ApiEnvelope> {
    return this.execute(path, http.RequestMethod.DELETE, undefined, cancellation);
  }

  /**
   * 将认证资源流式写入用户授权的目标 URI，未授权时刷新并完整重试一次。
   */
  async downloadToUri(path: string, targetUri: string,
    onProgress?: (receivedBytes: number, totalBytes: number) => void,
    cancellation?: DownloadCancellationToken): Promise<number> {
    const safePath = this.policy.path(path);
    this.throwIfCancelled(cancellation);
    let accessToken = await this.authManager.validAccessToken();
    let result = await this.streamToUri(safePath, targetUri, accessToken, onProgress,
      AuthorizedApiClient.MAX_DOWNLOAD_BYTES, '', cancellation);
    if (result.status === 401 && cancellation?.isCancelled() !== true) {
      if (onProgress !== undefined) onProgress(0, 0);
      accessToken = await this.authManager.forceRefresh(accessToken);
      result = await this.streamToUri(safePath, targetUri, accessToken, onProgress,
        AuthorizedApiClient.MAX_DOWNLOAD_BYTES, '', cancellation);
    }
    this.throwIfCancelled(cancellation);
    if (result.status < 200 || result.status >= 300) {
      throw new Error(`下载失败：HTTP ${result.status}`);
    }
    if (result.bytes === 0) throw new Error('远程文件为空');
    return result.bytes;
  }

  /**
   * 将受认证的成片流式写入目标 URI，未授权时刷新并完整重试一次；上限 200 MB。
   */
  async downloadVideoToUri(path: string, targetUri: string,
    onProgress?: (receivedBytes: number, totalBytes: number) => void): Promise<number> {
    const safePath = this.policy.path(path);
    let accessToken = await this.authManager.validAccessToken();
    let result = await this.streamToUri(safePath, targetUri, accessToken, onProgress,
      AuthorizedApiClient.MAX_VIDEO_BYTES, 'video/*');
    if (result.status === 401) {
      if (onProgress !== undefined) onProgress(0, 0);
      accessToken = await this.authManager.forceRefresh(accessToken);
      result = await this.streamToUri(safePath, targetUri, accessToken, onProgress,
        AuthorizedApiClient.MAX_VIDEO_BYTES, 'video/*');
    }
    if (result.status < 200 || result.status >= 300) throw new Error(`视频下载失败：HTTP ${result.status}`);
    if (result.bytes < 4) throw new Error('视频为空或格式无效');
    return result.bytes;
  }

  /** 将受认证的有界JPEG缩略图写入应用缓存，未授权时只完整重试一次。 */
  async downloadJpegThumbnailToUri(path: string, targetUri: string,
    cancellation?: DownloadCancellationToken): Promise<number> {
    return this.downloadJpegToUri(path, targetUri, 512 * 1024, cancellation);
  }

  /** 将受认证的系统分享JPEG写入应用缓存，最大12 MB。 */
  async downloadJpegShareToUri(path: string, targetUri: string,
    cancellation?: DownloadCancellationToken): Promise<number> {
    return this.downloadJpegToUri(path, targetUri, 12 * 1024 * 1024, cancellation);
  }

  /** 将受认证的常见图片原文件写入应用缓存，最大64 MB。 */
  async downloadImageToUri(path: string, targetUri: string,
    cancellation?: DownloadCancellationToken): Promise<number> {
    const safePath = this.policy.path(path);
    this.throwIfCancelled(cancellation);
    let accessToken = await this.authManager.validAccessToken();
    let result = await this.streamToUri(safePath, targetUri, accessToken, undefined,
      64 * 1024 * 1024, 'image/*', cancellation);
    if (result.status === 401 && cancellation?.isCancelled() !== true) {
      accessToken = await this.authManager.forceRefresh(accessToken);
      result = await this.streamToUri(safePath, targetUri, accessToken, undefined,
        64 * 1024 * 1024, 'image/*', cancellation);
    }
    this.throwIfCancelled(cancellation);
    if (result.status < 200 || result.status >= 300) throw new Error(`图片下载失败：HTTP ${result.status}`);
    if (result.bytes < 3) throw new Error('图片为空或格式无效');
    return result.bytes;
  }

  private async downloadJpegToUri(path: string, targetUri: string, maxBytes: number,
    cancellation?: DownloadCancellationToken): Promise<number> {
    const safePath = this.policy.path(path);
    this.throwIfCancelled(cancellation);
    let accessToken = await this.authManager.validAccessToken();
    let result = await this.streamToUri(safePath, targetUri, accessToken, undefined,
      maxBytes, 'image/jpeg', cancellation);
    if (result.status === 401 && cancellation?.isCancelled() !== true) {
      accessToken = await this.authManager.forceRefresh(accessToken);
      result = await this.streamToUri(safePath, targetUri, accessToken, undefined,
        maxBytes, 'image/jpeg', cancellation);
    }
    this.throwIfCancelled(cancellation);
    if (result.status < 200 || result.status >= 300) throw new Error(`缩略图下载失败：HTTP ${result.status}`);
    if (result.bytes < 3) throw new Error('缩略图为空或格式无效');
    return result.bytes;
  }

  private async streamToUri(path: string, targetUri: string,
    accessToken: string, onProgress?: (receivedBytes: number, totalBytes: number) => void,
    maxBytes: number = AuthorizedApiClient.MAX_DOWNLOAD_BYTES, expectedContentType: string = '',
    cancellation?: DownloadCancellationToken):
    Promise<StreamDownloadResult> {
    const active = this.businessBaseUrl();
    if (!this.pinEndpoint && this.allowPublicFallback && active === this.authManager.activeBusinessBaseUrl() &&
      /^\/api\/app\/v1\/media\/items\/[a-f0-9-]{36}\/thumbnail(?:\?edge=2048)?$/.test(path) &&
      active.startsWith('https://') && active !== this.authManager.publicBusinessBaseUrl()) {
      const routes = this.authManager.mediaRoutes;
      const epoch = routes.epoch;
      const publicUrl = this.authManager.publicBusinessBaseUrl();
      const selected = routes.choose(active, publicUrl, path.includes('2048') ? 256 * 1024 : 32 * 1024, true);
      const alternate = selected === active ? publicUrl : active;
      for (const origin of [selected, alternate]) {
        // 网络或会话变化后禁止旧请求尝试旧直连地址。
        this.throwIfCancelled(cancellation);
        if (routes.epoch !== epoch || active !== this.authManager.activeBusinessBaseUrl()) {
          throw new Error('Media network changed');
        }
        let first = -1;
        const start = Date.now();
        try {
          const result = await this.streamToUriOnce(origin, path, targetUri, accessToken, () => {
            if (first < 0) first = Date.now() - start;
          }, maxBytes, expectedContentType, cancellation, 8000);
          if (result.status === 200) routes.record(epoch, origin, result.bytes, first, Date.now() - start);
          return result;
        } catch (error) {
          if (error instanceof BusinessTransportError) routes.failed(epoch, origin);
          if (!(error instanceof BusinessTransportError) || origin === alternate) {
            throw error instanceof Error ? error : new Error('Media download failed');
          }
        }
      }
    }
    let baseUrl = this.businessBaseUrl();
    for (let attempt = 0; attempt < 3; attempt++) {
      const route = this.requestRoute(baseUrl);
      try {
        return await this.streamToUriOnce(baseUrl, path, targetUri, accessToken, onProgress,
          maxBytes, expectedContentType, cancellation);
      } catch (error) {
        const fallback = this.publicFallbackAfterFailure(baseUrl, error, true, route, cancellation);
        if (fallback.length === 0 || attempt === 2) throw error instanceof Error ? error : new Error('Endpoint request failed');
        if (onProgress !== undefined) onProgress(0, 0);
        baseUrl = fallback;
      }
    }
    throw new Error('Endpoint retry budget exhausted');
  }

  private async streamToUriOnce(baseUrl: string, path: string, targetUri: string,
    accessToken: string, onProgress?: (receivedBytes: number, totalBytes: number) => void,
    maxBytes: number = AuthorizedApiClient.MAX_DOWNLOAD_BYTES, expectedContentType: string = '',
    cancellation?: DownloadCancellationToken, idleLimit: number = 0): Promise<StreamDownloadResult> {
    const request = http.createHttp();
    let file: fs.File | undefined = undefined;
    let received = 0;
    let total: number | undefined = undefined;
    let streamError = '';
    let contentType = '';
    let committed = false;
    let networkPending = false;
    let responseReceived = false;
    let idleTimer: number = -1;
    let stalled: boolean = false;
    const resetIdleTimer = (): void => {
      if (idleLimit <= 0) return;
      if (idleTimer >= 0) clearTimeout(idleTimer);
      idleTimer = setTimeout((): void => {
        stalled = true;
        request.destroy();
      }, idleLimit);
    };
    let localFileError = false;
    const signature: number[] = [];
    const cancelHandler = (): void => request.destroy();
    try {
      cancellation?.bind(cancelHandler);
      file = fs.openSync(targetUri, fs.OpenMode.WRITE_ONLY | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      request.on('headersReceive', (headers: Object) => {
        resetIdleTimer();
        const values = headers as Record<string, Object>;
        Object.keys(values).forEach((key: string) => {
          const lower = key.toLowerCase();
          if (lower === 'location') {
            streamError = '认证下载不允许重定向';
            request.destroy();
          } else if (lower === 'content-length') {
            const declared = Number(values[key]);
            if (!Number.isSafeInteger(declared) || declared < 0 || declared > maxBytes) {
              streamError = '远程文件长度无效或超过下载上限';
              request.destroy();
            } else {
              total = declared;
            }
          } else if (lower === 'content-type') {
            contentType = `${values[key]}`.toLowerCase().split(';')[0].trim();
          } else if (lower === 'content-encoding' && `${values[key]}`.trim().toLowerCase() !== 'identity') {
            streamError = '认证下载不接受压缩传输';
            request.destroy();
          }
        });
      });
      request.on('dataReceive', (buffer: ArrayBuffer) => {
        if (file === undefined || streamError.length > 0) return;
        if (cancellation?.isCancelled() === true) {
          streamError = '下载已取消';
          request.destroy();
          return;
        }
        if (buffer.byteLength > 0) resetIdleTimer();
        received += buffer.byteLength;
        if (received > maxBytes) {
          streamError = '远程文件超过下载上限';
          request.destroy();
          return;
        }
        const bytes = new Uint8Array(buffer);
        for (let index = 0; index < bytes.length && signature.length < 12; index++) signature.push(bytes[index]);
        let written = 0;
        try {
          written = fs.writeSync(file.fd, buffer);
        } catch (_) {
          localFileError = true;
          streamError = '目标文件写入失败';
          request.destroy();
          return;
        }
        if (written !== buffer.byteLength) {
          localFileError = true;
          streamError = '目标文件写入不完整';
          request.destroy();
          return;
        }
        if (onProgress !== undefined) onProgress(received, total ?? 0);
      });
      networkPending = true;
      resetIdleTimer();
      const status = await request.requestInStream(`${baseUrl}${path}`, {
        addressFamily: AuthApi.directAddressFamily(baseUrl, this.authManager.publicBusinessBaseUrl()),
        usingProxy: HomeChannelOrigin.owns(baseUrl) ? false : undefined,
        method: http.RequestMethod.GET,
        // 二进制内容必须保持原始字节；代理层压缩会破坏长度与文件签名校验。
        header: { 'Authorization': `Bearer ${accessToken}`, 'Accept-Encoding': 'identity' },
        connectTimeout: idleLimit > 0 ? idleLimit : 10000,
        readTimeout: 120000
      });
      networkPending = false;
      if (stalled) throw new BusinessTransportError(baseUrl, 'Media read stalled');
      responseReceived = true;
      if (cancellation?.isCancelled() === true) streamError = '下载已取消';
      if (streamError.length > 0) throw new Error(streamError);
      const integrityError = this.downloadIntegrityPolicy.error(status, received, total, expectedContentType,
        contentType, signature);
      if (integrityError.length > 0 && status !== 401) throw new Error(integrityError);
      committed = integrityError.length === 0;
      const result: StreamDownloadResult = { status: status, bytes: received };
      return result;
    } catch (error) {
      this.throwIfCancelled(cancellation);
      if (streamError.length > 0) {
        if (localFileError) throw new Error(streamError);
        throw new BusinessResponseError(baseUrl, streamError);
      }
      if (stalled || networkPending) throw new BusinessTransportError(baseUrl,
        error instanceof Error ? error.message : '远程文件下载失败');
      if (responseReceived) throw new BusinessResponseError(baseUrl,
        error instanceof Error ? error.message : '远程文件下载失败');
      if (error instanceof Error) throw error;
      throw new Error('远程文件下载失败');
    } finally {
      if (idleTimer >= 0) clearTimeout(idleTimer);
      if (file !== undefined) {
        try {
          fs.closeSync(file);
        } catch (_) {
        }
      }
      request.off('headersReceive');
      request.off('dataReceive');
      request.destroy();
      cancellation?.unbind(cancelHandler);
      if (!committed) this.truncateTarget(targetUri);
    }
  }

  private throwIfCancelled(cancellation?: DownloadCancellationToken): void {
    if (cancellation?.isCancelled() === true) throw new Error('下载已取消');
  }

  private truncateTarget(targetUri: string): void {
    let file: fs.File | undefined = undefined;
    try {
      file = fs.openSync(targetUri, fs.OpenMode.WRITE_ONLY | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
    } catch (_) {
    } finally {
      if (file !== undefined) {
        try {
          fs.closeSync(file);
        } catch (_) {
        }
      }
    }
  }

  private async execute(path: string, method: http.RequestMethod, body?: string,
    cancellation?: DownloadCancellationToken): Promise<ApiEnvelope> {
    const safePath = this.policy.path(path);
    const bodyBytes = body === undefined ? 0 : new util.TextEncoder().encodeInto(body).length;
    this.policy.requestBody(body, bodyBytes, safePath);
    this.throwIfCancelled(cancellation);
    let accessToken = await this.authManager.validAccessToken();
    let response = await this.requestWithFallback(safePath, method, accessToken, body, cancellation);
    if (response.status === 401 && cancellation?.isCancelled() !== true) {
      accessToken = await this.authManager.forceRefresh(accessToken);
      response = await this.requestWithFallback(safePath, method, accessToken, body, cancellation);
    }
    this.throwIfCancelled(cancellation);
    if (response.status < 200 || response.status >= 300 || response.envelope.code !== '0000') {
      const message = typeof response.envelope.message === 'string' ? response.envelope.message : '';
      throw new Error(message.length > 0 ? message : `请求失败：HTTP ${response.status}`);
    }
    return response.envelope;
  }

  private async requestWithFallback(path: string, method: http.RequestMethod, accessToken: string,
    body?: string, cancellation?: DownloadCancellationToken): Promise<ApiResponse> {
    let baseUrl = this.businessBaseUrl();
    for (let attempt = 0; attempt < 3; attempt++) {
      const route = this.requestRoute(baseUrl);
      try {
        return await this.request(baseUrl, path, method, accessToken, body, cancellation);
      } catch (error) {
        const fallback = this.publicFallbackAfterFailure(baseUrl, error, method === http.RequestMethod.GET,
          route, cancellation);
        if (fallback.length === 0 || attempt === 2) throw error instanceof Error ? error : new Error('Endpoint request failed');
        baseUrl = fallback;
      }
    }
    throw new Error('Endpoint retry budget exhausted');
  }

  /** 固定公网探针保持原地址，普通客户端逐次解析当前会话路由。 */
  private businessBaseUrl(): string {
    return this.pinEndpoint ? this.baseUrl : this.authManager.resolveBusinessBaseUrl(this.baseUrl);
  }

  /** 请求开始时保存身份和实际传输来源，地址相同也不能跨网络代次汇报失败。 */
  private requestRoute(baseUrl: string): RequestRoute {
    const effective = this.authManager.resolveBusinessBaseUrl(this.authManager.activeBusinessBaseUrl());
    return { sessionRevision: this.authManager.sessionRevision(), endpointRevision: this.authManager.endpointRevision(),
      effectiveBaseUrl: effective, wasActive: baseUrl === effective, usedChannel: HomeChannelOrigin.owns(baseUrl) };
  }

  /** 失败先统一降级后续请求，仅原生网络失败的只读请求允许在总预算内逐级补发。 */
  private publicFallbackAfterFailure(baseUrl: string, error: Object, readOnly: boolean,
    route: RequestRoute, cancellation?: DownloadCancellationToken): string {
    if (cancellation?.isCancelled() === true) return '';
    if (route.sessionRevision !== this.authManager.sessionRevision() ||
      route.endpointRevision !== this.authManager.endpointRevision()) return '';
    const effective = this.authManager.resolveBusinessBaseUrl(this.authManager.activeBusinessBaseUrl());
    if (route.usedChannel && error instanceof BusinessTransportError) {
      // 首个故障已关闭旧通道时，其余并发读取仍可恢复；旧故障不能停掉刚建立的新通道。
      if (route.wasActive && HomeChannelOrigin.forService(this.authManager.publicBusinessBaseUrl()) === baseUrl &&
        HomeChannelOrigin.owns(baseUrl) && effective === route.effectiveBaseUrl) this.authManager.onHomeChannelUnavailable?.();
      return readOnly && !this.pinEndpoint && this.allowPublicFallback ?
        this.authManager.resolveBusinessBaseUrl(this.authManager.activeBusinessBaseUrl()) : '';
    }
    if (!route.wasActive) return '';
    if (effective !== route.effectiveBaseUrl) {
      // 旧直连读取失败时，优先恢复到同会话刚完成验证的应用通道，不降级新通道。
      return readOnly && !this.pinEndpoint && this.allowPublicFallback && error instanceof BusinessTransportError &&
        baseUrl.startsWith('https://') && baseUrl !== this.authManager.publicBusinessBaseUrl() &&
        HomeChannelOrigin.owns(effective) ? effective : '';
    }
    const publicBaseUrl = this.authManager.publicBusinessBaseUrl();
    const ownsFailure = this.authManager.activeBusinessBaseUrl() === baseUrl;
    if (error instanceof BusinessTransportError || error instanceof BusinessResponseError) {
      this.authManager.reportBusinessEndpointUnavailable(baseUrl, route.endpointRevision);
    }
    if (this.pinEndpoint || !this.allowPublicFallback || !readOnly || !(error instanceof BusinessTransportError) ||
      !ownsFailure || publicBaseUrl.length === 0 || baseUrl === publicBaseUrl ||
      this.authManager.activeBusinessBaseUrl() === baseUrl) return '';
    return this.authManager.activeBusinessBaseUrl();
  }

  private async request(baseUrl: string, path: string, method: http.RequestMethod, accessToken: string,
    body?: string, cancellation?: DownloadCancellationToken): Promise<ApiResponse> {
    if (method === http.RequestMethod.GET && baseUrl.startsWith('https://') &&
      baseUrl !== this.authManager.publicBusinessBaseUrl() &&
      (path === '/api/app/v1/connectivity/bootstrap' || path === '/api/app/v1/identity/me')) {
      return this.strictHomeJson(baseUrl, path, accessToken, cancellation);
    }
    const request = http.createHttp();
    const startedAt = Date.now();
    let responseStatus = 0;
    let responseError = '';
    let networkPending = false;
    const cancelHandler = (): void => request.destroy();
    try {
      cancellation?.bind(cancelHandler);
      request.on('headersReceive', (headers: Object) => {
        const values = headers as Record<string, Object>;
        Object.keys(values).forEach((key: string) => {
          const lower = key.toLowerCase();
          if (lower === 'location') {
            responseError = '认证API不允许重定向';
            request.destroy();
          } else if (lower === 'content-length') {
            const length = Number(values[key]);
            if (!Number.isSafeInteger(length) || length < 0 || length > 64 * 1024 * 1024) {
              responseError = '认证API响应长度无效或超过64 MB限制';
              request.destroy();
            }
          }
        });
      });
      networkPending = true;
      const response = await request.request(`${baseUrl}${path}`, {
        addressFamily: AuthApi.directAddressFamily(baseUrl, this.authManager.publicBusinessBaseUrl()),
        usingProxy: HomeChannelOrigin.owns(baseUrl) ? false : undefined,
        method: method,
        header: {
          'Accept': 'application/json',
          'Accept-Encoding': 'gzip',
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${accessToken}`
        },
        extraData: body,
        // 登录后的任务状态必须读取服务器最新结果，不能复用系统 HTTP 缓存。
        usingCache: false,
        connectTimeout: 10000,
        readTimeout: this.jsonReadTimeout
      });
      networkPending = false;
      responseStatus = response.responseCode;
      this.throwIfCancelled(cancellation);
      if (responseError.length > 0) throw new Error(responseError);
      // 204 没有正文，直接结束；部分真机对空文本编码返回 undefined，继续读取 length 会误报删除失败。
      if (response.responseCode === 204) {
        return { status: 204, envelope: this.policy.responseEnvelope(204, '', 0) };
      }
      const text = this.ResponseText(response.result);
      const bytes = new util.TextEncoder().encodeInto(text).length;
      return {
        status: response.responseCode,
        envelope: this.policy.responseEnvelope(response.responseCode, text, bytes)
      };
    } catch (error) {
      this.throwIfCancelled(cancellation);
      // 所有线路只记录固定类别、数字错误码和 API 模块，避免公网故障无法定位。
      const resource = path.split('?')[0].split('/').slice(0, path.startsWith('/api/app/v1/') ? 5 : 3).join('/');
      const route = HomeChannelOrigin.owns(baseUrl) ? 'CHANNEL' :
        baseUrl === this.authManager.publicBusinessBaseUrl() ? 'PUBLIC' : 'DIRECT';
      const nativeCode = (error as BusinessError)?.code;
      console.info(`Connectivity request failure: ${networkPending ? 'TRANSPORT' : 'RESPONSE'}, ` +
        `route=${route}, code=${Number.isFinite(nativeCode) ? nativeCode : 0}, ` +
        `status=${responseStatus}, elapsed=${Date.now() - startedAt}, resource=${resource}`);
      if (responseError.length > 0) throw new BusinessResponseError(baseUrl, responseError);
      const message = error instanceof Error ? error.message : '网络请求失败';
      if (networkPending) throw new BusinessTransportError(baseUrl, message);
      throw new BusinessResponseError(baseUrl, message);
    } finally {
      cancellation?.unbind(cancelHandler);
      request.off('headersReceive');
      request.destroy();
    }
  }

  /** 固定的家庭控制接口使用严格 TLS 通道读取，保留原有信封、刷新和取消语义。 */
  private async strictHomeJson(baseUrl: string, path: string, accessToken: string,
    cancellation?: DownloadCancellationToken): Promise<ApiResponse> {
    const client = new StrictHomeHttpClient();
    const cancelHandler = (): void => client.close();
    let networkPending = true;
    try {
      this.throwIfCancelled(cancellation);
      cancellation?.bind(cancelHandler);
      const response = path === '/api/app/v1/connectivity/bootstrap' ?
        await client.bootstrap(baseUrl, accessToken, this.jsonReadTimeout) :
        await client.identity(baseUrl, accessToken, this.jsonReadTimeout);
      networkPending = false;
      this.throwIfCancelled(cancellation);
      const bytes = new util.TextEncoder().encodeInto(response.text).length;
      return { status: response.status, envelope: this.policy.responseEnvelope(response.status, response.text, bytes) };
    } catch (error) {
      this.throwIfCancelled(cancellation);
      if (error instanceof BusinessResponseError) throw error;
      const message = error instanceof Error ? error.message : 'Home control request failed';
      if (networkPending) throw new BusinessTransportError(baseUrl, message);
      throw new BusinessResponseError(baseUrl, message);
    } finally {
      cancellation?.unbind(cancelHandler);
      client.close();
    }
  }

  /** 原始字节上传复用与 JSON 请求相同的信封解析、重定向与长度校验。 */
  private async binaryRequest(path: string, accessToken: string, bytes: ArrayBuffer,
    contentType: string): Promise<ApiResponse> {
    const baseUrl = this.businessBaseUrl();
    const route = this.requestRoute(baseUrl);
    const request = http.createHttp();
    let responseError = '';
    let networkPending = false;
    try {
      request.on('headersReceive', (headers: Object) => {
        const values = headers as Record<string, Object>;
        Object.keys(values).forEach((key: string) => {
          const lower = key.toLowerCase();
          if (lower === 'location') {
            responseError = '认证API不允许重定向';
            request.destroy();
          } else if (lower === 'content-length') {
            const length = Number(values[key]);
            if (!Number.isSafeInteger(length) || length < 0 || length > 64 * 1024 * 1024) {
              responseError = '认证API响应长度无效或超过64 MB限制';
              request.destroy();
            }
          }
        });
      });
      networkPending = true;
      const response = await request.request(`${baseUrl}${path}`, {
        addressFamily: AuthApi.directAddressFamily(baseUrl, this.authManager.publicBusinessBaseUrl()),
        usingProxy: HomeChannelOrigin.owns(baseUrl) ? false : undefined,
        method: http.RequestMethod.POST,
        header: {
          'Accept': 'application/json',
          'Content-Type': contentType,
          'Authorization': `Bearer ${accessToken}`
        },
        extraData: bytes,
        // 上传必须保持原始字节，缓存或压缩都会破坏服务端的实测结果。
        usingCache: false,
        connectTimeout: 10000,
        // 服务端需要对大素材实测时长与尺寸，读取超时放宽到 120 秒。
        readTimeout: 120000
      });
      networkPending = false;
      if (responseError.length > 0) throw new Error(responseError);
      // 无正文成功响应不进入原生文本解码和编码流程。
      if (response.responseCode === 204) {
        return { status: 204, envelope: this.policy.responseEnvelope(204, '', 0) };
      }
      const text = this.ResponseText(response.result);
      const size = new util.TextEncoder().encodeInto(text).length;
      return {
        status: response.responseCode,
        envelope: this.policy.responseEnvelope(response.responseCode, text, size)
      };
    } catch (error) {
      // 上传失败只改变后续路由，不重放可能已被服务端接收的原始字节。
      const message = responseError || (error instanceof Error ? error.message : '上传请求失败');
      if (responseError.length === 0 && networkPending) {
        const failure = new BusinessTransportError(baseUrl, message);
        this.publicFallbackAfterFailure(baseUrl, failure, false, route);
        throw failure;
      }
      const failure = new BusinessResponseError(baseUrl, message);
      this.publicFallbackAfterFailure(baseUrl, failure, false, route);
      throw failure;
    } finally {
      request.off('headersReceive');
      request.destroy();
    }
  }

  /** 统一不同HarmonyOS版本返回的字符串、JSON对象和二进制响应。 */
  private ResponseText(result: string | Object | ArrayBuffer | undefined | null): string {
    // 部分真机的 204 响应没有 result，必须在编码与长度校验前归一为空字符串。
    if (result instanceof ArrayBuffer) {
      return util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(new Uint8Array(result));
    }
    return this.policy.responseText(result);
  }
}
