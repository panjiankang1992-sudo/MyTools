import { HomeChannelWebSocket } from './HomeChannelWebSocket';
import util from '@ohos.util';
import loopback from 'libhome_channel.so';
import { HomeChannelOrigin } from './HomeChannelOrigin';

interface ChannelWaiter {
  bulk: boolean;
}

/** 一条长连接在完成一笔 HTTP 交换之后才允许复用。 */
class ChannelLane {
  readonly wire: HomeChannelWebSocket = new HomeChannelWebSocket();
  peer: number = -1;
  ready: boolean = false;
  bulk: boolean = false;
  closed: boolean = false;
  private queuedBytes: number = 0;
  private output: Promise<void> = Promise.resolve();
  private firstForwardAt: number = -1;
  private binaryBytes: number = 0;
  private binaryFrames: number = 0;
  private writtenBytes: number = 0;
  private completedWrites: number = 0;
  private writeAwaitMs: number = 0;
  private queuePeakBytes: number = 0;

  async connect(url: string, access: string, approvedPublicOrigin: string): Promise<void> {
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => { this.close(); reject(new Error('Channel handshake timeout')); }, 7500);
      const failed = (): void => {
        clearTimeout(timer);
        this.close();
        reject(new Error('Channel unavailable'));
      };
      this.wire.on('error', (error) => { console.warn(`Home channel websocket error: ${error?.code ?? 0}`); failed(); });
      this.wire.on('close', () => {
        if (!this.closed) console.info('Home channel event: remoteclose');
        failed();
      });
      this.wire.on('message', (err, data: string | ArrayBuffer) => {
        if ((err && err.code !== 0) || this.closed) { failed(); return; }
        if (!this.ready) {
          if (data !== 'READY') { failed(); return; }
          clearTimeout(timer); this.ready = true; resolve(); return;
        }
        if (this.peer < 0) { failed(); return; }
        const peer = this.peer;
        const length = typeof data === 'string' ? 0 : data.byteLength;
        if (typeof data !== 'string') { this.binaryBytes += length; this.binaryFrames++; }
        this.queuedBytes += length;
        this.queuePeakBytes = Math.max(this.queuePeakBytes, this.queuedBytes);
        if (this.queuedBytes > 1280 * 1024 || length > 65536) { failed(); return; }
        this.output = this.output.then(async (): Promise<void> => {
          if (this.closed || this.peer !== peer) return;
          if (typeof data !== 'string') {
            try {
              const started = Date.now();
              await loopback.write(peer, data);
              this.writtenBytes += length;
              this.completedWrites++;
              this.writeAwaitMs += Math.max(0, Date.now() - started);
              this.queuedBytes -= length;
            } catch (_) {
              console.info('Home channel event: localwrite');
              throw new Error('Channel output unavailable');
            }
          } else if (data === 'FLUSH') {
            await this.wire.send('ACK');
          } else if (data === 'END') {
            // 本地连接以完整 HTTP 响应结束；确认后长连接才可分配给下一笔请求。
            this.peer = -2;
            loopback.end(peer);
            await this.wire.send('ACK');
            if (!this.closed) { this.bulk = false; this.peer = -1; }
          } else {
            throw new Error('Unknown channel frame');
          }
        }).catch((): void => this.close());
      });
      this.wire.connect(url, { proxy: 'no-proxy', approvedPublicOrigin: approvedPublicOrigin,
        header: { 'Authorization': 'Bearer ' + access, 'X-MyTools-Channel': '1' } }).catch(() => failed());
    });
  }

  async forward(peer: number, first: ArrayBuffer, pending: Promise<ArrayBuffer>): Promise<void> {
    if (this.firstForwardAt < 0) {
      this.firstForwardAt = Date.now();
      this.wire.resetReadStats();
    }
    this.peer = peer;
    try {
      await this.wire.send('BEGIN');
      for (let offset = 0; offset < first.byteLength; offset += 65536) {
        await this.wire.send(first.slice(offset, offset + 65536));
      }
      let next = pending;
      while (!this.closed && this.peer === peer) {
        const data = await next;
        if (this.closed || this.peer !== peer) return;
        // 本地取消后立即销毁仍属于该请求的通道，避免慢响应或未完成上传占满连接池。
        if (data.byteLength === 0) {
          this.close();
          return;
        }
        await this.wire.send(data);
        if (!this.closed && this.peer === peer) next = loopback.read(peer);
      }
    } catch (_) {
      if (this.peer === peer) this.close();
    }
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    if (this.peer >= 0) loopback.end(this.peer);
    this.peer = -2;
    const input = this.firstForwardAt >= 0 ? this.wire.readStats() : undefined;
    try { this.wire.close().catch((): void => {}); } catch (_) { /* 连接可能已结束。 */ }
    if (input !== undefined) {
      // 每条真实转发连接关闭时只输出一行累计值，不记录路径、凭据或正文。
      console.info(`Home channel summary: tlsBytes=${input.bytes}, tlsCallbacks=${input.callbacks}, tlsSpanMs=${input.spanMs}, binaryBytes=${this.binaryBytes}, binaryFrames=${this.binaryFrames}, writtenBytes=${this.writtenBytes}, writes=${this.completedWrites}, writeMs=${this.writeAwaitMs}, queuePeakBytes=${this.queuePeakBytes}, forwardMs=${Math.max(0, Date.now() - this.firstForwardAt)}`);
    }
  }
}

