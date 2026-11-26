/** 只包含固定控制接口响应需要的状态与原始正文。 */
export interface HomeHttpResponseBytes {
  status: number;
  body: Uint8Array;
}

/** 有界解析单笔 HTTP/1 控制响应，不接受重定向、压缩或模糊的消息边界。 */
export class BoundedHomeHttpResponse {
  private static readonly MAX_HEADER: number = 16384;
  private readonly maximumBody: number;
  private buffer: Uint8Array = new Uint8Array(0);
  private body: Uint8Array = new Uint8Array(0);
  private bodyLength: number = 0;
  private status: number = 0;
  private headerBytes: number = 0;
  private trailerBytes: number = 0;
  private headersRead: boolean = false;
  private chunked: boolean = false;
  private closeDelimited: boolean = false;
  private remaining: number = -1;
  private chunkRemaining: number = -1;
  private trailers: boolean = false;
  private finished: boolean = false;

  /** 固定接口各自声明正文上限，最高允许一 MiB 的既有身份资料响应。 */
  constructor(maximumBody: number = 65536) {
    if (!Number.isSafeInteger(maximumBody) || maximumBody < 0 || maximumBody > 1048576) {
      throw new Error('Invalid home HTTP limit');
    }
    this.maximumBody = maximumBody;
    this.body = new Uint8Array(maximumBody);
  }

  /** 接收任意分片；只有完整响应才返回正文。 */
  feed(incoming: Uint8Array): HomeHttpResponseBytes | undefined {
    if (this.finished && incoming.length > 0) throw new Error('Invalid home HTTP response');
    if (this.buffer.length + incoming.length > BoundedHomeHttpResponse.MAX_HEADER + this.maximumBody) {
      throw new Error('Home HTTP response limit exceeded');
    }
    const joined = new Uint8Array(this.buffer.length + incoming.length);
    joined.set(this.buffer);
    joined.set(incoming, this.buffer.length);
    this.buffer = joined;
    if (!this.headersRead) {
      const end = this.separator(this.buffer, true);
      if (end < 0) {
        if (this.buffer.length >= BoundedHomeHttpResponse.MAX_HEADER) throw new Error('Invalid home HTTP response');
        return undefined;
      }
      if (end + 4 > BoundedHomeHttpResponse.MAX_HEADER) throw new Error('Invalid home HTTP response');
      this.readHeaders(this.ascii(this.buffer.subarray(0, end)));
      this.headerBytes = end + 4;
      this.buffer = this.buffer.slice(end + 4);
      this.headersRead = true;
    }
    // 有长度与分块两种边界独立处理，禁止同时声明并产生歧义。
    if (this.chunked) this.readChunks();
    else {
      const count = this.closeDelimited ? this.buffer.length : Math.min(this.remaining, this.buffer.length);
      this.append(this.buffer.subarray(0, count));
      this.buffer = this.buffer.slice(count);
      if (!this.closeDelimited) {
        this.remaining -= count;
        if (this.remaining === 0) this.finish();
      }
    }
    return this.finished ? this.result() : undefined;
  }

  /** 连接关闭只能结束明确采用 EOF 边界的完整响应。 */
  end(): HomeHttpResponseBytes {
    if (this.headersRead && this.closeDelimited) this.finish();
    if (!this.finished) throw new Error('Incomplete home HTTP response');
    return this.result();
  }

  private readHeaders(text: string): void {
    const lines = text.split('\r\n');
    const match = /^HTTP\/1\.[01] ([2-5][0-9]{2})(?: [\x20-\x7e]*)?$/.exec(lines[0]);
    if (match === null) throw new Error('Invalid home HTTP response');
    this.status = Number(match[1]);
    if (this.status >= 300 && this.status < 400) throw new Error('Invalid home HTTP response');
    const headers: Map<string, string> = new Map();
    for (let i = 1; i < lines.length; i++) {
      if (/[\r\n]/.test(lines[i])) throw new Error('Invalid home HTTP response');
      const colon = lines[i].indexOf(':');
      const name = lines[i].substring(0, colon).toLowerCase();
      if (colon <= 0 || !this.headerName(name) || (headers.has(name) &&
        ['content-length', 'transfer-encoding', 'content-encoding', 'location'].includes(name))) {
        throw new Error('Invalid home HTTP response');
      }
      // Vary 等非边界字段允许合法重复；正文分帧字段仍须唯一。
      if (!headers.has(name)) headers.set(name, lines[i].substring(colon + 1).trim());
    }
    if (headers.has('location') || (headers.has('content-encoding') && headers.get('content-encoding')?.toLowerCase() !== 'identity')) {
      throw new Error('Invalid home HTTP response');
    }
    const length = headers.get('content-length');
    const transfer = headers.get('transfer-encoding');
    if (length !== undefined && transfer !== undefined) throw new Error('Invalid home HTTP response');
    if (transfer !== undefined) {
      if (transfer.toLowerCase() !== 'chunked') throw new Error('Invalid home HTTP response');
      this.chunked = true;
    } else if (length !== undefined) {
      if (!/^[0-9]{1,8}$/.test(length)) throw new Error('Invalid home HTTP response');
      this.remaining = Number(length);
      if (this.remaining > this.maximumBody) throw new Error('Home HTTP response limit exceeded');
    } else if (this.status === 204 || this.status === 304) this.remaining = 0;
    else this.closeDelimited = true;
  }

