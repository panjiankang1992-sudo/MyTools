export interface RemoteMediaThumbnailProjection {
  path: string;
  uri: string;
}

/**
 * 保证全屏查看器销毁并重建图库组件后，仍能恢复有效缩略图投影。
 */
export class RemoteMediaThumbnailRestorePolicy {
  /** 合并当前投影与进入全屏前快照，并剔除失效路径或已删除缓存。 */
  restore(validPaths: string[], current: RemoteMediaThumbnailProjection[],
    snapshot: RemoteMediaThumbnailProjection[], available: (uri: string) => boolean): RemoteMediaThumbnailProjection[] {
    const allowed = new Set<string>(validPaths);
    const merged = new Map<string, RemoteMediaThumbnailProjection>();
    current.concat(snapshot).forEach((entry: RemoteMediaThumbnailProjection) => {
      if (allowed.has(entry.path) && available(entry.uri)) merged.set(entry.path, entry);
    });
    return Array.from(merged.values());
  }

  /** 返回当前图库仍缺少有效投影的媒体路径。 */
  missing(validPaths: string[], restored: RemoteMediaThumbnailProjection[]): string[] {
    const resolved = new Set<string>(restored.map((entry: RemoteMediaThumbnailProjection) => entry.path));
    return validPaths.filter((path: string) => !resolved.has(path));
  }
}
