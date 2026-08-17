import { asset } from '@kit.AssetStoreKit';
import { util } from '@kit.ArkTS';
import { BookSourceCredentialHeader, BookSourceCredentialPolicy,
  NormalizedBookSourceCredential } from './BookSourceCredentialPolicy';
import { SourceCookieSessionPolicy } from './SourceCookieSessionPolicy';

interface AssetFailure {
  code: number;
}

interface StoredBookSourceCredential extends NormalizedBookSourceCredential {
}

/**
 * 使用Asset Store按书源保存敏感请求头，不进入书源JSON或Preferences。
 */
export class BookSourceCredentialStore {
  private static readonly MAX_RECORDS: number = 64;
  private static readonly GUEST_SCOPE: string = 'device:v2:guest';
  private readonly policy: BookSourceCredentialPolicy = new BookSourceCredentialPolicy();
  private readonly cookiePolicy: SourceCookieSessionPolicy = new SourceCookieSessionPolicy();
  private readonly sessionCookies: Map<string, string> = new Map<string, string>();
  private accountScope: string = BookSourceCredentialStore.GUEST_SCOPE;
  private scopeRevision: number = 0;

  /**
   * 切换凭据命名空间；账户作用域只接受应用生成的SHA-256标识。
   */
  selectScope(scope: string): void {
    if (scope !== BookSourceCredentialStore.GUEST_SCOPE && !/^account:v2:[a-f0-9]{64}$/.test(scope)) {
      throw new Error('书源凭据账户作用域无效');
    }
    if (this.accountScope !== scope) {
      this.sessionCookies.clear();
      this.scopeRevision++;
    }
    this.accountScope = scope;
  }

  /** 返回用于异步请求归属校验的当前凭据作用域版本。 */
  currentScopeRevision(): number {
    return this.scopeRevision;
  }

  /** 判断异步请求捕获的凭据作用域是否仍为当前作用域。 */
  scopeRevisionCurrent(revision: number): boolean {
    return Number.isSafeInteger(revision) && revision === this.scopeRevision;
  }

  /**
   * 保存或替换一个书源的敏感请求头。
   */
  async save(sourceUrl: string, headerName: string, value: string): Promise<void> {
    const alias = this.currentAlias();
    const normalizedSourceUrl = sourceUrl.trim();
    const record: StoredBookSourceCredential = {
      sourceUrl: normalizedSourceUrl,
      origin: this.policy.originOf(normalizedSourceUrl),
      headerName: this.policy.normalizeHeaderName(headerName),
      value: this.policy.normalizeValue(value)
    };
    const records = (await this.load(alias)).filter((item: StoredBookSourceCredential) =>
      item.sourceUrl !== normalizedSourceUrl);
    records.push(record);
    await this.replace(alias, records.slice(-BookSourceCredentialStore.MAX_RECORDS));
  }

  /**
   * 返回只适用于该书源且与目标地址同源的敏感请求头。
   */
  async headersFor(sourceUrl: string, targetUrl: string): Promise<Record<string, string>> {
    const alias = this.currentAlias();
    const revision = this.scopeRevision;
    let targetOrigin = '';
    try {
      targetOrigin = this.policy.originOf(targetUrl);
    } catch (_) {
      return {};
    }
    const record = (await this.load(alias)).find((item: StoredBookSourceCredential) =>
      item.sourceUrl === sourceUrl.trim());
    if (!this.scopeRevisionCurrent(revision)) return {};
    const headers: Record<string, string> = {};
    const sessionCookie = this.sessionCookies.get(`${sourceUrl.trim()}|${targetOrigin}`);
    if (sessionCookie !== undefined) headers['Cookie'] = sessionCookie;
    if (record !== undefined && record.origin === targetOrigin) {
      headers[record.headerName] = record.value;
    }
    return headers;
  }

