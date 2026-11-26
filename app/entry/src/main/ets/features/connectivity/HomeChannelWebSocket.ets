import socket from '@ohos.net.socket';
import cryptoFramework from '@ohos.security.cryptoFramework';
import util from '@ohos.util';
import { StrictHomeTlsConnection } from './StrictHomeTlsConnection';

/** 仅携带固定业务请求头；测试环境可显式指定 PEM 信任根。 */
export interface HomeChannelConnectOptions {
  header: Record<string, string>;
  ca?: string;
  proxy?: string;
  approvedPublicOrigin?: string;
}

/** 仅包含 TLS 输入数字，不携带地址或业务内容。 */
export interface HomeChannelReadStats {
  bytes: number;
  callbacks: number;
  spanMs: number;
}

/** 不包含地址、请求头或令牌的传输错误。 */
export class HomeChannelSocketError extends Error {
  readonly code: number = 1;

  /** 创建可安全显示的固定错误。 */
  constructor() { super('Secure channel unavailable'); }
}

type WireCallback = (error: HomeChannelSocketError | undefined, data: string | ArrayBuffer) => void;

/** 在严格验证证书链和主机名的 TLS 连接中实现有界 RFC 6455 客户端。 */
export class HomeChannelWebSocket {
  private tls?: socket.TLSSocket;
  private readonly connector: StrictHomeTlsConnection = new StrictHomeTlsConnection();
  private messageHandler?: WireCallback;
  private errorHandler?: WireCallback;
  private closeHandler?: WireCallback;
  private buffer: Uint8Array = new Uint8Array(0);
  private fragments: Uint8Array = new Uint8Array(0);
  private fragmentOpcode: number = 0;
  private upgraded: boolean = false;
  private closed: boolean = false;
  private started: boolean = false;
  private expectedAccept: string = '';
  private handshakeResolve?: (value: boolean) => void;
  private handshakeReject?: (error: Error) => void;
  private handshakeTimer: number = -1;
  private outgoing: Promise<void> = Promise.resolve();
  private readBytes: number = 0;
  private readCallbacks: number = 0;
  private firstReadAt: number = -1;
  private lastReadAt: number = -1;

  /** 在开始首笔业务交换时排除升级握手和 READY 的输入统计。 */
  resetReadStats(): void {
    this.readBytes = 0;
    this.readCallbacks = 0;
    this.firstReadAt = -1;
    this.lastReadAt = -1;
  }

  /** 获取匿名数字快照，连接关闭后仍可读取最后累计值。 */
  readStats(): HomeChannelReadStats {
    return { bytes: this.readBytes, callbacks: this.readCallbacks,
      spanMs: this.firstReadAt < 0 ? 0 : Math.max(0, this.lastReadAt - this.firstReadAt) };
  }

  /** 注册通道所需的消息、故障或关闭回调。 */
  on(type: string, handler: WireCallback): void {
    if (type === 'message') this.messageHandler = handler;
    else if (type === 'error') this.errorHandler = handler;
    else if (type === 'close') this.closeHandler = handler;
    else throw new Error('Unsupported channel event');
  }

