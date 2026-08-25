import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import {
  MediaCatalogFilters, MediaCatalogItem, MediaCatalogKind, MediaDirectoryFilter, MediaGalleryPage,
  MediaStoryboardFrame, MediaTagFilter, MediaVideoDetail, MediaVideoDirectory
} from './MediaCatalogModels';
import { RemotePlaybackDescriptor } from './RemoteMediaModels';
import { RemoteMediaResponseNormalizer } from './RemoteMediaResponseNormalizer';

/**
 * 新版图库与视频目录 API，响应中不接收真实文件路径。
 */
export class MediaCatalogApi {
  private readonly client: AuthorizedApiClient;
  private readonly baseUrl: string;
  private readonly playbackNormalizer: RemoteMediaResponseNormalizer = new RemoteMediaResponseNormalizer();

  constructor(baseUrl: string, authManager: AuthSessionManager) {
    this.baseUrl = authManager.normalizeBusinessBaseUrl(baseUrl);
    this.client = new AuthorizedApiClient(baseUrl, authManager);
  }

  /** 查询图片或视频页面独立的目录与标签筛选项。 */
  async filters(mode: string, keyword: string, excludeAdult: boolean,
    cancellation?: DownloadCancellationToken): Promise<MediaCatalogFilters> {
    if (mode !== 'gallery' && mode !== 'video') throw new Error('多媒体页面模式无效');
    // 新媒体服务尚未提供聚合筛选接口，页面先使用完整媒体列表的客户端筛选。
    return { directories: [], tags: [] };
  }

  /** 查询图库分页。 */
  async gallery(directoryId: string, tag: string, keyword: string, page: number, excludeAdult: boolean,
    cancellation?: DownloadCancellationToken): Promise<MediaGalleryPage> {
    const envelope = await this.client.get('/api/app/v1/media/items?includeMissing=false&limit=100', cancellation);
    const value = this.record(envelope.data);
    const raw = value['items'];
    if (!Array.isArray(raw) || raw.length > 100) throw new Error('图库响应无效');
    const query = keyword.trim().toLocaleLowerCase();
    const items = raw.map((entry: Object): MediaCatalogItem => this.item(entry))
      .filter((item: MediaCatalogItem): boolean => item.kind === 'IMAGE' &&
        (query.length === 0 || item.name.toLocaleLowerCase().includes(query)) &&
        (tag.length === 0 || item.tags.includes(tag)) &&
        (!excludeAdult || !item.tags.some((name: string): boolean => name.toLocaleLowerCase() === 'r18')));
    return { items, total: items.length, page, pageSize: 100 };
  }

  /** 查询视频目录聚合。 */
  async videoDirectories(directoryId: string, tag: string, keyword: string, excludeAdult: boolean,
    cancellation?: DownloadCancellationToken): Promise<MediaVideoDirectory[]> {
    const envelope = await this.client.get('/api/app/v1/media/items?includeMissing=false&limit=100', cancellation);
    const value = this.record(envelope.data);
    const raw = value['items'];
    if (!Array.isArray(raw) || raw.length > 100) throw new Error('视频目录响应无效');
    const query = keyword.trim().toLocaleLowerCase();
    const items = raw.map((entry: Object): MediaCatalogItem => this.item(entry))
      .filter((item: MediaCatalogItem): boolean => item.kind === 'VIDEO' &&
        (query.length === 0 || item.name.toLocaleLowerCase().includes(query)) &&
        (tag.length === 0 || item.tags.includes(tag)) &&
        (!excludeAdult || !item.tags.some((name: string): boolean => name.toLocaleLowerCase() === 'r18')));
    if (items.length === 0) return [];
    return [{ directoryId: '000000000000000000000001', name: '媒体库', fileCount: items.length,
      totalSizeBytes: items.reduce((total: number, item: MediaCatalogItem): number => total + item.sizeBytes, 0),
      latestModifiedAt: '', topItems: items.slice(0, 3) }];
  }

  /** 查询一个视频目录中的文件。 */
  async videoDirectoryItems(directoryId: string, excludeAdult: boolean,
    cancellation?: DownloadCancellationToken): Promise<MediaCatalogItem[]> {
    if (!/^[0-9a-f]{24}$/.test(directoryId)) throw new Error('视频目录标识无效');
    const directories = await this.videoDirectories('', '', '', excludeAdult, cancellation);
    return directories.length === 0 ? [] : directories[0].topItems;
  }

