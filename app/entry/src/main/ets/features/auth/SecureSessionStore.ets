import { asset } from '@kit.AssetStoreKit';
import { util } from '@kit.ArkTS';
import { AuthSession } from './AuthModels';
import { AuthResponseNormalizer } from './AuthResponseNormalizer';

interface AssetFailure {
  code: number;
}

interface BoundAuthSession {
  baseUrl: string;
  session: AuthSession;
}

/**
 * 使用 Asset Store Kit 保存登录令牌和最小账户信息。
 */
export class SecureSessionStore {
  private readonly alias: Uint8Array = new util.TextEncoder().encodeInto('mytools.auth.session.v2');
  private readonly legacyAlias: Uint8Array = new util.TextEncoder().encodeInto('mytools.auth.session.v1');
  private readonly normalizer: AuthResponseNormalizer = new AuthResponseNormalizer();

  /**
   * 保存会话，覆盖同一应用内的旧会话。
   */
  async save(baseUrl: string, session: AuthSession): Promise<void> {
    await this.removeIfPresent(this.legacyAlias);
    if (baseUrl.length < 9 || baseUrl.length > 2048 || !baseUrl.startsWith('https://') ||
      /[\u0000-\u001F\u007F]/.test(baseUrl)) throw new Error('安全会话服务地址无效');
    const payload: BoundAuthSession = { baseUrl, session: this.normalizer.normalizeStored(session, Date.now(), false) };
    const secret = new util.TextEncoder().encodeInto(JSON.stringify(payload));
    if (secret.length > 64 * 1024) throw new Error('安全会话超过64 KB限制');
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, this.alias);
    const updates: asset.AssetMap = new Map();
    updates.set(asset.Tag.SECRET, secret);
    try {
      await asset.update(query, updates);
    } catch (error) {
      const failure = error as AssetFailure;
      if (failure.code !== asset.ErrorCode.NOT_FOUND) throw new Error('安全会话更新失败');
      const attributes: asset.AssetMap = new Map();
      attributes.set(asset.Tag.ALIAS, this.alias);
      attributes.set(asset.Tag.SECRET, secret);
      attributes.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_FIRST_UNLOCKED);
      try {
        await asset.add(attributes);
      } catch (_) {
        throw new Error('安全会话写入失败');
      }
    }
  }

  /**
   * 读取当前会话；未登录或数据损坏时返回空值。
   */
  async load(expectedBaseUrl: string): Promise<AuthSession | undefined> {
    try {
      await this.removeIfPresent(this.legacyAlias);
    } catch (_) {
      return undefined;
    }
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, this.alias);
    query.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ALL);
    try {
      const results = await asset.query(query);
      if (results.length === 0) {
        return undefined;
      }
      const secret = results[0].get(asset.Tag.SECRET);
      if (!(secret instanceof Uint8Array)) {
        return undefined;
      }
      if (secret.length === 0 || secret.length > 64 * 1024) return undefined;
      const payload = new util.TextDecoder().decodeToString(secret);
      if (payload.length === 0 || payload.length > 64 * 1024) return undefined;
      const stored = JSON.parse(payload) as BoundAuthSession;
      if (stored.baseUrl !== expectedBaseUrl || stored.session === undefined) return undefined;
      return this.normalizer.normalizeStored(stored.session);
    } catch (error) {
      console.info('Secure session is not available');
      return undefined;
    }
  }

  /**
   * 删除本机保存的登录会话。
   */
  async clear(): Promise<void> {
    await this.removeIfPresent(this.alias);
    await this.removeIfPresent(this.legacyAlias);
  }

  private async removeIfPresent(alias: Uint8Array): Promise<void> {
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, alias);
    try {
      await asset.remove(query);
    } catch (error) {
      const failure = error as AssetFailure;
      if (failure.code === asset.ErrorCode.NOT_FOUND) return;
      throw new Error('安全凭证删除失败');
    }
  }
}
