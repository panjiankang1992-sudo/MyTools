import common from '@ohos.app.ability.common';
import fs from '@ohos.file.fs';
import { RemoteMediaApi } from './RemoteMediaApi';
import { DigestTool } from '../tools/DigestTool';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import util from '@ohos.util';

/** 缓存由MyTools后端安全缩放后的远程图片缩略图。 */
export class RemoteMediaThumbnailCache {
  private static readonly MAX_FILES: number = 256;
  private readonly active: Map<string, Promise<string>> = new Map<string, Promise<string>>();
  private activeOwnedCount: number = 0;
  private protectedBrowsePaths: Set<string> = new Set<string>();
  private readonly digestTool: DigestTool = new DigestTool();

  /** 保护当前图库仍在展示的缩略图，防止全屏高清图和预加载任务将其淘汰。 */
  protectBrowseUris(uris: string[]): void {
    const protectedPaths = new Set<string>();
    uris.slice(0, 100).forEach((uri: string) => {
      if (!uri.startsWith('file://')) return;
      const path = uri.substring('file://'.length);
      if (path.includes('/remote-media-thumbnails/') && path.endsWith('.jpg')) protectedPaths.add(path);
    });
    this.protectedBrowsePaths = protectedPaths;
  }

  /** 返回账户和路径隔离的本地JPEG缩略图URI。 */
  async cache(context: common.UIAbilityContext, api: RemoteMediaApi, accountId: string, path: string,
    cancellation?: DownloadCancellationToken, allowLocalImageFallback: boolean = false): Promise<string> {
    return this.cacheEdge(context, api, accountId, path, 512, cancellation, allowLocalImageFallback);
  }

  /** 返回最大2048像素、仅供系统分享使用的本地JPEG URI。 */
  async cacheShare(context: common.UIAbilityContext, api: RemoteMediaApi, accountId: string,
    path: string, cancellation?: DownloadCancellationToken): Promise<string> {
    if (accountId.startsWith('local:') || accountId === 'library') {
      return this.cacheEdge(context, api, accountId, path, 0, cancellation, true);
    }
    return this.cacheEdge(context, api, accountId, path, 2048, cancellation);
  }

  /** 缓存MyTools本地媒体接口返回的受认证原图，用于全屏查看。 */
  async cacheLocalOriginal(context: common.UIAbilityContext, api: RemoteMediaApi, accountId: string,
    path: string, cancellation?: DownloadCancellationToken): Promise<string> {
    if (!accountId.startsWith('local:') && accountId !== 'library') throw new Error('当前媒体来源不支持原图缓存');
    return this.cacheEdge(context, api, accountId, path, 0, cancellation, true);
  }

  /** 统计可重新生成的远程媒体缩略图字节数。 */
  size(context: common.UIAbilityContext): number {
    return this.directorySize(`${context.cacheDir}/remote-media-thumbnails`);
  }

  /** 清理远程媒体缩略图并返回释放的字节数。 */
  clear(context: common.UIAbilityContext): number {
    if (this.active.size > 0 || this.activeOwnedCount > 0) {
      throw new Error('远程媒体缩略图仍在生成，请稍后重试');
    }
    const directory = `${context.cacheDir}/remote-media-thumbnails`;
    this.protectedBrowsePaths = new Set<string>();
    let released = 0;
    try {
      if (!fs.accessSync(directory)) return 0;
      fs.listFileSync(directory).forEach((name: string) => {
        const path = `${directory}/${name}`;
        try {
          const size = fs.statSync(path).size;
          fs.unlinkSync(path);
          if (Number.isSafeInteger(size) && size > 0) released += size;
        } catch (_) {
        }
      });
      return released;
    } catch (_) {
      throw new Error('无法清理远程媒体缩略图缓存');
    }
  }

