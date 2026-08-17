import { AuthSessionManager } from '../auth/AuthSessionManager';
import common from '@ohos.app.ability.common';
import request from '@ohos.request';
import { http } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { RemoteMediaResponseNormalizer } from './RemoteMediaResponseNormalizer';
import { RemoteMediaRequestPolicy } from './RemoteMediaRequestPolicy';
import { RemoteSubtitleNetworkPolicy } from './RemoteSubtitleNetworkPolicy';
import { RemoteUploadHeaderResult, RemoteUploadProgress, RemoteUploadTaskPolicy } from './RemoteUploadTaskPolicy';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import {
  RemoteDirectory,
  RemoteMediaFilterOptions,
  RemoteMediaSource,
  RemotePlaybackDescriptor,
  RemotePlaybackMetrics
} from './RemoteMediaModels';

interface RemoteRenameRequest {
  path: string;
  newName: string;
}

interface RemoteMoveRequest {
  path: string;
  from: string;
  to: string;
}

interface RemotePathRequest {
  path: string;
}

interface LocalMediaNameRequest { name: string; }
interface LocalMediaLocationRequest { directoryPath: string; }
interface LocalMediaTagsRequest { tags: string[]; }

interface UploadAttemptResult {
  succeeded: boolean;
  authenticationRejected: boolean;
  message: string;
}

/**
 * 通过 MyTools 后端访问远程媒体来源。
 */
export class RemoteMediaApi {
  private readonly client: AuthorizedApiClient;
  private readonly baseUrl: string;
  private readonly authManager: AuthSessionManager;
  private readonly normalizer: RemoteMediaResponseNormalizer = new RemoteMediaResponseNormalizer();
  private readonly requestPolicy: RemoteMediaRequestPolicy = new RemoteMediaRequestPolicy();
  private readonly subtitleNetworkPolicy: RemoteSubtitleNetworkPolicy = new RemoteSubtitleNetworkPolicy();
  private readonly uploadTaskPolicy: RemoteUploadTaskPolicy = new RemoteUploadTaskPolicy();

  constructor(baseUrl: string, authManager: AuthSessionManager) {
    this.baseUrl = authManager.normalizeBusinessBaseUrl(baseUrl);
    this.authManager = authManager;
    this.client = new AuthorizedApiClient(baseUrl, authManager);
  }

  /**
   * 获取当前用户配置的远程数据源。
   */
  async listSources(cancellation?: DownloadCancellationToken): Promise<RemoteMediaSource[]> {
    const localEnvelope = await this.client.get('/api/localfiles/directories', cancellation);
    const localSources = this.normalizer.normalizeLocalSources(localEnvelope.data);
    try {
      const remoteEnvelope = await this.client.get('/api/webdav/accounts', cancellation);
      return localSources.concat(this.normalizer.normalizeSources(remoteEnvelope.data));
    } catch (_) {
      return localSources;
    }
  }

  /** 获取电子书专用远程来源，包含MyTools EBOOK目录与WebDAV账号。 */
  async listBookSources(cancellation?: DownloadCancellationToken): Promise<RemoteMediaSource[]> {
    const localEnvelope = await this.client.get('/api/localfiles/directories', cancellation);
    const localSources = this.normalizer.normalizeLocalBookSources(localEnvelope.data);
    try {
      const remoteEnvelope = await this.client.get('/api/webdav/accounts', cancellation);
      return localSources.concat(this.normalizer.normalizeSources(remoteEnvelope.data));
    } catch (_) {
      return localSources;
    }
  }

