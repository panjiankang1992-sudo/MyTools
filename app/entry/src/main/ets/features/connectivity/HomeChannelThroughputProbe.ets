import { http } from '@kit.NetworkKit';
import { AuthSessionManager } from '../auth/AuthSessionManager';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import { HomeChannelOrigin } from './HomeChannelOrigin';

/** 使用固定公开模式检查候选通道吞吐，不保存业务内容或修改活动路由。 */
export class HomeChannelThroughputProbe {
  /** 两点五秒内完整验证五百一十二 KiB，账号和本进程能力入口必须始终相同。 */
  static accepts(base: string, auth: AuthSessionManager, current: () => boolean,
    cancellation?: DownloadCancellationToken): Promise<boolean> {
    const started = Date.now();
    const account = auth.sessionRevision();
    const wire = http.createHttp();
    let received = 0;
    let declared = false;
    let noStore = false;
    let settled = false;
    let timer = -1;
    const valid = (): boolean => current() && account === auth.sessionRevision() &&
      HomeChannelOrigin.owns(base) && cancellation?.isCancelled() !== true;
    return new Promise<boolean>((resolve) => {
      const finish = (accepted: boolean): void => {
        if (settled) return;
        settled = true;
        const result = accepted && valid() && Date.now() - started <= 2500;
        clearTimeout(timer);
        cancellation?.unbind(cancel);
        try { wire.off('headersReceive'); } catch (_) { /* 尚未注册时也安全清理。 */ }
        try { wire.off('dataReceive'); } catch (_) { /* 不保留任何分块引用。 */ }
        try { wire.destroy(); } catch (_) { /* 超时和原生结束可能同时到达。 */ }
        console.info(`Home channel quality: accepted=${result}, bytes=${received}, ms=${Math.max(0, Date.now() - started)}`);
        resolve(result);
      };
      const cancel = (): void => finish(false);
      timer = setTimeout(cancel, 2500);
      cancellation?.bind(cancel);
      if (settled) return;
      if (!valid()) { finish(false); return; }
      try {
        wire.on('headersReceive', (value: Object): void => {
          if (settled) return;
          if (!valid()) { finish(false); return; }
          const headers = value as Record<string, Object>;
          for (const name of Object.keys(headers)) {
            const key = name.toLowerCase();
            const text = String(headers[name]).trim().toLowerCase();
            if (key === 'location' || (key === 'content-encoding' && text !== 'identity')) {
              finish(false); return;
            }
            if (key === 'content-length') {
              if (text !== '524288') { finish(false); return; }
              declared = true;
            }
            if (key === 'cache-control') noStore = text.split(',').some((part: string): boolean => part.trim() === 'no-store');
          }
        });
        wire.on('dataReceive', (chunk: ArrayBuffer): void => {
          if (settled) return;
          if (!valid() || received + chunk.byteLength > 524288) { finish(false); return; }
          // 每个字节都按固定模式校验，响应分块边界不影响序号。
          const bytes = new Uint8Array(chunk);
          for (let index = 0; index < bytes.length; index++) {
            if (bytes[index] !== ((received + index) % 256)) { finish(false); return; }
          }
          received += bytes.length;
        });
      } catch (_) { finish(false); return; }
      auth.validAccessToken().then((token: string): void => {
        if (settled) return;
        if (!valid() || !token) { finish(false); return; }
        wire.requestInStream(base + '/api/app/v1/connectivity/transport-probe', {
          method: http.RequestMethod.GET, usingCache: false, usingProxy: false,
          header: { 'Authorization': 'Bearer ' + token, 'Accept-Encoding': 'identity' },
          connectTimeout: 2500, readTimeout: 2500
        }).then((status: number): void => {
          finish(status === 200 && declared && noStore && received === 524288);
        }).catch((): void => finish(false));
      }).catch((): void => finish(false));
    });
  }
}
