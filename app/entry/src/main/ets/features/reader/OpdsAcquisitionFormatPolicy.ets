import { BookFormat, resolveBookFormat } from './ReaderModels';

/** 仅把明确的OPDS acquisition媒体类型或文件扩展名映射为阅读格式。 */
export class OpdsAcquisitionFormatPolicy {
  /** 返回受支持格式；未知或伪造媒体类型失败关闭。 */
  resolve(url: string, mediaType: string): BookFormat {
    const byName = resolveBookFormat(url.split('?')[0]);
    if (byName !== 'unknown') return byName;
    const normalized = mediaType.toLowerCase().split(';')[0].trim();
    if (normalized === 'application/epub+zip') return 'epub';
    if (normalized === 'application/pdf') return 'pdf';
    if (normalized === 'application/vnd.comicbook+zip' || normalized === 'application/x-cbz' ||
      normalized === 'application/vnd.comicbook-rar') return 'cbz';
    if (normalized === 'text/plain') return 'txt';
    if (normalized === 'application/x-mobipocket-ebook' || normalized === 'application/vnd.amazon.ebook') {
      return 'mobi';
    }
    return 'unknown';
  }
}
