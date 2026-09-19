import fs from '@ohos.file.fs';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { AuthSessionManager } from '../auth/AuthSessionManager';

/** 输入角色及其素材类型，决定该角色上传图片还是视频。 */
export interface VideoRoleSpec {
  name: string;
  kind: string;
}

/** 单个模式的输入契约与验收状态；只有 validated 为真才允许提交。 */
export interface VideoModeSpec {
  mode: string;
  minInputs: number;
  maxInputs: number;
  roles: VideoRoleSpec[];
  requiresPrompt: boolean;
  frames: number[];
  validated: boolean;
  reasonCode: string;
}

/** 模型能力与可用状态。 */
export interface VideoModel {
  id: string;
  name: string;
  location: string;
  status: string;
  sizes: string[];
  frames: number[];
  fps: number;
  modes: VideoModeSpec[];
  reasonCode: string;
}

/** 上传结果：尺寸与时长由服务端实测，客户端不得自行换算。 */
export interface VideoUpload {
  id: string;
  kind: string;
  mimeType: string;
  sizeBytes: number;
  durationMs?: number;
  width?: number;
  height?: number;
}

/** 一份输入素材引用。 */
export interface VideoInput {
  uploadId: string;
  role: string;
  description?: string;
  trimStartMs?: number;
  trimEndMs?: number;
}

/** 输出规格：尺寸、帧数与帧率。 */
export interface VideoOutput {
  size: string;
  frames: number;
  fps: number;
}

/** 生成请求；幂等键由界面生成，重试必须复用同一幂等键。 */
export interface VideoRequest {
  resourceId: string;
  mode: string;
  prompt: string;
  inputs: VideoInput[];
  output: VideoOutput;
  seed: number;
  idempotencyKey: string;
  audioPolicy?: string;
}

/** 成功结果的实际输出参数，非终态任务可能为空。 */
export interface VideoResult {
  frames?: number;
  fps?: number;
  durationMs?: number;
  width?: number;
  height?: number;
}

/** 视频任务；request 为提交时的快照，用于「再次创作」。 */
export interface VideoJob {
  id: string;
  status: string;
  createdAt: string;
  elapsedMillis: number;
  errorCode: string;
  request: VideoRequest;
  result: VideoResult;
}

/** 图片素材上传上限，与服务端 ffprobe/ImageIO 实测口径一致。 */
const MAX_IMAGE_BYTES: number = 20 * 1024 * 1024;
/** 视频素材上传上限，与服务端实测口径一致。 */
const MAX_VIDEO_BYTES: number = 200 * 1024 * 1024;
/** 服务端只接受这三种素材类型；客户端不做压缩或转码。 */
const IMAGE_PNG: string = 'image/png';
const IMAGE_JPEG: string = 'image/jpeg';
const VIDEO_MP4: string = 'video/mp4';
const UPLOAD_IMAGE_PATH: string = '/api/app/v1/video-generation/uploads/image';
const UPLOAD_VIDEO_PATH: string = '/api/app/v1/video-generation/uploads/video';

/** 播放票据：URL 里带随机票据，原生播放器无需登录头即可取流。 */
export interface VideoTicket {
  token: string;
  url: string;
  expiresAt: string;
  kind: string;
}

