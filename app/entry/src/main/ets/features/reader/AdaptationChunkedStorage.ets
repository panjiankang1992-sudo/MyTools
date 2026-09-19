import { AdaptationPendingStorage } from './AdaptationPendingJournal';

/** 每条安全资产至多 1024 字节，端口不得回落到普通明文存储。 */
export interface AdaptationAssetPort {
  read(alias: string): Promise<Uint8Array | undefined>;
  write(alias: string, value: Uint8Array): Promise<void>;
  digest(value: Uint8Array): Promise<string>;
  encode(value: string): Uint8Array;
  decode(value: Uint8Array): string;
}
interface PendingManifest { version: number; bank: string; size: number; sha256: string; }

/** 双槽日志先写非活动分片，再原子切换索引；读写由上层账户日志串行化。 */
export class AdaptationChunkedStorage implements AdaptationPendingStorage {
  private readonly port: AdaptationAssetPort;
  private readonly prefix: string;
  constructor(scope: string, port: AdaptationAssetPort) {
    if (!/^account:v2:[a-f0-9]{64}$/.test(scope)) throw new Error('ADAPTATION_ACCOUNT_INVALID');
    this.port = port; this.prefix = `mytools.adaptation.pending.v1.${scope}`;
  }
  /** 只读取已提交槽位，缺片、错摘要和非法 UTF-8 不能当成没有旧请求。 */
  async load(): Promise<string | undefined> {
    const manifest = await this.manifest(); if (manifest === undefined) return undefined;
    const value = new Uint8Array(manifest.size);
    try {
      for (let offset = 0; offset < manifest.size; offset += 1024) {
        const chunk = await this.port.read(`${this.prefix}.${manifest.bank}.${offset / 1024}`);
        if (chunk === undefined || chunk.length !== Math.min(1024, manifest.size - offset)) throw this.invalid();
        value.set(chunk, offset);
      }
      if (await this.port.digest(value) !== manifest.sha256) throw this.invalid();
      return this.port.decode(value);
    } finally { value.fill(0); }
  }
  /** 任一分片失败不替换活动索引，新请求只有整个保存返回后才能发出。 */
  async save(text: string): Promise<void> {
    const value = this.port.encode(text);
    try {
      if (value.length < 1 || value.length > 65536) throw new Error('ADAPTATION_PENDING_CAPACITY');
      const previous = await this.manifest(); const bank = previous?.bank === 'a' ? 'b' : 'a';
      const hash = await this.port.digest(value);
      if (!/^[a-f0-9]{64}$/.test(hash)) throw this.invalid();
      for (let offset = 0; offset < value.length; offset += 1024) {
        await this.port.write(`${this.prefix}.${bank}.${offset / 1024}`, value.slice(offset, Math.min(offset + 1024, value.length)));
      }
      // 所有分片成功后仅切换一个小索引，提交前旧槽始终可读。
      const next: PendingManifest = { version: 1, bank: bank, size: value.length, sha256: hash };
      await this.port.write(this.prefix, this.port.encode(JSON.stringify(next)));
    } finally { value.fill(0); }
  }
  private async manifest(): Promise<PendingManifest | undefined> {
    const raw = await this.port.read(this.prefix); if (raw === undefined) return undefined;
    if (raw.length < 1 || raw.length > 1024) throw this.invalid();
    let value: PendingManifest;
    try { value = JSON.parse(this.port.decode(raw)) as PendingManifest; } catch (_) { throw this.invalid(); }
    if (value === null || typeof value !== 'object' || value.version !== 1 || !['a', 'b'].includes(value.bank) ||
      !Number.isSafeInteger(value.size) || value.size < 1 || value.size > 65536 ||
      typeof value.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(value.sha256)) throw this.invalid();
    return value;
  }
  private invalid(): Error { return new Error('ADAPTATION_PENDING_INVALID'); }
}
