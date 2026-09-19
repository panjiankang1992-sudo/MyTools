import common from '@ohos.app.ability.common';
import fs from '@ohos.file.fs';
import util from '@ohos.util';
import { Book } from './ReaderModels';
import { DigestTool } from '../tools/DigestTool';

/** 书架文件的私有缓存；身份与版本隔离，缓存命中不保存或复用播放票据。 */
export class ReaderShelfFileCache {
  private target: string = '';

  /** 只为已上架文件启用，按账号、服务、来源、版本和日期划分缓存。 */
  async prepare(context: common.UIAbilityContext, scope: string, endpoint: string, book: Book): Promise<string> {
    this.target = '';
    if (!scope || !['txt', 'epub', 'cbz', 'pdf', 'mobi', 'azw3'].includes(book.format)) return '';
    const key = JSON.stringify([scope, endpoint, book.id, book.sourceId, book.resourceUri,
      book.cacheVersion ?? '', book.format, Math.floor(Date.now() / 86400000)]);
    const hash = await new DigestTool().digestText(key, 'SHA256');
    const directory = `${context.cacheDir}/reader-shelf-files-v1`;
    try {
      if (!fs.accessSync(directory)) fs.mkdirSync(directory, true);
      this.target = `${directory}/${hash}.${book.format}`;
      if (fs.accessSync(this.target)) {
        const size = fs.statSync(this.target).size;
        if (size > 0 && size <= 100 * 1024 * 1024) return this.target;
        this.invalidate();
      }
    } catch (_) { this.target = ''; }
    return '';
  }

  /** 下载和解析校验成功后原子保存，写入失败不影响本次阅读。 */
  save(source: string): void {
    if (!this.target || source === this.target) return;
    const temporary = `${this.target}.${util.generateRandomUUID(true)}.part`;
    try {
      const size = fs.statSync(source).size;
      if (size <= 0 || size > 100 * 1024 * 1024) return;
      fs.copyFileSync(source, temporary);
      if (fs.accessSync(this.target)) fs.unlinkSync(this.target);
      fs.renameSync(temporary, this.target);
      const directory = this.target.substring(0, this.target.lastIndexOf('/'));
      const paths = fs.listFileSync(directory).filter((name: string) =>
        /^[a-f0-9]{64}\.(txt|epub|cbz|pdf|mobi|azw3)$/.test(name))
        .map((name: string) => `${directory}/${name}`)
        .sort((a: string, b: string) => fs.statSync(a).mtime - fs.statSync(b).mtime);
      while (paths.length > 8) {
        const index = paths.findIndex((path: string) => path !== this.target);
        if (index < 0) break;
        fs.unlinkSync(paths.splice(index, 1)[0]);
      }
    } catch (_) {
      // 缓存是加速副本，不替代来源，不传播磁盘写入故障。
    } finally {
      try { if (fs.accessSync(temporary)) fs.unlinkSync(temporary); } catch (_) {}
    }
  }

  /** 仅清理本次命中且校验失败的缓存副本。 */
  invalidate(): void {
    try { if (this.target && fs.accessSync(this.target)) fs.unlinkSync(this.target); } catch (_) {}
  }
}
