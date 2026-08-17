/** 约束混合媒体查看器的相邻资源预加载范围和票据复用条件。 */
export class RemoteMediaPreloadPolicy {
  /** 按距离由近到远返回当前项前后各五项的去重索引，序列首尾循环衔接。 */
  neighborIndexes(length: number, currentIndex: number, radius: number = 5): number[] {
    if (!Number.isSafeInteger(length) || !Number.isSafeInteger(currentIndex) || length < 2 ||
      currentIndex < 0 || currentIndex >= length || !Number.isSafeInteger(radius) || radius < 1) return [];
    const indexes: number[] = [];
    const maximum = Math.min(radius, length - 1);
    for (let distance = 1; distance <= maximum; distance++) {
      const previous = (currentIndex - distance + length) % length;
      const next = (currentIndex + distance) % length;
      if (previous !== currentIndex && !indexes.includes(previous)) indexes.push(previous);
      if (next !== currentIndex && !indexes.includes(next)) indexes.push(next);
    }
    return indexes;
  }

  /** 判断本地文件URI或短期播放票据是否仍适合直接复用。 */
  reusable(url: string, expiresAt: number, now: number = Date.now()): boolean {
    if (url.startsWith('file://')) return true;
    return Number.isFinite(expiresAt) && expiresAt > now + 30000;
  }
}
