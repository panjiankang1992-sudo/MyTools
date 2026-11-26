/** 保存当前会话的可信公网归属与已验证业务端点。 */
export class BusinessEndpointState {
  private publicBaseUrl: string = '';
  private activeBaseUrl: string = '';
  private routeRevision: number = 0;
  private readonly selectedBaseUrls: Set<string> = new Set<string>();
  private verifiedIpv6Origins: string[] = [];
  private verifiedUntil: number = 0;
  private readonly unavailableUntil: Map<string, number> = new Map<string, number>();

  /** 绑定账户所属公网地址，并清除前一会话的端点历史。 */
  bindPublicBaseUrl(baseUrl: string): void {
    this.publicBaseUrl = baseUrl;
    this.activeBaseUrl = baseUrl;
    this.selectedBaseUrls.clear();
    this.invalidateNetworkCandidates();
    if (baseUrl.length > 0) this.selectedBaseUrls.add(baseUrl);
  }

  /** 返回当前会话全部业务请求使用的活动地址。 */
  activeBusinessBaseUrl(): string {
    return this.activeBaseUrl;
  }

  /** 返回线路和网络状态代次，隔离同一地址重新激活前的请求。 */
  endpointRevision(): number {
    return this.routeRevision;
  }

  /** 已选择过的端点跟随当前路由，尚未验证的候选保持原地址。 */
  resolveBusinessBaseUrl(requested: string): string {
    return this.selectedBaseUrls.has(requested) && this.activeBaseUrl.length > 0 ? this.activeBaseUrl : requested;
  }

  /** 将当前会话已知来源的绝对资源地址映射到活动端点，保留资源路径与查询参数。 */
  resolveBusinessResourceUrl(value: string): string {
    if (this.activeBaseUrl.length === 0 || /[\u0000-\u001F\u007F]/.test(value) || value.includes('\\')) return value;
    for (const baseUrl of this.selectedBaseUrls) {
      // 必须匹配完整来源后的路径分隔符，避免相似域名被误识别为可信资源。
      if (value.startsWith(`${baseUrl}/`)) return `${this.activeBaseUrl}${value.substring(baseUrl.length)}`;
    }
    return value;
  }

  /** 激活已由调用方完成服务身份证明验证的业务地址。 */
  activateBusinessEndpoint(baseUrl: string): void {
    if (this.publicBaseUrl.length === 0) throw new Error('当前会话尚未绑定可信服务地址');
    if (baseUrl !== this.activeBaseUrl) this.routeRevision++;
    this.selectedBaseUrls.add(baseUrl);
    this.activeBaseUrl = baseUrl;
  }

  /** 接收本轮已通过服务身份证明验证的短期 IPv6 备用入口。 */
  registerVerifiedIpv6Endpoints(origins: string[], expiresAt: number): void {
    this.verifiedIpv6Origins = origins.filter((origin: string): boolean => origin.startsWith('https://') &&
      origin !== this.publicBaseUrl && !this.isEndpointCoolingDown(origin)).slice(0, 4);
    this.verifiedUntil = Math.min(expiresAt, Date.now() + 60000);
  }

  /** 网络改变时丢弃旧网络的健康结论及冷却状态。 */
  invalidateNetworkCandidates(): void {
    this.routeRevision++;
    this.verifiedIpv6Origins = [];
    this.verifiedUntil = 0;
    this.unavailableUntil.clear();
  }

  /** 避免每个请求都重复连接已失败的端点。 */
  isEndpointCoolingDown(baseUrl: string): boolean {
    return (this.unavailableUntil.get(baseUrl) ?? 0) > Date.now();
  }

  /** 将后续业务请求统一恢复到所属公网地址。 */
  resetBusinessEndpoint(): void {
    if (this.activeBaseUrl !== this.publicBaseUrl) this.routeRevision++;
    this.activeBaseUrl = this.publicBaseUrl;
  }

  /** 当前端点失败时依次选择已验证 IPv6 或公网，旧请求不能覆盖新路由。 */
  reportBusinessEndpointUnavailable(baseUrl: string, expectedRevision: number = this.routeRevision): boolean {
    if (expectedRevision !== this.routeRevision || baseUrl !== this.activeBaseUrl || baseUrl === this.publicBaseUrl) return false;
    this.unavailableUntil.set(baseUrl, Date.now() + 30000);
    const fallback = baseUrl.startsWith('http://') && this.verifiedUntil > Date.now() ?
      this.verifiedIpv6Origins.find((origin: string): boolean => !this.isEndpointCoolingDown(origin)) : undefined;
    this.activeBaseUrl = fallback ?? this.publicBaseUrl;
    this.routeRevision++;
    this.selectedBaseUrls.add(this.activeBaseUrl);
    return true;
  }
}
