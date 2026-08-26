import { http } from '@kit.NetworkKit';
import fs from '@ohos.file.fs';
import util from '@ohos.util';
import { AuthSessionManager } from '../../features/auth/AuthSessionManager';
import { ApiEnvelope, ApiResponse } from './AuthorizedApiModels';
import { AuthorizedApiPolicy } from './AuthorizedApiPolicy';
import { DownloadCancellationToken } from './DownloadCancellationToken';
import { DownloadStreamIntegrityPolicy } from './DownloadStreamIntegrityPolicy';

interface StreamDownloadResult {
  status: number;
  bytes: number;
}

/**
 * 自动附加令牌并在未授权时刷新一次的 API 客户端。
 */
export class AuthorizedApiClient {
  private static readonly MAX_DOWNLOAD_BYTES: number = 2 * 1024 * 1024 * 1024;
  private readonly baseUrl: string;
  private readonly authManager: AuthSessionManager;
  private readonly allowPublicFallback: boolean;
  private readonly policy: AuthorizedApiPolicy = new AuthorizedApiPolicy();
  private readonly downloadIntegrityPolicy: DownloadStreamIntegrityPolicy = new DownloadStreamIntegrityPolicy();

  constructor(baseUrl: string, authManager: AuthSessionManager, allowPublicFallback: boolean = true) {
    this.baseUrl = authManager.normalizeBusinessBaseUrl(baseUrl);
    this.authManager = authManager;
    this.allowPublicFallback = allowPublicFallback;
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
  async putJson(path: string, body: Object): Promise<ApiEnvelope> {
    return this.execute(path, http.RequestMethod.PUT, JSON.stringify(body));
  }

  /**
   * 发起无请求体的 JSON DELETE 请求。
   */
  async delete(path: string): Promise<ApiEnvelope> {
    return this.execute(path, http.RequestMethod.DELETE);
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
    const request = http.createHttp();
    let file: fs.File | undefined = undefined;
    let received = 0;
    let total: number | undefined = undefined;
    let streamError = '';
    let contentType = '';
    let committed = false;
    const signature: number[] = [];
    const cancelHandler = (): void => request.destroy();
    try {
      cancellation?.bind(cancelHandler);
      file = fs.openSync(targetUri, fs.OpenMode.WRITE_ONLY | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      request.on('headersReceive', (headers: Object) => {
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
        received += buffer.byteLength;
        if (received > maxBytes) {
          streamError = '远程文件超过下载上限';
          request.destroy();
          return;
        }
        const bytes = new Uint8Array(buffer);
        for (let index = 0; index < bytes.length && signature.length < 12; index++) signature.push(bytes[index]);
        const written = fs.writeSync(file.fd, buffer);
        if (written !== buffer.byteLength) {
          streamError = '目标文件写入不完整';
          request.destroy();
          return;
        }
        if (onProgress !== undefined) onProgress(received, total ?? 0);
      });
      const status = await request.requestInStream(`${this.baseUrl}${path}`, {
        method: http.RequestMethod.GET,
        // 二进制内容必须保持原始字节；代理层压缩会破坏长度与文件签名校验。
        header: { 'Authorization': `Bearer ${accessToken}`, 'Accept-Encoding': 'identity' },
        connectTimeout: 10000,
        readTimeout: 120000
      });
      if (cancellation?.isCancelled() === true) streamError = '下载已取消';
      if (streamError.length > 0) throw new Error(streamError);
      const integrityError = this.downloadIntegrityPolicy.error(status, received, total, expectedContentType,
        contentType, signature);
      if (integrityError.length > 0 && status !== 401) throw new Error(integrityError);
      committed = integrityError.length === 0;
      const result: StreamDownloadResult = { status: status, bytes: received };
      return result;
    } catch (error) {
      if (streamError.length > 0) throw new Error(streamError);
      if (error instanceof Error) throw error;
      throw new Error('远程文件下载失败');
    } finally {
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
    this.policy.requestBody(body, bodyBytes);
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
    try {
      return await this.request(this.baseUrl, path, method, accessToken, body, cancellation);
    } catch (error) {
      const publicBaseUrl = this.authManager.publicBusinessBaseUrl();
      if (!this.allowPublicFallback || !this.baseUrl.startsWith('http://') || publicBaseUrl.length === 0 ||
        cancellation?.isCancelled() === true) {
        throw error instanceof Error ? error : new Error('局域网业务请求失败');
      }
      this.authManager.reportBusinessEndpointUnavailable(this.baseUrl);
      return this.request(publicBaseUrl, path, method, accessToken, body, cancellation);
    }
  }

  private async request(baseUrl: string, path: string, method: http.RequestMethod, accessToken: string,
    body?: string, cancellation?: DownloadCancellationToken): Promise<ApiResponse> {
    const request = http.createHttp();
    let responseError = '';
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
      const response = await request.request(`${baseUrl}${path}`, {
        method: method,
        header: {
          'Accept': 'application/json',
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${accessToken}`
        },
        extraData: body,
        connectTimeout: 10000,
        readTimeout: 20000
      });
      this.throwIfCancelled(cancellation);
      if (responseError.length > 0) throw new Error(responseError);
      const text = this.ResponseText(response.result);
      const bytes = new util.TextEncoder().encodeInto(text).length;
      return {
        status: response.responseCode,
        envelope: this.policy.envelope(text, bytes)
      };
    } catch (error) {
      this.throwIfCancelled(cancellation);
      if (responseError.length > 0) throw new Error(responseError);
      const message = error instanceof Error ? error.message : '网络请求失败';
      throw new Error(message);
    } finally {
      cancellation?.unbind(cancelHandler);
      request.off('headersReceive');
      request.destroy();
    }
  }

  /** 统一不同HarmonyOS版本返回的字符串、JSON对象和二进制响应。 */
  private ResponseText(result: string | Object | ArrayBuffer): string {
    if (typeof result === 'string') return result;
    if (result instanceof ArrayBuffer) {
      return util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(new Uint8Array(result));
    }
    return JSON.stringify(result);
  }
}