  private async cacheEdge(context: common.UIAbilityContext, api: RemoteMediaApi, accountId: string, path: string,
    edge: number, cancellation?: DownloadCancellationToken, allowLocalImageFallback: boolean = false): Promise<string> {
    const identity = `${edge}:${allowLocalImageFallback ? 1 : 0}:${accountId.length}:${accountId}${path}`;
    // 有取消所有权的页面任务不共享Promise，避免一个消费者取消其他消费者的下载。
    if (cancellation !== undefined) {
      this.activeOwnedCount++;
      try {
        return await this.download(context, api, accountId, path, identity, edge, cancellation,
          allowLocalImageFallback);
      } finally {
        this.activeOwnedCount--;
      }
    }
    const running = this.active.get(identity);
    if (running !== undefined) return running;
    const task = this.download(context, api, accountId, path, identity, edge, undefined, allowLocalImageFallback);
    this.active.set(identity, task);
    try {
      return await task;
    } finally {
      this.active.delete(identity);
    }
  }

  private async download(context: common.UIAbilityContext, api: RemoteMediaApi, accountId: string, path: string,
    identity: string, edge: number, cancellation?: DownloadCancellationToken,
    allowLocalImageFallback: boolean = false): Promise<string> {
    if (cancellation?.isCancelled()) throw new Error('远程图片预览加载已取消');
    const directory = `${context.cacheDir}/remote-media-thumbnails`;
    const target = `${directory}/v4-${await this.digestTool.digestText(identity, 'SHA256')}.jpg`;
    try {
      if (!fs.accessSync(directory)) fs.mkdirSync(directory, true);
      if (fs.accessSync(target) && fs.statSync(target).size > 2) return `file://${target}`;
    } catch (_) {
      throw new Error('无法访问远程媒体缩略图缓存');
    }
    // 每个任务使用独立临时文件，避免同键共享任务与可取消任务互相截断写入。
    const temporary = `${target}.${util.generateRandomUUID(true)}.part`;
    try {
      if (edge === 512) {
        try {
          await api.downloadThumbnail(accountId, path, temporary, cancellation);
        } catch (error) {
          if (!allowLocalImageFallback || !accountId.startsWith('local:') || cancellation?.isCancelled()) {
            throw error instanceof Error ? error : new Error('远程媒体缩略图下载失败');
          }
          await api.downloadLocalImagePreview(path, temporary, cancellation);
        }
      }
      else if (edge === 2048) await api.downloadShareImage(accountId, path, temporary, cancellation);
      else if (edge === 0 && allowLocalImageFallback) {
        await api.downloadLocalImagePreview(path, temporary, cancellation);
      }
      else throw new Error('远程媒体预览尺寸无效');
      if (cancellation?.isCancelled()) throw new Error('远程图片预览加载已取消');
      try {
        // 另一个同键任务可能已率先提交；有效目标可以直接复用。
        if (fs.accessSync(target) && fs.statSync(target).size > 2) {
          fs.unlinkSync(temporary);
        } else {
          fs.renameSync(temporary, target);
        }
      } catch (_) {
        throw new Error('无法提交远程媒体缩略图缓存');
      }
      this.trim(directory, target);
      return `file://${target}`;
    } finally {
      try {
        if (fs.accessSync(temporary)) fs.unlinkSync(temporary);
      } catch (_) {
      }
    }
  }

  private trim(directory: string, protectedPath: string): void {
    let files: string[] = [];
    try {
      files = fs.listFileSync(directory).filter((name: string) => name.endsWith('.jpg'))
        .map((name: string) => `${directory}/${name}`);
    } catch (_) {
      return;
    }
    files.sort((left: string, right: string) => this.modifiedAt(right) - this.modifiedAt(left));
    let remaining = files.length;
    for (let index = files.length - 1; index >= 0 && remaining > RemoteMediaThumbnailCache.MAX_FILES; index--) {
      const path = files[index];
      if (path === protectedPath || this.protectedBrowsePaths.has(path)) continue;
      try {
        fs.unlinkSync(path);
        remaining--;
      } catch (_) {
      }
    }
  }

  private modifiedAt(path: string): number {
    try {
      return fs.statSync(path).mtime;
    } catch (_) {
      return 0;
    }
  }

  private directorySize(directory: string): number {
    try {
      if (!fs.accessSync(directory)) return 0;
      return fs.listFileSync(directory).reduce((total: number, name: string) => {
        try {
          const size = fs.statSync(`${directory}/${name}`).size;
          return Number.isSafeInteger(size) && size > 0 ? total + size : total;
        } catch (_) {
          return total;
        }
      }, 0);
    } catch (_) {
      return 0;
    }
  }

}
