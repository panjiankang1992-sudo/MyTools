import socket from '@ohos.net.socket';
import connection from '@ohos.net.connection';
import { HomeChannelCertificate } from './HomeChannelCertificate';
import { Ipv6EndpointPolicy } from './Ipv6EndpointPolicy';
import { TrustedLanEndpointPolicy } from './TrustedLanEndpointPolicy';
import { SystemIpv6Resolver } from './SystemIpv6Resolver';

interface PublicCandidates {
  addresses: string[];
}

/** 直连升级已连接 TCP；可信公网入口使用系统域名 TLS，并在发送业务数据前核对身份和对端。 */
export class StrictHomeTlsConnection {
  private stopped: boolean = false;
  private pendingTcp?: socket.TCPSocket;
  private activeTls?: socket.TLSSocket;
  private cancelAttempt?: () => void;
  private cancelResolution?: () => void;

  /** 在固定总预算内建立已验证 TLS，调用者只能在返回后发送业务数据。 */
  static async connect(origin: string, addresses?: string[], ca: string = '', timeoutMs: number = 6000,
    approvedPublicOrigin: string = ''):
    Promise<socket.TLSSocket> {
    return new StrictHomeTlsConnection().open(origin, addresses, ca, timeoutMs, approvedPublicOrigin);
  }

  /** 默认只接入全球 IPv6；只有完全相等的可信公网来源才允许系统解析的全球 IPv4。 */
  async open(origin: string, addresses?: string[], ca: string = '', timeoutMs: number = 6000,
    approvedPublicOrigin: string = ''):
    Promise<socket.TLSSocket> {
    const started = Date.now();
    const deadline = started + Math.max(1, Math.min(timeoutMs, 6000));
    let stage: string = 'origin';
    try {
      const match = /^https:\/\/([a-z0-9.-]+)(?::([0-9]{1,5}))?$/.exec(origin);
      if (!match) throw new Error();
      const host = match[1];
      const port = match[2] ? Number(match[2]) : 443;
      if (port < 1 || port > 65535 || this.stopped) throw new Error();
      // 授权值由 App 登录配置传入，不能以域名前缀、不同端口或自选地址扩大访问范围。
      if (approvedPublicOrigin.length > 0 && (origin !== approvedPublicOrigin || addresses !== undefined)) throw new Error();
      const publicOrigin = approvedPublicOrigin.length > 0;
      // 私网 IPv4 只供显式传入测试信任根的隔离验收使用。
      const fixture = !publicOrigin && ca.length > 0 && new TrustedLanEndpointPolicy().isPrivateIpv4(host);
      const policy = new Ipv6EndpointPolicy();
      if (!fixture) policy.normalizeOrigin(origin);
      stage = 'dns';
      let publicCandidates: PublicCandidates | undefined;
      if (publicOrigin) publicCandidates = await this.publicCandidates(host, Math.min(deadline, Date.now() + 1800));
      const candidates = publicCandidates ? publicCandidates.addresses :
        fixture ? [host] : addresses ?? await new SystemIpv6Resolver().resolve(origin);
      stage = 'candidate-policy';
      if (this.stopped || candidates.length === 0 || candidates.length > 16 ||
        (!fixture && !candidates.every((address: string): boolean => publicOrigin ?
          this.isGlobalIpv4(address) : policy.isGlobalIpv6(address)))) throw new Error();
      stage = 'attempt';
      for (const address of candidates.slice(0, 2)) {
        const remaining = deadline - Date.now();
        if (this.stopped || remaining <= 0) throw new Error();
        try {
          // 公网冷握手可用剩余总预算；直连和隔离验收仍沿用原单次上限。
          return await this.attempt(host, port, address, fixture || publicOrigin ? 1 : 2, ca,
            Math.min(publicOrigin ? 6000 : 2500, remaining), publicCandidates?.addresses);
        } catch (_) {
          if (this.stopped) throw new Error();
        }
      }
      throw new Error();
    } catch (error) {
      if (!this.stopped && stage !== 'attempt') {
        const code = (error as Record<string, number>)?.code;
        console.warn(`Home TLS failure: stage=${stage}, code=${Number.isFinite(code) ? code : 0}, ms=${Date.now() - started}`);
      }
      this.close();
      throw new Error('Secure home TLS connection unavailable');
    }
  }

