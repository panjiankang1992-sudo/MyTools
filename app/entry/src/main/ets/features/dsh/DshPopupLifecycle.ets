/** 约束预开登录窗口的绑定、转交与取消，保证每个窗口请求恰好结束一次。 */
export class DshPopupLifecycle {
  private active: boolean = true;
  private pending: boolean = true;
  private forwarded: boolean = false;
  private rejectPending: () => void;

  /** 保存尚未挂载时的拒绝回调，不持有授权地址或凭据。 */
  constructor(rejectPending: () => void) { this.rejectPending = rejectPending; }

  /** 取消后到达的挂载回调不能重新绑定已释放的窗口请求。 */
  attach(bind: () => void): boolean {
    if (!this.active || !this.pending) return false;
    bind();
    this.pending = false;
    return true;
  }

  /** 一个已绑定的窗口只允许发起一次浏览器转交。 */
  forward(): boolean {
    if (!this.active || this.pending || this.forwarded) return false;
    this.forwarded = true;
    return true;
  }

  /** 未挂载的请求必须显式拒绝，避免阻塞主网页渲染。 */
  close(): void {
    if (!this.active) return;
    this.active = false;
    if (this.pending) {
      this.pending = false;
      this.rejectPending();
    }
  }
}
