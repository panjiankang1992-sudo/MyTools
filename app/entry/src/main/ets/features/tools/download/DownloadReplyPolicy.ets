/** 区分任务失败与已经保存的文件，避免把后续处理失败描述成全部下载失败。 */
export class DownloadReplyPolicy {
  /** 仅在失败任务确有归档结果时显示部分完成，不改变任务本身的状态。 */
  static hasSavedFiles(status: string, storageUris: string[]): boolean {
    return status === 'FAILED' && storageUris.some((uri: string) => typeof uri === 'string' && uri.trim().length > 0);
  }
}