  /** 保存同一书源同一HTTPS origin返回的短期会话Cookie；手工Cookie仍拥有更高优先级。 */
  captureSessionCookies(sourceUrl: string, targetUrl: string, value: string, revision: number): void {
    if (!this.scopeRevisionCurrent(revision)) return;
    let targetOrigin = '';
    try {
      targetOrigin = this.policy.originOf(targetUrl);
    } catch (_) {
      return;
    }
    const key = `${sourceUrl.trim()}|${targetOrigin}`;
    if (value.length === 0) return;
    const normalized = this.cookiePolicy.merge(this.sessionCookies.get(key) ?? '', value);
    if (normalized.length === 0) {
      this.sessionCookies.delete(key);
      return;
    }
    if (this.sessionCookies.size >= 64 && !this.sessionCookies.has(key)) this.sessionCookies.clear();
    this.sessionCookies.set(key, normalized);
  }

  /**
   * 判断书源是否已配置安全凭据。
   */
  async hasCredential(sourceUrl: string): Promise<boolean> {
    const alias = this.currentAlias();
    return (await this.load(alias)).some((item: StoredBookSourceCredential) => item.sourceUrl === sourceUrl.trim());
  }

  /**
   * 删除指定书源的敏感凭据。
   */
  async clear(sourceUrl: string): Promise<void> {
    const alias = this.currentAlias();
    const normalizedSourceUrl = sourceUrl.trim();
    Array.from(this.sessionCookies.keys()).forEach((key: string) => {
      if (key.startsWith(`${normalizedSourceUrl}|`)) this.sessionCookies.delete(key);
    });
    const records = (await this.load(alias)).filter((item: StoredBookSourceCredential) =>
      item.sourceUrl !== normalizedSourceUrl);
    await this.replace(alias, records);
  }

  private currentAlias(): Uint8Array {
    if (this.accountScope === BookSourceCredentialStore.GUEST_SCOPE) {
      // 旧凭据无法证明云账户归属，只在访客区继续使用原槽。
      return new util.TextEncoder().encodeInto('mytools.book-source.credentials.v1');
    }
    const suffix = this.accountScope.substring('account:v2:'.length);
    return new util.TextEncoder().encodeInto(`mytools.book-source.credentials.v2.${suffix}`);
  }

  private async load(alias: Uint8Array): Promise<StoredBookSourceCredential[]> {
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, alias);
    query.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ALL);
    try {
      const results = await asset.query(query);
      if (results.length === 0) return [];
      const secret = results[0].get(asset.Tag.SECRET);
      if (!(secret instanceof Uint8Array) || secret.byteLength > 64 * 1024) return [];
      const text = util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(secret);
      const parsed = JSON.parse(text) as Object;
      return this.policy.restore(parsed);
    } catch (error) {
      const failure = error as AssetFailure;
      if (failure.code === asset.ErrorCode.NOT_FOUND) return [];
      throw new Error('书源凭据安全存储暂时不可用');
    }
  }

  private async replace(alias: Uint8Array, records: StoredBookSourceCredential[]): Promise<void> {
    if (records.length === 0) {
      await this.removeIfPresent(alias);
      return;
    }
    const secret = new util.TextEncoder().encodeInto(JSON.stringify(records));
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, alias);
    const updates: asset.AssetMap = new Map();
    updates.set(asset.Tag.SECRET, secret);
    try {
      await asset.update(query, updates);
    } catch (error) {
      const failure = error as AssetFailure;
      if (failure.code !== asset.ErrorCode.NOT_FOUND) throw new Error('书源凭据安全更新失败');
      const attributes: asset.AssetMap = new Map();
      attributes.set(asset.Tag.ALIAS, alias);
      attributes.set(asset.Tag.SECRET, secret);
      attributes.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_FIRST_UNLOCKED);
      try {
        await asset.add(attributes);
      } catch (_) {
        throw new Error('书源凭据安全写入失败');
      }
    }
  }

  private async removeIfPresent(alias: Uint8Array): Promise<void> {
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, alias);
    try {
      await asset.remove(query);
    } catch (error) {
      const failure = error as AssetFailure;
      if (failure.code !== asset.ErrorCode.NOT_FOUND) throw new Error('书源凭据删除失败');
    }
  }
}
