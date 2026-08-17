export interface LogoutRiskSnapshot {
  pendingProgress: number;
  pendingMarkers: number;
  pendingShelfBooks: number;
  pendingBookSources: number;
  syncActive: boolean;
}

/**
 * 退出登录前的本地同步风险策略。
 */
export class LogoutRiskPolicy {
  /**
   * 计算尚未确认写入云端的阅读数据数量。
   *
   * @param snapshot 当前同步队列快照。
   * @returns 有界的待同步项目总数。
   */
  pendingCount(snapshot: LogoutRiskSnapshot): number {
    return this.safeCount(snapshot.pendingProgress) + this.safeCount(snapshot.pendingMarkers) +
      this.safeCount(snapshot.pendingShelfBooks) + this.safeCount(snapshot.pendingBookSources);
  }

  /**
   * 判断退出登录是否需要二次确认。
   *
   * @param snapshot 当前同步队列快照。
   * @returns 存在待同步项目或正在进行同步时返回true。
   */
  requiresConfirmation(snapshot: LogoutRiskSnapshot): boolean {
    return snapshot.syncActive || this.pendingCount(snapshot) > 0;
  }

  private safeCount(value: number): number {
    return Number.isSafeInteger(value) && value > 0 ? Math.min(value, 1000000) : 0;
  }
}