/** 视频任务只通过当前认证网关访问，供应方切换不改变认证端点。 */
export class VideoGenerationApi {
  private client: AuthorizedApiClient;
  private readonly baseUrl: string;
  constructor(baseUrl: string, auth: AuthSessionManager) {
    this.client = new AuthorizedApiClient(baseUrl, auth, false);
    this.baseUrl = baseUrl.endsWith('/') ? baseUrl.substring(0, baseUrl.length - 1) : baseUrl;
  }
  /** 读取逐模式的能力与验收状态。 */
  async models(): Promise<VideoModel[]> {
    const reply = await this.client.get('/api/app/v1/video-generation/models');
    if (!Array.isArray(reply.data)) throw new Error('模型目录响应无效');
    return reply.data as VideoModel[];
  }
  /** 分页读取本人作品历史。 */
  async works(page: number): Promise<VideoJob[]> {
    if (!Number.isInteger(page) || page < 0 || page > 10000) throw new Error('页码无效');
    const reply = await this.client.get(`/api/app/v1/video-generation/works?page=${page}`);
    if (!Array.isArray(reply.data)) throw new Error('作品列表响应无效');
    return reply.data as VideoJob[];
  }
  /** 幂等创建视频任务。 */
  async create(request: VideoRequest): Promise<VideoJob> {
    const reply = await this.client.postJson('/api/app/v1/video-generation/jobs', request);
    return this.normalize(reply.data);
  }
  /** 请求取消本人任务，返回取消后的任务状态。 */
  async cancel(id: string): Promise<VideoJob> {
    const reply = await this.client.post(`/api/app/v1/video-generation/jobs/${this.id(id)}/cancel`);
    return this.normalize(reply.data);
  }
  /** 查询单个任务的最新状态。 */
  async job(id: string): Promise<VideoJob> {
    const reply = await this.client.get(`/api/app/v1/video-generation/jobs/${this.id(id)}`);
    return this.normalize(reply.data);
  }
  /** 以原始字节上传图片素材，返回服务端登记的素材标识。 */
  async uploadImage(path: string): Promise<string> {
    return this.upload(UPLOAD_IMAGE_PATH, path, this.imageType(path), MAX_IMAGE_BYTES, '图片素材');
  }
  /** 以原始字节上传视频素材，返回服务端登记的素材标识。 */
  async uploadVideo(path: string): Promise<string> {
    return this.upload(UPLOAD_VIDEO_PATH, path, VIDEO_MP4, MAX_VIDEO_BYTES, '视频素材');
  }
  /** 将成片下载到本地缓存路径。 */
  async downloadVideoToUri(job: string, targetUri: string,
    onProgress?: (receivedBytes: number, totalBytes: number) => void): Promise<void> {
    await this.client.downloadVideoToUri(`/api/app/v1/video-generation/jobs/${this.id(job)}/video`,
      targetUri, onProgress);
  }
  /** 为成片签发播放票据，返回可直接交给播放器的绝对地址（服务端只回相对路径）。 */
  async videoTicket(job: string): Promise<VideoTicket> {
    const reply = await this.client.post(`/api/app/v1/video-generation/jobs/${this.id(job)}/ticket`);
    return this.ticket(reply.data);
  }
  /** 为原素材签发播放票据，供"原片对比"使用。 */
  async sourceTicket(upload: string): Promise<VideoTicket> {
    const reply = await this.client.post(`/api/app/v1/video-generation/uploads/${this.id(upload)}/ticket`);
    return this.ticket(reply.data);
  }
  /** 校验票据响应并把相对路径补成绝对地址；票据缺失直接报错而不是退化成无声失败。 */
  private ticket(data: Object | undefined): VideoTicket {
    const value = data as VideoTicket;
    if (value === undefined || typeof value.token !== 'string' || !/^[0-9a-f]{32}$/.test(value.token) ||
      typeof value.url !== 'string' || !value.url.startsWith('/')) {
      throw new Error('播放票据无效，请稍后重试。');
    }
    value.url = `${this.baseUrl}${value.url}`;
    return value;
  }
  /** 将成片封面下载到本地缓存路径。 */
  async downloadCoverToUri(job: string, targetUri: string): Promise<void> {
    await this.client.downloadImageToUri(`/api/app/v1/video-generation/jobs/${this.id(job)}/cover`, targetUri);
  }
  /** 读取本地文件原始字节后按服务端要求的类型上传，不做压缩或重编码。 */
  private async upload(endpoint: string, path: string, contentType: string, maxBytes: number,
    label: string): Promise<string> {
    let file: fs.File | undefined = undefined;
    let bytes: ArrayBuffer = new ArrayBuffer(0);
    try {
      file = fs.openSync(path, fs.OpenMode.READ_ONLY);
      const size = fs.statSync(file.fd).size;
      if (!Number.isSafeInteger(size) || size <= 0 || size > maxBytes) {
        throw new Error(`${label}必须为 1 B 至 ${Math.floor(maxBytes / (1024 * 1024))} MB`);
      }
      const buffer = new ArrayBuffer(size);
      const read = fs.readSync(file.fd, buffer);
      if (read !== size) throw new Error(`${label}读取不完整`);
      bytes = buffer;
    } catch (error) {
      throw error instanceof Error ? error : new Error(`${label}读取失败`);
    } finally {
      if (file !== undefined) {
        try { fs.closeSync(file); } catch (_) {}
      }
    }
    const reply = await this.client.postBinary(endpoint, bytes, contentType);
    return this.id((reply.data as VideoUpload).id);
  }
  /** 图片素材按扩展名选择服务端接受的实际类型，避免按错误类型探测。 */
  private imageType(path: string): string {
    const extension = path.toLowerCase().substring(path.lastIndexOf('.') + 1);
    if (extension === 'png') return IMAGE_PNG;
    if (extension === 'jpg' || extension === 'jpeg') return IMAGE_JPEG;
    throw new Error('图片素材请选择 PNG 或 JPEG');
  }
  /** 校验服务端返回的任务信封，缺失或标识格式错误时立即失败。 */
  private normalize(data: Object | undefined): VideoJob {
    if (data === undefined || data === null) throw new Error('视频任务响应无效');
    const job = data as VideoJob;
    this.id(job.id);
    return job;
  }
  /** 所有路径参数都必须是服务端下发的 UUID，禁止拼接任意字符串。 */
  private id(value: string): string {
    if (typeof value !== 'string' ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)) {
      throw new Error('视频资源标识无效');
    }
    return value;
  }
}
