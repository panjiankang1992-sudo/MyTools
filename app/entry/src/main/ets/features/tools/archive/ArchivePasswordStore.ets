import { asset } from '@kit.AssetStoreKit';
import { util } from '@kit.ArkTS';
import cryptoFramework from '@ohos.security.cryptoFramework';
import { ArchivePassword, ArchivePasswordSummary } from './ArchiveModels';

interface AssetFailure { code: number; }

/** 每条解压密码独立加密保存，按当前服务账户隔离。 */
export class ArchivePasswordStore {
  private namespace: Uint8Array = new Uint8Array();

  /** 根据服务与账户生成固定长度的业务命名空间。 */
  async initialize(scope: string): Promise<void> {
    try {
    const hash = cryptoFramework.createMd('SHA256');
    await hash.update({ data: new util.TextEncoder().encodeInto('mytools.archive.password.v1|' + scope) });
    this.namespace = (await hash.digest()).data;
    } catch (error) {
      console.info(`Archive vault failure: ${(error as AssetFailure).code ?? -1}`);
      throw new Error('ARCHIVE_012');
    }
  }

  private query(): asset.AssetMap {
    if (this.namespace.length === 0) throw new Error('ARCHIVE_012');
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.DATA_LABEL_CRITICAL_1, this.namespace);
    return query;
  }

  /** 分页读取当前账户密码，不依赖单个不断增长的索引。 */
  async load(): Promise<ArchivePassword[]> {
    const records: ArchivePassword[] = [];
    try {
      for (let offset = 0; ; offset += 100) {
        const query = this.query();
        query.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ATTRIBUTES);
        query.set(asset.Tag.RETURN_LIMIT, 100);
        query.set(asset.Tag.RETURN_OFFSET, offset);
        let page: asset.AssetMap[] = [];
        try { page = await asset.query(query); }
        catch (error) {
          if ((error as AssetFailure).code === asset.ErrorCode.NOT_FOUND) break;
          console.info(`Archive vault query: ${(error as AssetFailure).code ?? -1}`);
          throw new Error('ARCHIVE_012');
        }
        for (const row of page) {
          // 系统不支持批量读取明文：先分页查询别名，再逐条解密。
          const alias = row.get(asset.Tag.ALIAS);
          if (!(alias instanceof Uint8Array)) throw new Error('ARCHIVE_012');
          const detail = this.query();
          detail.set(asset.Tag.ALIAS, alias);
          detail.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ALL);
          const values = await asset.query(detail);
          const secret = values.length > 0 ? values[0].get(asset.Tag.SECRET) : undefined;
          if (!(secret instanceof Uint8Array)) throw new Error('ARCHIVE_012');
          const record = JSON.parse(new util.TextDecoder().decodeToString(secret)) as ArchivePassword;
          if (!record.id || typeof record.value !== 'string' || !Number.isFinite(record.lastUsedAt)) {
            throw new Error('ARCHIVE_012');
          }
          records.push(record);
        }
        if (page.length < 100) break;
      }
      return records;
    } catch (error) {
      console.info(`Archive vault failure: ${(error as AssetFailure).code ?? -1}`);
      throw new Error('ARCHIVE_012');
    }
  }

  /** 只向密码库界面提供统计，不返回密码内容。 */
  async summaries(): Promise<ArchivePasswordSummary[]> {
    return (await this.load()).map((item: ArchivePassword) => ({ id: item.id, lastUsedAt: item.lastUsedAt,
      attempts: item.attempts, successes: item.successes } as ArchivePasswordSummary));
  }

  /** 保存真正提交给引擎的密码，包括没有成功的记录。 */
  async record(candidate: ArchivePassword, success: boolean, fingerprint: string, attempt: boolean): Promise<void> {
    if (candidate.value.length === 0) return;
    const now = Date.now();
    if (candidate.id.length === 0) { candidate.id = util.generateRandomUUID(); candidate.createdAt = now; }
    candidate.lastUsedAt = now;
    if (attempt) candidate.attempts++;
    if (success) { candidate.successes++; candidate.fingerprint = fingerprint; }
    const secret = new util.TextEncoder().encodeInto(JSON.stringify(candidate));
    if (secret.length > 64000) throw new Error('ARCHIVE_012');
    const query = this.query();
    query.set(asset.Tag.ALIAS, new util.TextEncoder().encodeInto(candidate.id));
    const changes: asset.AssetMap = new Map();
    changes.set(asset.Tag.SECRET, secret);
    try {
      try { await asset.update(query, changes); }
      catch (error) {
        if ((error as AssetFailure).code !== asset.ErrorCode.NOT_FOUND) throw new Error('ARCHIVE_012');
        query.set(asset.Tag.SECRET, secret);
        query.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_UNLOCKED);
        query.set(asset.Tag.SYNC_TYPE, asset.SyncType.NEVER);
        await asset.add(query);
      }
    } catch (error) {
      console.info(`Archive vault failure: ${(error as AssetFailure).code ?? -1}`);
      throw new Error('ARCHIVE_012');
    }
  }

  /** 删除当前账户中选中的业务记录。 */
  async remove(id: string): Promise<void> {
    // 删除接口不接受业务标签，先确认别名归属，再按唯一别名删除。
    const owned = (await this.load()).some((item: ArchivePassword) => item.id === id);
    if (!owned) return;
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, new util.TextEncoder().encodeInto(id));
    try { await asset.remove(query); }
    catch (error) {
      if ((error as AssetFailure).code !== asset.ErrorCode.NOT_FOUND) throw new Error('ARCHIVE_012');
    }
  }

  /** 仅清空当前账户的解压密码，不影响登录凭据。 */
  async clear(): Promise<void> {
    const records = await this.load();
    for (const record of records) await this.remove(record.id);
  }
}
