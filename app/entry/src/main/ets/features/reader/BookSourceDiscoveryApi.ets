import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';

interface StartDiscoveryRequest {
  url: string;
}

/** 书源网站分析任务。 */
export interface BookSourceDiscoveryTask {
  taskId: string;
  status: string;
  sourceJson: string;
  message: string;
  updatedAt: number;
}

/**
 * 调用MyTools后端的工程化书源导入能力。
 */
export class BookSourceDiscoveryApi {
  private readonly client: AuthorizedApiClient;

  constructor(baseUrl: string, authManager: AuthSessionManager) {
    this.client = new AuthorizedApiClient(baseUrl, authManager);
  }

  /** 启动网站书源分析。 */
  async start(url: string): Promise<BookSourceDiscoveryTask> {
    const request: StartDiscoveryRequest = { url: url };
    return this.task((await this.client.postJson('/api/app/v1/reader/source-discovery', request)).data);
  }

  /** 查询网站书源分析任务。 */
  async find(taskId: string): Promise<BookSourceDiscoveryTask> {
    if (!/^[0-9a-f-]{36}$/.test(taskId)) throw new Error('书源分析任务标识无效');
    return this.task((await this.client.get(`/api/app/v1/reader/source-discovery/${encodeURIComponent(taskId)}`)).data);
  }

  private task(value: Object | undefined): BookSourceDiscoveryTask {
    if (value === undefined || value === null || Array.isArray(value) || typeof value !== 'object') {
      throw new Error('书源分析响应无效');
    }
    const data = value as Record<string, Object>;
    const taskId = `${data['taskId'] ?? ''}`;
    const status = `${data['status'] ?? ''}`;
    const sourceJson = `${data['sourceJson'] ?? ''}`;
    const message = `${data['message'] ?? ''}`;
    const updatedAt = Number(data['updatedAt']);
    if (!/^[0-9a-f-]{36}$/.test(taskId) || !['RUNNING', 'SUCCEEDED', 'FAILED'].includes(status)
      || sourceJson.length > 5 * 1024 * 1024 || message.length > 500 || !Number.isSafeInteger(updatedAt) || updatedAt < 0) {
      throw new Error('书源分析响应字段无效');
    }
    return { taskId: taskId, status: status, sourceJson: sourceJson, message: message, updatedAt: updatedAt };
  }
}
