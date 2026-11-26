/** 约束混合媒体查看器的相邻资源预加载范围和票据复用条件。 */
export class RemoteMediaPreloadPolicy {
  /** 按距离优先下一项，准备前后各十二项；分页图库关闭循环，避免预热临时页尾。 */
  neighborIndexes(length: number, currentIndex: number, radius: number = 12, wrap: boolean = true, preferPrevious: boolean = false): number[] {
    if (!Number.isSafeInteger(length) || !Number.isSafeInteger(currentIndex) || length < 2 ||
      currentIndex < 0 || currentIndex >= length || !Number.isSafeInteger(radius) || radius < 1) return [];
    const indexes: number[] = [];
    const maximum = Math.min(radius, length - 1);
    for (let distance = 1; distance <= maximum; distance++) {
      const previous = wrap ? (currentIndex - distance + length) % length : currentIndex - distance;
      const next = wrap ? (currentIndex + distance) % length : currentIndex + distance;
      const pair = preferPrevious ? [previous, next] : [next, previous];
      for (const index of pair) {
        if (index >= 0 && index < length && index !== currentIndex && !indexes.includes(index)) indexes.push(index);
      }
    }
    return indexes;
  }

  /** 判断本地文件URI或短期播放票据是否仍适合直接复用。 */
  reusable(url: string, expiresAt: number, now: number = Date.now()): boolean {
    if (url.startsWith('file://')) return true;
    return Number.isFinite(expiresAt) && expiresAt > now + 30000;
  }
}
