import connection from '@ohos.net.connection';
import socket from '@ohos.net.socket';
import cryptoFramework from '@ohos.security.cryptoFramework';
import deviceInfo from '@ohos.deviceInfo';
import { Ipv6EndpointPolicy } from './Ipv6EndpointPolicy';

interface DnsName {
  value: string;
  next: number;
}

interface DnsAnswer {
  owner: string;
  type: number;
  alias: string;
  address: string;
}

/** 保留成功解析的严格规则；仅兼容系统 IPv6 查询异常，解析来源沿用当前网络。 */
export class SystemIpv6Resolver {
  private readonly policy: Ipv6EndpointPolicy = new Ipv6EndpointPolicy();

  /** 获取 HTTPS 入口的全球 IPv6 地址，所有解析操作共用一个短截止时间。 */
  async resolve(origin: string): Promise<string[]> {
    const host = this.policy.hostname(origin);
    if (host.length > 253) throw new Error('IPv6 resolution unavailable');
    const deadline = Date.now() + 1800;
    let addresses: connection.NetAddress[];
    try {
      addresses = await this.bounded(connection.getAddressesByName(host), Math.min(350, deadline - Date.now()));
    } catch (_) {
      return this.resolveAfterFailure(host, deadline);
    }
    // 成功但为空、包含 IPv4 或私网地址的回答不能触发兼容回退。
    return this.checked(addresses.map((address: connection.NetAddress): string => address.address));
  }

  private async resolveAfterFailure(host: string, deadline: number): Promise<string[]> {
    if (deviceInfo.sdkApiVersion >= 23) {
      let addresses: connection.NetAddress[] | undefined;
      try {
        addresses = await this.bounded(connection.getAddressesByNameWithOptions(host,
          { family: connection.FamilyType.FAMILY_TYPE_IPV6 }), Math.min(400, deadline - Date.now()));
      } catch (_) { /* 新接口不可用时，向当前网络的同一解析器发送标准 AAAA 查询。 */ }
      if (addresses !== undefined) {
        return this.checked(addresses.map((address: connection.NetAddress): string => address.address));
      }
    }
    let net: connection.NetHandle;
    let properties: connection.ConnectionProperties;
    try {
      net = await this.bounded(connection.getDefaultNet(), deadline - Date.now());
      properties = await this.bounded(connection.getConnectionProperties(net), deadline - Date.now());
    } catch (_) {
      throw new Error('IPv6 resolution unavailable');
    }
    const sources: string[] = [];
    const servers = properties.dnses.filter((server: connection.NetAddress): boolean => {
      const source = SystemIpv6Resolver.addressKey(server.address);
      if (!source || sources.includes(source)) return false;
      sources.push(source);
      return true;
    }).slice(0, 3);
    if (!servers.length || deadline <= Date.now()) throw new Error('IPv6 resolution unavailable');
    const stop: Array<() => void> = [];
    try {
      return await new Promise<string[]>((resolve, reject) => {
        let failed = 0;
        servers.forEach((server: connection.NetAddress): void => {
          this.query(host, server, net, deadline, stop).then(resolve).catch((): void => {
            if (++failed === servers.length) reject(new Error('IPv6 resolution unavailable'));
          });
        });
      });
    } finally {
      // 成功、失败或外层截止之后，都立即回收仍在等待的其他 DNS 套接字。
      stop.forEach((close: () => void): void => close());
    }
  }

  private checked(addresses: string[]): string[] {
    if (!addresses.length || addresses.length > 16 ||
      !addresses.every((address: string): boolean => this.policy.isGlobalIpv6(address))) {
      throw new Error('IPv6 resolution unavailable');
    }
    return Array.from(new Set<string>(addresses));
  }

