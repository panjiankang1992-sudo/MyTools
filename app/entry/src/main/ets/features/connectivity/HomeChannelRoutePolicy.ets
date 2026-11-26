/** 同一网络内保留质量结论，避免每轮发现重新选择已经变慢的直连。 */
export class HomeChannelRoutePolicy {
  private rejected: string[] = [];

  /** 仅使用已验证直连与账号固定的公网来源，单轮最多比较各一条。 */
  candidates(verifiedOrigins: string[], publicOrigin: string): string[] {
    if (!this.httpsOrigin(publicOrigin)) return [];
    const direct = verifiedOrigins.find((origin: string): boolean => origin !== publicOrigin &&
      this.httpsOrigin(origin) && !this.rejected.includes(origin));
    return direct ? [direct, publicOrigin] : [publicOrigin];
  }

  /** 失败结论只影响直连；公网仍可在通道实际中断后有界重试。 */
  reject(origin: string, publicOrigin: string): void {
    if (origin !== publicOrigin && !this.rejected.includes(origin)) this.rejected.push(origin);
  }

  /** 网络变化和用户重试后才允许重新测量先前失败的直连。 */
  reset(): void { this.rejected = []; }

  private httpsOrigin(origin: string): boolean {
    return /^https:\/\/[a-z0-9.-]+(?::[0-9]{1,5})?$/.test(origin);
  }
}
