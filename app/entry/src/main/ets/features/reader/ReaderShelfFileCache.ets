import common from '@ohos.app.ability.common';
import fs from '@ohos.file.fs';
import util from '@ohos.util';
import { Book } from './ReaderModels';
import { DigestTool } from '../tools/DigestTool';

/** 书架文件的私有缓存；身份与版本隔离，缓存命中不保存或复用播放票据。 */
export class ReaderShelfFileCache {
  private target: string = '';
  private maximumBytes: number = 100 * 1024 * 1024;

  /** 只为已上架文件启用，按账号、服务、来源和版本复用缓存，不因跨日重新下载。 */
  async prepare(context: common.UIAbilityContext, scope: string, endpoint: string, book: Book,
    legacyEndpoint: string = endpoint): Promise<string> {
    this.target = '';
    if (!scope || !['txt', 'epub', 'cbz', 'pdf', 'mobi', 'azw3'].includes(book.format)) return '';
    this.maximumBytes = (book.format === 'txt' ? 500 : 100) * 1024 * 1024;
    const identity = [scope, endpoint, book.id, book.sourceId, book.resourceUri,
      book.cacheVersion ?? '', book.format];
    const key = JSON.stringify(identity);
    const hash = await new DigestTool().digestText(key, 'SHA256');
    // 书架副本是用户保留的离线内容，不能放入系统可随时回收的缓存目录。
    const directory = `${context.filesDir}/reader-shelf-files-v2`;
    try {
      if (!fs.accessSync(directory)) fs.mkdirSync(directory, true);
      this.target = `${directory}/${hash}.${book.format}`;
      if (!fs.accessSync(this.target)) {
        // 迁移稳定地址及当前局域网地址下的旧副本，避免升级后重复下载。
        for (const oldEndpoint of [endpoint, legacyEndpoint]) {
          const oldIdentity: Object[] = [scope, oldEndpoint, book.id, book.sourceId, book.resourceUri,
            book.cacheVersion ?? '', book.format];
          for (const oldKey of [JSON.stringify(oldIdentity),
            JSON.stringify(oldIdentity.concat(Math.floor(Date.now() / 86400000) as Object))]) {
            const oldHash = await new DigestTool().digestText(oldKey, 'SHA256');
            const oldPath = `${context.cacheDir}/reader-shelf-files-v1/${oldHash}.${book.format}`;
            if (!fs.accessSync(this.target) && fs.accessSync(oldPath)) fs.renameSync(oldPath, this.target);
          }
        }
      }
      if (fs.accessSync(this.target)) {
        const size = fs.statSync(this.target).size;
        if (size > 0 && size <= this.maximumBytes) return this.target;
        this.invalidate();
      }
    } catch (_) { this.target = ''; }
    return '';
  }

  /** 返回已准备的持久文件位置，供解析结果缓存使用。 */
  path(): string { return this.target; }

  /** 检查持久副本是否完整存在，隔离磁盘访问异常。 */
  available(): boolean {
    try {
      if (!this.target || !fs.accessSync(this.target)) return false;
      const size = fs.statSync(this.target).size;
      return size > 0 && size <= this.maximumBytes;
    } catch (_) { return false; }
  }

  /** 下载和解析校验成功后原子保存，写入失败不影响本次阅读。 */
  save(source: string): string {
    if (!this.target) return '';
    if (source === this.target) return this.target;
    const temporary = `${this.target}.${util.generateRandomUUID(true)}.part`;
    try {
      const size = fs.statSync(source).size;
      if (size <= 0 || size > this.maximumBytes) return '';
      fs.copyFileSync(source, temporary);
      if (fs.accessSync(this.target)) fs.unlinkSync(this.target);
      fs.renameSync(temporary, this.target);
      // 已上架文件不按最近八本淘汰，由用户清除离线缓存或移出书架时释放。
      return this.target;
    } catch (_) {
      // 缓存是加速副本，不替代来源，不传播磁盘写入故障。
      return '';
    } finally {
      try { if (fs.accessSync(temporary)) fs.unlinkSync(temporary); } catch (_) {}
    }
  }

  /** 仅清理本次命中且校验失败的缓存副本。 */
  invalidate(): void {
    try { if (this.target && fs.accessSync(this.target)) fs.unlinkSync(this.target); } catch (_) {}
    try {
      if (this.target && fs.accessSync(`${this.target}.prepared-v1`)) fs.rmdirSync(`${this.target}.prepared-v1`);
    } catch (_) {}
  }
}
