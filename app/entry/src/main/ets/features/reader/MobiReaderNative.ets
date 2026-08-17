import mobiReader from 'libmobi_reader.so';
import fs from '@ohos.file.fs';
import { ReaderBlock, ReaderChapter, ReaderDocument } from './ReaderModels';

interface NativeMobiResult {
  title: string;
  content: string;
  version: number;
  encoding: number;
  sectionTitles: string[];
  resources: Record<string, string>;
  coverUri: string;
}

const MOBI_MAX_CHAPTERS: number = 2000;
const MOBI_MAX_CONTENT_CHARACTERS: number = 20 * 1024 * 1024;
const MOBI_MAX_RESOURCES: number = 1000;
const MOBI_MAX_RESOURCE_BYTES: number = 100 * 1024 * 1024;

/**
 * 调用独立原生模块解析无加密MOBI6，避免二进制处理阻塞UI模型代码。
 */
export class MobiReaderNative {
  /**
   * 解析缓存路径中的MOBI6并转换为当前阅读器文档。
   */
  parse(path: string, resourceDirectory: string): ReaderDocument {
    try {
      const result = JSON.parse(mobiReader.parse(path, resourceDirectory)) as NativeMobiResult;
      this.validateResult(result, resourceDirectory);
      const chapters = this.splitChapters(result.content, result.title, result.sectionTitles, result.resources);
      if (chapters.length === 0) throw new Error('MOBI中没有可显示的正文');
      return { kind: 'text', chapters: chapters, imageUris: [], coverUri: result.coverUri };
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('MOBI解析失败');
    }
  }

  private splitChapters(html: string, bookTitle: string, sectionTitles: string[],
    resources: Record<string, string>): ReaderChapter[] {
    const sections = html.split(/<\s*(?:mbp:)?pagebreak\b[^>]*>/gi);
    if (sections.length > MOBI_MAX_CHAPTERS) throw new Error('MOBI章节数量超过2000');
    const chapters: ReaderChapter[] = [];
    for (let index = 0; index < sections.length; index++) {
      const blocks = this.htmlToBlocks(sections[index], resources);
      const content = blocks.filter((block: ReaderBlock) => block.kind === 'text')
        .map((block: ReaderBlock) => block.text).join('\n').trim();
      const hasImage = blocks.some((block: ReaderBlock) => block.kind === 'image');
      if (content.length === 0 && !hasImage) continue;
      const heading = /<h[1-6][^>]*>([\s\S]*?)<\/h[1-6]>/i.exec(sections[index]);
      let title = index < sectionTitles.length ? this.htmlToText(sectionTitles[index]).substring(0, 80) : '';
      if (title.length === 0) title = heading === null ? '' : this.htmlToText(heading[1]).substring(0, 80);
      if (title.length === 0 && chapters.length === 0 && bookTitle.trim().length > 0) {
        title = bookTitle.trim().substring(0, 80);
      }
      if (title.length === 0) title = `第${chapters.length + 1}节`;
      chapters.push({ title: title, content: content.length > 0 ? content : '图片章节', blocks: blocks });
    }
    return chapters;
  }

  private validateResult(result: NativeMobiResult, resourceDirectory: string): void {
    if (typeof result.title !== 'string' || result.title.length > 4096 || typeof result.content !== 'string' ||
      result.content.length > MOBI_MAX_CONTENT_CHARACTERS || !Number.isSafeInteger(result.version) ||
      !Number.isSafeInteger(result.encoding) || !Array.isArray(result.sectionTitles) ||
      result.sectionTitles.length > MOBI_MAX_CHAPTERS || typeof result.resources !== 'object' ||
      result.resources === null || Array.isArray(result.resources) || typeof result.coverUri !== 'string') {
      throw new Error('MOBI原生解析结果结构无效');
    }
    for (let index = 0; index < result.sectionTitles.length; index++) {
      if (typeof result.sectionTitles[index] !== 'string' || result.sectionTitles[index].length > 4096) {
        throw new Error('MOBI目录标题无效');
      }
    }
    const keys = Object.keys(result.resources);
    if (keys.length > MOBI_MAX_RESOURCES) throw new Error('MOBI图片资源超过1000个');
    let totalBytes = 0;
    for (let index = 0; index < keys.length; index++) {
      if (!/^[1-9][0-9]{0,5}$/.test(keys[index])) throw new Error('MOBI图片资源编号无效');
      const uri = result.resources[keys[index]];
      totalBytes = this.validateResourceUri(uri, resourceDirectory, totalBytes, keys[index]);
    }
    if (result.coverUri.length > 0) this.validateResourceUri(result.coverUri, resourceDirectory, totalBytes, 'cover');
  }