  /**
   * 浏览指定远程数据源的目录。
   */
  async listDirectory(accountId: string, path: string,
    cancellation?: DownloadCancellationToken, localRootPath: string = '', page: number = 1,
    pageSize: number = 24, subdirectory: string = '', fileType: string = '',
    tagNames: string[] = [], matchAllTags: boolean = false, keyword: string = ''): Promise<RemoteDirectory> {
    if (accountId.startsWith('local:')) {
      const directoryId = this.requestPolicy.accountId(accountId.substring(6));
      const queryTags = tagNames.slice(0, 20).map((name: string) =>
        `&tagNames=${encodeURIComponent(name)}`).join('');
      const envelope = await this.client.get(`/api/localfiles?directoryId=${encodeURIComponent(directoryId)}` +
        `&page=${encodeURIComponent(`${page}`)}&pageSize=${encodeURIComponent(`${pageSize}`)}` +
        `&subdirectory=${encodeURIComponent(subdirectory)}&fileType=${encodeURIComponent(fileType)}` +
        `&matchAllTags=${matchAllTags ? 'true' : 'false'}&keyword=${encodeURIComponent(keyword)}${queryTags}`,
      cancellation);
      return this.normalizer.normalizeLocalFiles(envelope.data, localRootPath, page, pageSize);
    }
    const safeAccountId = this.requestPolicy.accountId(accountId);
    const safePath = this.requestPolicy.path(path.length > 0 ? path : '/');
    const envelope = await this.client.get(`/api/cloud/files?path=${encodeURIComponent(safePath)}` +
      `&depth=1&accountId=${encodeURIComponent(safeAccountId)}`, cancellation);
    return this.normalizer.normalizeDirectory(envelope.data, safePath);
  }

  /** 获取MyTools媒体目录的完整目录与标签筛选项。 */
  async listFilterOptions(accountId: string,
    cancellation?: DownloadCancellationToken): Promise<RemoteMediaFilterOptions> {
    if (!accountId.startsWith('local:')) return { directories: [], tags: [] };
    const directoryId = this.requestPolicy.accountId(accountId.substring(6));
    const envelope = await this.client.get(
      `/api/localfiles/filters?directoryId=${encodeURIComponent(directoryId)}`, cancellation);
    return this.normalizer.normalizeLocalFilters(envelope.data);
  }

  /**
   * 获取 Alist 媒体的直接流式播放地址。
   */
  async resolvePlayback(source: RemoteMediaSource, path: string,
    cancellation?: DownloadCancellationToken): Promise<RemotePlaybackDescriptor> {
    if (source.type === 'MYTOOLS_LOCAL') {
      const match = /^\/local\/([1-9]\d{0,18})$/.exec(path);
      if (match === null) throw new Error('MyTools本地媒体文件标识无效');
      const ticketEnvelope = await this.client.post(
        `/api/app/v1/local-media/tickets?fileId=${encodeURIComponent(match[1])}`, cancellation);
      return this.normalizer.normalizePlayback(ticketEnvelope.data, this.baseUrl, source.type);
    }
    const safeAccountId = this.requestPolicy.accountId(source.id);
    const safePath = this.requestPolicy.path(path, false);
    const ticketEnvelope = await this.client.post(
      `/api/app/v1/media/tickets?path=${encodeURIComponent(safePath)}&accountId=${encodeURIComponent(safeAccountId)}`,
      cancellation);
    return this.normalizer.normalizePlayback(ticketEnvelope.data, this.baseUrl, source.type);
  }

  /** 预读视频开头最多256 KB，建立连接并预热服务端Range链路。 */
  async preloadVideoSegment(url: string, cancellation?: DownloadCancellationToken): Promise<void> {
    if (cancellation?.isCancelled() || !url.startsWith(`${this.baseUrl}/api/app/v1/`)) return;
    const request = http.createHttp();
    let received = 0;
    let warmed = false;
    const cancelHandler = (): void => request.destroy();
    try {
      cancellation?.bind(cancelHandler);
      request.on('dataReceive', (buffer: ArrayBuffer) => {
        if (cancellation?.isCancelled()) return;
        received += buffer.byteLength;
        if (received >= 256 * 1024) {
          warmed = true;
          request.destroy();
        }
      });
      try {
        const status = await request.requestInStream(url, {
          method: http.RequestMethod.GET,
          header: { 'Range': 'bytes=0-262143', 'Accept': 'video/*,application/octet-stream' },
          connectTimeout: 8000,
          readTimeout: 10000
        });
        warmed = warmed || ((status === 200 || status === 206) && received > 0);
      } catch (_) {
        if (cancellation?.isCancelled()) return;
      }
      if (!warmed) return;
    } finally {
      cancellation?.unbind(cancelHandler);
      request.off('dataReceive');
      request.destroy();
    }
  }

