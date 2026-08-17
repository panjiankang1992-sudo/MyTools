import { LanBootstrap, LanChallenge } from './ConnectivityModels';
import { TrustedLanEndpointPolicy } from './TrustedLanEndpointPolicy';

/**
 * 严格校验局域网连接协商响应。
 */
export class ConnectivityResponseNormalizer {
  private readonly endpointPolicy: TrustedLanEndpointPolicy = new TrustedLanEndpointPolicy();
  /** 校验可信公网接口签发的局域网探测材料。 */
  normalizeBootstrap(value: Object | undefined): LanBootstrap {
    const data = this.record(value);
    const rawAddresses = data['lanAddresses'];
    if (!Array.isArray(rawAddresses) || rawAddresses.length > 64) throw new Error('局域网地址列表无效');
    const addresses: string[] = rawAddresses.map((value: Object): string => `${value}`)
      .filter((value: string) => this.endpointPolicy.isPrivateIpv4(value));
    if (addresses.length !== rawAddresses.length) throw new Error('局域网地址列表无效');
    const result: LanBootstrap = {
      instanceId: this.identifier(data, 'instanceId'),
      serviceType: this.text(data, 'serviceType', 64),
      apiVersion: this.identifier(data, 'apiVersion'),
      probeId: this.pattern(data, 'probeId', /^[0-9a-f]{32}$/),
      proofKey: this.pattern(data, 'proofKey', /^[A-Za-z0-9_-]{43}$/),
      expiresAt: this.positiveInteger(data, 'expiresAt'),
      lanAddresses: addresses
    };
    if (result.serviceType !== '_mytools._tcp.local') throw new Error('局域网服务类型无效');
    if (result.expiresAt <= Date.now()) throw new Error('局域网探测材料已过期');
    return result;
  }

  /** 校验局域网候选服务返回的挑战响应。 */
  normalizeChallenge(value: Object | undefined): LanChallenge {
    const data = this.record(value);
    return {
      instanceId: this.identifier(data, 'instanceId'),
      apiVersion: this.identifier(data, 'apiVersion'),
      nonce: this.pattern(data, 'nonce', /^[A-Za-z0-9_-]{16,96}$/),
      expiresAt: this.positiveInteger(data, 'expiresAt'),
      proof: this.pattern(data, 'proof', /^[A-Za-z0-9_-]{43}$/)
    };
  }

  private record(value: Object | undefined): Record<string, Object> {
    if (value === undefined || value === null || Array.isArray(value) || typeof value !== 'object') {
      throw new Error('局域网服务响应格式无效');
    }
    return value as Record<string, Object>;
  }

  private identifier(data: Record<string, Object>, key: string): string {
    return this.pattern(data, key, /^[A-Za-z0-9._-]{1,64}$/);
  }

  private pattern(data: Record<string, Object>, key: string, pattern: RegExp): string {
    const value = data[key];
    if (typeof value !== 'string' || !pattern.test(value)) throw new Error(`局域网服务字段${key}无效`);
    return value;
  }

  private text(data: Record<string, Object>, key: string, maxLength: number): string {
    const value = data[key];
    if (typeof value !== 'string' || value.length === 0 || value.length > maxLength ||
      /[\u0000-\u001F\u007F]/.test(value)) throw new Error(`局域网服务字段${key}无效`);
    return value;
  }

  private positiveInteger(data: Record<string, Object>, key: string): number {
    const value = Number(data[key]);
    if (!Number.isSafeInteger(value) || value <= 0) throw new Error(`局域网服务字段${key}无效`);
    return value;
  }

}
