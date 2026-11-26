import { HomeChannelOrigin } from '../connectivity/HomeChannelOrigin';
import { TrustedLanEndpointPolicy } from '../connectivity/TrustedLanEndpointPolicy';

/** 远程播放器保留HTTPS兼容，仅为可信局域网放行固定的只读票据路径。 */
export class RemoteMediaPlaybackUrlPolicy {
  private readonly lanPolicy: TrustedLanEndpointPolicy = new TrustedLanEndpointPolicy();

  /** 校验播放地址，禁止把任意HTTP资源交给系统播放器。 */
  url(value: string): string {
    // 已有HTTPS来源继续沿用原地址，局域网扩展不改变外部媒体行为。
    if (value.startsWith('https://') || HomeChannelOrigin.playerUrl(value)) return value;
    const suffix = /^(http:\/\/[^/?#]+\/api\/app\/v1\/media\/tickets\/[a-f0-9]{32})\/(master\.m3u8|mobile\.mp4)$/.exec(value);
    if (suffix && suffix[0] === value) return this.url(suffix[1]) + '/' + suffix[2];
    const matched = /^(http:\/\/[^/?#]+)(\/api\/app\/v1\/(?:(?:media|local-media)\/tickets|drive-tickets|audiobook-playback\/tickets|audiobook-voice-preview\/tickets)\/[a-f0-9]{32})$/.exec(value);
    // 完整匹配同时拒绝查询、片段、编码路径、重复分隔符和尾部换行。
    if (matched === null || matched[0] !== value) throw new Error('REMOTE_MEDIA_URL_INVALID');
    const origin = this.lanPolicy.normalize(matched[1]);
    // 地址不得依赖去空白或其他规范化修复，避免播放器采用不同的解析结果。
    if (origin !== matched[1]) throw new Error('REMOTE_MEDIA_URL_INVALID');
    return `${origin}${matched[2]}`;
  }
}
