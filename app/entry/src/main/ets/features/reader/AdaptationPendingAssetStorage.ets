import { asset } from '@kit.AssetStoreKit';
import { util } from '@kit.ArkTS';
import cryptoFramework from '@ohos.security.cryptoFramework';
import { AdaptationPendingStorage } from './AdaptationPendingJournal';
import { AdaptationAssetPort, AdaptationChunkedStorage } from './AdaptationChunkedStorage';

interface PendingAssetFailure { code: number; }

/** 仅使用系统安全资产承载加密分片，不保存到普通 Preferences 或日志。 */
class SystemPendingAssetPort implements AdaptationAssetPort {
  /** 只有系统明确返回不存在才表示没有未知请求，损坏或读取失败必须阻止新发送。 */
  async read(alias: string): Promise<Uint8Array | undefined> {
    const query: asset.AssetMap = new Map(); query.set(asset.Tag.ALIAS, this.encode(alias)); query.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ALL);
    try {
      const records = await asset.query(query); if (records.length === 0) return undefined;
      if (records.length !== 1) throw new Error('ADAPTATION_PENDING_INVALID');
      const secret = records[0].get(asset.Tag.SECRET);
      if (!(secret instanceof Uint8Array) || secret.length < 1 || secret.length > 1024) throw new Error('ADAPTATION_PENDING_INVALID');
      return secret;
    } catch (error) {
      if ((error as PendingAssetFailure).code === asset.ErrorCode.NOT_FOUND) return undefined;
      throw new Error('ADAPTATION_PENDING_STORAGE_UNAVAILABLE');
    }
  }
  /** 每条资产遵守系统的一千零二十四字节限制，更新失败不能删除旧条目。 */
  async write(alias: string, value: Uint8Array): Promise<void> {
    if (value.length < 1 || value.length > 1024) throw new Error('ADAPTATION_PENDING_CAPACITY');
    const query: asset.AssetMap = new Map(); query.set(asset.Tag.ALIAS, this.encode(alias));
    const update: asset.AssetMap = new Map(); update.set(asset.Tag.SECRET, value);
    try { await asset.update(query, update); }
    catch (error) {
      if ((error as PendingAssetFailure).code !== asset.ErrorCode.NOT_FOUND) throw new Error('ADAPTATION_PENDING_STORAGE_UNAVAILABLE');
      update.set(asset.Tag.ALIAS, this.encode(alias)); update.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_FIRST_UNLOCKED);
      try { await asset.add(update); } catch (_) { throw new Error('ADAPTATION_PENDING_STORAGE_UNAVAILABLE'); }
    } finally { value.fill(0); }
  }
  /** 绑定索引和完整分片，检测损坏或混入其他写入代次。 */
  async digest(value: Uint8Array): Promise<string> {
    try {
      const digest = cryptoFramework.createMd('SHA256'); await digest.update({ data: value });
      const result = await digest.digest(); let hex = '';
      result.data.forEach((byte: number) => { hex += byte.toString(16).padStart(2, '0'); }); return hex;
    } catch (_) { throw new Error('ADAPTATION_PENDING_STORAGE_UNAVAILABLE'); }
  }
  /** 以 UTF-8 字节分片，不以字符串长度猜测平台容量。 */
  encode(value: string): Uint8Array { return new util.TextEncoder().encodeInto(value); }
  /** 完整拼接后严格解码，跨分片的多字节字符不会被替换。 */
  decode(value: Uint8Array): string { return util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(value); }
}

/** 按包含服务身份的账户作用域保留最多两个槽位，不存储令牌或小说正文。 */
export class AdaptationPendingAssetStorage implements AdaptationPendingStorage {
  private readonly storage: AdaptationChunkedStorage;
  constructor(scope: string) { this.storage = new AdaptationChunkedStorage(scope, new SystemPendingAssetPort()); }
  /** 恢复已原子提交的安全日志，残留未提交片段不能触发请求。 */
  async load(): Promise<string | undefined> { return this.storage.load(); }
  /** 先持久化所有分片再切换索引，调用者在成功前不得发送创建请求。 */
  async save(value: string): Promise<void> { await this.storage.save(value); }
}
