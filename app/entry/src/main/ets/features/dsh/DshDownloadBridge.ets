import common from '@ohos.app.ability.common';
import webview from '@ohos.web.webview';
import fs from '@ohos.file.fs';
import picker from '@ohos.file.picker';
import { DshWebCapabilityPolicy } from './DshWebCapabilityPolicy';

/** 下载任务保留独立身份，迟到回调不能接管新请求。 */
class DshDownloadTask {
  item: webview.WebDownloadItem;
  path: string;
  name: string;
  finishing: boolean = false;

  /** 使用应用缓存作为唯一临时目标。 */
  constructor(item: webview.WebDownloadItem, path: string) {
    this.item = item;
    this.path = path;
    this.name = DshWebCapabilityPolicy.filename(item.getSuggestedFileName());
  }
}

/** ArkWeb 下载先写私有缓存，再通过系统保存器交给用户指定位置。 */
export class DshDownloadBridge {
  private readonly delegate: webview.WebDownloadDelegate = new webview.WebDownloadDelegate();
  private task?: DshDownloadTask;
  private sequence: number = 0;
  private context: common.UIAbilityContext;
  private currentPage: () => string;
  private currentBase: () => string;
  private active: () => boolean;
  private notice: (message: Resource) => void;

  /** 绑定单个网页控制器，下载请求不会转发给无会话的网络客户端。 */
  constructor(context: common.UIAbilityContext, controller: webview.WebviewController,
    currentPage: () => string, currentBase: () => string,
    active: () => boolean, notice: (message: Resource) => void) {
    this.context = context;
    this.currentPage = currentPage;
    this.currentBase = currentBase;
    this.active = active;
    this.notice = notice;
    this.delegate.onBeforeDownload((item: webview.WebDownloadItem) => this.begin(item));
    this.delegate.onDownloadFinish((item: webview.WebDownloadItem) => {
      const task = this.task;
      if (task && task.item.getGuid() === item.getGuid() && !task.finishing) this.save(task);
    });
    this.delegate.onDownloadFailed((item: webview.WebDownloadItem) => {
      const task = this.task;
      if (!task || task.item.getGuid() !== item.getGuid()) return;
      this.task = undefined;
      this.remove(task);
      if (this.active()) this.notice($r('app.string.dsh_web_download_failed'));
    });
    controller.setDownloadDelegate(this.delegate);
  }

  /** 离页或切换账号立即取消下载，异步保存器返回后也不再写出文件。 */
  close(): void {
    const task = this.task;
    this.task = undefined;
    if (!task) return;
    try { task.item.cancel(); } catch (_) { /* 已结束的下载仍清理缓存。 */ }
    if (!task.finishing) this.remove(task);
  }

  /** 同时保留一个系统保存流程，避免多个保存器互相覆盖。 */
  private begin(item: webview.WebDownloadItem): void {
    try {
      if (!this.active() || !DshWebCapabilityPolicy.download(item.getUrl(), this.currentPage(), this.currentBase())) {
        item.cancel();
        return;
      }
      if (this.task) {
        item.cancel();
        this.notice($r('app.string.dsh_web_download_busy'));
        return;
      }
      const task = new DshDownloadTask(item, `${this.context.cacheDir}/dsh-download-${Date.now()}-${++this.sequence}.tmp`);
      this.task = task;
      this.notice($r('app.string.dsh_web_download_started'));
      item.start(task.path);
    } catch (_) {
      this.close();
      if (this.active()) this.notice($r('app.string.dsh_web_download_failed'));
    }
  }

  /** 只有同一页面仍持有任务时才能保存，取消选择不生成副本。 */
  private async save(task: DshDownloadTask): Promise<void> {
    task.finishing = true;
    try {
      if (this.task !== task || !this.active()) return;
      const options = new picker.DocumentSaveOptions();
      options.newFileNames = [task.name];
      const destinations = await new picker.DocumentViewPicker(this.context).save(options);
      if (this.task !== task || !this.active()) return;
      if (destinations.length === 0) {
        this.notice($r('app.string.dsh_web_download_cancelled'));
        return;
      }
      const destination = await fs.open(destinations[0], fs.OpenMode.READ_WRITE);
      try {
        // 打开系统目标同样跨越异步边界，离页后的请求不能继续写入。
        if (this.task !== task || !this.active()) return;
        await fs.copyFile(task.path, destination.fd);
      }
      finally { await fs.close(destination); }
      if (this.task === task && this.active()) this.notice($r('app.string.dsh_web_download_saved'));
    } catch (_) {
      if (this.task === task && this.active()) this.notice($r('app.string.dsh_web_download_failed'));
    } finally {
      if (this.task === task) this.task = undefined;
      this.remove(task);
    }
  }

  /** 只清理本类生成的私有临时文件。 */
  private remove(task: DshDownloadTask): void {
    try { fs.unlinkSync(task.path); } catch (_) { /* 下载失败时临时文件可能尚未创建。 */ }
  }
}
