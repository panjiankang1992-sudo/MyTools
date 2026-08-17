import common from '@ohos.app.ability.common';
import fs from '@ohos.file.fs';
import http from '@ohos.net.http';
import { SourceUrlPolicy } from './SourceUrlPolicy';
import util from '@ohos.util';
import { DownloadCancellationToken } from './DownloadCancellationToken';

interface CoverFormat {
  extension: string;
  width: number;
  height: number;
}

/**
 * 通过受控网络请求缓存远程封面，避免ArkUI图片组件直接访问不可信URL。
 */
export class SafeRemoteCoverCache {
  private static readonly MAX_BYTES: number = 2 * 1024 * 1024;
  private static readonly MAX_HEADER_BYTES: number = 128 * 1024;
  private static readonly MAX_DIMENSION: number = 20000;
  private static readonly MAX_PIXELS: number = 80 * 1000 * 1000;
  private static readonly MAX_FILES: number = 96;
  private static readonly MAX_TOTAL_BYTES: number = 128 * 1024 * 1024;
  private static readonly MAX_READER_PAGE_BYTES: number = 12 * 1024 * 1024;
  private static readonly MAX_READER_PAGE_FILES: number = 256;
  private static readonly MAX_READER_PAGE_TOTAL_BYTES: number = 512 * 1024 * 1024;
  private readonly urlPolicy: SourceUrlPolicy = new SourceUrlPolicy();
  private readonly active: Map<string, Promise<string>> = new Map<string, Promise<string>>();
  private activeOwnedCount: number = 0;