  private async publicCandidates(host: string, deadline: number): Promise<PublicCandidates> {
    try {
      const net = await this.boundedResolution(connection.getDefaultNet(), deadline);
      if (net.netId <= 0) throw new Error();
      const found = await this.boundedResolution(net.getAddressesByName(host), deadline);
      if (!found.length || found.length > 16) throw new Error();
      // 双栈回答只选择 IPv4；任何非公网 IPv4 都使本次解析失败，不能静默滤过。
      const ipv4 = found.map((item: connection.NetAddress): string => item.address)
        .filter((address: string): boolean => !address.includes(':'));
      if (!ipv4.length || !ipv4.every((address: string): boolean => this.isGlobalIpv4(address))) throw new Error();
      return { addresses: Array.from(new Set<string>(ipv4)).slice(0, 2) };
    } catch (_) {
      // 系统网络接口的同步异常和异步拒绝都转为固定的安全错误。
      throw new Error('Secure home DNS unavailable');
    }
  }

  private boundedResolution<T>(work: Promise<T>, deadline: number): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      let finished: boolean = false;
      const fail = (): void => {
        if (finished) return;
        finished = true;
        clearTimeout(timer);
        if (this.cancelResolution === fail) this.cancelResolution = undefined;
        reject(new Error('Secure home DNS unavailable'));
      };
      const timer = setTimeout(fail, Math.max(0, deadline - Date.now()));
      this.cancelResolution = fail;
      if (this.stopped) fail();
      work.then((value: T): void => {
        if (finished) return;
        finished = true;
        clearTimeout(timer);
        if (this.cancelResolution === fail) this.cancelResolution = undefined;
        resolve(value);
      }).catch((): void => fail());
    });
  }

  private isGlobalIpv4(address: string): boolean {
    const parts = address.split('.');
    if (parts.length !== 4 || !parts.every((part: string): boolean => /^(?:0|[1-9][0-9]{0,2})$/.test(part) &&
      Number(part) <= 255)) return false;
    const first = Number(parts[0]);
    const second = Number(parts[1]);
    const third = Number(parts[2]);
    // 排除私网、共享地址、文档、基准、特殊转发、组播及保留网段。
    return first !== 0 && first !== 10 && first !== 127 && first < 224 &&
      !(first === 100 && second >= 64 && second <= 127) &&
      !(first === 169 && second === 254) && !(first === 172 && second >= 16 && second <= 31) &&
      !(first === 192 && (second === 168 || (second === 0 && (third === 0 || third === 2)) ||
        (second === 88 && third === 99))) &&
      !(first === 198 && (second === 18 || second === 19 || (second === 51 && third === 100))) &&
      !(first === 203 && second === 0 && third === 113);
  }

  private attempt(host: string, port: number, address: string, family: number, ca: string, timeoutMs: number,
    publicAddresses?: string[]):
    Promise<socket.TLSSocket> {
    return new Promise<socket.TLSSocket>((resolve, reject) => {
      const started = Date.now();
      let stage: string = 'tcp-init';
      let nativeCode: number = 0;
      let timedOut: boolean = false;
      let finished: boolean = false;
      let tcp: socket.TCPSocket | undefined;
      let tls: socket.TLSSocket | undefined;
      const dispose = (): void => {
        try {
          if (tls) tls.close().catch((): void => {});
          else if (tcp) tcp.close().catch((): void => {});
        } catch (_) { /* 未完成连接或已移交的句柄允许重复释放。 */ }
        if (this.pendingTcp === tcp) this.pendingTcp = undefined;
        if (this.activeTls === tls) this.activeTls = undefined;
      };
      const fail = (): void => {
        if (finished) return;
        finished = true;
        if (!this.stopped) {
          console.warn(`Home TLS failure: stage=${stage}, code=${nativeCode}, timeout=${timedOut}, ms=${Date.now() - started}`);
        }
        clearTimeout(timer);
        if (this.cancelAttempt === fail) this.cancelAttempt = undefined;
        dispose();
        reject(new Error('Secure home TLS attempt failed'));
      };
      const timer = setTimeout((): void => { timedOut = true; fail(); }, timeoutMs);
      this.cancelAttempt = fail;
      const current = (): boolean => {
        if (!finished && !this.stopped) return true;
        // 取消后才完成的系统异步连接仍须关闭，不能遗留后台套接字。
        dispose();
        return false;
      };
      const connect = async (): Promise<void> => {
        try {
          if (publicAddresses !== undefined) {
            // 公网域名采用系统标准 TLS 连接路径，避免依赖扩展套接字的握手细节。
            stage = 'tls-init';
            tls = socket.constructTLSSocketInstance();
            this.activeTls = tls;
            stage = 'bind';
            await tls.bind({ address: '0.0.0.0', port: 0, family: 1 });
            if (!current()) return;
          } else {
            tcp = socket.constructTCPSocketInstance();
            this.pendingTcp = tcp;
            stage = 'bind';
            await tcp.bind({ address: family === 2 ? '::' : '0.0.0.0', port: 0, family: family });
            if (!current()) return;
            stage = 'connect';
            await tcp.connect({ address: { address: address, port: port, family: family }, timeout: timeoutMs });
            if (!current()) return;
            // IPv6 直连仍升级已连接 TCP，原主机继续用于证书校验。
            tls = socket.constructTLSSocketInstance(tcp);
            this.pendingTcp = undefined;
            this.activeTls = tls;
          }
          stage = 'tls';
          const secure: socket.TLSSecureOptions = ca ? { ca: ca } : {};
          await tls.connect({ address: { address: host, port: port, family: family }, secureOptions: secure,
            ALPNProtocols: ['http/1.1'], skipRemoteValidation: false });
          if (!current()) return;
          stage = 'cert-read';
          const certificate = await tls.getRemoteCertificate();
          if (!current()) return;
          stage = 'cert-match';
          await HomeChannelCertificate.verify(certificate, host);
          if (!current()) return;
          stage = 'peer';
          const peer = await tls.getRemoteAddress();
          if (!current()) return;
          stage = 'peer-check';
          if (peer.port !== port || (publicAddresses !== undefined ?
            !this.isGlobalIpv4(peer.address) || !publicAddresses.includes(peer.address) :
            family === 2 ? !new Ipv6EndpointPolicy().isGlobalIpv6(peer.address) : peer.address !== address)) throw new Error();
          finished = true;
          clearTimeout(timer);
          if (this.cancelAttempt === fail) this.cancelAttempt = undefined;
          resolve(tls);
        } catch (error) {
          const code = (error as Record<string, number>)?.code;
          nativeCode = Number.isFinite(code) ? code : 0;
          if (finished) dispose();
          else fail();
        }
      };
      connect().catch((): void => fail());
    });
  }

  /** 释放等待中的 TCP、TLS 及其后续晚完成回调。 */
  close(): void {
    this.stopped = true;
    this.cancelResolution?.();
    this.cancelResolution = undefined;
    this.cancelAttempt?.();
    this.cancelAttempt = undefined;
    try { this.activeTls?.close().catch((): void => {}); } catch (_) { /* 已关闭连接可再次释放。 */ }
    try { this.pendingTcp?.close().catch((): void => {}); } catch (_) { /* 尚未绑定的句柄可直接释放。 */ }
    this.activeTls = undefined;
    this.pendingTcp = undefined;
  }
}
