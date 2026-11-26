import { asset } from '@kit.AssetStoreKit';
import { util } from '@kit.ArkTS';
import { Ipv6EndpointPolicy } from './Ipv6EndpointPolicy';

/** 可信入口描述与十分钟挑战材料分开保存。 */
export interface DirectEndpointDescriptor {
  publicBaseUrl: string;
  instanceId: string;
  origins: string[];
  expiresAt: number;
}

/** 使用系统安全存储保护受信任直连来源，绝不保存挑战密钥。 */
export class DirectEndpointStore {
  private readonly alias: Uint8Array = new util.TextEncoder().encodeInto('mytools.direct.endpoints.v1');

  /** 读取仍有效且属于当前公网服务的入口描述。 */
  async load(publicBaseUrl: string): Promise<DirectEndpointDescriptor | undefined> {
    try {
      const query: asset.AssetMap = new Map();
      query.set(asset.Tag.ALIAS, this.alias);
      query.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ALL);
      const rows = await asset.query(query);
      if (rows.length !== 1) return undefined;
      const secret = rows[0].get(asset.Tag.SECRET);
      if (!(secret instanceof Uint8Array) || secret.length > 4096) return undefined;
      const value = JSON.parse(new util.TextDecoder().decodeToString(secret)) as DirectEndpointDescriptor;
      if (value.publicBaseUrl !== publicBaseUrl || !/^[A-Za-z0-9._-]{1,64}$/.test(value.instanceId) ||
        !Number.isSafeInteger(value.expiresAt) || value.expiresAt <= Date.now() ||
        value.expiresAt > Date.now() + 7 * 86400000 || !Array.isArray(value.origins) || value.origins.length > 4) {
        return undefined;
      }
      const policy = new Ipv6EndpointPolicy();
      value.origins = value.origins.map((origin: string): string => policy.normalizeOrigin(origin));
      return value;
    } catch (_) {
      return undefined;
    }
  }

  /** 保存由可信连接签发的来源描述，存储失败不阻断当前网络访问。 */
  async save(value: DirectEndpointDescriptor): Promise<void> {
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, this.alias);
    const update: asset.AssetMap = new Map();
    update.set(asset.Tag.SECRET, new util.TextEncoder().encodeInto(JSON.stringify(value)));
    try {
      await asset.update(query, update);
    } catch (_) {
      try {
        update.set(asset.Tag.ALIAS, this.alias);
        update.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_FIRST_UNLOCKED);
        await asset.add(update);
      } catch (_) { /* 只影响下一次冷启动的直连优化。 */ }
    }
  }
}
