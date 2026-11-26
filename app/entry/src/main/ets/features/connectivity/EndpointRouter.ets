import { BusinessError } from '@ohos.base';
import { HomeChannelOrigin } from './HomeChannelOrigin';
import { StrictHomeHttpClient } from './StrictHomeHttpClient';
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
import { DirectEndpointStore } from './DirectEndpointStore';
import { SystemIpv6Resolver } from './SystemIpv6Resolver';
import { AuthApi } from '../auth/AuthApi';
import { DownloadCancellationToken } from '../../shared/network/DownloadCancellationToken';

/** 隔离单轮候选挑战的探测材料拒绝状态。 */
interface LanProbeAttempt {
  probeRejected: boolean;
}

/**
 * 发现并验证局域网和 IPv6 MyTools 服务，失败时回退可信公网地址。
 */
export class EndpointRouter {
  private static cachedBootstrap?: LanBootstrap;
  private static cachedPublicBaseUrl: string = '';
  private readonly publicBaseUrl: string;
  private readonly context: common.UIAbilityContext;
  private readonly authManager: AuthSessionManager;
  private readonly normalizer: ConnectivityResponseNormalizer = new ConnectivityResponseNormalizer();
  private readonly verifier: LanProofVerifier = new LanProofVerifier();
  private readonly endpointPolicy: TrustedLanEndpointPolicy = new TrustedLanEndpointPolicy();
  private readonly directStore: DirectEndpointStore = new DirectEndpointStore();

  constructor(publicBaseUrl: string, context: common.UIAbilityContext, authManager: AuthSessionManager) {
    this.publicBaseUrl = authManager.normalizeBaseUrl(publicBaseUrl);
    this.context = context;
    this.authManager = authManager;
  }

  /** 按局域网、IPv6 的优先级选择可信入口，无可用候选时返回公网地址。 */
  async resolve(): Promise<LanEndpointResult> {
    return this.resolveWithBootstrap(await this.bootstrap(), true);
  }

  private async resolveWithBootstrap(bootstrap: LanBootstrap, refreshRejectedProbe: boolean): Promise<LanEndpointResult> {
    const attempt: LanProbeAttempt = { probeRejected: false };
    // 公网签发的私网地址优先并行验证，避免等待 mDNS 才开始直连。
    const addresses = bootstrap.lanAddresses.slice(0, 16)
      .map((address: string): string => `http://${address}:${bootstrap.lanPort}`)
      .filter((address: string): boolean => !this.authManager.isBusinessEndpointCoolingDown(address));
    const origins = (bootstrap.ipv6DirectOrigins ?? []).filter((origin: string): boolean =>
      origin !== this.publicBaseUrl && !this.authManager.isBusinessEndpointCoolingDown(origin));
    if ((bootstrap.ipv6DirectOrigins ?? []).length > 0) {
      // 两类候选并行验证，最多等待短截止时间，避免逐级累积长超时。
      const lanTask = this.bounded(this.firstVerified(addresses, bootstrap, attempt), 1800, '');
      const ipv6Task = Promise.all(origins.map(async (origin: string): Promise<string> =>
        await this.verifyIpv6(origin, bootstrap, attempt) ? origin : ''));
      const lan = await lanTask;
      const verified = (await ipv6Task).filter((origin: string): boolean => origin.length > 0);
      // 诊断只记录候选数量与线路类型，避免泄露地址、身份材料或令牌。
      console.info(`Connectivity probe: lan=${lan.length > 0}, ipv6=${verified.length}/${origins.length}`);
      if (refreshRejectedProbe && attempt.probeRejected && lan.length === 0 && verified.length === 0) {
        return this.resolveWithBootstrap(await this.bootstrap(true), false);
      }
      return { baseUrl: lan || verified[0] || this.publicBaseUrl, instanceId: bootstrap.instanceId,
        verifiedIpv6Origins: verified, verifiedUntil: Math.min(bootstrap.expiresAt, Date.now() + 60000) };
    }
    const direct = await this.firstVerified(addresses, bootstrap, attempt);
    if (direct.length > 0) return { baseUrl: direct, instanceId: bootstrap.instanceId };
    const discovered = await this.discover(bootstrap.serviceType);
    // mDNS 只重试可信材料签发过的地址，不能用可转发的匿名证明扩展 JWT 的目标主机。
    const candidates = Array.from(new Set<string>(discovered
      .map((service: mdns.LocalServiceInfo): string => this.endpoint(service))
      .filter((endpoint: string): boolean => endpoint.length > 0 && addresses.includes(endpoint))));
    const candidate = await this.firstVerified(candidates, bootstrap, attempt);
    if (candidate.length > 0) return { baseUrl: candidate, instanceId: bootstrap.instanceId };
    // Gateway 重启可能丢失未过期探测记录，仅明确拒绝材料时强制刷新并完整重试一次。
    if (refreshRejectedProbe && attempt.probeRejected) {
      return this.resolveWithBootstrap(await this.bootstrap(true), false);
    }
    return { baseUrl: this.publicBaseUrl, instanceId: bootstrap.instanceId };
  }

