import { ImagePriority, MediaImageSlots } from './MediaImageSlots';
import common from '@ohos.app.ability.common';
import fs from '@ohos.file.fs';
import { RemoteMediaApi } from './RemoteMediaApi';
import { DigestTool } from '../tools/DigestTool';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import util from '@ohos.util';

/** 缓存由MyTools后端安全缩放后的远程图片缩略图。 */
export class RemoteMediaThumbnailCache {
  readonly slots: MediaImageSlots = new MediaImageSlots();
  private readonly priorities: Map<string, ImagePriority> = new Map<string, ImagePriority>();
  private readonly lastTrim: Map<string, number> = new Map<string, number>();
  private readonly active: Map<string, Promise<string>> = new Map<string, Promise<string>>();
  private activeOwnedCount: number = 0;
  private readonly downloadTokens: Map<string, DownloadCancellationToken> = new Map<string, DownloadCancellationToken>();
  private readonly consumers: Map<string, number> = new Map<string, number>();
  private protectedBrowsePaths: Set<string> = new Set<string>();
  private readonly digestTool: DigestTool = new DigestTool();

  /** 已分配给后台工作线程的卡片进入屏幕时，提升其共享下载的优先级。 */
  promoteCatalog(itemId: string): void {
    const priority = this.priorities.get('512:0:7:library/media/' + itemId);
    if (priority && priority.level > 1) {
      priority.level = 1;
      this.slots.dispatch();
    }
  }

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
    cancellation?: DownloadCancellationToken, allowLocalImageFallback: boolean = false, priority: number = 0): Promise<string> {
    return this.cacheEdge(context, api, accountId, path, 512, cancellation, allowLocalImageFallback, priority);
  }

  /** 返回最大2048像素、仅供系统分享使用的本地JPEG URI。 */
  async cacheShare(context: common.UIAbilityContext, api: RemoteMediaApi, accountId: string,
    path: string, cancellation?: DownloadCancellationToken): Promise<string> {
    if (accountId.startsWith('local:') || accountId === 'library') {
      return this.cacheEdge(context, api, accountId, path, 0, cancellation, true);
    }
    return this.cacheEdge(context, api, accountId, path, 2048, cancellation);
  }

  /** 全屏预览使用有限尺寸图片，分享仍可单独读取原始文件。 */
  async cachePreview(context: common.UIAbilityContext, api: RemoteMediaApi, accountId: string,
    path: string, cancellation?: DownloadCancellationToken): Promise<string> {
    if (accountId.startsWith('local:')) return this.cacheEdge(context, api, accountId, path, 0, cancellation, true);
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
    return this.directorySize(`${context.cacheDir}/remote-media-thumbnails`) +
      this.directorySize(`${context.cacheDir}/remote-media-previews`);
  }

  /** 清理远程媒体缩略图并返回释放的字节数。 */
  clear(context: common.UIAbilityContext): number {
    if (this.active.size > 0 || this.activeOwnedCount > 0) {
      throw new Error('远程媒体缩略图仍在生成，请稍后重试');
    }
    const released = this.clearDirectory(`${context.cacheDir}/remote-media-thumbnails`) +
      this.clearDirectory(`${context.cacheDir}/remote-media-previews`);
    this.protectedBrowsePaths.clear();
    return released;
  }

  private clearDirectory(directory: string): number {
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
    edge: number, cancellation?: DownloadCancellationToken, allowLocalImageFallback: boolean = false, level: number = 0): Promise<string> {
    const identity = `${edge}:${allowLocalImageFallback ? 1 : 0}:${accountId.length}:${accountId}${path}`;
    if (cancellation?.isCancelled()) throw new Error('Image request cancelled');
    const existing = this.priorities.get(identity);
    if (existing && level < existing.level) {
      existing.level = level;
      this.slots.dispatch();
    }
    let task = this.active.get(identity);
    if (task === undefined || this.downloadTokens.get(identity)?.isCancelled()) {
      const token = new DownloadCancellationToken();
      this.downloadTokens.set(identity, token);
      this.activeOwnedCount++;
      const priority: ImagePriority = { level };
      this.priorities.set(identity, priority);
      task = this.download(context, api, accountId, path, identity, edge, token, allowLocalImageFallback, priority)
        .finally(() => {
          this.activeOwnedCount--;
          if (this.priorities.get(identity) === priority) this.priorities.delete(identity);
        });
      this.active.set(identity, task);
    }
    const shared = task;
    const ownedToken = this.downloadTokens.get(identity)!;
    this.consumers.set(identity, (this.consumers.get(identity) ?? 0) + 1);
    // 取消只释放当前消费者；最后一个消费者离开时才真正中止共同下载。
    return new Promise<string>((resolve, reject) => {
      let finished = false;
      const finish = (abort: boolean = false): boolean => {
        if (finished) return false;
        finished = true;
        cancellation?.unbind(cancel);
        if (this.active.get(identity) === shared) {
          const remaining = Math.max(0, (this.consumers.get(identity) ?? 1) - 1);
          this.consumers.set(identity, remaining);
          if (remaining === 0) {
            if (abort) ownedToken.cancel();
            this.active.delete(identity);
            this.downloadTokens.delete(identity);
            this.consumers.delete(identity);
          }
        }
        return true;
      };
      const cancel = (): void => { if (finish(true)) reject(new Error('Image request cancelled')); };
      cancellation?.bind(cancel);
      shared.then((uri: string) => { if (finish()) resolve(uri); },
        (error: Error) => { if (finish()) reject(error); });
    });
  }

  private async download(context: common.UIAbilityContext, api: RemoteMediaApi, accountId: string, path: string,
    identity: string, edge: number, cancellation: DownloadCancellationToken,
    allowLocalImageFallback: boolean, priority: ImagePriority): Promise<string> {
    if (cancellation?.isCancelled()) throw new Error('远程图片预览加载已取消');
    const thumbs = `${context.cacheDir}/remote-media-thumbnails`;
    const directory = edge === 512 ? thumbs : `${context.cacheDir}/remote-media-previews`;
    const name = `v5-${await this.digestTool.digestText(identity, 'SHA256')}.jpg`;
    const target = `${directory}/${name}`;
    try {
      if (!fs.accessSync(directory)) fs.mkdirSync(directory, true);
      if (edge !== 512 && !fs.accessSync(target) && fs.accessSync(`${thumbs}/${name}`)) {
        fs.renameSync(`${thumbs}/${name}`, target);
      }
      if (fs.accessSync(target) && fs.statSync(target).size > 2) {
        try { fs.utimes(target, Date.now()); } catch (_) { /* 访问时间更新失败仍可复用文件。 */ }
        return `file://${target}`;
      }
    } catch (_) {
      throw new Error('无法访问远程媒体缩略图缓存');
    }
    // 每个任务使用独立临时文件，避免同键共享任务与可取消任务互相截断写入。
    const release = await this.slots.acquire(priority, cancellation);
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
      else if (edge === 2048) await api.downloadPreviewImage(accountId, path, temporary, cancellation);
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
      // 清理异步执行并限频，不在每张图片完成时同步排序和反复读取磁盘元数据。
      if (Date.now() - (this.lastTrim.get(directory) ?? 0) > 60000) {
        this.lastTrim.set(directory, Date.now());
        this.trim(directory, target).catch(() => {});
      }
      return `file://${target}`;
    } finally {
      release();
      try {
        if (fs.accessSync(temporary)) fs.unlinkSync(temporary);
      } catch (_) {
      }
    }
  }

  private async trim(directory: string, protectedPath: string): Promise<void> {
    let files: string[] = [];
    try {
      files = (await fs.listFile(directory)).filter((name: string) => name.endsWith('.jpg'))
        .map((name: string) => `${directory}/${name}`);
    } catch (_) {
      return;
    }
    const thumbs = directory.endsWith('/remote-media-thumbnails');
    const maxFiles = thumbs ? 512 : 96;
    const maxBytes = (thumbs ? 48 : 96) * 1024 * 1024;
    const modified = new Map<string, number>();
    const sizes = new Map<string, number>();
    let bytes = 0;
    for (const path of files) {
      try {
        const stat = await fs.stat(path);
        modified.set(path, stat.mtime);
        sizes.set(path, stat.size);
        bytes += stat.size;
      } catch (_) { /* 文件可能已被清理。 */ }
    }
    files.sort((a: string, b: string) => (modified.get(a) ?? 0) - (modified.get(b) ?? 0));
    let remaining = files.length;
    for (const path of files) {
      if (remaining <= maxFiles && bytes <= maxBytes) break;
      if (path === protectedPath || this.protectedBrowsePaths.has(path)) continue;
      try {
        await fs.unlink(path);
        remaining--;
        bytes -= sizes.get(path) ?? 0;
      } catch (_) { /* 其他消费者可能已自行清理。 */ }
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
