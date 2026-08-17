import { RemoteMediaItem } from './RemoteMediaModels';

/**
 * 在当前远程目录中选择与视频匹配的有界外挂字幕。
 */
export class RemoteSubtitlePolicy {
  /**
   * 优先选择同名VTT、同名SRT，再选择稳定排序后的语言后缀字幕。
   */
  findSidecar(items: RemoteMediaItem[], video: RemoteMediaItem): RemoteMediaItem | undefined {
    return this.findSidecars(items, video)[0];
  }

  /**
   * 返回最多32条稳定排序的同名或语言后缀字幕轨道。
   */
  findSidecars(items: RemoteMediaItem[], video: RemoteMediaItem): RemoteMediaItem[] {
    const dot = video.name.lastIndexOf('.');
    const base = (dot > 0 ? video.name.substring(0, dot) : video.name).toLowerCase();
    const candidates = items.filter((item: RemoteMediaItem) => {
      const lower = item.name.toLowerCase();
      return item.path !== video.path && Number.isFinite(item.size) && item.size >= 0 && item.size <= 1024 * 1024 &&
        (lower === `${base}.vtt` || lower === `${base}.srt` ||
          ((lower.startsWith(`${base}.`) || lower.startsWith(`${base}-`)) && /\.(?:vtt|srt)$/.test(lower)));
    });
    return candidates.sort((left: RemoteMediaItem, right: RemoteMediaItem) => {
      const leftExact = left.name.toLowerCase() === `${base}.vtt` ? 0 :
        (left.name.toLowerCase() === `${base}.srt` ? 1 : 2);
      const rightExact = right.name.toLowerCase() === `${base}.vtt` ? 0 :
        (right.name.toLowerCase() === `${base}.srt` ? 1 : 2);
      return leftExact === rightExact ? left.name.localeCompare(right.name) : leftExact - rightExact;
    }).slice(0, 32);
  }
}
