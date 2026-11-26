import { webview } from '@kit.ArkWeb';
import { DshPopupLifecycle } from './DshPopupLifecycle';

/** 将网页预开的空白窗口交给系统浏览器，主会话保持连接。 */
@Component
export struct DshPopupRelay {
  handler: ControllerHandler | undefined = undefined;
  lifecycle: DshPopupLifecycle = new DshPopupLifecycle(() => {});
  onOpen: (url: string, lifecycle: DshPopupLifecycle) => void = () => {};
  onClose: (lifecycle: DshPopupLifecycle) => void = () => {};
  onFailure: (lifecycle: DshPopupLifecycle) => void = () => {};
  private controller: webview.WebviewController = new webview.WebviewController();

  /** 中转窗口只接受空白页，任何外部页面都不在此执行。 */
  build() {
    Web({ src: '', controller: this.controller })
      .javaScriptAccess(false).domStorageAccess(false).databaseAccess(false).fileAccess(false)
      .width(1).height(1).opacity(0).accessibilityLevel('no-hide-descendants')
      .onControllerAttached(() => {
        try { this.lifecycle.attach(() => { this.handler?.setWebController(this.controller); }); }
        catch (_) { this.onFailure(this.lifecycle); }
      })
      .onLoadIntercept((event) => {
        if (!event || !event.data.isMainFrame()) return true;
        const url = event.data.getRequestUrl();
        if (url === 'about:blank') return false;
        // 登录授权地址稍后才到达，只转交一次，避免重复唤起浏览器。
        if (this.lifecycle.forward()) this.onOpen(url, this.lifecycle);
        return true;
      })
      .onErrorReceive((event) => { if (event.request.isMainFrame()) this.onFailure(this.lifecycle); })
      .onWindowExit(() => this.onClose(this.lifecycle))
  }
}
