import { RemoteMediaTag } from '../media/RemoteMediaModels';

export interface RemoteBookDisplayMetadata {
  title: string;
  tags: string[];
}

/**
 * 将远程文件传输命名还原为适合列表与详情展示的图书元数据。
 */
export class RemoteBookDisplayMetadataPolicy {
  /**
   * 清理扩展名与传输后缀，并合并文件名和服务端提供的标签。
   */
  metadata(filename: string, serverTags?: RemoteMediaTag[]): RemoteBookDisplayMetadata {
    const base = this.removeExtension(filename.trim());
    const marker = base.toLowerCase().lastIndexOf('_tags_');
    const rawTitle = marker > 0 ? base.substring(0, marker) : base;
    const rawTagSection = marker > 0 ? base.substring(marker + 6) : '';
    const userMarker = rawTagSection.toLowerCase().lastIndexOf('_user');
    const filenameTags = this.tagNames(userMarker >= 0 ? rawTagSection.substring(0, userMarker) : rawTagSection);
    const apiTags = (serverTags ?? []).map((tag: RemoteMediaTag) => tag.name);
    const normalizedTitle = rawTitle.replace(/_+/g, ' ').replace(/\s+/g, ' ').trim();
    const bracketStart = normalizedTitle.indexOf('《');
    const bracketEnd = normalizedTitle.indexOf('》', bracketStart + 1);
    // 传输文件名常在书名后拼接排版、连载与作者信息，优先保留明确的中文书名边界。
    const title = bracketStart >= 0 && bracketEnd > bracketStart ?
      normalizedTitle.substring(bracketStart, bracketEnd + 1) : normalizedTitle;
    return { title: title.length > 0 ? title : base, tags: this.uniqueTags(filenameTags.concat(apiTags)) };
  }

  private removeExtension(value: string): string {
    const dot = value.lastIndexOf('.');
    return dot > 0 ? value.substring(0, dot).trim() : value;
  }

  private tagNames(value: string): string[] {
    if (value.length === 0) return [];
    return value.split(/[,，;；|#]+/).map((tag: string) => tag.replace(/^_+|_+$/g, '').trim())
      .filter((tag: string) => tag.length > 0);
  }

  private uniqueTags(values: string[]): string[] {
    const result: string[] = [];
    const seen = new Set<string>();
    values.forEach((value: string) => {
      const normalized = value.replace(/\s+/g, ' ').trim();
      const key = normalized.toLocaleLowerCase('zh-CN');
      if (normalized.length === 0 || normalized.length > 32 || seen.has(key) || result.length >= 8) return;
      seen.add(key);
      result.push(normalized);
    });
    return result.sort((left: string, right: string) => {
      const leftAdult = left.startsWith('R18-');
      const rightAdult = right.startsWith('R18-');
      if (leftAdult === rightAdult) return 0;
      return leftAdult ? -1 : 1;
    });
  }
}
