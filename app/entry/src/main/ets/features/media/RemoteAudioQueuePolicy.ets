import { RemoteMediaItem } from './RemoteMediaModels';

/**
 * 为远程音频提供有界、稳定且可循环的当前目录播放队列。
 */
export class RemoteAudioQueuePolicy {
  /**
   * 从已归一化目录中提取最多10000个音频项。
   */
  queue(items: RemoteMediaItem[]): RemoteMediaItem[] {
    return items.filter((item: RemoteMediaItem) => item.kind === 'audio').slice(0, 10000);
  }

  /**
   * 计算循环相邻项，非法输入返回-1。
   */
  nextIndex(itemCount: number, currentIndex: number, direction: number): number {
    if (!Number.isInteger(itemCount) || itemCount < 1 || itemCount > 10000 ||
      !Number.isInteger(currentIndex) || currentIndex < 0 || currentIndex >= itemCount ||
      !Number.isFinite(direction) || direction === 0) return -1;
    const step = direction > 0 ? 1 : -1;
    return (currentIndex + step + itemCount) % itemCount;
  }

  /**
   * 返回当前项附近最多101项供界面惰性展示，播放队列本身不被截断。
   */
  visibleWindow(queue: RemoteMediaItem[], currentPath: string): RemoteMediaItem[] {
    if (queue.length <= 101) return queue;
    const current = queue.findIndex((item: RemoteMediaItem) => item.path === currentPath);
    if (current < 0) return queue.slice(0, 101);
    const start = Math.max(0, Math.min(queue.length - 101, current - 50));
    return queue.slice(start, start + 101);
  }
}
