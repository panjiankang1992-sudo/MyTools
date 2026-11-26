import { HomeChannelOrigin } from '../../features/connectivity/HomeChannelOrigin';

interface TransferSample {
  latency: number;
  perByte: number;
  count: number;
  updated: number;
}

/** 当前网络内按已验证线路的实际传输估算媒体完成时间。 */
export class MediaTransferRoutes {
  private samples: Map<string, TransferSample> = new Map<string, TransferSample>();
  private readonly preferred: Map<number, string> = new Map<number, string>();
  private readonly cooldown: Map<string, number> = new Map<string, number>();
  private videoDirect: string = '';
  private videoPublic: string = '';
  private videoChoice: string = '';
  private videoUntil: number = 0;
  private lastTrial: number = 0;
  private requests: number = 0;
  epoch: number = 0;

  /** 网络或登录归属变化后忽略全部旧样本及尚未完成的请求。 */
  clear(): void {
    this.epoch++;
    this.videoUntil = 0;
    this.samples.clear();
    this.preferred.clear();
    this.cooldown.clear();
    this.lastTrial = 0;
    this.requests = 0;
  }

  /** 只记录通过完整性检查的非空网络响应。 */
  record(epoch: number, origin: string, bytes: number, first: number, elapsed: number): void {
    if (epoch !== this.epoch || bytes < 4096 || elapsed <= 0 || first < 0 || first > elapsed) return;
    const now = Date.now();
    const old = this.sample(origin, now);
    // 单块响应无法估算持续传输，只能把完成时间作为保守的字节成本。
    const rate = Math.max(elapsed - first, elapsed * 0.15, 1) / bytes;
    this.samples.set(origin, { latency: old ? old.latency * 0.5 + first * 0.5 : first,
      perByte: old ? old.perByte * 0.5 + rate * 0.5 : rate, count: (old?.count ?? 0) + 1, updated: now });
  }

  /** 传输失败只影响媒体首选，写请求和账户路由继续独立处理。 */
  failed(epoch: number, origin: string): void {
    if (epoch === this.epoch) this.cooldown.set(origin, Date.now() + 30000);
  }

  /** 媒体读取只在活动直连与账户公网之间分流；真实请求承担有限的探样。 */
  choose(active: string, publicUrl: string, bytes: number, trial: boolean): string {
    if (!active.startsWith('https://') || active === publicUrl || !publicUrl.startsWith('https://')) return active;
    const now = Date.now();
    const directCooling = (this.cooldown.get(active) ?? 0) > now;
    const publicCooling = (this.cooldown.get(publicUrl) ?? 0) > now;
    if (directCooling !== publicCooling) return directCooling ? publicUrl : active;
    const direct = this.sample(active, now);
    const proxy = this.sample(publicUrl, now);
    if (direct && proxy && direct.count >= 2 && proxy.count >= 2) {
      const a = direct.latency + direct.perByte * bytes;
      const b = proxy.latency + proxy.perByte * bytes;
      if (b < a * 0.75) this.preferred.set(bytes, publicUrl);
      else if (a < b * 0.75) this.preferred.set(bytes, active);
    } else this.preferred.delete(bytes);
    const best = this.preferred.get(bytes) === publicUrl ? publicUrl : active;
    if (trial && ++this.requests >= 3 && now - this.lastTrial >= (proxy && proxy.count >= 2 ? 90000 : 1200)) {
      this.lastTrial = now;
      this.requests = 0;
      return best === active ? publicUrl : active;
    }
    return best;
  }

  /** 外网大视频用真实票据选路，同一网络和入口组合五分钟内复用结果。 */
  async chooseVideo(active: string, publicUrl: string, measure: (origin: string) => Promise<number>,
    current: () => boolean): Promise<string> {
    const channel = HomeChannelOrigin.forService(publicUrl) === active && HomeChannelOrigin.owns(active);
    if ((!active.startsWith('https://') && !channel) || !publicUrl.startsWith('https://') || active === publicUrl) return active;
    if (this.videoDirect === active && this.videoPublic === publicUrl && Date.now() < this.videoUntil &&
      (this.cooldown.get(this.videoChoice) ?? 0) <= Date.now()) return this.videoChoice;
    const epoch = this.epoch;
    // 单条探测的系统异常按未完成处理，另一条成功结果仍可用于起播。
    const times = await Promise.all([measure(active).catch((): number => Infinity),
      measure(publicUrl).catch((): number => Infinity)]);
    if (epoch !== this.epoch || !current()) return active;
    const measured = Number.isFinite(times[0]) || Number.isFinite(times[1]);
    const choice = measured ? (times[1] < times[0] * 0.75 ? publicUrl : active) :
      this.choose(active, publicUrl, 1024 * 1024, false);
    // 两路均无有效样本时短暂沿用已有估计，让下一部视频有机会重新取样。
    this.preferVideoForNetwork(active, publicUrl, choice, measured ? 300000 : 15000);
    console.info(`Media startup route: ${choice === publicUrl ? 'PUBLIC' : channel ? 'CHANNEL' : 'IPv6'}, measured=${measured}`);
    return choice;
  }

  /** 记录当前受信任入口组合的短期选择，供该网络后续视频复用。 */
  preferVideoForNetwork(active: string, publicUrl: string, choice: string, ttl: number): void {
    if (choice !== active && choice !== publicUrl) return;
    this.videoDirect = active;
    this.videoPublic = publicUrl;
    this.videoChoice = choice;
    this.videoUntil = Date.now() + Math.min(300000, Math.max(0, ttl));
  }

  private sample(origin: string, now: number): TransferSample | undefined {
    const value = this.samples.get(origin);
    return value && now - value.updated < 300000 ? value : undefined;
  }
}
