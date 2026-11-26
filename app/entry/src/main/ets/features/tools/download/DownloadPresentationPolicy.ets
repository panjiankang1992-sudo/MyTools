/** 下载结果展示模型，不依赖网络或设备能力。 */
export interface DownloadTagSource { name: string; }
export interface DownloadFileSource { fileName: string; sizeBytes?: number; storageUri: string; tagStatus: string; tags: DownloadTagSource[]; }
export interface DownloadFilePresentation { name: string; size: string; kind: string; archive: string; tagStatus: string; tags: string[]; }

/** 将历史接口中的缺失字段和任务状态转换为可安全展示的数据。 */
export class DownloadPresentationPolicy {
  /** 只格式化有限且非负的整数字节数，未知大小不伪装成零。 */
  static size(bytes: number | undefined): string {
    if (typeof bytes !== 'number' || !Number.isSafeInteger(bytes) || bytes < 0) return '';
    const units: string[] = ['B', 'KB', 'MB', 'GB', 'TB'];
    let value = bytes;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
    return `${value.toFixed(unit === 0 ? 0 : 2)} ${units[unit]}`;
  }
  /** 根据文件扩展名区分展示图标，视频即使归入图片库也仍显示为视频。 */
  static kind(name: string): string {
    const extension = name.split('.').pop()?.toLowerCase() ?? '';
    if (['mp4', 'mov', 'mkv', 'webm', 'avi', 'm4v'].includes(extension)) return 'video';
    if (['jpg', 'jpeg', 'png', 'webp', 'gif', 'heic', 'bmp'].includes(extension)) return 'image';
    if (['mp3', 'wav', 'm4a', 'aac', 'flac', 'ogg'].includes(extension)) return 'audio';
    return 'file';
  }
  /** 兼容旧记录的空列表与缺失标签，去重后保持服务端标签顺序。 */
  static files(items: DownloadFileSource[] | undefined): DownloadFilePresentation[] {
    if (!Array.isArray(items)) return [];
    return items.filter((item: DownloadFileSource) => !!item).map((item: DownloadFileSource): DownloadFilePresentation => {
      const name = typeof item.fileName === 'string' ? item.fileName.trim() : '';
      const archive = typeof item.storageUri === 'string' ? item.storageUri.trim() : '';
      const tags: string[] = [];
      // 历史数据可能缺少 tags，不能因此让整个聊天页面渲染失败。
      if (Array.isArray(item.tags)) item.tags.forEach((tag: DownloadTagSource) => {
        const value = tag && typeof tag.name === 'string' ? tag.name.trim() : '';
        if (value && !tags.includes(value)) tags.push(value);
      });
      return { name, size: DownloadPresentationPolicy.size(item.sizeBytes), kind: DownloadPresentationPolicy.kind(name), archive,
        tagStatus: typeof item.tagStatus === 'string' ? item.tagStatus : 'PENDING', tags };
    });
  }
  /** 已保存结果与后处理失败分别展示，未知状态不能显示成功。 */
  static state(status: string, files: DownloadFilePresentation[]): string {
    if (['ACCEPTED', 'PLANNING', 'RUNNING', 'UPLOADING', 'CANCELLING'].includes(status)) return 'active';
    if (status === 'FAILED') return files.some((file: DownloadFilePresentation) => !!file.archive) ? 'partial' : 'failed';
    if (status === 'SUCCEEDED') return files.some((file: DownloadFilePresentation) => file.tagStatus === 'FAILED') ? 'attention' : 'done';
    if (status === 'CANCELLED') return 'cancelled';
    if (status === 'NO_MATCH') return 'no_match';
    if (status === 'EXPIRED') return 'expired';
    return 'unknown';
  }
  /** 限制进度在有效百分比范围，非法值按未报告处理。 */
  static progress(value: number | undefined): number {
    return typeof value === 'number' && Number.isFinite(value) ? Math.round(Math.max(0, Math.min(100, value))) : 0;
  }
}