  /**
   * 读取当前用户播放票据的实际代理传输指标。
   */
  async getPlaybackMetrics(ticket: string,
    cancellation?: DownloadCancellationToken): Promise<RemotePlaybackMetrics> {
    const safeTicket = this.requestPolicy.ticket(ticket);
    const envelope = await this.client.get(`/api/app/v1/media/tickets/${safeTicket}/metrics`, cancellation);
    return this.normalizer.normalizeMetrics(envelope.data);
  }

  /**
   * 使用独立短期票据读取最多1 MB的严格UTF-8远程字幕。
   */
  async loadSubtitle(source: RemoteMediaSource, path: string,
    cancellation?: DownloadCancellationToken): Promise<string> {
    if (cancellation?.isCancelled()) throw new Error('远程字幕加载已取消');
    const safePath = this.subtitleNetworkPolicy.path(path);
    const descriptor = await this.resolvePlayback(source, safePath, cancellation);
    if (cancellation?.isCancelled()) throw new Error('远程字幕加载已取消');
    const request = http.createHttp();
    const decoder = util.TextDecoder.create('utf-8', { fatal: true });
    let received = 0;
    let text = '';
    let streamError = '';
    const cancelHandler = (): void => request.destroy();
    try {
      cancellation?.bind(cancelHandler);
      request.on('headersReceive', (headers: Object) => {
        if (cancellation?.isCancelled()) return;
        streamError = this.subtitleNetworkPolicy.headerError(headers);
        if (streamError.length > 0) request.destroy();
      });
      request.on('dataReceive', (buffer: ArrayBuffer) => {
        if (cancellation?.isCancelled() || streamError.length > 0) return;
        received += buffer.byteLength;
        if (received > 1024 * 1024) {
          streamError = '远程字幕超过1 MB限制';
          request.destroy();
          return;
        }
        try {
          text += decoder.decodeToString(new Uint8Array(buffer), { stream: true });
          if (text.length > 1024 * 1024) {
            streamError = '远程字幕超过1 MB限制';
            request.destroy();
          }
        } catch (_) {
          streamError = '远程字幕不是有效的UTF-8文本';
          request.destroy();
        }
      });
      let status = 0;
      try {
        status = await request.requestInStream(descriptor.url, {
          method: http.RequestMethod.GET,
          header: { 'Accept': 'text/vtt,text/plain,application/x-subrip,application/octet-stream' },
          connectTimeout: 10000,
          readTimeout: 30000
        });
      } catch (_) {
        if (cancellation?.isCancelled()) throw new Error('远程字幕加载已取消');
        if (streamError.length > 0) throw new Error(streamError);
        throw new Error('远程字幕下载失败');
      }
      if (cancellation?.isCancelled()) throw new Error('远程字幕加载已取消');
      if (streamError.length > 0) throw new Error(streamError);
      if (status < 200 || status >= 300) throw new Error(`远程字幕下载失败：HTTP ${status}`);
      try {
        text += decoder.decodeToString(new Uint8Array(0), { stream: false });
      } catch (_) {
        throw new Error('远程字幕不是有效的UTF-8文本');
      }
      if (received === 0 || text.length === 0) throw new Error('远程字幕为空');
      return text;
    } finally {
      cancellation?.unbind(cancelHandler);
      request.off('headersReceive');
      request.off('dataReceive');
      request.destroy();
    }
  }

