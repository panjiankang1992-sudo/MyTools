import { http } from '@kit.NetworkKit';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import { AuthApi } from '../auth/AuthApi';
import { HomeChannelOrigin } from '../connectivity/HomeChannelOrigin';

/** 对当前票据读取相同的原片区间，启动比较最多读取 256 KiB。 */
export async function videoRangeTime(url: string, token: DownloadCancellationToken, initial: boolean = false,
  publicBaseUrl: string = ''): Promise<number> {
  if (token.isCancelled()) return Infinity;
  const request = http.createHttp();
  const start = Date.now();
  const limit = initial ? 262144 : 65536;
  let expected = 0;
  let received = 0;
  const stop = (): void => request.destroy();
  const deadline = setTimeout(stop, 4500);
  try {
    token.bind(stop);
    request.on('headersReceive', (headers: Object) => {
      const values = headers as Record<string, Object>;
      for (const key of Object.keys(values)) {
        if (key.toLowerCase() === 'location') { stop(); return; }
        if (key.toLowerCase() === 'content-range') {
          const match = /^bytes 0-(\d+)\/(\d+)$/.exec(String(values[key]));
          if (match && Number(match[1]) + 1 === Math.min(Number(match[2]), limit)) expected = Number(match[1]) + 1;
        }
      }
    });
    request.on('dataReceive', (chunk: ArrayBuffer) => {
      received += chunk.byteLength;
      if (expected === 0 || received > expected) stop();
    });
    const path = url.indexOf('/api/');
    const origin = path > 0 ? url.substring(0, path) : '';
    const direct = publicBaseUrl.length > 0 && origin.startsWith('https://') && origin !== publicBaseUrl;
    const status = await request.requestInStream(url, { method: http.RequestMethod.GET, usingCache: false,
      usingProxy: HomeChannelOrigin.owns(origin) || direct ? false : undefined,
      addressFamily: publicBaseUrl ? AuthApi.directAddressFamily(origin, publicBaseUrl) : undefined,
      header: { 'Range': `bytes=0-${limit - 1}`, 'Accept-Encoding': 'identity' }, connectTimeout: 3000, readTimeout: 4500 });
    return status === 206 && received > 0 && received === expected ? Math.max(1, Date.now() - start) : Infinity;
  } catch (_) { return Infinity; }
  finally {
    clearTimeout(deadline);
    token.unbind(stop);
    request.off('headersReceive');
    request.off('dataReceive');
    request.destroy();
  }
}