  private async bootstrap(forceRefresh: boolean = false): Promise<LanBootstrap> {
    const cached = EndpointRouter.cachedBootstrap;
    // 有效期内复用可信材料，公网暂时断开时仍能在已知家庭网络重新发现。
    if (!forceRefresh && EndpointRouter.cachedPublicBaseUrl === this.publicBaseUrl && cached !== undefined &&
      cached.expiresAt > Date.now() + 5000) return cached;
    const activeBaseUrl = this.authManager.activeBusinessBaseUrl();
    // 首次材料仍由 HTTPS 签发，之后允许同会话已激活的可信入口续签。
    if (EndpointRouter.cachedPublicBaseUrl === this.publicBaseUrl && cached !== undefined &&
      this.authManager.publicBusinessBaseUrl() === this.publicBaseUrl &&
      activeBaseUrl.length > 0 && activeBaseUrl !== this.publicBaseUrl) {
      const useChannel = HomeChannelOrigin.forService(this.publicBaseUrl).length > 0;
      const renewalRevision = this.authManager.endpointRevision();
      const renewalSession = this.authManager.sessionRevision();
      let materialRejected = false;
      try {
        const envelope = await new AuthorizedApiClient(activeBaseUrl, this.authManager, false, 8000, !useChannel)
          .get('/api/app/v1/connectivity/bootstrap');
        let refreshed: LanBootstrap;
        try {
          refreshed = this.normalizer.normalizeBootstrap(envelope.data);
        } catch (_) {
          materialRejected = true;
          throw new Error('Invalid connectivity renewal material');
        }
        return this.remember(refreshed);
      } catch (_) {
        // 材料校验属于路由层，仅仍属于原会话和原传输的错误才使旧端点冷却。
        if (materialRejected && !useChannel && HomeChannelOrigin.forService(this.publicBaseUrl).length === 0 &&
          this.authManager.endpointRevision() === renewalRevision && this.authManager.sessionRevision() === renewalSession &&
          this.authManager.activeBusinessBaseUrl() === activeBaseUrl) {
          this.authManager.reportBusinessEndpointUnavailable(activeBaseUrl, renewalRevision);
        }
        // 网络层已处理自身故障，续签失败继续尝试固定的可信公网来源。
      }
    }
    // 冷启动只使用安全存储内的可信 HTTPS 来源，避免每次先等待公网隧道。
    const descriptor = await this.directStore.load(this.publicBaseUrl);
    if (descriptor !== undefined) {
      const origin = descriptor.origins.find((value: string): boolean =>
        value !== this.publicBaseUrl && !this.authManager.isBusinessEndpointCoolingDown(value));
      if (origin !== undefined) {
        const cancellation = new DownloadCancellationToken();
        let timer = -1;
        try {
          if (await this.bounded(this.hasIpv6(origin), 2100, false)) {
            // DNS 有独立预算，HTTP 截止仅从确认地址后开始，不能提前销毁刚创建的请求。
            timer = setTimeout(() => cancellation.cancel(), 4000);
            const envelope = await this.bounded(new AuthorizedApiClient(origin, this.authManager, false, 4000, true)
              .get('/api/app/v1/connectivity/bootstrap', cancellation), 4000, undefined);
            if (envelope !== undefined) {
              const refreshed = this.normalizer.normalizeBootstrap(envelope.data);
              if (refreshed.instanceId === descriptor.instanceId) return this.remember(refreshed);
            }
          }
        } catch (_) { /* 已知直连不可用后使用独立的公网域名。 */ }
        finally { if (timer >= 0) clearTimeout(timer); cancellation.cancel(); }
      }
    }
    const envelope = await new AuthorizedApiClient(this.publicBaseUrl, this.authManager, false, 8000, true)
      .get('/api/app/v1/connectivity/bootstrap');
    const bootstrap = this.normalizer.normalizeBootstrap(envelope.data);
    return this.remember(bootstrap);
  }