  /**
   * 删除当前用户指定账号中的单个远程文件。
   */
  async deleteFile(accountId: string, path: string): Promise<void> {
    if (accountId.startsWith('local:')) {
      await this.client.delete(`/api/localfiles/${this.localFileId(path)}`);
      return;
    }
    const safeAccountId = this.requestPolicy.accountId(accountId);
    const safePath = this.requestPolicy.path(path, false);
    await this.client.delete(`/api/cloud/file?path=${encodeURIComponent(safePath)}` +
      `&recursive=false&accountId=${encodeURIComponent(safeAccountId)}`);
  }

  /** 下载后端安全缩放后的JPEG缩略图到应用缓存URI。 */
  async downloadThumbnail(accountId: string, path: string, targetUri: string,
    cancellation?: DownloadCancellationToken): Promise<number> {
    if (accountId.startsWith('local:')) {
      const match = /^\/local\/([1-9]\d{0,18})$/.exec(path);
      if (match === null) throw new Error('MyTools本地媒体文件标识无效');
      return this.client.downloadJpegThumbnailToUri(`/api/localfiles/${match[1]}/thumbnail`, targetUri, cancellation);
    }
    const safeAccountId = this.requestPolicy.accountId(accountId);
    const safePath = this.requestPolicy.path(path, false);
    return this.client.downloadJpegThumbnailToUri(`/api/app/v1/media/thumbnail?accountId=` +
      `${encodeURIComponent(safeAccountId)}&path=${encodeURIComponent(safePath)}&edge=512`, targetUri, cancellation);
  }

  /** 当服务端尚未生成缩略图时，下载受认证的本地原图作为预览降级。 */
  async downloadLocalImagePreview(path: string, targetUri: string,
    cancellation?: DownloadCancellationToken): Promise<number> {
    const fileId = this.localFileId(path);
    return this.client.downloadImageToUri(`/api/localfiles/${fileId}/content`, targetUri, cancellation);
  }

  /** 下载最大2048像素的安全JPEG用于系统分享。 */
  async downloadShareImage(accountId: string, path: string, targetUri: string,
    cancellation?: DownloadCancellationToken): Promise<number> {
    const safeAccountId = this.requestPolicy.accountId(accountId);
    const safePath = this.requestPolicy.path(path, false);
    return this.client.downloadJpegShareToUri(`/api/app/v1/media/thumbnail?accountId=` +
      `${encodeURIComponent(safeAccountId)}&path=${encodeURIComponent(safePath)}&edge=2048`, targetUri, cancellation);
  }

  /**
   * 重命名当前用户远程账号中的文件或目录。
   */
  async renameEntry(accountId: string, path: string, newName: string): Promise<void> {
    if (accountId.startsWith('local:')) {
      const fileId = this.localFileId(path);
      const request: LocalMediaNameRequest = { name: this.requestPolicy.name(newName) };
      await this.client.putJson(`/api/localfiles/${fileId}/name`, request);
      return;
    }
    const safeAccountId = this.requestPolicy.accountId(accountId);
    const safePath = this.requestPolicy.path(path, false);
    const normalizedName = this.requestPolicy.name(newName);
    const request: RemoteRenameRequest = {
      path: safePath,
      newName: normalizedName
    };
    await this.client.postJson(`/api/cloud/rename?accountId=${encodeURIComponent(safeAccountId)}`, request);
  }

