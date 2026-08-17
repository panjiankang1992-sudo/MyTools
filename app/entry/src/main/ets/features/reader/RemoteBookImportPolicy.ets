import { RemoteMediaItem, RemoteMediaSource } from '../media/RemoteMediaModels';
import { BookFormat, resolveBookFormat } from './ReaderModels';
import { RemoteBookDisplayMetadataPolicy } from './RemoteBookDisplayMetadataPolicy';

export interface RemoteBookCandidate {
  name: string;
  author: string;
  format: BookFormat;
  resourceUri: string;
  sourceId: string;
  identityMaterial: string;
  tags: string[];
  size: number;
  lastModified: string;
}

/**
 * 将远程目录项投影为可进入同步书架的最小图书元数据。
 */
export class RemoteBookImportPolicy {
  private readonly displayMetadataPolicy: RemoteBookDisplayMetadataPolicy = new RemoteBookDisplayMetadataPolicy();

  /**
   * 复核来源、路径、格式、大小和服务端书架字段上限。
   */
  candidate(item: RemoteMediaItem, source: RemoteMediaSource): RemoteBookCandidate {
    const sourceId = this.text(source.id, 256, false, '远程来源标识');
    const author = this.text(source.name, 200, true, '远程来源名称') || '远程书库';
    const path = this.path(item.path);
    const filename = this.text(item.name, 512, false, '远程图书文件名');
    if (item.kind === 'directory') throw new Error('文件夹不能加入书架');
    const format = resolveBookFormat(filename);
    if (format === 'unknown') throw new Error('远程图书格式不受支持');
    if (format === 'cbr') throw new Error('CBR解码器尚未接入，请转换为CBZ或ZIP后阅读');
    if (!Number.isSafeInteger(item.size) || item.size <= 0) throw new Error('远程图书大小无效');
    const maximum = format === 'txt' ? 500 * 1024 * 1024 : 100 * 1024 * 1024;
    if (item.size > maximum) throw new Error(format === 'txt' ? '远程TXT超过500 MB限制' :
      '远程图书超过100 MB限制');
    const metadata = this.displayMetadataPolicy.metadata(filename, item.tags);
    const name = metadata.title;
    if (name.length < 1 || name.length > 300) throw new Error('远程图书名称无效');
    return {
      name: name,
      author: author,
      format: format,
      resourceUri: path,
      sourceId: sourceId,
      identityMaterial: `remote:v1\u0000${sourceId}\u0000${path}`,
      tags: metadata.tags,
      size: item.size,
      lastModified: this.text(item.lastModified, 256, true, '远程图书更新时间')
    };
  }

  private path(value: string): string {
    if (typeof value !== 'string' || value.length < 2 || value.length > 4096 || !value.startsWith('/') ||
      value.endsWith('/') || value.includes('//') || /[\u0000-\u001F\u007F\\]/.test(value) ||
      value.split('/').some((part: string) => part === '.' || part === '..')) {
      throw new Error('远程图书路径无效');
    }
    return value;
  }

  private text(value: string, maximum: number, optional: boolean, label: string): string {
    if (typeof value !== 'string' || value.length > maximum || /[\u0000-\u001F\u007F]/.test(value) ||
      (!optional && value.length === 0)) throw new Error(`${label}无效`);
    return value;
  }
}
