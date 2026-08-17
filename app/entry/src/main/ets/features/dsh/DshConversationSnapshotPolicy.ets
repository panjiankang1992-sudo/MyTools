import { DshConversationSnapshot, DshMessage } from './DshModels';

/**
 * DSH 本机会话预览规范化策略。
 */
export class DshConversationSnapshotPolicy {
  private readonly ownerPattern: RegExp = /^account:v2:[a-f0-9]{64}$/;
  private readonly sessionPattern: RegExp = /^session-[A-Za-z0-9-]{1,96}$/;
  private readonly unsafeTextPattern: RegExp = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/;

  /** 规范化本机会话预览，并强制账户、容量和文本安全边界。 */
  normalize(conversations: DshConversationSnapshot[]): DshConversationSnapshot[] {
    if (!Array.isArray(conversations)) return [];
    const candidates = conversations.slice(0, 1000).filter((conversation: DshConversationSnapshot) =>
      this.isConversationHeaderValid(conversation)).sort(
      (left: DshConversationSnapshot, right: DshConversationSnapshot) => right.updatedAt - left.updatedAt);
    const result: DshConversationSnapshot[] = [];
    const ownerCounts: Record<string, number> = {};
    const conversationIds: Record<string, boolean> = {};
    let ownerCount = 0;
    let textBudget = 0;
    candidates.forEach((conversation: DshConversationSnapshot) => {
      if (result.length >= 120) return;
      const conversationKey = `${conversation.owner}\u0000${conversation.id}`;
      if (conversationIds[conversationKey] === true) return;
      const count = ownerCounts[conversation.owner] ?? 0;
      if (count >= 12 || (count === 0 && ownerCount >= 10)) return;
      const messages: DshMessage[] = [];
      const messageIds: Record<string, boolean> = {};
      conversation.messages.slice(-20).forEach((message: DshMessage) => {
        if (!this.isMessageValid(message) || messageIds[message.id] === true ||
          textBudget + message.text.length > 512000) return;
        messageIds[message.id] = true;
        textBudget += message.text.length;
        messages.push({ id: message.id, role: message.role, text: message.text,
          status: message.status === 'sent' ? 'sent' : 'done' });
      });
      conversationIds[conversationKey] = true;
      ownerCounts[conversation.owner] = count + 1;
      if (count === 0) ownerCount += 1;
      result.push({ id: conversation.id, owner: conversation.owner, title: conversation.title,
        messages: messages, updatedAt: conversation.updatedAt });
    });
    return result;
  }

  private isConversationHeaderValid(conversation: DshConversationSnapshot): boolean {
    return conversation !== null && typeof conversation === 'object' && typeof conversation.id === 'string' &&
      this.sessionPattern.test(conversation.id) && typeof conversation.owner === 'string' &&
      this.ownerPattern.test(conversation.owner) && typeof conversation.title === 'string' &&
      conversation.title.length > 0 && conversation.title.length <= 160 &&
      !this.unsafeTextPattern.test(conversation.title) && Number.isSafeInteger(conversation.updatedAt) &&
      conversation.updatedAt > 0 && Array.isArray(conversation.messages);
  }

  private isMessageValid(message: DshMessage): boolean {
    return message !== null && typeof message === 'object' && typeof message.id === 'string' &&
      message.id.length > 0 && message.id.length <= 160 && !this.unsafeTextPattern.test(message.id) &&
      (message.role === 'user' || message.role === 'assistant') && typeof message.text === 'string' &&
      message.text.length > 0 && message.text.length <= 4000 && !this.unsafeTextPattern.test(message.text);
  }
}
