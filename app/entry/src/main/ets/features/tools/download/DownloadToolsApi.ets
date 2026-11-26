import { http } from '@kit.NetworkKit';
import fs from '@ohos.file.fs';
import util from '@ohos.util';
import { AuthSessionManager } from '../../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../../shared/network/AuthorizedApiClient';
import { AuthorizedApiPolicy } from '../../../shared/network/AuthorizedApiPolicy';

export interface DownloadTag { name: string; type: string; confidence: number; }
export interface DownloadItem { fileName: string; sizeBytes?: number; storageUri: string; tagStatus: string; tags: DownloadTag[]; }
export interface DownloadSummary { postProcessingStatus?: string; postProcessingPending?: number; postProcessingFailed?: number; itemCount: number; totalBytes: number; progressPercent?: number; items: DownloadItem[]; }
export interface DownloadMessage { id: string; requestId: string; kind: string; name: string; status: string; createdAt: string; result?: DownloadSummary; }
export interface DownloadHistoryPage { items: DownloadMessage[]; nextCursor: string | null; }
interface UploadState { id: string; bytesReceived: number; chunkBytes: number; requestId?: string; }
interface LinkBody { text: string; idempotencyKey: string; }
interface UploadBody { name: string; bytes: number; idempotencyKey: string; }

/** 下载聊天 API，分块上传且不暴露任何 Storage 内部凭据。 */
export class DownloadToolsApi {
  private base: string;
  private auth: AuthSessionManager;
  private client: AuthorizedApiClient;
  private policy: AuthorizedApiPolicy = new AuthorizedApiPolicy();
  constructor(base: string, auth: AuthSessionManager) {
    this.auth = auth; this.base = auth.normalizeBusinessBaseUrl(base);
    this.client = new AuthorizedApiClient(base, auth, false);
  }
  /** 读取最新会话或游标之前的历史记录。 */
  async history(before: string = ''): Promise<DownloadHistoryPage> {
    const cursor = before ? '&before=' + encodeURIComponent(before) : '';
    const body = (await this.client.get('/api/app/v1/download-tools/history?limit=10' + cursor)).data;
    if (Array.isArray(body) && !before) {
      // 协议滚动发布期间仍可打开旧服务上的首屏。
      const rows = body as DownloadMessage[];
      return { items: rows.filter((row: DownloadMessage, index: number) => index < 10 || this.isUnfinished(row)),
        nextCursor: null };
    }
    const page = body as DownloadHistoryPage;
    if (!page || !Array.isArray(page.items) || (page.nextCursor !== null && typeof page.nextCursor !== 'string')) {
      throw new Error('DOWNLOAD_003');
    }
    return page;
  }
  /** 查询一条已加载的记录，补齐退出未完成集合后的最终状态。 */
  async detail(id: string): Promise<DownloadMessage> {
    return (await this.client.get(`/api/app/v1/download-tools/messages/${this.id(id)}`)).data as DownloadMessage;
  }
  /** 区分仍可变化的下载记录和已结束的记录。 */
  isUnfinished(row: DownloadMessage): boolean {
    return ['ACCEPTED', 'PLANNING', 'QUEUED', 'RUNNING', 'CANCELLING', 'UPLOADING'].includes(row.status);
  }
  async send(text: string, key: string): Promise<DownloadMessage> {
    const body: LinkBody = { text: text, idempotencyKey: key };
    return await this.write('/messages', JSON.stringify(body), key) as DownloadMessage;
  }
  async cancel(id: string): Promise<void> {
    await this.write(`/messages/${this.id(id)}/cancel`, '{}', '');
  }
  async upload(uri: string, name: string, key: string, progress: (value: number, id: string) => void,
    active: () => boolean): Promise<DownloadMessage> {
    let file: fs.File | undefined = undefined;
    try {
      file = fs.openSync(uri, fs.OpenMode.READ_ONLY);
      const size = fs.statSync(file.fd).size;
      if (!Number.isSafeInteger(size) || size <= 0 || size > 536870912) throw new Error('DOWNLOAD_001');
      const body: UploadBody = { name: name, bytes: size, idempotencyKey: key };
      const state = await this.write('/uploads', JSON.stringify(body), key) as UploadState;
      this.id(state.id);
      progress(Math.round(state.bytesReceived * 100 / size), state.id);
      if (!state.requestId) {
        if (!Number.isSafeInteger(state.bytesReceived) || state.bytesReceived < 0 || state.bytesReceived > size) throw new Error('DOWNLOAD_003');
        for (let offset = 0; offset < size; offset += 1048576) {
          if (!active()) throw new Error('DOWNLOAD_002');
          const buffer = new ArrayBuffer(Math.min(1048576, size - offset));
          if (fs.readSync(file.fd, buffer) !== buffer.byteLength) throw new Error('DOWNLOAD_001');
          if (offset >= state.bytesReceived) await this.write(`/uploads/${state.id}/content`, buffer, key, offset);
          progress(Math.round((offset + buffer.byteLength) * 100 / size), state.id);
        }
      }
      if (!active()) throw new Error('DOWNLOAD_002');
      return await this.write(`/uploads/${state.id}/complete`, '{}', key) as DownloadMessage;
    } catch (error) { throw new Error((error as Error).message || 'DOWNLOAD_003'); }
    finally { if (file !== undefined) { try { fs.closeSync(file); } catch (_) {} } }
  }
  private id(value: string): string {
    if (!/^[0-9a-f-]{36}$/.test(value)) throw new Error('DOWNLOAD_001');
    return value;
  }
  private async write(path: string, body: string | ArrayBuffer, key: string, offset: number = -1): Promise<Object | undefined> {
    const safe = this.policy.path('/api/app/v1/download-tools' + path);
    let token = await this.auth.validAccessToken();
    for (let attempt = 0; attempt < 2; attempt++) {
      const request = http.createHttp();
      let rejected = false;
      try {
        request.on('headersReceive', (headers: Object) => {
          const entries = headers as Record<string, Object>;
          Object.keys(entries).forEach((name: string) => {
            if (name.toLowerCase() === 'location' || (name.toLowerCase() === 'content-length' && Number(entries[name]) > 1048576)) { rejected = true; request.destroy(); }
          });
        });
        const headers: Record<string, string> = { 'Authorization': `Bearer ${token}`,
          'Content-Type': offset < 0 ? 'application/json' : 'application/octet-stream',
          'Idempotency-Key': key, 'X-Upload-Offset': `${Math.max(0, offset)}` };
        const response = await request.request(this.auth.resolveBusinessBaseUrl(this.base) + safe, { method: offset < 0 ? http.RequestMethod.POST : http.RequestMethod.PUT,
          header: headers, extraData: body, expectDataType: http.HttpDataType.STRING, usingCache: false,
          connectTimeout: 10000, readTimeout: 60000 });
        if (rejected) throw new Error('DOWNLOAD_003');
        if (response.responseCode === 401 && attempt === 0) { token = await this.auth.forceRefresh(token); continue; }
        const text = response.result as string;
        const envelope = this.policy.responseEnvelope(response.responseCode, text, new util.TextEncoder().encodeInto(text).length);
        if (response.responseCode >= 400 || envelope.code !== '0000') throw new Error(envelope.message || 'DOWNLOAD_003');
        return envelope.data;
      } catch (error) { throw new Error((error as Error).message || 'DOWNLOAD_003'); }
      finally { request.off('headersReceive'); request.destroy(); }
    }
    throw new Error('DOWNLOAD_002');
  }
}