  /**
   * 移动当前用户远程账号中的文件或目录。
   */
  async moveEntry(accountId: string, from: string, targetDirectory: string): Promise<void> {
    if (accountId.startsWith('local:')) {
      const fileId = this.localFileId(from);
      const request: LocalMediaLocationRequest = { directoryPath: this.requestPolicy.path(targetDirectory) };
      await this.client.putJson(`/api/localfiles/${fileId}/location`, request);
      return;
    }
    const safeAccountId = this.requestPolicy.accountId(accountId);
    const safeFrom = this.requestPolicy.path(from, false);
    const normalizedTarget = this.requestPolicy.path(targetDirectory);
    if (normalizedTarget === safeFrom || normalizedTarget.startsWith(`${safeFrom}/`)) {
      throw new Error('不能把远程对象移动到自身内部');
    }
    const lastSlash = safeFrom.lastIndexOf('/');
    const name = this.requestPolicy.name(safeFrom.substring(lastSlash + 1));
    const destination = normalizedTarget === '/' ? `/${name}` : `${normalizedTarget}/${name}`;
    if (destination === safeFrom) throw new Error('目标目录与当前目录相同');
    const request: RemoteMoveRequest = {
      path: safeFrom,
      from: safeFrom,
      to: destination
    };
    await this.client.postJson(`/api/cloud/move?accountId=${encodeURIComponent(safeAccountId)}`, request);
  }

  /**
   * 删除当前用户远程账号中的文件或目录。
   */
  async deleteEntry(accountId: string, path: string, recursive: boolean): Promise<void> {
    if (accountId.startsWith('local:')) {
      if (recursive) throw new Error('MyTools媒体文件不支持递归删除');
      await this.client.delete(`/api/localfiles/${this.localFileId(path)}`);
      return;
    }
    const safeAccountId = this.requestPolicy.accountId(accountId);
    const safePath = this.requestPolicy.path(path, false);
    await this.client.delete(`/api/cloud/file?path=${encodeURIComponent(safePath)}&recursive=${recursive}` +
      `&accountId=${encodeURIComponent(safeAccountId)}`);
  }

  /** 使用完整集合替换一个MyTools本地媒体文件的人工标签。 */
  async replaceTags(accountId: string, path: string, tags: string[]): Promise<void> {
    if (!accountId.startsWith('local:')) throw new Error('当前远程来源暂不支持标签管理');
    const normalized = tags.slice(0, 32).map((tag: string) => this.requestPolicy.name(tag));
    const request: LocalMediaTagsRequest = { tags: normalized };
    await this.client.putJson(`/api/localfiles/${this.localFileId(path)}/tags`, request);
  }

  private localFileId(path: string): string {
    const match = /^\/local\/([1-9]\d{0,18})$/.exec(path);
    if (match === null) throw new Error('MyTools本地媒体文件标识无效');
    return match[1];
  }

  /**
   * 在指定远程目录中新建文件夹。
   */
  async createDirectory(accountId: string, parent: string, name: string): Promise<void> {
    const safeAccountId = this.requestPolicy.accountId(accountId);
    const safeParent = this.requestPolicy.path(parent);
    const normalizedName = this.requestPolicy.name(name);
    const path = safeParent === '/' ? `/${normalizedName}` : `${safeParent}/${normalizedName}`;
    const request: RemotePathRequest = { path: path };
    await this.client.postJson(`/api/cloud/dir?accountId=${encodeURIComponent(safeAccountId)}`, request);
  }

  /**
   * 将远程文件流式下载到用户授权的目标 URI。
   */
  async downloadFile(accountId: string, path: string, targetUri: string,
    onProgress?: (receivedBytes: number, totalBytes: number) => void,
    cancellation?: DownloadCancellationToken): Promise<number> {
    const safeAccountId = this.requestPolicy.accountId(accountId);
    const safePath = this.requestPolicy.path(path, false);
    const safeTargetUri = this.requestPolicy.fileUri(targetUri);
    return this.client.downloadToUri(`/api/app/v1/files/download?path=${encodeURIComponent(safePath)}` +
      `&accountId=${encodeURIComponent(safeAccountId)}`, safeTargetUri, onProgress, cancellation);
  }

