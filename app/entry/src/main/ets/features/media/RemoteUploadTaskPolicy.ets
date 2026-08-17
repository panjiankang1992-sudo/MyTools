export interface RemoteUploadHeaderResult {
  status: number;
  error: string;
}

export interface RemoteUploadProgress {
  uploadedBytes: number;
  totalBytes: number;
}

/**
 * 系统后台上传任务的不可信响应头、状态码和进度回调策略。
 */
export class RemoteUploadTaskPolicy {
  /** 解析上传响应头，拒绝重定向、超大响应和非JSON结果。 */
  headers(value: Object): RemoteUploadHeaderResult {
    const headers = value as Record<string, Object>;
    let status = 0;
    let contentType = '';
    for (const key of Object.keys(headers)) {
      const lower = key.toLowerCase();
      if (lower === 'location') return { status: status, error: '远程上传不允许重定向' };
      if ([':status', 'status', 'statuscode', 'responsecode'].includes(lower)) {
        const parsed = Number(headers[key]);
        if (!Number.isSafeInteger(parsed) || parsed < 100 || parsed > 599) {
          return { status: 0, error: '远程上传响应状态无效' };
        }
        if (status !== 0 && status !== parsed) return { status: 0, error: '远程上传响应状态冲突' };
        status = parsed;
      } else if (lower === 'content-length') {
        const length = Number(headers[key]);
        if (!Number.isSafeInteger(length) || length < 0 || length > 1024 * 1024) {
          return { status: status, error: '远程上传响应长度无效或超过1 MB限制' };
        }
      } else if (lower === 'content-type') {
        contentType = `${headers[key]}`.toLowerCase();
      } else if (lower === 'content-encoding') {
        const encoding = `${headers[key]}`.trim().toLowerCase();
        if (encoding.length > 0 && encoding !== 'identity') {
          return { status: status, error: '远程上传不接受压缩响应' };
        }
      }
    }
    if (contentType.length > 0 && !contentType.includes('application/json') && !contentType.includes('+json')) {
      return { status: status, error: '远程上传响应类型无效' };
    }
    return { status: status, error: '' };
  }

  /** 只向页面投影单调、有界且内部一致的上传进度。 */
  progress(uploadedBytes: number, totalBytes: number, previousUploadedBytes: number): RemoteUploadProgress | undefined {
    if (!Number.isSafeInteger(uploadedBytes) || !Number.isSafeInteger(totalBytes) ||
      !Number.isSafeInteger(previousUploadedBytes) || uploadedBytes < 0 || totalBytes < 1 ||
      totalBytes > 2 * 1024 * 1024 * 1024 || uploadedBytes > totalBytes || uploadedBytes < previousUploadedBytes) {
      return undefined;
    }
    return { uploadedBytes: uploadedBytes, totalBytes: totalBytes };
  }
}
