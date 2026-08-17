import common from '@ohos.app.ability.common';
import mdns from '@ohos.net.mdns';
import { http } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import cryptoFramework from '@ohos.security.cryptoFramework';
import { AuthSessionManager } from '../auth/AuthSessionManager';
import { AuthorizedApiClient } from '../../shared/network/AuthorizedApiClient';
import { ConnectivityResponseNormalizer } from './ConnectivityResponseNormalizer';
import { LanBootstrap, LanChallenge, LanEndpointResult } from './ConnectivityModels';
import { LanProofVerifier } from './LanProofVerifier';
import { TrustedLanEndpointPolicy } from './TrustedLanEndpointPolicy';

/**
 * 发现并验证局域网 MyTools 服务，失败时始终回退可信公网地址。
 */
export class EndpointRouter {
  private readonly publicBaseUrl: string;
  private readonly context: common.UIAbilityContext;
  private readonly authManager: AuthSessionManager;
  private readonly normalizer: ConnectivityResponseNormalizer = new ConnectivityResponseNormalizer();
  private readonly verifier: LanProofVerifier = new LanProofVerifier();
  private readonly endpointPolicy: TrustedLanEndpointPolicy = new TrustedLanEndpointPolicy();

  constructor(publicBaseUrl: string, context: common.UIAbilityContext, authManager: AuthSessionManager) {
    this.publicBaseUrl = authManager.normalizeBaseUrl(publicBaseUrl);
    this.context = context;
    this.authManager = authManager;
  }

  /** 搜索并验证一个IPv4直连地址，没有可信候选时返回公网地址。 */
  async resolve(): Promise<LanEndpointResult> {
    const envelope = await new AuthorizedApiClient(this.publicBaseUrl, this.authManager)
      .get('/api/app/v1/connectivity/bootstrap');
    const bootstrap = this.normalizer.normalizeBootstrap(envelope.data);
    const candidates = await this.discover(bootstrap.serviceType);
    for (const candidate of candidates) {
      if (!bootstrap.lanAddresses.includes(candidate.host?.address ?? '')) continue;
      const endpoint = this.endpoint(candidate);
      if (endpoint.length === 0) continue;
      try {
        if (await this.verifyCandidate(endpoint, bootstrap)) {
          return { baseUrl: endpoint, instanceId: bootstrap.instanceId };
        }
      } catch (_) {
      }
    }
    return { baseUrl: this.publicBaseUrl, instanceId: bootstrap.instanceId };
  }

  private async discover(serviceType: string): Promise<mdns.LocalServiceInfo[]> {
    const discovery = mdns.createDiscoveryService(this.context, serviceType.replace(/\.local$/, ''));
    const resolved: mdns.LocalServiceInfo[] = [];
    const pending: Promise<void>[] = [];
    const found = (service: mdns.LocalServiceInfo): void => {
      pending.push(mdns.resolveLocalService(this.context, service).then((value: mdns.LocalServiceInfo) => {
        resolved.push(value);
      }).catch(() => {}));
    };
    try {
      discovery.on('serviceFound', found);
      discovery.startSearchingMDNS();
      await new Promise<void>((resolve: () => void) => setTimeout(resolve, 1800));
      discovery.stopSearchingMDNS();
      await Promise.all(pending);
      return resolved.slice(0, 16);
    } finally {
      discovery.off('serviceFound', found);
      try {
        discovery.stopSearchingMDNS();
      } catch (_) {
      }
    }
  }

  private endpoint(service: mdns.LocalServiceInfo): string {
    const address = service.host?.address ?? '';
    const port = service.port ?? 0;
    if (!this.endpointPolicy.isPrivateIpv4(address) || port !== 23110) return '';
    return `http://${address}:${port}`;
  }

  private async verifyCandidate(endpoint: string, bootstrap: LanBootstrap): Promise<boolean> {
    const request = http.createHttp();
    try {
      const nonceBytes = cryptoFramework.createRandom().generateRandomSync(24).data;
      const nonce = new util.Base64Helper().encodeToStringSync(nonceBytes, util.Type.BASIC_URL_SAFE)
        .replace(/=+$/, '');
      const response = await request.request(`${endpoint}/api/public/connectivity/challenge`, {
        method: http.RequestMethod.POST,
        header: { 'Accept': 'application/json', 'Content-Type': 'application/json; charset=utf-8' },
        extraData: JSON.stringify({ probeId: bootstrap.probeId, nonce: nonce }),
        connectTimeout: 2500,
        readTimeout: 2500
      });
      if (response.responseCode < 200 || response.responseCode >= 300) return false;
      const parsed = JSON.parse(typeof response.result === 'string' ? response.result : `${response.result}`) as Object;
      if (parsed === null || Array.isArray(parsed) || typeof parsed !== 'object') return false;
      const envelope = parsed as Record<string, Object>;
      if (`${envelope['code'] ?? ''}` !== '0000') return false;
      const challenge: LanChallenge = this.normalizer.normalizeChallenge(envelope['data']);
      return this.verifier.verify(bootstrap, challenge, nonce);
    } catch (_) {
      return false;
    } finally {
      request.destroy();
    }
  }
}
