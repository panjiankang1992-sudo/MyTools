import common from '@ohos.app.ability.common';
import fs from '@ohos.file.fs';
import request from '@ohos.request';
import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { RemoteUploadHeaderResult, RemoteUploadProgress, RemoteUploadTaskPolicy } from
  '../media/RemoteUploadTaskPolicy';

interface SourceImportRequest {
  sourceUrl: string;
  bookUrl: string;
  title: string;
  author: string;
}

interface UploadAttemptResult {
  succeeded: boolean;
  authenticationRejected: boolean;
  message: string;
}

/** 后端电子书导入任务。 */
export interface EbookImportTask {
  taskId: string;
  status: string;
  fileName: string;
  message: string;
  updatedAt: number;
}

/**
 * 通过MyTools后端上传本地图书或启动书源图书下载。
 */
export class EbookImportApi {
  private static stagingSequence: number = 0;
  private readonly baseUrl: string;
  private readonly authManager: AuthSessionManager;
  private readonly client: AuthorizedApiClient;
  private readonly uploadTaskPolicy: RemoteUploadTaskPolicy = new RemoteUploadTaskPolicy();

  constructor(baseUrl: string, authManager: AuthSessionManager) {
    this.baseUrl = authManager.normalizeBusinessBaseUrl(baseUrl);
    this.authManager = authManager;
    this.client = new AuthorizedApiClient(baseUrl, authManager);
  }

  /** 上传一个用户主动选择的电子书。 */
  async upload(context: common.UIAbilityContext, uri: string, filename: string,
    onProgress?: (uploadedBytes: number, totalBytes: number) => void): Promise<void> {
    if (!uri.startsWith('file://') || filename.length < 3 || filename.length > 300) {
      throw new Error('电子书上传参数无效');
    }
    const stagedPath = await this.stageSelectedFile(context, uri, filename);
    const stagedUri = `internal://cache/${stagedPath.substring(context.cacheDir.length + 1)}`;
    try {
      let token = await this.authManager.validAccessToken();
      let result = await this.uploadOnce(context, stagedUri, filename, token, onProgress);
      if (result.authenticationRejected) {
        token = await this.authManager.forceRefresh();
        result = await this.uploadOnce(context, stagedUri, filename, token, onProgress);
      }
      if (!result.succeeded) throw new Error(result.message.length > 0 ? result.message : '电子书上传失败');
    } finally {
      try {
        fs.unlinkSync(stagedPath);
      } catch (_) {
      }
    }
  }

  /** 启动书源图书后台下载。 */
  async startSource(book: SourceImportRequest): Promise<EbookImportTask> {
    return this.task((await this.client.postJson('/api/ebooks/import/source', book)).data);
  }

  /** 查询书源图书后台下载状态。 */
  async findSource(taskId: string): Promise<EbookImportTask> {
    if (!/^[0-9a-f-]{36}$/.test(taskId)) throw new Error('电子书导入任务标识无效');
    return this.task((await this.client.get(`/api/ebooks/import/source/${encodeURIComponent(taskId)}`)).data);
  }

  private async uploadOnce(context: common.UIAbilityContext, uri: string, filename: string, token: string,
    onProgress?: (uploadedBytes: number, totalBytes: number) => void): Promise<UploadAttemptResult> {
    const file: request.File = { filename: filename, name: 'file', uri: uri, type: 'application/octet-stream' };
    const nameData: request.RequestData = { name: 'filename', value: filename };
    const config: request.UploadConfig = {
      url: `${this.baseUrl}/api/ebooks/import/upload`,
      header: { 'Authorization': `Bearer ${token}` },
      method: 'POST', files: [file], data: [nameData]
    };
    try {
      const task = await request.uploadFile(context, config);
      return await new Promise<UploadAttemptResult>((resolve: (value: UploadAttemptResult) => void) => {
      let settled = false;
      let responseStatus = 0;
      let responseError = '';
      let lastUploadedBytes = 0;
      const finish = (states: request.TaskState[], failedEvent: boolean): void => {
        if (settled) return;
        settled = true;
        task.off('progress');
        task.off('headerReceive');
        task.off('complete');
        task.off('fail');
        const failed = states.find((state: request.TaskState) => state.responseCode !== 0);
        if (!failedEvent && failed === undefined && responseError.length === 0 &&
          responseStatus >= 200 && responseStatus < 300) {
          resolve({ succeeded: true, authenticationRejected: false, message: '' });
          return;
        }
        const code = responseStatus > 0 ? responseStatus : (failed?.responseCode ?? 0);
        const message = responseError.length > 0 ? responseError : (failed?.message ?? '电子书上传失败');
        resolve({ succeeded: false, authenticationRejected: code === 401,
          message: code > 0 ? `上传失败：${code} · ${message}` : message });
      };
      task.on('progress', (uploadedSize: number, totalSize: number) => {
        const progress: RemoteUploadProgress | undefined = this.uploadTaskPolicy.progress(
          uploadedSize, totalSize, lastUploadedBytes);
        if (progress === undefined) return;
        lastUploadedBytes = progress.uploadedBytes;
        onProgress?.(progress.uploadedBytes, progress.totalBytes);
      });
      task.on('headerReceive', (header: object) => {
        const result: RemoteUploadHeaderResult = this.uploadTaskPolicy.headers(header);
        if (result.status > 0) responseStatus = result.status;
        if (result.error.length > 0) responseError = result.error;
      });
      task.on('complete', (states: request.TaskState[]) => finish(states, false));
      task.on('fail', (states: request.TaskState[]) => finish(states, true));
      });
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('无法创建电子书上传任务');
    }
  }