  private readChunks(): void {
    while (!this.finished) {
      if (this.trailers) {
        const end = this.separator(this.buffer, false);
        if (end < 0) {
          if (this.headerBytes + this.trailerBytes + this.buffer.length > BoundedHomeHttpResponse.MAX_HEADER) {
            throw new Error('Home HTTP response limit exceeded');
          }
          return;
        }
        this.trailerBytes += end + 2;
        if (this.headerBytes + this.trailerBytes > BoundedHomeHttpResponse.MAX_HEADER) throw new Error('Invalid home HTTP response');
        const line = this.ascii(this.buffer.subarray(0, end));
        this.buffer = this.buffer.slice(end + 2);
        if (line.length === 0) { this.finish(); return; }
        if (/[\r\n]/.test(line)) throw new Error('Invalid home HTTP response');
        const colon = line.indexOf(':');
        const name = line.substring(0, colon).toLowerCase();
        if (colon <= 0 || !this.headerName(name) ||
          ['content-length', 'transfer-encoding', 'content-encoding', 'location'].includes(name)) {
          throw new Error('Invalid home HTTP response');
        }
        continue;
      }
      if (this.chunkRemaining < 0) {
        const end = this.separator(this.buffer, false);
        if (end < 0) {
          if (this.buffer.length > 128) throw new Error('Invalid home HTTP response');
          return;
        }
        if (end > 128) throw new Error('Invalid home HTTP response');
        const line = this.ascii(this.buffer.subarray(0, end));
        if (!/^[0-9a-fA-F]{1,8}(?:;[\x20-\x7e]*)?$/.test(line)) throw new Error('Invalid home HTTP response');
        this.chunkRemaining = Number.parseInt(line.split(';')[0], 16);
        if (this.chunkRemaining > this.maximumBody - this.bodyLength) throw new Error('Home HTTP response limit exceeded');
        this.buffer = this.buffer.slice(end + 2);
        if (this.chunkRemaining === 0) { this.trailers = true; continue; }
      }
      // 正文逐段消耗，慢响应不会令未处理缓冲随分块数量增长。
      if (this.chunkRemaining > 0) {
        const count = Math.min(this.chunkRemaining, this.buffer.length);
        this.append(this.buffer.subarray(0, count));
        this.buffer = this.buffer.slice(count);
        this.chunkRemaining -= count;
        if (this.chunkRemaining > 0) return;
      }
      if (this.buffer.length < 2) return;
      if (this.buffer[0] !== 13 || this.buffer[1] !== 10) throw new Error('Invalid home HTTP response');
      this.buffer = this.buffer.slice(2);
      this.chunkRemaining = -1;
    }
  }

  private append(bytes: Uint8Array): void {
    if (this.bodyLength + bytes.length > this.maximumBody) throw new Error('Home HTTP response limit exceeded');
    this.body.set(bytes, this.bodyLength);
    this.bodyLength += bytes.length;
  }

  private finish(): void {
    if (this.buffer.length > 0) throw new Error('Invalid home HTTP response');
    this.finished = true;
  }

  private result(): HomeHttpResponseBytes { return { status: this.status, body: this.body.slice(0, this.bodyLength) }; }

  private separator(bytes: Uint8Array, header: boolean): number {
    const tail = header ? 4 : 2;
    for (let i = 0; i + tail <= bytes.length; i++) {
      if (bytes[i] === 13 && bytes[i + 1] === 10 &&
        (!header || (bytes[i + 2] === 13 && bytes[i + 3] === 10))) return i;
    }
    return -1;
  }

  private ascii(bytes: Uint8Array): string {
    let text = '';
    for (const value of bytes) {
      if ((value < 32 && value !== 9 && value !== 13 && value !== 10) || value > 126) throw new Error('Invalid home HTTP response');
      text += String.fromCharCode(value);
    }
    return text;
  }

  private headerName(value: string): boolean { return /^[!#$%&'*+.^_`|~a-z0-9-]+$/.test(value); }
}