  /** TLS 身份验证完成后，才发送带认证头的 WebSocket 升级请求。 */
  async connect(url: string, options: HomeChannelConnectOptions): Promise<boolean> {
    try {
      if (this.started || this.closed) throw new HomeChannelSocketError();
      this.started = true;
      const match = /^wss:\/\/([a-z0-9.-]+)(?::([0-9]{1,5}))?(\/[^\s#]*)$/.exec(url);
      if (!match) throw new HomeChannelSocketError();
      const host = match[1];
      const port = match[2] ? Number(match[2]) : 443;
      if (port < 1 || port > 65535) throw new HomeChannelSocketError();
      const key = new util.Base64Helper().encodeToStringSync(cryptoFramework.createRandom().generateRandomSync(16).data);
      const digest = cryptoFramework.createMd('SHA1');
      await digest.update({ data: new util.TextEncoder().encodeInto(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11') });
      this.expectedAccept = new util.Base64Helper().encodeToStringSync((await digest.digest()).data);
      let request = 'GET ' + match[3] + ' HTTP/1.1\r\nHost: ' + host + (port === 443 ? '' : ':' + port) +
        '\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ' + key + '\r\nSec-WebSocket-Version: 13\r\n';
      for (const name of Object.keys(options.header)) {
        const value = options.header[name];
        if (!/^[!#$%&'*+.^_\x60|~a-zA-Z0-9-]+$/.test(name) || /[^\x20-\x7e]/.test(value) ||
          !['authorization', 'x-mytools-channel'].includes(name.toLowerCase())) throw new HomeChannelSocketError();
        request += name + ': ' + value + '\r\n';
      }
      if (request.length > 8192) throw new HomeChannelSocketError();
      request += '\r\n';
      return new Promise<boolean>((resolve, reject) => {
        this.handshakeResolve = resolve;
        this.handshakeReject = reject;
        this.handshakeTimer = setTimeout((): void => this.terminate(true), 7500);
        const origin = 'https://' + host + (match[2] ? ':' + match[2] : '');
        this.upgrade(origin, request, options).catch((): void => this.terminate(true));
      });
    } catch (_) {
      this.terminate(true);
      throw new HomeChannelSocketError();
    }
  }

  private async upgrade(origin: string, request: string, options: HomeChannelConnectOptions): Promise<void> {
    try {
      const tls = await this.connector.open(origin, undefined, options.ca ?? '', 6000, options.approvedPublicOrigin ?? '');
      if (this.closed) {
        this.connector.close();
        throw new HomeChannelSocketError();
      }
      this.tls = tls;
      tls.on('message', (event: socket.SocketMessageInfo): void => {
        if (this.closed) return;
        this.readBytes += event.message.byteLength;
        this.readCallbacks++;
        const now = Date.now();
        if (this.firstReadAt < 0) this.firstReadAt = now;
        this.lastReadAt = now;
        try { this.receive(new Uint8Array(event.message)); } catch (_) { this.terminate(true); }
      });
      tls.on('error', (): void => this.terminate(true));
      tls.on('close', (): void => this.terminate(false));
      await tls.send(request);
    } catch (_) {
      throw new HomeChannelSocketError();
    }
  }

  /** 按顺序发送经过随机掩码处理的完整文本或二进制帧。 */
  send(data: string | ArrayBuffer): Promise<boolean> {
    const bytes = typeof data === 'string' ? new util.TextEncoder().encodeInto(data) : new Uint8Array(data);
    if (!this.upgraded || this.closed || bytes.length > 65536) return Promise.reject(new HomeChannelSocketError());
    return this.sendFrame(typeof data === 'string' ? 1 : 2, bytes);
  }

  private sendFrame(opcode: number, bytes: Uint8Array): Promise<boolean> {
    const operation = this.outgoing.then(async (): Promise<boolean> => {
      const tls = this.tls;
      if (this.closed || !tls) throw new HomeChannelSocketError();
      const prefix = bytes.length < 126 ? 2 : bytes.length <= 65535 ? 4 : 10;
      const frame = new Uint8Array(prefix + 4 + bytes.length);
      frame[0] = 128 | opcode;
      frame[1] = 128 | (prefix === 2 ? bytes.length : prefix === 4 ? 126 : 127);
      if (prefix === 4) new DataView(frame.buffer).setUint16(2, bytes.length, false);
      if (prefix === 10) new DataView(frame.buffer).setUint32(6, bytes.length, false);
      const mask = cryptoFramework.createRandom().generateRandomSync(4).data;
      frame.set(mask, prefix);
      for (let i = 0; i < bytes.length; i++) frame[prefix + 4 + i] = bytes[i] ^ mask[i % 4];
      await tls.send(frame.buffer as ArrayBuffer);
      return true;
    });
    this.outgoing = operation.then((): void => {}, (): void => {});
    return operation.catch((): boolean => { this.terminate(true); throw new HomeChannelSocketError(); });
  }

  private receive(incoming: Uint8Array): void {
    if (this.closed) return;
    // 服务端窗口与帧长度均有上限，残留头部或无确认的大量数据不会无限占用内存。
    if (this.buffer.length + incoming.length > 1280 * 1024) throw new HomeChannelSocketError();
    const joined = new Uint8Array(this.buffer.length + incoming.length);
    joined.set(this.buffer);
    joined.set(incoming, this.buffer.length);
    this.buffer = joined;
    if (!this.upgraded) {
      let end = -1;
      for (let i = 0; i + 3 < this.buffer.length; i++) {
        if (this.buffer[i] === 13 && this.buffer[i + 1] === 10 && this.buffer[i + 2] === 13 && this.buffer[i + 3] === 10) {
          end = i + 4;
          break;
        }
      }
      if (end < 0) {
        if (this.buffer.length > 16384) throw new HomeChannelSocketError();
        return;
      }
      if (end > 16384) throw new HomeChannelSocketError();
      this.verifyUpgrade(this.buffer.subarray(0, end));
      this.buffer = this.buffer.slice(end);
      this.upgraded = true;
      clearTimeout(this.handshakeTimer);
      this.handshakeTimer = -1;
      this.handshakeResolve?.(true);
      this.handshakeResolve = undefined;
      this.handshakeReject = undefined;
    }
    while (!this.closed && this.readFrame()) { /* 一次 TLS 回调可包含多个帧。 */ }
  }

  private verifyUpgrade(bytes: Uint8Array): void {
    let text = '';
    for (const byte of bytes) {
      if (byte > 126 || (byte < 32 && byte !== 13 && byte !== 10 && byte !== 9)) throw new HomeChannelSocketError();
      text += String.fromCharCode(byte);
    }
    const lines = text.split('\r\n');
    if (!/^HTTP\/1\.1 101(?: |$)/.test(lines[0])) {
      const status = /^HTTP\/1\.1 ([0-9]{3})(?: |$)/.exec(lines[0]);
      console.info(`Home channel upgrade rejected: kind=status, status=${status ? Number(status[1]) : 0}`);
      throw new HomeChannelSocketError();
    }
    const headers: Map<string, string> = new Map();
    for (let i = 1; i < lines.length; i++) {
      if (!lines[i]) continue;
      const colon = lines[i].indexOf(':');
      if (colon <= 0) {
        console.info('Home channel upgrade rejected: kind=header-name');
        throw new HomeChannelSocketError();
      }
      const name = lines[i].substring(0, colon).toLowerCase();
      if (!/^[!#$%&'*+.^_\x60|~a-z0-9-]+$/.test(name)) {
        console.info('Home channel upgrade rejected: kind=header-name');
        throw new HomeChannelSocketError();
      }
      // 普通头可以合法重复且不参与握手决策，只保存五个安全关键字段。
      if (!['upgrade', 'connection', 'sec-websocket-accept', 'sec-websocket-extensions',
        'sec-websocket-protocol'].includes(name)) continue;
      if (headers.has(name)) {
        // 关键头不得合并或覆盖，日志也不能包含原字段名称与值。
        console.info('Home channel upgrade rejected: kind=duplicate-header, category=critical');
        throw new HomeChannelSocketError();
      }
      headers.set(name, lines[i].substring(colon + 1).trim());
    }
    if (headers.get('upgrade')?.toLowerCase() !== 'websocket' ||
      !(headers.get('connection') ?? '').toLowerCase().split(',').map((value: string): string => value.trim()).includes('upgrade') ||
      headers.get('sec-websocket-accept') !== this.expectedAccept) {
      console.info('Home channel upgrade rejected: kind=required-headers');
      throw new HomeChannelSocketError();
    }
    if (headers.has('sec-websocket-extensions')) {
      console.info('Home channel upgrade rejected: kind=extension');
      throw new HomeChannelSocketError();
    }
    if (headers.has('sec-websocket-protocol')) {
      console.info('Home channel upgrade rejected: kind=subprotocol');
      throw new HomeChannelSocketError();
    }
  }

  private readFrame(): boolean {
    const input = this.buffer;
    if (input.length < 2) return false;
    const final = (input[0] & 128) !== 0;
    const opcode = input[0] & 15;
    if ((input[0] & 112) !== 0 || (input[1] & 128) !== 0 || ![0, 1, 2, 8, 9, 10].includes(opcode)) {
      throw new HomeChannelSocketError();
    }
    let length = input[1] & 127;
    let offset = 2;
    if (length === 126) {
      if (input.length < 4) return false;
      length = new DataView(input.buffer, input.byteOffset, input.byteLength).getUint16(2, false);
      if (length < 126) throw new HomeChannelSocketError();
      offset = 4;
    } else if (length === 127) {
      if (input.length < 10) return false;
      const view = new DataView(input.buffer, input.byteOffset, input.byteLength);
      if (view.getUint32(2, false) !== 0) throw new HomeChannelSocketError();
      length = view.getUint32(6, false);
      if (length < 65536) throw new HomeChannelSocketError();
      offset = 10;
    }
    if (length > 65536 || (opcode >= 8 && (!final || length > 125))) throw new HomeChannelSocketError();
    if (input.length < offset + length) return false;
    const bytes = input.slice(offset, offset + length);
    this.buffer = input.slice(offset + length);
    if (opcode === 8) {
      if (length === 1) throw new HomeChannelSocketError();
      this.terminate(false);
    } else if (opcode === 9) {
      this.sendFrame(10, bytes).catch((): void => {});
    } else if (opcode < 8) {
      this.dataFrame(opcode, final, bytes);
    }
    return true;
  }

  private dataFrame(opcode: number, final: boolean, bytes: Uint8Array): void {
    if ((opcode === 0 && !this.fragmentOpcode) || (opcode !== 0 && this.fragmentOpcode)) throw new HomeChannelSocketError();
    if (this.fragments.length + bytes.length > 65536) throw new HomeChannelSocketError();
    const body = new Uint8Array(this.fragments.length + bytes.length);
    body.set(this.fragments);
    body.set(bytes, this.fragments.length);
    if (!final) {
      this.fragmentOpcode = opcode || this.fragmentOpcode;
      this.fragments = body;
      return;
    }
    const completeOpcode = opcode || this.fragmentOpcode;
    this.fragments = new Uint8Array(0);
    this.fragmentOpcode = 0;
    const data = completeOpcode === 1 ? util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(body) : body.buffer as ArrayBuffer;
    this.messageHandler?.(undefined, data);
  }

  private terminate(failed: boolean): void {
    if (this.closed) return;
    this.closed = true;
    this.upgraded = false;
    this.buffer = new Uint8Array(0);
    this.fragments = new Uint8Array(0);
    clearTimeout(this.handshakeTimer);
    this.handshakeTimer = -1;
    this.handshakeReject?.(new Error('Secure channel unavailable'));
    this.handshakeResolve = undefined;
    this.handshakeReject = undefined;
    this.connector.close();
    this.tls = undefined;
    if (failed) this.errorHandler?.(new HomeChannelSocketError(), '');
    this.closeHandler?.(undefined, '');
  }

  /** 立即释放底层 TLS 套接字及等待中的握手。 */
  close(): Promise<boolean> {
    this.terminate(false);
    return Promise.resolve(true);
  }
}