  private validateResourceUri(uri: string, resourceDirectory: string, totalBytes: number, resourceId: string): number {
    if (typeof uri !== 'string' || !uri.startsWith('file://')) throw new Error('MOBI图片资源URI无效');
    const path = uri.substring('file://'.length);
    const expectedPrefix = `${resourceDirectory}/`;
    if (!path.startsWith(expectedPrefix)) throw new Error('MOBI图片资源路径越界');
    const name = path.substring(expectedPrefix.length);
    const escapedId = resourceId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const expectedName = resourceId === 'cover' ? /^cover\.(?:jpg|png|gif|webp|bmp)$/ :
      new RegExp(`^resource-${escapedId}\\.(?:jpg|png|gif|webp|bmp)$`);
    if (name.includes('/') || !expectedName.test(name)) {
      throw new Error('MOBI图片资源文件名无效');
    }
    let size = 0;
    try {
      const stat = fs.lstatSync(path);
      if (!stat.isFile() || stat.isSymbolicLink() || stat.size <= 0 || stat.size > 20 * 1024 * 1024) {
        throw new Error('MOBI图片资源文件无效');
      }
      size = stat.size;
    } catch (_) {
      throw new Error('MOBI图片资源文件无效');
    }
    const next = totalBytes + size;
    if (!Number.isSafeInteger(next) || next > MOBI_MAX_RESOURCE_BYTES) throw new Error('MOBI图片资源总量超过100 MB');
    return next;
  }

  private htmlToBlocks(html: string, resources: Record<string, string>): ReaderBlock[] {
    const blocks: ReaderBlock[] = [];
    const imagePattern = /<img\b([^>]*)>/gi;
    let offset = 0;
    let match: RegExpExecArray | null = imagePattern.exec(html);
    while (match !== null) {
      const text = this.htmlToText(html.substring(offset, match.index));
      if (text.length > 0) blocks.push({ kind: 'text', text: text, uri: '' });
      const reference = /recindex\s*=\s*["']?([0-9]+)/i.exec(match[1]);
      const uri = reference === null ? '' : (resources[reference[1]] ?? '');
      if (uri.length > 0) blocks.push({ kind: 'image', text: '', uri: uri });
      offset = match.index + match[0].length;
      match = imagePattern.exec(html);
    }
    const tail = this.htmlToText(html.substring(offset));
    if (tail.length > 0) blocks.push({ kind: 'text', text: tail, uri: '' });
    return blocks;
  }

  private htmlToText(html: string): string {
    return html.replace(/<script[\s\S]*?<\/script>/gi, '').replace(/<style[\s\S]*?<\/style>/gi, '')
      .replace(/<(br|\/p|\/div|\/h[1-6]|\/li)>/gi, '\n').replace(/<[^>]+>/g, '')
      .replace(/&nbsp;/gi, ' ').replace(/&amp;/gi, '&').replace(/&lt;/gi, '<').replace(/&gt;/gi, '>')
      .replace(/&quot;/gi, '"').replace(/&#39;/gi, "'").replace(/\r/g, '').replace(/\n{3,}/g, '\n\n').trim();
  }
}
