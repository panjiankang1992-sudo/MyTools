/** 页面加载有独立截止时间，脚本挂起或迟到结果不能阻塞重连。 */
export class DshWebLoadMonitor {
  private revision: number = 0;
  private running: boolean = false;
  private deadline: number = -1;
  private polling: number = -1;
  private intervalMs: number = 1000;
  private checkTimeoutMs: number = 0;
  private observing: boolean = false;
  private checkDeadline: number = -1;
  private checkRevision: number = 0;
  private checking: boolean = false;
  private probe?: () => Promise<string>;
  private report?: (result: string) => void;

  /** 只返回状态码，不把页面正文或认证信息带回原生端。 */
  static probeScript(): string {
    return '(function(){if(!document.body)return 0;' +
      'if(document.querySelector(".hHd-Xa_root"))return 1;' +
      'if(document.querySelector("#root"))return 3;' +
      'return (document.body.textContent||"").trimStart().startsWith("dsh web authentication required;")?2:0;})()';
  }

  /** 票据、文档和页面挂载共享一次截止时间，重定向不延长等待。 */
  start(probe: () => Promise<string>, report: (result: string) => void, timeoutMs: number = 60000,
    intervalMs: number = 1000, checkTimeoutMs: number = 0): void {
    this.close();
    this.running = true;
    this.probe = probe;
    this.report = report;
    this.intervalMs = intervalMs;
    this.checkTimeoutMs = checkTimeoutMs;
    this.observing = timeoutMs === 0;
    const revision = this.revision;
    if (timeoutMs > 0) this.deadline = setTimeout(() => this.finish(revision, 'timeout'), timeoutMs);
    this.schedule(revision);
  }

  /** 文档完成事件可提前触发检查，但不会重置截止时间或并发执行脚本。 */
  checkNow(): void {
    if (!this.running || this.checking) return;
    if (this.polling >= 0) clearTimeout(this.polling);
    this.polling = -1;
    this.check(this.revision);
  }

  /** 判断当前是否已有加载检查，避免重定向反复创建定时器。 */
  isRunning(): boolean { return this.running; }

  /** 页面离开、网络错误和账号切换均使旧脚本结果失效。 */
  close(): void {
    this.revision++;
    this.running = false;
    this.checking = false;
    if (this.deadline >= 0) clearTimeout(this.deadline);
    if (this.polling >= 0) clearTimeout(this.polling);
    if (this.checkDeadline >= 0) clearTimeout(this.checkDeadline);
    this.deadline = -1;
    this.polling = -1;
    this.checkDeadline = -1;
    this.probe = undefined;
    this.report = undefined;
  }

  /** 仅在页面未就绪时每秒检查，加载完成后不继续扫描聊天内容。 */
  private schedule(revision: number): void {
    this.polling = setTimeout(() => {
      this.polling = -1;
      this.check(revision);
    }, this.intervalMs);
  }

  /** 同一轮只保留一个脚本请求，独立截止计时仍可中断挂起请求。 */
  private async check(revision: number): Promise<void> {
    if (!this.running || this.checking || revision !== this.revision || !this.probe) return;
    this.checking = true;
    const checkRevision = ++this.checkRevision;
    if (this.checkTimeoutMs > 0) {
      this.checkDeadline = setTimeout((): void => {
        if (revision !== this.revision || checkRevision !== this.checkRevision) return;
        this.checkRevision++;
        this.checkDeadline = -1;
        this.checking = false;
        this.schedule(revision);
      }, this.checkTimeoutMs);
    }
    let result = '0';
    try { result = await this.probe(); } catch (_) { /* 脚本上下文尚未就绪时继续等待。 */ }
    if (!this.running || revision !== this.revision || checkRevision !== this.checkRevision) return;
    if (this.checkDeadline >= 0) clearTimeout(this.checkDeadline);
    this.checkDeadline = -1;
    this.checking = false;
    if (result === '1' || (!this.observing && result === '2')) {
      this.finish(revision, result);
      return;
    }
    if (this.observing) this.report?.(result);
    if (this.running && revision === this.revision) this.schedule(revision);
  }

  /** 先释放计时器再通知调用者，通知内立即重连也不会被旧状态覆盖。 */
  private finish(revision: number, result: string): void {
    if (!this.running || revision !== this.revision) return;
    const report = this.report;
    this.close();
    report?.(result);
  }
}