  private remember(bootstrap: LanBootstrap): LanBootstrap {
    EndpointRouter.cachedPublicBaseUrl = this.publicBaseUrl;
    EndpointRouter.cachedBootstrap = bootstrap;
    if (bootstrap.directConfigExpiresAt !== undefined) {
      this.directStore.save({ publicBaseUrl: this.publicBaseUrl, instanceId: bootstrap.instanceId,
        origins: bootstrap.ipv6DirectOrigins ?? [], expiresAt: bootstrap.directConfigExpiresAt }).catch(() => {});
    }
    return bootstrap;
  }

  private async hasIpv6(origin: string): Promise<boolean> {
    try {
      const addresses = await new SystemIpv6Resolver().resolve(origin);
      console.info('Connectivity IPv6 resolver: verified global addresses');
      return addresses.length > 0;
    } catch (_) {
      console.info('Connectivity IPv6 resolver: unavailable');
      return false;
    }
  }

  private async verifyIpv6(origin: string, bootstrap: LanBootstrap, attempt: LanProbeAttempt): Promise<boolean> {
    if (!await this.bounded(this.hasIpv6(origin), 2100, false)) {
      console.info('Connectivity IPv6 probe: DNS unavailable');
      return false;
    }
    const valid = await this.bounded(this.verifyCandidate(origin, bootstrap, attempt, 4500), 4500, false);
    console.info(`Connectivity IPv6 probe: verified=${valid}`);
    return valid;
  }

  private bounded<T>(work: Promise<T>, milliseconds: number, fallback: T): Promise<T> {
    return new Promise<T>((resolve: (value: T) => void) => {
      const timer = setTimeout(() => resolve(fallback), milliseconds);
      work.then((value: T) => { clearTimeout(timer); resolve(value); })
        .catch(() => { clearTimeout(timer); resolve(fallback); });
    });
  }

  private async firstVerified(candidates: string[], bootstrap: LanBootstrap, attempt: LanProbeAttempt): Promise<string> {
    if (candidates.length === 0) return '';
    // 任意候选验证成功立即返回，其余候选不携带令牌且有独立短超时。
    return new Promise<string>((resolve: (value: string) => void) => {
      let remaining = candidates.length;
      let completed = false;
      candidates.forEach((endpoint: string) => {
        this.verifyCandidate(endpoint, bootstrap, attempt).then((valid: boolean) => {
          remaining--;
          if (!completed && valid) {
            completed = true;
            resolve(endpoint);
          } else if (!completed && remaining === 0) {
            resolve('');
          }
        }).catch(() => {
          remaining--;
          if (!completed && remaining === 0) resolve('');
        });
      });
    });
  }