/** 应用私有的 HTTP 字节流通道；只监听回环地址，不创建系统 VPN。 */
export class HomeAppChannel {
  private lanes: ChannelLane[] = [];
  private opening: number = 0;
  private openingBulk: number = 0;
  private waiters: ChannelWaiter[] = [];
  private generation: number = 0;
  private url: string = '';
  private approvedPublicOrigin: string = '';
  private key: string = '';
  private token?: () => Promise<string>;

  async start(origin: string, service: string, token: () => Promise<string>): Promise<string> {
    this.stop();
    if (!/^https:\/\/[a-z0-9.-]+(?::[0-9]{1,5})?$/.test(origin)) throw new Error('Invalid channel server');
    const revision = this.generation;
    this.url = 'wss://' + origin.substring(8) + '/_mytools/app-channel/v1';
    // 只由当前登录服务的完全相同来源授予 IPv4 公网中继权限。
    this.approvedPublicOrigin = origin === service ? service : '';
    this.token = token;
    this.key = util.generateRandomUUID(true).replace(/-/g, '').toLowerCase();
    await this.createLane(revision);
    if (revision !== this.generation) throw new Error('Channel changed');
    const port = loopback.start((id: number): void => {
      this.accept(id, revision).catch((): void => loopback.end(id));
    });
    return HomeChannelOrigin.open(port, this.key, service);
  }

  private async createLane(revision: number, reserve: boolean = false, bulk: boolean = false): Promise<ChannelLane> {
    if (!this.token) throw new Error('Channel closed');
    const lane = new ChannelLane();
    this.opening++;
    if (bulk) this.openingBulk++;
    try {
      const token = await this.token();
      if (revision !== this.generation) throw new Error('Channel changed');
      await lane.connect(this.url, token, this.approvedPublicOrigin);
      if (revision !== this.generation) throw new Error('Channel changed');
      if (reserve) { lane.peer = -3; lane.bulk = bulk; }
      this.lanes.push(lane);
      return lane;
    } catch (err) { lane.close(); throw err instanceof Error ? err : new Error('Channel unavailable'); }
    finally { this.opening--; if (bulk) this.openingBulk--; }
  }

