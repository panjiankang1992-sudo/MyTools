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
  private static readonly MAX_LIBRARY_ITEMS: number = 10000;
  private static readonly GALLERY_PAGE_SIZE: number = 100;
  private readonly client: AuthorizedApiClient;
  private readonly baseUrl: string;
  private readonly playbackNormalizer: RemoteMediaResponseNormalizer = new RemoteMediaResponseNormalizer();
  private readonly libraryItemCache: Map<string, Object[]> = new Map<string, Object[]>();

  constructor(baseUrl: string, authManager: AuthSessionManager) {
    this.baseUrl = authManager.normalizeBusinessBaseUrl(baseUrl);
    this.client = new AuthorizedApiClient(baseUrl, authManager);
  }

  /** 查询图片、视频或音频页面独立的目录与标签筛选项。 */
  async filters(mode: string, keyword: string, excludeAdult: boolean,
    cancellation?: DownloadCancellationToken): Promise<MediaCatalogFilters> {
    if (mode !== 'gallery' && mode !== 'video' && mode !== 'audio') throw new Error('多媒体页面模式无效');
    const prefix = mode === 'gallery' ? 'image/' : (mode === 'video' ? 'video/' : 'audio/');
    const query = keyword.trim().toLocaleLowerCase();
    const items = (await this.cachedLibraryItems(prefix, cancellation))
      .map((entry: Object): MediaCatalogItem => this.item(entry))
      .filter((item: MediaCatalogItem): boolean =>
        (query.length === 0 || item.name.toLocaleLowerCase().includes(query) ||
          item.tags.some((tag: string): boolean => tag.toLocaleLowerCase().includes(query))) &&
        (!excludeAdult || !item.tags.some((name: string): boolean => name.toLocaleLowerCase() === 'r18')));
    const counts = new Map<string, number>();
    items.forEach((item: MediaCatalogItem): void => item.tags.forEach((tag: string): void => {
      counts.set(tag, (counts.get(tag) ?? 0) + 1);
    }));
    const tags: MediaTagFilter[] = Array.from(counts.entries())
      .map((entry: [string, number]): MediaTagFilter => ({ name: entry[0], fileCount: entry[1] }))
      .sort((left: MediaTagFilter, right: MediaTagFilter): number =>
        right.fileCount - left.fileCount || left.name.localeCompare(right.name));
    const directories: MediaDirectoryFilter[] = items.length === 0 ? [] : [{
      directoryId: '000000000000000000000001', name: '媒体库', fileCount: items.length, latestModifiedAt: ''
    }];
    return { directories, tags };
  }

  /** 查询图库分页。 */
  async gallery(directoryId: string, tag: string, keyword: string, page: number, excludeAdult: boolean,
    cancellation?: DownloadCancellationToken): Promise<MediaGalleryPage> {
    const safePage = Math.max(1, page);
    const raw = await this.cachedLibraryItems('image/', cancellation);
    const query = keyword.trim().toLocaleLowerCase();
    const items = raw.map((entry: Object): MediaCatalogItem => this.item(entry))
      .filter((item: MediaCatalogItem): boolean => item.kind === 'IMAGE' &&
        (query.length === 0 || item.name.toLocaleLowerCase().includes(query) ||
          item.tags.some((name: string): boolean => name.toLocaleLowerCase().includes(query))) &&
        (tag.length === 0 || item.tags.includes(tag)) &&
        (!excludeAdult || !item.tags.some((name: string): boolean => name.toLocaleLowerCase() === 'r18')));
    const start = (safePage - 1) * MediaCatalogApi.GALLERY_PAGE_SIZE;
    return { items: items.slice(start, start + MediaCatalogApi.GALLERY_PAGE_SIZE), total: items.length,
      page: safePage, pageSize: MediaCatalogApi.GALLERY_PAGE_SIZE };
  }

  /** 查询视频目录聚合。 */
  async videoDirectories(directoryId: string, tag: string, keyword: string, excludeAdult: boolean,
    cancellation?: DownloadCancellationToken): Promise<MediaVideoDirectory[]> {
    const raw = await this.allLibraryItems('video/', 2000, cancellation);
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
    const raw = await this.allLibraryItems('video/', 2000, cancellation);
    return raw.map((entry: Object): MediaCatalogItem => this.item(entry))
      .filter((item: MediaCatalogItem): boolean => !excludeAdult ||
        !item.tags.some((name: string): boolean => name.toLocaleLowerCase() === 'r18'));
  }

  /** 查询音频专辑聚合。 */
  async audioDirectories(directoryId: string, tag: string, keyword: string, excludeAdult: boolean,
    cancellation?: DownloadCancellationToken): Promise<MediaVideoDirectory[]> {
    const raw = await this.allLibraryItems('audio/', 2000, cancellation);
    const query = keyword.trim().toLocaleLowerCase();
    const items = raw.map((entry: Object): MediaCatalogItem => this.item(entry))
      .filter((item: MediaCatalogItem): boolean => item.kind === 'AUDIO' &&
        (query.length === 0 || item.name.toLocaleLowerCase().includes(query)) &&
        (tag.length === 0 || item.tags.includes(tag)) &&
        (!excludeAdult || !item.tags.some((name: string): boolean => name.toLocaleLowerCase() === 'r18')));
    const grouped = new Map<string, MediaCatalogItem[]>();
    items.forEach((item: MediaCatalogItem): void => {
      const name = item.directoryName.length > 0 ? item.directoryName : 'Unknown';
      grouped.set(name, (grouped.get(name) ?? []).concat(item));
    });
    const directories: MediaVideoDirectory[] = [];
    let index = 0;
    grouped.forEach((albumItems: MediaCatalogItem[], name: string): void => {
      directories.push({ directoryId: index.toString(16).padStart(24, '0'), name,
        fileCount: albumItems.length,
        totalSizeBytes: albumItems.reduce((total: number, item: MediaCatalogItem): number => total + item.sizeBytes, 0),
        latestModifiedAt: albumItems.length > 0 ? albumItems[0].modifiedAt : '', topItems: albumItems.slice(0, 3) });
      index++;
    });
    return directories;
  }

  /** 查询一个音频专辑中的文件。 */
  async audioDirectoryItems(directoryName: string, excludeAdult: boolean,
    cancellation?: DownloadCancellationToken): Promise<MediaCatalogItem[]> {
    const raw = await this.allLibraryItems('audio/', 2000, cancellation);
    return raw.map((entry: Object): MediaCatalogItem => this.item(entry))
      .filter((item: MediaCatalogItem): boolean => item.directoryName === directoryName &&
        (!excludeAdult || !item.tags.some((name: string): boolean => name.toLocaleLowerCase() === 'r18')));
  }

  /** 查询视频详情。 */
  async videoDetail(videoId: string, cancellation?: DownloadCancellationToken): Promise<MediaVideoDetail> {
    if (!this.isUuid(videoId)) throw new Error('视频标识无效');
    const envelope = await this.client.get(`/api/app/v1/media/items/${videoId}`, cancellation);
    const value = this.record(envelope.data);
    const item = this.libraryItem(value);
    if (item.kind !== 'VIDEO') throw new Error('媒体不是视频');
    const format = item.mimeType.startsWith('video/') ? item.mimeType.substring(6) : '';
    return {
      videoId: item.itemId, name: item.name, sizeBytes: item.sizeBytes, format,
      videoCodec: '', audioCodec: '', durationMs: 0, width: 0, height: 0,
      directoryName: item.directoryName, summary: '', description: item.tags.join('·'),
      descriptionStatus: 'PENDING', thumbnailUrl: item.thumbnailUrl, storyboard: []
    };
  }

  /** 为视频详情页签发短期播放票据。 */
  async playTicket(videoId: string, cancellation?: DownloadCancellationToken): Promise<RemotePlaybackDescriptor> {
    if (!this.isUuid(videoId)) throw new Error('视频标识无效');
    const envelope = await this.client.post(`/api/app/v1/media/items/${videoId}/play-ticket`, cancellation);
    return this.playbackNormalizer.normalizePlayback(envelope.data, this.baseUrl, 'MYTOOLS_LIBRARY');
  }

  /** 将受认证缩略图或截图下载到缓存URI。 */
  async downloadImage(path: string, targetUri: string,
    cancellation?: DownloadCancellationToken): Promise<number> {
    if (!/^\/api\/(localfiles\/[1-9]\d{0,18}\/thumbnail|app\/v1\/videos\/[1-9]\d{0,18}\/storyboard\/(?:[1-9]|1[0-2])|app\/v1\/media\/items\/[0-9a-f-]{36}\/thumbnail)$/.test(path)) {
      throw new Error('媒体图片地址无效');
    }
    return this.client.downloadImageToUri(path, targetUri, cancellation);
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
      sizeBytes: this.number(data, 'sizeBytes'), modifiedAt: '',
      thumbnailUrl: kind === 'IMAGE' || kind === 'VIDEO' ? `/api/app/v1/media/items/${itemId}/thumbnail` : '',
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

  private isUuid(value: string): boolean {
    return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
  }

  private async allLibraryItems(mimePrefix: string, maximum: number,
    cancellation?: DownloadCancellationToken): Promise<Object[]> {
    const result: Object[] = [];
    let afterId = '';
    while (result.length < maximum) {
      const cursor = afterId.length > 0 ? `&afterId=${encodeURIComponent(afterId)}` : '';
      const envelope = await this.client.get('/api/app/v1/media/items?includeMissing=false' +
        `&mimePrefix=${encodeURIComponent(mimePrefix)}&limit=100${cursor}`, cancellation);
      const value = this.record(envelope.data);
      const raw = value['items'];
      if (!Array.isArray(raw) || raw.length > 100) throw new Error('媒体库分页响应无效');
      result.push(...raw);
      const next = value['nextAfterId'];
      if (typeof next !== 'string' || !this.isUuid(next)) break;
      afterId = next;
    }
    return result.slice(0, maximum);
  }

  /** 在单次目录请求内复用完整媒体索引，避免筛选项与列表重复拉取。 */
  private async cachedLibraryItems(mimePrefix: string,
    cancellation?: DownloadCancellationToken): Promise<Object[]> {
    const cached = this.libraryItemCache.get(mimePrefix);
    if (cached !== undefined) return cached;
    const items = await this.allLibraryItems(mimePrefix, MediaCatalogApi.MAX_LIBRARY_ITEMS, cancellation);
    this.libraryItemCache.set(mimePrefix, items);
    return items;
  }
}