  private async discover(serviceType: string): Promise<mdns.LocalServiceInfo[]> {
    const discovery = mdns.createDiscoveryService(this.context, serviceType.replace(/\.local$/, ''));
    const resolved: mdns.LocalServiceInfo[] = [];
    const pending: Promise<void>[] = [];
    const found = (service: mdns.LocalServiceInfo): void => {
      if (pending.length >= 16) return;
      pending.push(new Promise<void>((resolve: () => void) => {
        let completed = false;
        const timer = setTimeout(() => { completed = true; resolve(); }, 2500);
        mdns.resolveLocalService(this.context, service).then((value: mdns.LocalServiceInfo) => {
          if (!completed) resolved.push(value);
        }).catch(() => {}).finally(() => { clearTimeout(timer); resolve(); });
      }));
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
    if (!this.endpointPolicy.isPrivateIpv4(address) || port !== 23201) return '';
    return `http://${address}:${port}`;
  }

  private async verifyCandidate(endpoint: string, bootstrap: LanBootstrap, attempt: LanProbeAttempt,
    deadline: number = 2500): Promise<boolean> {
    const useStrict = endpoint.startsWith('https://') && endpoint !== this.publicBaseUrl;
    const request = useStrict ? undefined : http.createHttp();
    const client = useStrict ? new StrictHomeHttpClient() : undefined;
    const timer = setTimeout(() => { request?.destroy(); client?.close(); }, deadline);
    try {
      const nonceBytes = cryptoFramework.createRandom().generateRandomSync(24).data;
      const nonce = new util.Base64Helper().encodeToStringSync(nonceBytes, util.Type.BASIC_URL_SAFE)
        .replace(/=+$/, '');
      const body = JSON.stringify({ probeId: bootstrap.probeId, nonce: nonce });
      let status = 0;
      let text = '';
      if (client !== undefined) {
        // 只发送公开探测材料；验证原有 HMAC 前不向候选入口提供会话凭证。
        const response = await client.challenge(endpoint, body, deadline);
        status = response.status;
        text = response.text;
      } else if (request !== undefined) {
        let headerRejected = false;
        request.on('headersReceive', (headers: Object) => {
          const values = headers as Record<string, Object>;
          if (Object.keys(values).some((key: string): boolean => key.toLowerCase() === 'location')) {
            headerRejected = true;
            request?.destroy();
          }
        });
        const response = await request.request(`${endpoint}/api/public/connectivity/challenge`, {
          method: http.RequestMethod.POST,
          addressFamily: AuthApi.directAddressFamily(endpoint, this.publicBaseUrl),
          header: { 'Accept': 'application/json', 'Content-Type': 'application/json; charset=utf-8' },
          extraData: body,
          connectTimeout: deadline,
          readTimeout: deadline
        });
        if (headerRejected) return false;
        status = response.responseCode;
        text = typeof response.result === 'string' ? response.result : JSON.stringify(response.result);
      }
      console.info(`Connectivity challenge response: status=${status}, bytes=${new util.TextEncoder().encodeInto(text).length}`);
      if (status === 401) {
        attempt.probeRejected = true;
        return false;
      }
      if (status < 200 || status >= 300 || text.length === 0 || text.length > 65536) return false;
      const parsed = JSON.parse(text) as Object;
      if (parsed === null || Array.isArray(parsed) || typeof parsed !== 'object') return false;
      const envelope = parsed as Record<string, Object>;
      const accepted = `${envelope['code'] ?? ''}` === '0000';
      console.info(`Connectivity challenge envelope: accepted=${accepted}`);
      if (!accepted) return false;
      const challenge: LanChallenge = this.normalizer.normalizeChallenge(envelope['data']);
      const valid = this.verifier.verify(bootstrap, challenge, nonce);
      console.info(`Connectivity challenge proof: valid=${valid}`);
      return valid;
    } catch (error) {
      const nativeCode = error === null || error === undefined ? 0 : (error as BusinessError).code;
      const kind = error instanceof Error ? error.name : 'NativeError';
      console.info(`Connectivity challenge failure: kind=${kind}, code=${Number.isFinite(nativeCode) ? nativeCode : 0}`);
      return false;
    } finally {
      clearTimeout(timer);
      request?.destroy();
      client?.close();
    }
  }
}
