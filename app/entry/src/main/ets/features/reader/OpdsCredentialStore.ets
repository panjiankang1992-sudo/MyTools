import { asset } from '@kit.AssetStoreKit';
import { util } from '@kit.ArkTS';
import { NormalizedOpdsCredential, OpdsCredentialPolicy } from './OpdsCredentialPolicy';

interface AssetFailure {
  code: number;
}

interface StoredOpdsCredential extends NormalizedOpdsCredential {}

/**
 * 在Asset Store中保存单个OPDS目录的HTTP Basic凭据，并严格限制为同源请求使用。
 */
export class OpdsCredentialStore {
  private static readonly GUEST_SCOPE: string = 'device:v2:guest';
  private readonly policy: OpdsCredentialPolicy = new OpdsCredentialPolicy();
  private accountScope: string = OpdsCredentialStore.GUEST_SCOPE;
  private scopeRevision: number = 0;

  /**
   * 切换凭据命名空间；不会复制或共享其他账户的秘密。
   */
  selectScope(scope: string): void {
    if (scope !== OpdsCredentialStore.GUEST_SCOPE && !/^account:v2:[a-f0-9]{64}$/.test(scope)) {
      throw new Error('OPDS凭据账户作用域无效');
    }
    if (this.accountScope !== scope) this.scopeRevision++;
    this.accountScope = scope;
  }

  /**
   * 保存凭据；用户名和密码不会写入Preferences或日志。
   */
  async save(catalogUrl: string, username: string, password: string): Promise<void> {
    const alias = this.currentAlias();
    const origin = this.policy.originOf(catalogUrl);
    const credential = this.policy.normalize(origin, username, password);
    if (credential === undefined) {
      throw new Error('OPDS账号或密码为空、过长或包含换行符');
    }
    const secret = new util.TextEncoder().encodeInto(JSON.stringify(credential));
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, alias);
    const updates: asset.AssetMap = new Map();
    updates.set(asset.Tag.SECRET, secret);
    try {
      await asset.update(query, updates);
    } catch (error) {
      const failure = error as AssetFailure;
      if (failure.code !== asset.ErrorCode.NOT_FOUND) throw new Error('OPDS凭据安全更新失败');
      const attributes: asset.AssetMap = new Map();
      attributes.set(asset.Tag.ALIAS, alias);
      attributes.set(asset.Tag.SECRET, secret);
      attributes.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_FIRST_UNLOCKED);
      try {
        await asset.add(attributes);
      } catch (_) {
        throw new Error('OPDS凭据安全写入失败');
      }
    }
  }

  /**
   * 返回仅适用于目标地址同源请求的Authorization头；没有匹配凭据时返回空值。
   */
  async authorizationFor(targetUrl: string): Promise<string | undefined> {
    const alias = this.currentAlias();
    const revision = this.scopeRevision;
    const targetOrigin = this.policy.originOf(targetUrl);
    const credential = await this.load(alias);
    if (revision !== this.scopeRevision) return undefined;
    if (credential === undefined || credential.origin !== targetOrigin) return undefined;
    const bytes = new util.TextEncoder().encodeInto(`${credential.username}:${credential.password}`);
    return `Basic ${this.policy.encodeBase64(bytes)}`;
  }

  /**
   * 判断指定目录是否已经配置同源凭据。
   */
  async hasCredential(catalogUrl: string): Promise<boolean> {
    const alias = this.currentAlias();
    const revision = this.scopeRevision;
    const credential = await this.load(alias);
    if (revision !== this.scopeRevision) return false;
    return credential !== undefined && credential.origin === this.policy.originOf(catalogUrl);
  }

  /**
   * 删除保存的OPDS凭据。
   */
  async clear(): Promise<void> {
    await this.removeIfPresent(this.currentAlias());
  }

  private currentAlias(): Uint8Array {
    if (this.accountScope === OpdsCredentialStore.GUEST_SCOPE) {
      // 旧凭据无法证明云账户归属，只在访客区继续使用原槽。
      return new util.TextEncoder().encodeInto('mytools.opds.basic.v1');
    }
    const suffix = this.accountScope.substring('account:v2:'.length);
    return new util.TextEncoder().encodeInto(`mytools.opds.basic.v2.${suffix}`);
  }

  private async load(alias: Uint8Array): Promise<StoredOpdsCredential | undefined> {
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, alias);
    query.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ALL);
    try {
      const results = await asset.query(query);
      if (results.length === 0) return undefined;
      const secret = results[0].get(asset.Tag.SECRET);
      if (!(secret instanceof Uint8Array) || secret.byteLength > 4 * 1024) return undefined;
      const text = util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(secret);
      const parsed = JSON.parse(text) as Object;
      return this.policy.restore(parsed);
    } catch (error) {
      const failure = error as AssetFailure;
      if (failure.code === asset.ErrorCode.NOT_FOUND) return undefined;
      throw new Error('OPDS凭据安全存储暂时不可用');
    }
  }

  private async removeIfPresent(alias: Uint8Array): Promise<void> {
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, alias);
    try {
      await asset.remove(query);
    } catch (error) {
      const failure = error as AssetFailure;
      if (failure.code !== asset.ErrorCode.NOT_FOUND) throw new Error('OPDS凭据删除失败');
    }
  }

}
