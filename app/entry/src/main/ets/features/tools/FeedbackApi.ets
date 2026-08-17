import { http } from '@kit.NetworkKit';
import util from '@ohos.util';
import { AuthorizedApiPolicy } from '../../shared/network/AuthorizedApiPolicy';
import { AuthApi } from '../auth/AuthApi';
import { FeedbackPolicy } from './FeedbackPolicy';

interface FeedbackUserInfo {
  username: string;
  email: string;
  phone: string;
}

interface FeedbackRequest {
  userInfo: FeedbackUserInfo;
  category: string;
  title: string;
  content: string;
}

/**
 * 提交意见反馈后返回的服务端回执。
 */
export interface FeedbackReceipt {
  feedbackId: string;
  status: string;
}

/**
 * MyTools 公开意见反馈接口。
 */
export class FeedbackApi {
  private readonly baseUrl: string;
  private readonly apiPolicy: AuthorizedApiPolicy = new AuthorizedApiPolicy();
  private readonly feedbackPolicy: FeedbackPolicy = new FeedbackPolicy();

  constructor(baseUrl: string) {
    this.baseUrl = AuthApi.normalizeBusinessBaseUrl(baseUrl);
  }

  /**
   * 提交经过客户端校验的意见反馈。
   */
  async submit(username: string, email: string, category: string, title: string,
    content: string): Promise<FeedbackReceipt> {
    const request = http.createHttp();
    const body: FeedbackRequest = {
      userInfo: { username: username, email: email, phone: '' },
      category: category,
      title: title,
      content: content
    };
    const serialized = JSON.stringify(body);
    const bodyBytes = new util.TextEncoder().encodeInto(serialized).length;
    this.feedbackPolicy.request(username, email, category, title, content, bodyBytes);
    let responseError = '';
    try {
      request.on('headersReceive', (headers: Object) => {
        const values = headers as Record<string, Object>;
        Object.keys(values).forEach((key: string) => {
          const lower = key.toLowerCase();
          if (lower === 'location') {
            responseError = '意见反馈接口不允许重定向';
            request.destroy();
          } else if (lower === 'content-length') {
            const length = Number(values[key]);
            if (!Number.isSafeInteger(length) || length < 0 || length > 1024 * 1024) {
              responseError = '意见反馈响应长度无效或超过1 MB限制';
              request.destroy();
            }
          }
        });
      });
      const response = await request.request(`${this.baseUrl}/api/public/feedback`, {
        method: http.RequestMethod.POST,
        header: { 'Accept': 'application/json', 'Content-Type': 'application/json' },
        extraData: serialized,
        connectTimeout: 10000,
        readTimeout: 20000
      });
      if (responseError.length > 0) throw new Error(responseError);
      const text = typeof response.result === 'string' ? response.result : `${response.result}`;
      const bytes = new util.TextEncoder().encodeInto(text).length;
      if (bytes > 1024 * 1024) throw new Error('意见反馈响应超过1 MB限制');
      const envelope = this.apiPolicy.envelope(text, bytes);
      if (response.responseCode < 200 || response.responseCode >= 300 || envelope.code !== '0000') {
        throw new Error(envelope.message.length > 0 ? envelope.message : `提交失败：HTTP ${response.responseCode}`);
      }
      return this.feedbackPolicy.receipt(envelope);
    } catch (error) {
      if (responseError.length > 0) throw new Error(responseError);
      if (error instanceof Error) throw error;
      throw new Error('意见反馈提交失败');
    } finally {
      request.off('headersReceive');
      request.destroy();
    }
  }
}