  private async accept(peer: number, revision: number): Promise<void> {
    // 缓慢或无能力路径的本地连接不能占用远端通道。
    let expired: boolean = false;
    const deadline = setTimeout(() => { expired = true; loopback.end(peer); }, 12000);
    try {
      const prefix = new Uint8Array(8192);
      let used = 0;
      let line = -1;
      let remainder: ArrayBuffer = new ArrayBuffer(0);
      while (line < 0) {
        const incoming = new Uint8Array(await loopback.read(peer));
        if (!incoming.length) throw new Error('Local request closed');
        for (let i = 0; i < incoming.length; i++) {
          if (used >= prefix.length) throw new Error('Invalid request line');
          prefix[used++] = incoming[i];
          if (used >= 2 && prefix[used - 2] === 13 && prefix[used - 1] === 10) {
            line = used;
            remainder = incoming.slice(i + 1).buffer as ArrayBuffer;
            break;
          }
        }
      }
      let head = '';
      for (let i = 0; i < used - 2; i++) head += String.fromCharCode(prefix[i]);
      const parts = head.split(' ');
      if (parts.length !== 3 || !/^(GET|HEAD|POST|PUT|PATCH|DELETE)$/.test(parts[0]) ||
        !parts[1].startsWith('/' + this.key + '/api/') || parts[2] !== 'HTTP/1.1') throw new Error('Invalid local request');
      const sanitized = parts[0] + ' ' + parts[1].substring(this.key.length + 1) + ' HTTP/1.1\r\n';
      const first = new Uint8Array(sanitized.length + remainder.byteLength);
      for (let i = 0; i < sanitized.length; i++) first[i] = sanitized.charCodeAt(i);
      first.set(new Uint8Array(remainder), sanitized.length);
      clearTimeout(deadline);
      const bulk = this.bulkRequest(parts[0], parts[1].substring(this.key.length + 1));
      const waiter: ChannelWaiter = { bulk: bulk };
      if (this.waiters.length >= 12) {
        console.info('Home channel event: poolfull');
        await this.busyReply(peer, parts[0] === 'HEAD');
        return;
      }
      this.waiters.push(waiter);
      const dequeue = (): void => {
        this.waiters = this.waiters.filter((item: ChannelWaiter) => item !== waiter);
      };
      let cancelled: boolean = false;
      let poolExpired: boolean = false;
      let busy: Promise<void> | undefined;
      const poolTimer = setTimeout((): void => {
        poolExpired = true;
        dequeue();
        console.info('Home channel event: pooldeadline');
        busy = this.busyReply(peer, parts[0] === 'HEAD');
      }, 15000);
      let lane: ChannelLane | undefined;
      try {
        // 排队时最多暂存一块请求体，并观察无需请求体的读操作是否已经被调用者取消。
        const pending = loopback.read(peer).then((data: ArrayBuffer): ArrayBuffer => {
          if (data.byteLength === 0) { cancelled = true; dequeue(); loopback.end(peer); }
          return data;
        }).catch((): ArrayBuffer => { cancelled = true; dequeue(); loopback.end(peer); return new ArrayBuffer(0); });
        while (!lane && revision === this.generation && !cancelled && !poolExpired) {
          this.lanes = this.lanes.filter((item: ChannelLane) => !item.closed);
          const activeBulk = this.openingBulk + this.lanes.filter((item: ChannelLane) =>
            item.bulk && item.peer !== -1).length;
          const first = this.waiters.find((item: ChannelWaiter) => !item.bulk || activeBulk < 4);
          if (first === waiter) {
            lane = this.lanes.find((item: ChannelLane) => item.ready && item.peer === -1);
            if (lane) {
              dequeue();
              lane.peer = -3;
              lane.bulk = bulk;
            } else if (this.lanes.length + this.opening < 5) {
              dequeue();
              lane = await this.createLane(revision, true, bulk);
            }
          }
          if (!lane) await new Promise<void>((resolve) => { setTimeout(resolve, 25); });
        }
        if (!lane || revision !== this.generation || expired || cancelled || poolExpired) {
          if (lane && lane.peer === -3) { lane.bulk = false; lane.peer = -1; }
          if (poolExpired) { await busy; return; }
          throw new Error('Channel unavailable');
        }
        clearTimeout(poolTimer);
        await lane.forward(peer, first.buffer as ArrayBuffer, pending);
      } finally {
        clearTimeout(poolTimer);
        dequeue();
      }
    } finally { clearTimeout(deadline); }
  }

  /** 尚未转发的本地请求只收到容量响应，不能把拥塞误报为远端通道故障。 */
  private async busyReply(peer: number, head: boolean): Promise<void> {
    try {
      const body = '{"code":"CONNECTIVITY_BUSY","message":"Channel capacity busy","data":null}';
      const reply = 'HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json\r\n' +
        'Content-Length: ' + body.length + '\r\nRetry-After: 1\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n' + (head ? '' : body);
      const bytes = new util.TextEncoder().encodeInto(reply);
      await loopback.write(peer, bytes.buffer as ArrayBuffer);
    } catch (_) {
      // 调用者已经取消时，无需重试本地响应，更不能重放原业务请求。
      console.info('Home channel event: localwrite');
    } finally { loopback.end(peer); }
  }

  /** 仅按规范路径识别流式正文，查询参数不能将批量传输伪装成控制请求。 */
  private bulkRequest(method: string, target: string): boolean {
    if (method !== 'GET' && method !== 'HEAD') return false;
    const path = target.split('?')[0];
    if (/[\\%#]/.test(path) || path.includes('//') || path.split('/').some((part: string) =>
      part === '.' || part === '..')) return true;
    if (path === '/api/app/v1/reader/sources') return true;
    const ticket = /^\/api\/app\/v1\/(?:(?:media|local-media)\/(?:ebook-)?tickets|drive-tickets|audiobook-playback\/tickets|audiobook-voice-preview\/tickets)\/[a-f0-9]{32}(?:\/.*)?$/.test(path);
    if (ticket) return !/^\/api\/app\/v1\/media\/tickets\/[a-f0-9]{32}\/metrics$/.test(path);
    return /^\/api\/app\/v1\/media\/items\/[a-f0-9-]{36}\/(?:thumbnail|content)$/.test(path) ||
      /^\/api\/(?:app\/v1\/media\/thumbnail|app\/v1\/files\/download|localfiles\/[1-9][0-9]*\/(?:thumbnail|content)|ebooks\/[^/]+\/cover|cloud\/file)$/.test(path);
  }

  stop(): void {
    this.generation++;
    HomeChannelOrigin.close();
    this.token = undefined;
    this.approvedPublicOrigin = '';
    this.key = '';
    this.lanes.forEach((lane: ChannelLane) => lane.close());
    this.lanes = [];
    this.waiters = [];
    loopback.stop();
  }
}
