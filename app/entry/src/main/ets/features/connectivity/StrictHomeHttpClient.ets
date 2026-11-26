import socket from '@ohos.net.socket';
import { BusinessError } from '@ohos.base';
import util from '@ohos.util';
import { StrictHomeTlsConnection } from './StrictHomeTlsConnection';
import { BoundedHomeHttpResponse, HomeHttpResponseBytes } from './BoundedHomeHttpResponse';
import { Ipv6EndpointPolicy } from './Ipv6EndpointPolicy';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';
import { BusinessResponseError } from '../../shared/network/BusinessTransportError';

/** 固定控制接口的原始响应；业务信封和会话刷新继续由原调用方校验。 */
export interface StrictHomeHttpResponse {
  status: number;
  text: string;
}

/** 通过已核对原域名身份的 TLS 访问三个固定控制接口，兼容系统 IPv6 解析故障。 */
export class StrictHomeHttpClient {
  private stopped: boolean = false;
  private sequence: number = 0;
  private readonly cancellations: Map<number, () => void> = new Map();

  /** 从已保存且受信的家庭 HTTPS 来源续签发现材料。 */
  bootstrap(origin: string, accessToken: string, deadlineMs: number = 6000, addresses?: string[],
    cancellation?: DownloadCancellationToken): Promise<StrictHomeHttpResponse> {
    return this.exchange(origin, '/api/app/v1/connectivity/bootstrap', accessToken, '', 65536,
      deadlineMs, addresses, cancellation);
  }

  /** 用同一登录令牌校验已验证家庭来源的用户身份，允许既有的一 MiB 资料信封。 */
  identity(origin: string, accessToken: string, deadlineMs: number = 6000, addresses?: string[],
    cancellation?: DownloadCancellationToken): Promise<StrictHomeHttpResponse> {
    return this.exchange(origin, '/api/app/v1/identity/me', accessToken, '', 1048576,
      deadlineMs, addresses, cancellation);
  }

  /** 发送不含登录令牌的固定 HMAC 挑战，证明校验由原调用方完成。 */
  challenge(origin: string, body: string, deadlineMs: number = 4500, addresses?: string[],
    cancellation?: DownloadCancellationToken): Promise<StrictHomeHttpResponse> {
    return this.exchange(origin, '/api/public/connectivity/challenge', '', body, 65536,
      deadlineMs, addresses, cancellation);
  }

  /** 取消本实例的全部等待请求；后续请求必须使用新实例。 */
  close(): void {
    this.stopped = true;
    Array.from(this.cancellations.values()).forEach((cancel: () => void): void => cancel());
    this.cancellations.clear();
  }

