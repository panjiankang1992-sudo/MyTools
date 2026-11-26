import { http } from '@kit.NetworkKit';
import fs from '@ohos.file.fs';
import util from '@ohos.util';
import { AuthSessionManager } from '../../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../../shared/network/AuthorizedApiClient';
import { AuthorizedApiPolicy } from '../../../shared/network/AuthorizedApiPolicy';

export interface PdfCapabilities { operations: string[]; maxInputBytes: number; uploadChunkBytes: number; }
export interface PdfArtifact { filename: string; mediaType: string; bytes: number; pageCount?: number; }
export interface PdfPage { width: number; height: number; rotation: number; }
export interface PdfResult { pageCount?: number; fileCount?: number; artifacts?: PdfArtifact[]; previews?: PdfArtifact[]; pages?: PdfPage[]; warnings?: string[]; textPreview?: string; }
export interface PdfRecord { id: string; status: string; kind: string; name?: string; mediaType?: string; operation?: string; errorCode: string; result: PdfResult; expiresAt: string; }
export interface PdfOptions { axis?: string; position?: number; reverse?: boolean; ranges?: number[][]; pagesPerFile?: number; groups?: number[][][]; paperSize?: string; orientation?: string; fit?: string; marginMm?: number; dpi?: number; format?: string; fontPreset?: string; pageNumbers?: boolean; }
export interface PdfOutput { name: string; }
export interface PdfContent { format: string; text: string; }
export interface PdfPlanRequest { operation: string; inputIds?: string[]; attachmentIds?: string[]; content?: PdfContent; options: PdfOptions; output?: PdfOutput; clientRevision: number; }
interface UploadRequest { name: string; bytes: number; mediaType: string; }
interface JobRequest { planId: string; }

/** PDF 接口封装，保持原始字节、分块上限和认证刷新。 */
export class PdfToolsApi {
  private base: string;
  private auth: AuthSessionManager;
  private client: AuthorizedApiClient;
  private policy: AuthorizedApiPolicy = new AuthorizedApiPolicy();
  constructor(base: string, auth: AuthSessionManager) {
    this.auth = auth; this.base = auth.normalizeBusinessBaseUrl(base);
    this.client = new AuthorizedApiClient(base, auth, false);
  }
  async capabilities(): Promise<PdfCapabilities> {
    const value = (await this.client.get('/api/app/v1/pdf/capabilities')).data as PdfCapabilities;
    if (!Array.isArray(value.operations) || !Number.isSafeInteger(value.maxInputBytes)) throw new Error('PDF_010');
    return value;
  }
  async get(kind: string, id: string): Promise<PdfRecord> {
    return this.record((await this.client.get(`/api/app/v1/pdf/${kind}/${this.id(id)}`)).data);
  }
  async history(page: number): Promise<PdfRecord[]> {
    const data = (await this.client.get(`/api/app/v1/pdf/history?page=${page}`)).data;
    if (!Array.isArray(data)) throw new Error('PDF_010');
    return (data as PdfRecord[]).map((item: PdfRecord) => this.record(item));
  }
  async plan(body: PdfPlanRequest, key: string): Promise<PdfRecord> {
    return this.record(await this.write('/plans', JSON.stringify(body), key));
  }
  async start(id: string, key: string): Promise<PdfRecord> {
    const body: JobRequest = { planId: this.id(id) };
    return this.record(await this.write('/jobs', JSON.stringify(body), key));
  }
  async cancel(kind: string, id: string): Promise<PdfRecord> {
    return this.record((await this.client.post(`/api/app/v1/pdf/${kind}/${this.id(id)}/cancel`)).data);
  }
  async upload(uri: string, maxBytes: number, active: () => boolean,
    progress: (bytes: number, total: number) => void): Promise<PdfRecord> {
    let file: fs.File | undefined = undefined;
    try {
      file = fs.openSync(uri, fs.OpenMode.READ_ONLY);
      const size = fs.statSync(file.fd).size;
      if (size < 1 || size > maxBytes) throw new Error('PDF_003');
      const name = decodeURIComponent(uri.substring(uri.lastIndexOf('/') + 1));
      const extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
      let mime: string = 'application/pdf';
      if (extension === 'png') mime = 'image/png';
      else if (extension === 'jpg' || extension === 'jpeg') mime = 'image/jpeg';
      else if (extension === 'txt') mime = 'text/plain';
      else if (extension === 'md') mime = 'text/markdown';
      else if (extension !== 'pdf') throw new Error('PDF_002');
      const request: UploadRequest = { name: name, bytes: size, mediaType: mime };
      const created = this.record(await this.write('/uploads', JSON.stringify(request), util.generateRandomUUID()));
      for (let offset = 0; offset < size; offset += 1048576) {
        if (!active()) throw new Error('PDF_008');
        const buffer = new ArrayBuffer(Math.min(1048576, size - offset));
        if (fs.readSync(file.fd, buffer) !== buffer.byteLength) throw new Error('PDF_010');
        await this.write(`/uploads/${created.id}/content`, buffer, '', offset);
        if (!active()) throw new Error('PDF_008');
        progress(offset + buffer.byteLength, size);
      }
      return this.record((await this.client.post(`/api/app/v1/pdf/uploads/${created.id}/complete`)).data);
    } catch (error) { throw new Error((error as Error).message || 'PDF_010'); }
    finally { if (file !== undefined) { try { fs.closeSync(file); } catch (_) {} } }
  }
  async thumbnail(kind: string, id: string, index: number, uri: string): Promise<void> {
    const path = kind === 'inputs' ? `/inputs/${this.id(id)}/thumbnail` : `/plans/${this.id(id)}/pages/${index}/thumbnail`;
    await this.client.downloadImageToUri('/api/app/v1/pdf' + path, uri);
  }
  async download(job: string, index: number, uri: string): Promise<void> {
    await this.client.downloadToUri(`/api/app/v1/pdf/jobs/${this.id(job)}/artifacts/${index}/content`, uri);
  }
  private record(value: Object | undefined): PdfRecord {
    if (value === undefined) throw new Error('PDF_010');
    const row = value as PdfRecord;
    this.id(row.id);
    if (typeof row.status !== 'string' || row.result === undefined) throw new Error('PDF_010');
    return row;
  }
  private id(value: string): string {
    if (typeof value !== 'string' || !/^[0-9a-f-]{36}$/.test(value)) throw new Error('PDF_001');
    return value;
  }
  private async write(path: string, body: string | ArrayBuffer, key: string, offset: number = -1): Promise<Object | undefined> {
    const safe = this.policy.path('/api/app/v1/pdf' + path);
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
        if (rejected) throw new Error('PDF_009');
        if (response.responseCode === 401 && attempt === 0) { token = await this.auth.forceRefresh(token); continue; }
        const text = response.result as string;
        const envelope = this.policy.responseEnvelope(response.responseCode, text, new util.TextEncoder().encodeInto(text).length);
        if (response.responseCode >= 400 || envelope.code !== '0000') throw new Error(envelope.message || 'PDF_009');
        return envelope.data;
      } catch (error) { throw new Error((error as Error).message || 'PDF_009'); }
      finally { request.off('headersReceive'); request.destroy(); }
    }
    throw new Error('PDF_014');
  }
}
