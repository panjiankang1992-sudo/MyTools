import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { AuthSessionManager } from '../auth/AuthSessionManager';

export interface ImageModel {
  id: string;
  name: string;
  location: string;
  status: string;
  modes: string[];
  sizes: string[];
  counts: number[];
  maxReferences: number;
  reasonCode: string;
}
export interface ImageStyle {
  id: string;
  name: string;
  version: number;
  origin: string;
  kind: string;
  promptTemplate: string;
  compatibleModels: string[];
  modes: string[];
}
export interface ImageStyleWrite {
  name: string;
  promptTemplate: string;
  expectedVersion?: number;
}
export interface ImageRequest {
  styleId?: string;
  styleVersion?: number;
  styleSourceJobId?: string;
  styleSnapshot?: ImageStyle;
  effectivePrompt?: string;
  resourceId: string;
  prompt: string;
  mode: string;
  references: string[];
  size: string;
  count: number;
  seed: number;
  idempotencyKey: string;
}
export interface GeneratedImage {
  index: number;
  assetId: string;
  mimeType: string;
  sizeBytes: number;
}
export interface ImageResult {
  prompt?: string;
  unconfirmed?: boolean;
  images?: GeneratedImage[];
}
export interface ImageJob {
  id: string;
  status: string;
  createdAt: string;
  elapsedMillis?: number;
  errorCode: string;
  request: ImageRequest;
  result: ImageResult;
}
interface ImageUpload { id: string; }
interface ImageUploadRequest { base64: string; }

/** 图片任务只通过当前认证网关访问，供应方切换不改变认证端点。 */
export class ImageGenerationApi {
  private client: AuthorizedApiClient;
  constructor(baseUrl: string, auth: AuthSessionManager) {
    this.client = new AuthorizedApiClient(baseUrl, auth, false);
  }
  async styles(): Promise<ImageStyle[]> {
    const reply = await this.client.get('/api/app/v1/image-generation/styles');
    if (!Array.isArray(reply.data)) throw new Error('IMAGE_002');
    return reply.data as ImageStyle[];
  }
  async saveStyle(id: string, body: ImageStyleWrite): Promise<ImageStyle> {
    const path = `/api/app/v1/image-generation/styles/${this.id(id)}`;
    const reply = body.expectedVersion === undefined ? await this.client.postJson(path, body) : await this.client.putJson(path, body);
    return reply.data as ImageStyle;
  }
  async deleteStyle(id: string): Promise<void> {
    await this.client.delete(`/api/app/v1/image-generation/styles/${this.id(id)}`);
  }
  async models(): Promise<ImageModel[]> {
    const reply = await this.client.get('/api/app/v1/image-generation/models');
    if (!Array.isArray(reply.data)) throw new Error('模型目录响应无效');
    return reply.data as ImageModel[];
  }
  async works(page: number): Promise<ImageJob[]> {
    const reply = await this.client.get(`/api/app/v1/image-generation/works?page=${page}`);
    if (!Array.isArray(reply.data)) throw new Error('作品列表响应无效');
    return reply.data as ImageJob[];
  }
  async create(request: ImageRequest): Promise<ImageJob> {
    const reply = await this.client.postJson('/api/app/v1/image-generation/jobs', request);
    return reply.data as ImageJob;
  }
  async cancel(id: string): Promise<void> {
    await this.client.post(`/api/app/v1/image-generation/jobs/${this.id(id)}/cancel`);
  }
  async upload(base64: string): Promise<string> {
    const body: ImageUploadRequest = { base64: base64 };
    const reply = await this.client.postJson('/api/app/v1/image-generation/uploads', body);
    return this.id((reply.data as ImageUpload).id);
  }
  async reference(job: string, index: number): Promise<string> {
    if (!Number.isInteger(index) || index < 0 || index > 3) throw new Error('图片序号无效');
    const reply = await this.client.post(`/api/app/v1/image-generation/jobs/${this.id(job)}/images/${index}/reference`);
    return this.id((reply.data as ImageUpload).id);
  }
  async download(job: string, index: number, uri: string): Promise<void> {
    if (!Number.isInteger(index) || index < 0 || index > 3) throw new Error('图片序号无效');
    await this.client.downloadImageToUri(`/api/app/v1/image-generation/jobs/${this.id(job)}/images/${index}`, uri);
  }
  private id(value: string): string {
    if (typeof value !== 'string' || !/^[0-9a-f-]{36}$/.test(value)) throw new Error('图片标识无效');
    return value;
  }
}