  private bounded<T>(work: Promise<T>, milliseconds: number): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout((): void => reject(new Error('IPv6 resolution unavailable')), Math.max(0, milliseconds));
      work.then((result: T): void => { clearTimeout(timer); resolve(result); })
        .catch((): void => { clearTimeout(timer); reject(new Error('IPv6 resolution unavailable')); });
    });
  }

  private query(host: string, server: connection.NetAddress, net: connection.NetHandle,
    deadline: number, stop: Array<() => void>): Promise<string[]> {
    let id: number;
    let query: Uint8Array;
    let wire: socket.UDPSocket;
    try {
      const random = cryptoFramework.createRandom().generateRandomSync(2).data;
      id = random[0] * 256 + random[1];
      query = SystemIpv6Resolver.queryPacket(host, id);
      wire = socket.constructUDPSocketInstance();
    } catch (_) {
      return Promise.reject(new Error('IPv6 resolution unavailable'));
    }
    return new Promise<string[]>((resolve, reject) => {
      let done = false;
      const close = (): void => {
        try { wire.close().catch((): void => {}); } catch (_) { /* 套接字可能尚未完成绑定。 */ }
      };
      const finish = (addresses?: string[]): void => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        close();
        if (addresses !== undefined) resolve(addresses);
        else reject(new Error('IPv6 resolution unavailable'));
      };
      const timer = setTimeout((): void => finish(), Math.max(0, deadline - Date.now()));
      stop.push((): void => finish());
      const prepare = async (): Promise<void> => {
        const family = server.address.includes(':') ? 2 : 1;
        await wire.bind({ address: family === 2 ? '::' : '0.0.0.0', port: 0, family: family });
        if (done) { close(); return; }
        await net.bindSocket(wire);
        if (done) { close(); return; }
        wire.on('error', (): void => finish());
        wire.on('message', (event: socket.SocketMessageInfo): void => {
          if (done || event.remoteInfo.port !== 53 ||
            SystemIpv6Resolver.addressKey(event.remoteInfo.address) !== SystemIpv6Resolver.addressKey(server.address)) return;
          const bytes = new Uint8Array(event.message);
          // 不相关的旧响应不会改变当前查询；匹配事务的异常消息则拒绝。
          if (bytes.length < 2 || bytes[0] * 256 + bytes[1] !== id) return;
          try { finish(SystemIpv6Resolver.responseAddresses(bytes, host, id)); } catch (_) { finish(); }
        });
        await wire.send({ data: query.buffer as ArrayBuffer,
          address: { address: server.address, port: 53, family: family } });
      };
      prepare().catch((): void => finish());
    });
  }

  /** 构造单问题的标准递归 AAAA 查询，不使用外部解析器或硬编码地址。 */
  static queryPacket(host: string, id: number): Uint8Array {
    const policy = new Ipv6EndpointPolicy();
    if (host.length > 253 || policy.hostname('https://' + host) !== host || !Number.isInteger(id) || id < 0 || id > 65535) {
      throw new Error('Invalid IPv6 DNS packet');
    }
    const data: number[] = [id >> 8, id & 255, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0];
    host.split('.').forEach((label: string): void => {
      data.push(label.length);
      for (let i = 0; i < label.length; i++) data.push(label.charCodeAt(i));
    });
    data.push(0, 0, 28, 0, 1);
    return new Uint8Array(data);
  }

  /** 校验 DNS 响应事务、问题、压缩名称和别名链，仅返回链上全部合格的 AAAA。 */
  static responseAddresses(data: Uint8Array, host: string, id: number): string[] {
    if (data.length < 12 || data.length > 4096 || SystemIpv6Resolver.word(data, 0) !== id) {
      throw new Error('Invalid IPv6 DNS packet');
    }
    const flags = SystemIpv6Resolver.word(data, 2);
    const answers = SystemIpv6Resolver.word(data, 6);
    const authority = SystemIpv6Resolver.word(data, 8);
    const additional = SystemIpv6Resolver.word(data, 10);
    if ((flags & 0x8000) === 0 || (flags & 0x7a4f) !== 0 || SystemIpv6Resolver.word(data, 4) !== 1 ||
      answers > 64 || authority > 64 || additional > 64 || answers + authority + additional > 128) {
      throw new Error('Invalid IPv6 DNS packet');
    }
    const question = SystemIpv6Resolver.readName(data, 12);
    if (question.value !== host.toLowerCase() || SystemIpv6Resolver.word(data, question.next) !== 28 ||
      SystemIpv6Resolver.word(data, question.next + 2) !== 1) throw new Error('Invalid IPv6 DNS packet');
    let cursor = question.next + 4;
    const records: DnsAnswer[] = [];
    for (let index = 0; index < answers + authority + additional; index++) {
      const owner = SystemIpv6Resolver.readName(data, cursor);
      const type = SystemIpv6Resolver.word(data, owner.next);
      const recordClass = SystemIpv6Resolver.word(data, owner.next + 2);
      const length = SystemIpv6Resolver.word(data, owner.next + 8);
      const start = owner.next + 10;
      const end = start + length;
      if (end > data.length) throw new Error('Invalid IPv6 DNS packet');
      if (index < answers && recordClass === 1 && (type === 5 || type === 28)) {
        let alias = '';
        let address = '';
        if (type === 5) {
          const cname = SystemIpv6Resolver.readName(data, start);
          if (cname.next !== end) throw new Error('Invalid IPv6 DNS packet');
          alias = cname.value;
        } else {
          if (length !== 16) throw new Error('Invalid IPv6 DNS packet');
          const words: string[] = [];
          for (let i = start; i < end; i += 2) words.push(SystemIpv6Resolver.word(data, i).toString(16));
          address = words.join(':');
        }
        records.push({ owner: owner.value, type: type, alias: alias, address: address });
      }
      cursor = end;
    }
    if (cursor !== data.length) throw new Error('Invalid IPv6 DNS packet');
    const chain: string[] = [host.toLowerCase()];
    let current = host.toLowerCase();
    for (let depth = 0; depth <= 8; depth++) {
      const aliases = Array.from(new Set<string>(records.filter((record: DnsAnswer): boolean =>
        record.owner === current && record.type === 5).map((record: DnsAnswer): string => record.alias)));
      const addresses = records.filter((record: DnsAnswer): boolean => record.owner === current && record.type === 28)
        .map((record: DnsAnswer): string => record.address);
      if (addresses.length) {
        if (aliases.length) throw new Error('Invalid IPv6 DNS packet');
        return new SystemIpv6Resolver().checked(addresses);
      }
      if (aliases.length !== 1 || depth === 8 || chain.includes(aliases[0])) throw new Error('Invalid IPv6 DNS packet');
      current = aliases[0];
      chain.push(current);
    }
    throw new Error('Invalid IPv6 DNS packet');
  }

  private static word(data: Uint8Array, offset: number): number {
    if (offset < 0 || offset + 2 > data.length) throw new Error('Invalid IPv6 DNS packet');
    return data[offset] * 256 + data[offset + 1];
  }

  private static readName(data: Uint8Array, start: number): DnsName {
    let cursor = start;
    let next = -1;
    const labels: string[] = [];
    const visited: number[] = [];
    for (let count = 0; count < 128; count++) {
      if (cursor >= data.length || visited.includes(cursor)) throw new Error('Invalid IPv6 DNS packet');
      visited.push(cursor);
      const length = data[cursor++];
      if ((length & 0xc0) === 0xc0) {
        if (cursor >= data.length) throw new Error('Invalid IPv6 DNS packet');
        const pointer = ((length & 63) << 8) | data[cursor++];
        if (pointer < 12 || pointer >= cursor - 2) throw new Error('Invalid IPv6 DNS packet');
        if (next < 0) next = cursor;
        cursor = pointer;
      } else {
        if ((length & 0xc0) !== 0 || length > 63 || cursor + length > data.length) throw new Error('Invalid IPv6 DNS packet');
        if (length === 0) {
          const value = labels.join('.');
          if (value.length > 253) throw new Error('Invalid IPv6 DNS packet');
          return { value: value, next: next < 0 ? cursor : next };
        }
        let label = '';
        for (let i = 0; i < length; i++) label += String.fromCharCode(data[cursor++]);
        if (!/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/i.test(label)) throw new Error('Invalid IPv6 DNS packet');
        labels.push(label.toLowerCase());
      }
    }
    throw new Error('Invalid IPv6 DNS packet');
  }

  private static addressKey(value: string): string {
    const host = value.toLowerCase().split('%')[0];
    if (/^(?:\d{1,3}\.){3}\d{1,3}$/.test(host)) {
      const parts = host.split('.');
      return parts.every((part: string): boolean => Number(part) <= 255) ? 'v4:' + parts.map((part: string): string => String(Number(part))).join('.') : '';
    }
    if (!/^[0-9a-f:]+$/.test(host) || !host.includes(':')) return '';
    const halves = host.split('::');
    if (halves.length > 2) return '';
    const left = halves[0] ? halves[0].split(':') : [];
    const right = halves.length === 2 && halves[1] ? halves[1].split(':') : [];
    const count = left.length + right.length;
    if ((halves.length === 1 && count !== 8) || (halves.length === 2 && count >= 8) ||
      !left.concat(right).every((part: string): boolean => /^[0-9a-f]{1,4}$/.test(part))) return '';
    return 'v6:' + left.concat(new Array<string>(halves.length === 2 ? 8 - count : 0).fill('0')).concat(right)
      .map((part: string): string => Number.parseInt(part, 16).toString(16)).join(':');
  }
}
