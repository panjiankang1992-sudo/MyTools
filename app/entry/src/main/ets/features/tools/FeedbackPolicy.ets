import { ApiEnvelope } from '../../shared/network/AuthorizedApiModels';

export interface FeedbackPolicyReceipt {
  feedbackId: string;
  status: string;
}

/**
 * 公开意见反馈接口的请求字段与服务端回执安全策略。
 */
export class FeedbackPolicy {
  /** 校验与后端DTO一致的公开反馈字段边界。 */
  request(username: string, email: string, category: string, title: string, content: string, bytes: number): void {
    if (!this.text(username, 50, false) || !this.text(email, 100, false) ||
      !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || !this.text(category, 50, false) ||
      !this.text(title, 200, false) || !this.text(content, 5000, false, true) ||
      !Number.isSafeInteger(bytes) || bytes < 1 || bytes > 16 * 1024) {
      throw new Error('意见反馈字段无效或请求超过16 KB限制');
    }
  }

  /** 从已校验的通用信封中提取有界反馈回执。 */
  receipt(envelope: ApiEnvelope): FeedbackPolicyReceipt {
    const data = envelope.data;
    if (data === undefined || data === null || typeof data !== 'object' || Array.isArray(data)) {
      throw new Error('意见反馈回执无效');
    }
    const record = data as Record<string, Object>;
    const feedbackId = record['feedbackId'];
    const status = record['status'];
    if (typeof feedbackId !== 'string' || !/^[A-Za-z0-9-]{1,100}$/.test(feedbackId) ||
      typeof status !== 'string' || status.length < 1 || status.length > 50 || /[\u0000-\u001F\u007F]/.test(status)) {
      throw new Error('意见反馈回执无效');
    }
    return { feedbackId: feedbackId, status: status };
  }

  private text(value: string, maximum: number, allowEmpty: boolean, allowLineBreaks: boolean = false): boolean {
    return value.length <= maximum && (allowEmpty || value.trim().length > 0) &&
      !(allowLineBreaks ? /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/ : /[\u0000-\u001F\u007F]/).test(value);
  }
}
