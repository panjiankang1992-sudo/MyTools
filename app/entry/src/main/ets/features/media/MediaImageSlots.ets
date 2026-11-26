import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';

export interface ImagePriority {
  level: number;
}

interface ImageWaiter {
  priority: ImagePriority;
  grant: () => void;
}

/** 共用两个图片下载位置，后台任务至多使用其中一个。 */
export class MediaImageSlots {
  private waiting: ImageWaiter[] = [];
  private running: number = 0;
  private background: number = 0;
  private paused: boolean = false;

  /** 视频准备时只暂停尚未开始的后台下载。 */
  pauseBackground(value: boolean): void {
    this.paused = value;
    this.dispatch();
  }

  /** 等待空闲位置，取消后立即移除排队任务。 */
  acquire(priority: ImagePriority, token: DownloadCancellationToken): Promise<() => void> {
    return new Promise<() => void>((resolve, reject) => {
      const waiter: ImageWaiter = { priority, grant: (): void => {
        token.unbind(cancel);
        const background = priority.level >= 2;
        this.running++;
        if (background) this.background++;
        resolve((): void => {
          this.running--;
          if (background) this.background--;
          this.dispatch();
        });
      }};
      const cancel = (): void => {
        this.waiting = this.waiting.filter((entry: ImageWaiter) => entry !== waiter);
        reject(new Error('Image request cancelled'));
      };
      this.waiting.push(waiter);
      token.bind(cancel);
      this.dispatch();
    });
  }

  /** 优先唤醒当前图、可见图，最后才消费预热任务。 */
  dispatch(): void {
    this.waiting.sort((a: ImageWaiter, b: ImageWaiter) => a.priority.level - b.priority.level);
    while (this.running < 2 && this.waiting.length > 0) {
      if (this.waiting[0].priority.level >= 2 && (this.paused || this.background >= 1)) return;
      this.waiting.shift()!.grant();
    }
  }
}