  /** 查询视频详情。 */
  async videoDetail(videoId: string, cancellation?: DownloadCancellationToken): Promise<MediaVideoDetail> {
    if (!/^[1-9]\d{0,18}$/.test(videoId)) throw new Error('视频标识无效');
    const envelope = await this.client.get(`/api/app/v1/videos/${videoId}`, cancellation);
    const value = this.record(envelope.data);
    const rawStoryboard = value['storyboard'];
    if (!Array.isArray(rawStoryboard) || rawStoryboard.length > 12) throw new Error('视频截图响应无效');
    return {
      videoId: this.id(value, 'videoId'), name: this.text(value, 'name', 512),
      sizeBytes: this.number(value, 'sizeBytes'), format: this.optionalText(value, 'format', 64),
      videoCodec: this.optionalText(value, 'videoCodec', 64), audioCodec: this.optionalText(value, 'audioCodec', 64),
      durationMs: this.number(value, 'durationMs'), width: this.number(value, 'width'),
      height: this.number(value, 'height'), directoryName: this.text(value, 'directoryName', 512),
      summary: this.optionalText(value, 'summary', 500), description: this.optionalText(value, 'description', 2000),
      descriptionStatus: this.text(value, 'descriptionStatus', 32),
      thumbnailUrl: this.optionalPath(value, 'thumbnailUrl'),
      storyboard: rawStoryboard.map((entry: Object): MediaStoryboardFrame => this.storyboard(entry))
    };
  }

  /** 为视频详情页签发短期播放票据。 */
  async playTicket(videoId: string, cancellation?: DownloadCancellationToken): Promise<RemotePlaybackDescriptor> {
    if (!/^[1-9]\d{0,18}$/.test(videoId)) throw new Error('视频标识无效');
    const envelope = await this.client.post(`/api/app/v1/videos/${videoId}/play-ticket`, cancellation);
    return this.playbackNormalizer.normalizePlayback(envelope.data, this.baseUrl, 'MYTOOLS_LOCAL');
  }

  /** 将受认证缩略图或截图下载到缓存URI。 */
  async downloadImage(path: string, targetUri: string,
    cancellation?: DownloadCancellationToken): Promise<number> {
    if (!/^\/api\/(localfiles\/[1-9]\d{0,18}\/thumbnail|app\/v1\/videos\/[1-9]\d{0,18}\/storyboard\/(?:[1-9]|1[0-2]))$/.test(path)) {
      throw new Error('媒体图片地址无效');
    }
    return this.client.downloadJpegShareToUri(path, targetUri, cancellation);
  }

  private videoDirectory(value: Object): MediaVideoDirectory {
    const data = this.record(value);
    const raw = data['topItems'];
    if (!Array.isArray(raw) || raw.length > 3) throw new Error('视频目录预览无效');
    return { directoryId: this.hashId(data, 'directoryId'), name: this.text(data, 'name', 512),
      fileCount: this.number(data, 'fileCount'), totalSizeBytes: this.number(data, 'totalSizeBytes'),
      latestModifiedAt: this.optionalText(data, 'latestModifiedAt', 64),
      topItems: raw.map((entry: Object): MediaCatalogItem => this.item(entry)) };
  }

  private item(value: Object): MediaCatalogItem {
    const data = this.record(value);
    if (data['displayName'] !== undefined) return this.libraryItem(data);
    const kind = this.text(data, 'kind', 16) as MediaCatalogKind;
    if (!['VIDEO', 'IMAGE', 'AUDIO', 'WEB', 'TEXT', 'OTHER'].includes(kind)) throw new Error('媒体类型无效');
    const rawTags = data['tags'];
    if (!Array.isArray(rawTags) || rawTags.length > 64) throw new Error('媒体标签无效');
    return { itemId: this.id(data, 'itemId'), name: this.text(data, 'name', 512), kind,
      mimeType: this.optionalText(data, 'mimeType', 255), sizeBytes: this.number(data, 'sizeBytes'),
      modifiedAt: this.optionalText(data, 'modifiedAt', 64), thumbnailUrl: this.optionalPath(data, 'thumbnailUrl'),
      tags: rawTags.map((tag: Object): string => `${tag}`.substring(0, 64)),
      directoryId: this.hashId(data, 'directoryId'), directoryName: this.text(data, 'directoryName', 512) };
  }