  /**
   * 使用系统后台上传任务把用户主动选择的文件上传到远程目录。
   */
  async uploadFile(context: common.UIAbilityContext, accountId: string, directory: string,
    uri: string, filename: string, onProgress?: (uploadedBytes: number, totalBytes: number) => void): Promise<void> {
    const safeAccountId = this.requestPolicy.accountId(accountId);
    const safeDirectory = this.requestPolicy.path(directory);
    const safeUri = this.requestPolicy.fileUri(uri);
    const safeFilename = this.requestPolicy.name(filename);
    let token = await this.authManager.validAccessToken();
    let result = await this.uploadFileOnce(context, safeAccountId, safeDirectory, safeUri, safeFilename, token, onProgress);
    if (result.authenticationRejected) {
      token = await this.authManager.forceRefresh();
      result = await this.uploadFileOnce(context, safeAccountId, safeDirectory, safeUri, safeFilename, token, onProgress);
    }
    if (!result.succeeded) throw new Error(result.message.length > 0 ? result.message : '远程文件上传失败');
  }

  private async uploadFileOnce(context: common.UIAbilityContext, accountId: string, directory: string,
    uri: string, filename: string, token: string,
    onProgress?: (uploadedBytes: number, totalBytes: number) => void): Promise<UploadAttemptResult> {
    const file: request.File = { filename: filename, name: 'file', uri: uri, type: 'application/octet-stream' };
    const pathData: request.RequestData = { name: 'path', value: directory };
    const nameData: request.RequestData = { name: 'filename', value: filename };
    const config: request.UploadConfig = {
      url: `${this.baseUrl}/api/cloud/file?accountId=${encodeURIComponent(accountId)}`,
      header: { 'Authorization': `Bearer ${token}` },
      method: 'POST',
      files: [file],
      data: [pathData, nameData]
    };
    try {
      const task = await request.uploadFile(context, config);
      return await new Promise<UploadAttemptResult>((resolve: (value: UploadAttemptResult) => void) => {
        let settled = false;
        let responseStatus = 0;
        let responseError = '';
        let lastUploadedBytes = 0;
        const finish = (states: request.TaskState[], failedEvent: boolean): void => {
          if (settled) return;
          settled = true;
          task.off('progress');
          task.off('headerReceive');
          task.off('complete');
          task.off('fail');
          const failed = states.find((state: request.TaskState) => state.responseCode !== 0);
          if (!failedEvent && failed === undefined && responseError.length === 0 &&
            responseStatus >= 200 && responseStatus < 300) {
            resolve({ succeeded: true, authenticationRejected: false, message: '' });
            return;
          }
          const code = responseStatus > 0 ? responseStatus : (failed?.responseCode ?? 0);
          const message = responseError.length > 0 ? responseError : (failed?.message ??
            (responseStatus === 0 ? '上传响应缺少可信HTTP状态' : '远程文件上传失败'));
          const authenticationRejected = code === 401 || /(?:http|status|code)?\s*[:=]?\s*401|unauthori[sz]ed/i
            .test(message);
          resolve({ succeeded: false, authenticationRejected: authenticationRejected,
            message: authenticationRejected ? '上传认证已失效' :
              (code === 0 ? message : `上传失败：${code} · ${message}`) });
        };
        task.on('progress', (uploadedSize: number, totalSize: number) => {
          const progress: RemoteUploadProgress | undefined = this.uploadTaskPolicy.progress(
            uploadedSize, totalSize, lastUploadedBytes);
          if (progress === undefined) return;
          lastUploadedBytes = progress.uploadedBytes;
          onProgress?.(progress.uploadedBytes, progress.totalBytes);
        });
        task.on('headerReceive', (header: object) => {
          const result: RemoteUploadHeaderResult = this.uploadTaskPolicy.headers(header);
          if (result.status > 0) responseStatus = result.status;
          if (result.error.length > 0) responseError = result.error;
        });
        task.on('complete', (states: request.TaskState[]) => {
          finish(states, false);
        });
        task.on('fail', (states: request.TaskState[]) => finish(states, true));
      });
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('无法创建远程上传任务');
    }
  }

}