  private async stageSelectedFile(context: common.UIAbilityContext, uri: string, filename: string): Promise<string> {
    let sourceFile: fs.File | undefined = undefined;
    let targetFile: fs.File | undefined = undefined;
    let target = '';
    try {
      // 系统选择器回调结束前不可直接打开其返回的用户文件URI。
      await new Promise<void>((resolve: () => void) => setTimeout(resolve, 100));
      sourceFile = fs.openSync(uri, fs.OpenMode.READ_ONLY);
      const source = fs.statSync(sourceFile.fd);
      if (!source.isFile() || source.size < 1 || source.size > 500 * 1024 * 1024) {
        throw new Error('电子书为空或超过500 MB限制');
      }
      EbookImportApi.stagingSequence = (EbookImportApi.stagingSequence + 1) % 1000000;
      const dot = filename.lastIndexOf('.');
      const extension = dot >= 0 ? filename.substring(dot).toLowerCase().replace(/[^.a-z0-9]/g, '') : '';
      target = `${context.cacheDir}/ebook-upload-${Date.now()}-${EbookImportApi.stagingSequence}${extension}`;
      targetFile = fs.openSync(target, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
      const buffer = new ArrayBuffer(1024 * 1024);
      let copied = 0;
      while (copied < source.size) {
        const readBytes = fs.readSync(sourceFile.fd, buffer);
        if (readBytes <= 0) throw new Error('电子书临时文件读取不完整');
        const written = fs.writeSync(targetFile.fd, readBytes === buffer.byteLength ? buffer : buffer.slice(0, readBytes));
        if (written !== readBytes) throw new Error('电子书临时文件写入不完整');
        copied += readBytes;
      }
      fs.closeSync(sourceFile);
      sourceFile = undefined;
      fs.closeSync(targetFile);
      targetFile = undefined;
      if (fs.statSync(target).size !== source.size) {
        try {
          fs.unlinkSync(target);
        } catch (_) {
        }
        throw new Error('电子书临时文件校验失败');
      }
      return target;
    } catch (error) {
      if (target.length > 0) {
        try {
          fs.unlinkSync(target);
        } catch (_) {
        }
      }
      if (error instanceof Error) throw error;
      throw new Error('无法读取系统选择的电子书');
    } finally {
      try {
        if (sourceFile !== undefined) fs.closeSync(sourceFile);
      } catch (_) {
      }
      try {
        if (targetFile !== undefined) fs.closeSync(targetFile);
      } catch (_) {
      }
    }
  }

  private task(value: Object | undefined): EbookImportTask {
    if (value === undefined || value === null || Array.isArray(value) || typeof value !== 'object') {
      throw new Error('电子书导入任务响应无效');
    }
    const data = value as Record<string, Object>;
    const taskId = `${data['taskId'] ?? ''}`;
    const status = `${data['status'] ?? ''}`;
    const fileName = `${data['fileName'] ?? ''}`.substring(0, 300);
    const message = `${data['message'] ?? ''}`.substring(0, 500);
    const updatedAt = Number(data['updatedAt']);
    if (!/^[0-9a-f-]{36}$/.test(taskId) || !['QUEUED', 'RUNNING', 'COMPLETED', 'FAILED'].includes(status) ||
      !Number.isSafeInteger(updatedAt) || updatedAt < 0) throw new Error('电子书导入任务字段无效');
    return { taskId: taskId, status: status, fileName: fileName, message: message, updatedAt: updatedAt };
  }
}