  private libraryItem(data: Record<string, Object>): MediaCatalogItem {
    const itemId = this.uuid(data, 'id');
    const name = this.text(data, 'displayName', 1024);
    const mimeType = this.optionalText(data, 'mimeType', 255);
    const rawTags = data['tags'];
    if (!Array.isArray(rawTags) || rawTags.length > 64) throw new Error('媒体标签无效');
    const kind = mimeType.startsWith('image/') ? 'IMAGE' : mimeType.startsWith('video/') ? 'VIDEO' :
      mimeType.startsWith('audio/') ? 'AUDIO' : mimeType.startsWith('text/') ? 'TEXT' : 'OTHER';
    return { itemId, name, kind: kind as MediaCatalogKind, mimeType,
      sizeBytes: this.number(data, 'sizeBytes'), modifiedAt: '', thumbnailUrl: '',
      tags: rawTags.map((value: Object): string => `${value}`.substring(0, 64)),
      directoryId: '000000000000000000000001', directoryName: '媒体库' };
  }

  private directoryFilter(value: Object): MediaDirectoryFilter {
    const data = this.record(value);
    return { directoryId: this.hashId(data, 'directoryId'), name: this.text(data, 'name', 512),
      fileCount: this.number(data, 'fileCount'), latestModifiedAt: this.optionalText(data, 'latestModifiedAt', 64) };
  }

  private tagFilter(value: Object): MediaTagFilter {
    const data = this.record(value);
    return { name: this.text(data, 'name', 64), fileCount: this.number(data, 'fileCount') };
  }

  private storyboard(value: Object): MediaStoryboardFrame {
    const data = this.record(value);
    const sequence = this.number(data, 'sequence');
    if (sequence < 1 || sequence > 12) throw new Error('视频截图序号无效');
    return { sequence, timestampMs: this.number(data, 'timestampMs'), imageUrl: this.optionalPath(data, 'imageUrl') };
  }

  private record(value: Object | undefined): Record<string, Object> {
    if (value === undefined || value === null || typeof value !== 'object' || Array.isArray(value)) {
      throw new Error('多媒体响应无效');
    }
    return value as Record<string, Object>;
  }

  private text(data: Record<string, Object>, key: string, limit: number): string {
    const value = data[key];
    if (typeof value !== 'string' || value.length === 0 || value.length > limit || /[\u0000-\u001F\u007F]/.test(value)) {
      throw new Error(`多媒体字段${key}无效`);
    }
    return value;
  }

  private optionalText(data: Record<string, Object>, key: string, limit: number): string {
    const value = data[key];
    if (value === undefined || value === null) return '';
    if (typeof value !== 'string' || value.length > limit || /[\u0000-\u001F\u007F]/.test(value)) {
      throw new Error(`多媒体字段${key}无效`);
    }
    return value;
  }

  private optionalPath(data: Record<string, Object>, key: string): string {
    const value = this.optionalText(data, key, 256);
    if (value.length > 0 && !value.startsWith('/api/')) throw new Error(`多媒体字段${key}无效`);
    return value;
  }

  private number(data: Record<string, Object>, key: string): number {
    const value = Number(data[key]);
    if (!Number.isSafeInteger(value) || value < 0) throw new Error(`多媒体字段${key}无效`);
    return value;
  }

  private id(data: Record<string, Object>, key: string): string {
    const value = `${data[key] ?? ''}`;
    if (!/^[1-9]\d{0,18}$/.test(value)) throw new Error(`多媒体字段${key}无效`);
    return value;
  }

  private hashId(data: Record<string, Object>, key: string): string {
    const value = `${data[key] ?? ''}`;
    if (!/^[0-9a-f]{24}$/.test(value)) throw new Error(`多媒体字段${key}无效`);
    return value;
  }

  private uuid(data: Record<string, Object>, key: string): string {
    const value = `${data[key] ?? ''}`;
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)) {
      throw new Error(`多媒体字段${key}无效`);
    }
    return value;
  }
}
