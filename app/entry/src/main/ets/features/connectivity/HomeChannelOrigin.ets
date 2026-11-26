/** 只信任本进程刚刚建立、持有随机能力路径的回环入口。 */
export class HomeChannelOrigin {
  private static origin: string = '';

  private static service: string = '';

  private static confirmed: boolean = false;

  static open(port: number, key: string, service: string): string {
    if (!Number.isSafeInteger(port) || port < 1 || port > 65535 || !/^[a-f0-9]{32}$/.test(key)) {
      throw new Error('Invalid channel endpoint');
    }
    HomeChannelOrigin.confirmed = false;
    HomeChannelOrigin.service = service;
    HomeChannelOrigin.origin = `http://127.0.0.1:${port}/${key}`;
    return HomeChannelOrigin.origin;
  }

  static activate(base: string): void { HomeChannelOrigin.confirmed = HomeChannelOrigin.owns(base); }
  static current(): string { return HomeChannelOrigin.origin; }
  static forService(service: string): string { return HomeChannelOrigin.confirmed && HomeChannelOrigin.service === service ? HomeChannelOrigin.origin : ''; }
  static close(): void { HomeChannelOrigin.confirmed = false; HomeChannelOrigin.origin = ''; HomeChannelOrigin.service = ''; }
  static owns(value: string): boolean { return value.length > 0 && value === HomeChannelOrigin.origin; }

  static playerUrl(value: string): boolean {
    const base = HomeChannelOrigin.origin;
    if (!base || !value.startsWith(base + '/api/app/v1/')) return false;
    return /^\/api\/app\/v1\/(?:(?:media|local-media)\/tickets|drive-tickets|audiobook-playback\/tickets|audiobook-voice-preview\/tickets)\/[a-f0-9]{32}(?:\/(?:master\.m3u8|mobile\.mp4))?$/.test(value.substring(base.length));
  }
}