  /**
   * 下载并校验远程封面，成功后返回应用缓存中的本地文件URI。
   */
  async cache(context: common.UIAbilityContext, url: string,
    requestHeaders?: Record<string, string>, cancellation?: DownloadCancellationToken): Promise<string> {
    if (!/^https?:\/\//i.test(url)) return url;
    const activeKey = `cover|${url}|${this.headerIdentity(requestHeaders)}`;
    // 导航事务拥有的可取消封面不共享Promise，避免取消书架或搜索消费者。
    if (cancellation !== undefined) {
      this.activeOwnedCount++;
      try {
        return await this.download(context, url, requestHeaders, 'remote-cover-cache',
          SafeRemoteCoverCache.MAX_BYTES, SafeRemoteCoverCache.MAX_FILES,
          SafeRemoteCoverCache.MAX_TOTAL_BYTES, '远程封面', cancellation);
      } finally {
        this.activeOwnedCount--;
      }
    }
    const running = this.active.get(activeKey);
    if (running !== undefined) return running;
    const task = this.download(context, url, requestHeaders, 'remote-cover-cache', SafeRemoteCoverCache.MAX_BYTES,
      SafeRemoteCoverCache.MAX_FILES, SafeRemoteCoverCache.MAX_TOTAL_BYTES, '远程封面');
    this.active.set(activeKey, task);
    try {
      return await task;
    } finally {
      this.active.delete(activeKey);
    }
  }

  /**
   * 下载并校验书源漫画页，成功后返回与封面缓存隔离的本地文件URI。
   */
  async cacheReaderPage(context: common.UIAbilityContext, url: string,
    requestHeaders?: Record<string, string>, cancellation?: DownloadCancellationToken): Promise<string> {
    if (!/^https?:\/\//i.test(url)) throw new Error('书源漫画页仅支持HTTP或HTTPS');
    const activeKey = `reader-page|${url}|${this.headerIdentity(requestHeaders)}`;
    // 页面拥有的可取消任务不共享Promise，避免取消其他阅读事务。
    if (cancellation !== undefined) {
      this.activeOwnedCount++;
      try {
        return await this.download(context, url, requestHeaders, 'remote-reader-page-cache',
          SafeRemoteCoverCache.MAX_READER_PAGE_BYTES, SafeRemoteCoverCache.MAX_READER_PAGE_FILES,
          SafeRemoteCoverCache.MAX_READER_PAGE_TOTAL_BYTES, '书源漫画页', cancellation);
      } finally {
        this.activeOwnedCount--;
      }
    }
    const running = this.active.get(activeKey);
    if (running !== undefined) return running;
    const task = this.download(context, url, requestHeaders, 'remote-reader-page-cache',
      SafeRemoteCoverCache.MAX_READER_PAGE_BYTES, SafeRemoteCoverCache.MAX_READER_PAGE_FILES,
      SafeRemoteCoverCache.MAX_READER_PAGE_TOTAL_BYTES, '书源漫画页');
    this.active.set(activeKey, task);
    try {
      return await task;
    } finally {
      this.active.delete(activeKey);
    }
  }

  /**
   * 清理可重新下载的远程封面缓存，并返回释放的字节数。
   */
  clear(context: common.UIAbilityContext): number {
    if (this.active.size > 0 || this.activeOwnedCount > 0) throw new Error('封面仍在下载，请稍后重试');
    const directory = `${context.cacheDir}/remote-cover-cache`;
    let released = 0;
    try {
      if (!fs.accessSync(directory)) return 0;
      const names = fs.listFileSync(directory);
      names.forEach((name: string) => {
        const path = `${directory}/${name}`;
        try {
          const size = fs.statSync(path).size;
          fs.unlinkSync(path);
          released += size;
        } catch (_) {
        }
      });
      return released;
    } catch (_) {
      throw new Error('无法清理远程封面缓存');
    }
  }

  /**
   * 清理可重新下载的书源漫画页缓存，并返回释放的字节数。
   */
  clearReaderPages(context: common.UIAbilityContext): number {
    if (this.active.size > 0 || this.activeOwnedCount > 0) throw new Error('远程图片仍在下载，请稍后重试');
    return this.clearDirectory(`${context.cacheDir}/remote-reader-page-cache`, '无法清理书源漫画缓存');
  }

  /** 统计可重新下载的远程封面字节数。 */
  size(context: common.UIAbilityContext): number {
    return this.directorySize(`${context.cacheDir}/remote-cover-cache`);
  }

  /** 统计可重新下载的书源漫画页字节数。 */
  readerPageSize(context: common.UIAbilityContext): number {
    return this.directorySize(`${context.cacheDir}/remote-reader-page-cache`);
  }

  private async download(context: common.UIAbilityContext, url: string,
    requestHeaders: Record<string, string> | undefined, directoryName: string, maxBytes: number, maxFiles: number,
    maxTotalBytes: number, label: string, cancellation?: DownloadCancellationToken): Promise<string> {
    if (cancellation?.isCancelled()) throw new Error(`${label}加载已取消`);
    await this.urlPolicy.assertSafe(url);
    if (cancellation?.isCancelled()) throw new Error(`${label}加载已取消`);
    const directory = `${context.cacheDir}/${directoryName}`;
    try {
      if (!fs.accessSync(directory)) fs.mkdirSync(directory, true);
    } catch (_) {
      throw new Error('无法创建远程封面缓存');
    }
    const key = this.hash(`v3:${directoryName}:${url}:${this.headerFingerprint(requestHeaders)}`);
    const existing = this.findExisting(directory, key);
    if (existing.length > 0) {
      return `file://${existing}`;
    }
    // 缓存实例之间也必须隔离临时文件，避免同键下载互相截断。
    const temporaryPath = `${directory}/${key}.${util.generateRandomUUID(true)}.part`;
    const request = http.createHttp();
    let file: fs.File | undefined = undefined;
    let received = 0;
    let streamError = '';
    let contentType = '';
    const headerBytes: number[] = [];
    const cancelHandler = (): void => request.destroy();
    try {
      cancellation?.bind(cancelHandler);
      file = fs.openSync(temporaryPath, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
      request.on('headersReceive', (headers: Object) => {
        const values = headers as Record<string, Object>;
        Object.keys(values).forEach((keyName: string) => {
          const lower = keyName.toLowerCase();
          const value = `${values[keyName]}`;
          if (lower === 'location') {
            streamError = '远程封面重定向已阻止';
            request.destroy();
          } else if (lower === 'content-length') {
            const declared = Number(value);
            if (!Number.isSafeInteger(declared) || declared < 0) {
              streamError = `${label}响应长度无效`;
              request.destroy();
            } else if (declared > maxBytes) {
              streamError = `${label}超过${Math.floor(maxBytes / 1024 / 1024)} MB`;
              request.destroy();
            }
          } else if (lower === 'content-type') {
            contentType = value.toLowerCase().split(';')[0].trim();
          }
        });
      });
      request.on('dataReceive', (buffer: ArrayBuffer) => {
        if (cancellation?.isCancelled() || streamError.length > 0 || file === undefined) return;
        received += buffer.byteLength;
        if (received > maxBytes) {
          streamError = `${label}超过${Math.floor(maxBytes / 1024 / 1024)} MB`;
          request.destroy();
          return;
        }
        const bytes = new Uint8Array(buffer);
        for (let index = 0; index < bytes.length && headerBytes.length < SafeRemoteCoverCache.MAX_HEADER_BYTES;
          index++) headerBytes.push(bytes[index]);
        fs.writeSync(file.fd, buffer);
      });
      const headers: Record<string, string> = {
        'Accept': 'image/avif,image/webp,image/png,image/jpeg,image/gif,image/bmp'
      };
      if (requestHeaders !== undefined) {
        Object.keys(requestHeaders).forEach((key: string) => {
          const lower = key.toLowerCase();
          if (['accept', 'content-length', 'host', 'connection', 'transfer-encoding'].includes(lower)) return;
          headers[key] = requestHeaders[key];
        });
      }
      const responseCode = await request.requestInStream(url, {
        method: http.RequestMethod.GET,
        header: headers,
        connectTimeout: 10000,
        readTimeout: 20000
      });
      if (cancellation?.isCancelled()) throw new Error(`${label}加载已取消`);
      if (streamError.length > 0) throw new Error(streamError);
      if (responseCode !== 200) throw new Error(`${label}请求失败（${responseCode}）`);
      if (received === 0) throw new Error(`${label}为空`);
      const format = this.detectFormat(headerBytes, contentType, label);
      if (format.width <= 0 || format.height <= 0 || format.width > SafeRemoteCoverCache.MAX_DIMENSION ||
        format.height > SafeRemoteCoverCache.MAX_DIMENSION ||
        format.width * format.height > SafeRemoteCoverCache.MAX_PIXELS) {
        throw new Error(`${label}像素尺寸超过安全限制`);
      }
      fs.closeSync(file);
      file = undefined;
      const finalPath = `${directory}/${key}.${format.extension}`;
      // 跨实例同键任务可能已提交相同有效缓存，后完成者直接复用。
      if (fs.accessSync(finalPath) && fs.statSync(finalPath).size > 2) {
        fs.unlinkSync(temporaryPath);
      } else {
        fs.renameSync(temporaryPath, finalPath);
      }
      this.trim(directory, finalPath, maxFiles, maxTotalBytes);
      return `file://${finalPath}`;
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error(`${label}缓存失败`);
    } finally {
      cancellation?.unbind(cancelHandler);
      if (file !== undefined) {
        try {
          fs.closeSync(file);
        } catch (_) {
        }
      }
      request.off('headersReceive');
      request.off('dataReceive');
      request.destroy();
      try {
        if (fs.accessSync(temporaryPath)) {
          fs.unlinkSync(temporaryPath);
        }
      } catch (_) {
      }
    }
  }

  private detectFormat(bytes: number[], contentType: string, label: string): CoverFormat {
    let extension = '';
    let dimensions: number[] = [];
    if (bytes.length >= 3 && bytes[0] === 0xFF && bytes[1] === 0xD8 && bytes[2] === 0xFF) extension = 'jpg';
    else if (bytes.length >= 8 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4E &&
      bytes[3] === 0x47 && bytes[4] === 0x0D && bytes[5] === 0x0A && bytes[6] === 0x1A && bytes[7] === 0x0A) {
      extension = 'png';
    } else if (bytes.length >= 6 && bytes[0] === 0x47 && bytes[1] === 0x49 && bytes[2] === 0x46 &&
      bytes[3] === 0x38 && (bytes[4] === 0x37 || bytes[4] === 0x39) && bytes[5] === 0x61) extension = 'gif';
    else if (bytes.length >= 12 && bytes[0] === 0x52 && bytes[1] === 0x49 && bytes[2] === 0x46 &&
      bytes[3] === 0x46 && bytes[8] === 0x57 && bytes[9] === 0x45 && bytes[10] === 0x42 &&
      bytes[11] === 0x50) extension = 'webp';
    else if (bytes.length >= 2 && bytes[0] === 0x42 && bytes[1] === 0x4D) extension = 'bmp';
    if (extension.length === 0) throw new Error(`${label}不是支持的图片格式`);
    if (extension === 'jpg') dimensions = this.readJpegDimensions(bytes);
    else if (extension === 'png' && bytes.length >= 24) {
      dimensions = [this.readUInt32BE(bytes, 16), this.readUInt32BE(bytes, 20)];
    } else if (extension === 'gif' && bytes.length >= 10) {
      dimensions = [this.readUInt16LE(bytes, 6), this.readUInt16LE(bytes, 8)];
    } else if (extension === 'bmp' && bytes.length >= 26) {
      dimensions = [this.readUInt32LE(bytes, 18), Math.abs(this.readInt32LE(bytes, 22))];
    } else if (extension === 'webp') {
      dimensions = this.readWebpDimensions(bytes);
    }
    if (dimensions.length !== 2) throw new Error(`无法读取${label}像素尺寸`);
    const expected = extension === 'jpg' ? 'image/jpeg' : `image/${extension}`;
    if (contentType.length > 0 && contentType !== expected && contentType !== 'application/octet-stream') {
      throw new Error(`${label}响应类型与内容不一致`);
    }
    return { extension: extension, width: dimensions[0], height: dimensions[1] };
  }

  private readJpegDimensions(bytes: number[]): number[] {
    let cursor = 2;
    while (cursor + 4 <= bytes.length) {
      while (cursor < bytes.length && bytes[cursor] !== 0xFF) cursor++;
      while (cursor < bytes.length && bytes[cursor] === 0xFF) cursor++;
      if (cursor >= bytes.length) break;
      const marker = bytes[cursor++];
      if (marker === 0xD8 || marker === 0xD9 || (marker >= 0xD0 && marker <= 0xD7)) continue;
      if (cursor + 2 > bytes.length) break;
      const segmentLength = (bytes[cursor] << 8) | bytes[cursor + 1];
      if (segmentLength < 2 || cursor + segmentLength > bytes.length) break;
      const isSof = (marker >= 0xC0 && marker <= 0xC3) || (marker >= 0xC5 && marker <= 0xC7) ||
        (marker >= 0xC9 && marker <= 0xCB) || (marker >= 0xCD && marker <= 0xCF);
      if (isSof && segmentLength >= 7) {
        return [(bytes[cursor + 5] << 8) | bytes[cursor + 6],
          (bytes[cursor + 3] << 8) | bytes[cursor + 4]];
      }
      cursor += segmentLength;
    }
    throw new Error('无法在128 KB头部内读取JPEG尺寸');
  }

  private readWebpDimensions(bytes: number[]): number[] {
    if (bytes.length < 30) throw new Error('WebP图片头不完整');
    const kind = String.fromCharCode(bytes[12], bytes[13], bytes[14], bytes[15]);
    if (kind === 'VP8X') return [1 + this.readUInt24LE(bytes, 24), 1 + this.readUInt24LE(bytes, 27)];
    if (kind === 'VP8L' && bytes[20] === 0x2F) {
      return [1 + bytes[21] + ((bytes[22] & 0x3F) << 8),
        1 + (bytes[22] >> 6) + (bytes[23] << 2) + ((bytes[24] & 0x0F) << 10)];
    }
    if (kind === 'VP8 ' && bytes[23] === 0x9D && bytes[24] === 0x01 && bytes[25] === 0x2A) {
      return [this.readUInt16LE(bytes, 26) & 0x3FFF, this.readUInt16LE(bytes, 28) & 0x3FFF];
    }
    throw new Error('WebP图片尺寸信息无效');
  }

  private readUInt16LE(bytes: number[], offset: number): number {
    return bytes[offset] | (bytes[offset + 1] << 8);
  }

  private readUInt24LE(bytes: number[], offset: number): number {
    return bytes[offset] | (bytes[offset + 1] << 8) | (bytes[offset + 2] << 16);
  }

  private readUInt32LE(bytes: number[], offset: number): number {
    return (bytes[offset] | (bytes[offset + 1] << 8) | (bytes[offset + 2] << 16) |
      (bytes[offset + 3] << 24)) >>> 0;
  }

  private readUInt32BE(bytes: number[], offset: number): number {
    return ((bytes[offset] << 24) | (bytes[offset + 1] << 16) | (bytes[offset + 2] << 8) |
      bytes[offset + 3]) >>> 0;
  }

  private readInt32LE(bytes: number[], offset: number): number {
    return bytes[offset] | (bytes[offset + 1] << 8) | (bytes[offset + 2] << 16) | (bytes[offset + 3] << 24);
  }

  private findExisting(directory: string, key: string): string {
    try {
      const names = fs.listFileSync(directory);
      const name = names.find((value: string) => value.startsWith(`${key}.`) && !value.endsWith('.part'));
      return name === undefined ? '' : `${directory}/${name}`;
    } catch (_) {
      return '';
    }
  }

  private trim(directory: string, retainedPath: string, maxFiles: number, maxTotalBytes: number): void {
    let paths: string[] = [];
    try {
      paths = fs.listFileSync(directory).filter((name: string) => !name.endsWith('.part'))
        .map((name: string) => `${directory}/${name}`);
      paths.sort((left: string, right: string) => {
        if (left === retainedPath) return -1;
        if (right === retainedPath) return 1;
        return fs.statSync(right).mtime - fs.statSync(left).mtime;
      });
    } catch (_) {
      return;
    }
    let retainedBytes = 0;
    paths.forEach((path: string, index: number) => {
      let size = 0;
      try {
        size = fs.statSync(path).size;
      } catch (_) {
      }
      const keep = index < maxFiles && retainedBytes + size <= maxTotalBytes;
      if (keep) {
        retainedBytes += size;
        return;
      }
      try {
        fs.unlinkSync(path);
      } catch (_) {
      }
    });
  }

  private clearDirectory(directory: string, errorMessage: string): number {
    let released = 0;
    try {
      if (!fs.accessSync(directory)) return 0;
      fs.listFileSync(directory).forEach((name: string) => {
        const path = `${directory}/${name}`;
        try {
          released += fs.statSync(path).size;
          fs.unlinkSync(path);
        } catch (_) {
        }
      });
      return released;
    } catch (_) {
      throw new Error(errorMessage);
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

  private headerFingerprint(headers?: Record<string, string>): string {
    return this.hash(this.headerIdentity(headers));
  }

  private headerIdentity(headers?: Record<string, string>): string {
    if (headers === undefined) return 'public';
    const keys = Object.keys(headers).sort((left: string, right: string) => left.localeCompare(right));
    if (keys.length > 32) throw new Error('远程图片请求头超过32项');
    keys.forEach((key: string) => {
      const value = headers[key];
      if (!/^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}$/.test(key) || value.length > 4096 ||
        /[\u0000-\u001F\u007F]/.test(value)) {
        throw new Error('远程图片请求头名称或值无效');
      }
    });
    return keys.map((key: string) => `${key.toLowerCase()}:${headers[key]}`).join('\n');
  }

  private hash(value: string): string {
    let first = 2166136261;
    let second = 5381;
    for (let index = 0; index < value.length; index++) {
      const code = value.charCodeAt(index);
      first = Math.imul(first ^ code, 16777619);
      second = Math.imul(second, 33) ^ code;
    }
    return `${(first >>> 0).toString(16).padStart(8, '0')}${(second >>> 0).toString(16).padStart(8, '0')}`;
  }
}