  private async exchange(origin: string, path: string, accessToken: string, body: string, maximumBody: number,
    deadlineMs: number, addresses?: string[], cancellation?: DownloadCancellationToken): Promise<StrictHomeHttpResponse> {
    const started = Date.now();
    const deadline = Math.max(1, Math.min(30000, deadlineMs));
    const normalized = new Ipv6EndpointPolicy().normalizeOrigin(origin);
    if (this.stopped || cancellation?.isCancelled() || normalized !== origin || !Number.isFinite(deadlineMs)) {
      throw new Error('Home control request unavailable');
    }
    const post = path === '/api/public/connectivity/challenge';
    if (!post && path !== '/api/app/v1/connectivity/bootstrap' && path !== '/api/app/v1/identity/me') {
      throw new Error('Invalid home control endpoint');
    }
    if (post) {
      if (accessToken.length > 0) throw new Error('Invalid home control challenge');
      this.checkChallenge(body);
    }
    else if (!accessToken || accessToken.length > 8192 || !/^[A-Za-z0-9._~+\/-]+=*$/.test(accessToken)) {
      throw new Error('Invalid home control credential');
    }
    const bodyBytes = new util.TextEncoder().encodeInto(body);
    const authority = origin.substring(8);
    let request = (post ? 'POST ' : 'GET ') + path + ' HTTP/1.1\r\nHost: ' + authority +
      '\r\nAccept: application/json\r\nAccept-Encoding: identity\r\nConnection: close\r\n';
    if (post) request += 'Content-Type: application/json; charset=utf-8\r\nContent-Length: ' + bodyBytes.length + '\r\n';
    else request += 'Authorization: Bearer ' + accessToken + '\r\n';
    request += '\r\n' + body;
    const operation = ++this.sequence;
    return new Promise<StrictHomeHttpResponse>((resolve, reject) => {
      const connector = new StrictHomeTlsConnection();
      let active: socket.TLSSocket | undefined;
      let settled: boolean = false;
      let responseStarted: boolean = false;
      let responseStatus: number = 0;
      let phase: string = 'tls';
      let nativeCode: number = 0;
      let timedOut: boolean = false;
      const kind = post ? 'challenge' : path.endsWith('/identity/me') ? 'identity' : 'bootstrap';
      const parser = new BoundedHomeHttpResponse(maximumBody);
      const dispose = (): void => {
        try { connector.close(); } catch (_) { /* 取消后的晚完成连接仍由下方句柄关闭。 */ }
        try { active?.close().catch((): void => {}); } catch (_) { /* 已移交或关闭的连接可重复释放。 */ }
      };
      const clean = (): void => {
        clearTimeout(timer);
        this.cancellations.delete(operation);
        cancellation?.unbind(fail);
        dispose();
      };
      const fail = (): void => {
        if (settled) return;
        settled = true;
        if (!this.stopped && !cancellation?.isCancelled()) {
          console.warn(`Home HTTP failure: kind=${kind}, phase=${phase}, class=transport, code=${nativeCode}, ` +
            `status=${responseStatus}, started=${responseStarted}, timeout=${timedOut}, ms=${Date.now() - started}`);
        }
        clean();
        reject(new Error('Home control request unavailable'));
      };
      const invalid = (): void => {
        if (settled) return;
        settled = true;
        console.warn(`Home HTTP failure: kind=${kind}, phase=${phase}, class=response, code=${nativeCode}, ` +
          `status=${responseStatus}, started=${responseStarted}, timeout=${timedOut}, ms=${Date.now() - started}`);
        clean();
        reject(new BusinessResponseError(origin, 'Invalid home control response'));
      };
      const complete = (response: HomeHttpResponseBytes): void => {
        if (settled) return;
        try {
          responseStatus = response.status;
          phase = 'decode';
          const text = util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(response.body);
          settled = true;
          clean();
          resolve({ status: response.status, text: text });
        } catch (_) { invalid(); }
      };
      const remaining = Math.max(0, started + deadline - Date.now());
      const timer = setTimeout((): void => { timedOut = true; fail(); }, remaining);
      this.cancellations.set(operation, fail);
      cancellation?.bind(fail);
      if (settled) return;
      connector.open(origin, addresses, '', Math.min(6000, remaining)).then(async (tls: socket.TLSSocket): Promise<void> => {
        active = tls;
        if (settled) { dispose(); return; }
        phase = 'listen';
        // 完成证书链、域名与实际对端校验之后注册事件，再发送固定请求和认证头。
        tls.on('message', (event: socket.SocketMessageInfo): void => {
          if (settled) return;
          try {
            phase = 'response';
            responseStarted = responseStarted || event.message.byteLength > 0;
            const response = parser.feed(new Uint8Array(event.message));
            if (response !== undefined) complete(response);
          } catch (_) { invalid(); }
        });
        tls.on('error', (error): void => {
          nativeCode = Number.isFinite(error?.code) ? error.code : 0;
          fail();
        });
        tls.on('close', (): void => {
          if (settled) return;
          try { complete(parser.end()); } catch (_) {
            if (responseStarted) invalid();
            else fail();
          }
        });
        if (settled) { dispose(); return; }
        phase = 'send';
        await tls.send(request);
      }).catch((error: Error): void => {
        const code = (error as BusinessError)?.code;
        nativeCode = Number.isFinite(code) ? code : 0;
        fail();
      });
    });
  }

  private checkChallenge(body: string): void {
    try {
      const bytes = new util.TextEncoder().encodeInto(body);
      if (bytes.length === 0 || bytes.length > 65536) throw new Error();
      const parsed = JSON.parse(body) as Object;
      if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error();
      const value = parsed as Record<string, Object>;
      if (Object.keys(value).length !== 2 || typeof value['probeId'] !== 'string' || typeof value['nonce'] !== 'string') {
        throw new Error();
      }
      const probeId = value['probeId'] as string;
      const nonce = value['nonce'] as string;
      if (!/^[A-Za-z0-9_-]{1,128}$/.test(probeId) || !/^[A-Za-z0-9_-]{16,128}$/.test(nonce)) throw new Error();
    } catch (_) { throw new Error('Invalid home control challenge'); }
  }
}
