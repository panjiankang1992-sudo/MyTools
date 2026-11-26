/** 桌面快捷方式仅表示短期导航意图，不授予车辆操作权限。 */
export interface VehicleShortcutRequest {
  id: string;
  action: string;
  issuedAt: number;
}

/** 校验图标长按入口和内存导航请求，拒绝未知动作、过期及畸形参数。 */
export class VehicleShortcutPolicy {
  static readonly STORAGE_KEY: string = 'incomingVehicleShortcut';
  static readonly ACTION_PARAMETER: string = 'vehicleShortcutAction';
  static readonly MAX_AGE_MS: number = 5 * 60 * 1000;
  private static readonly PREFIX: string = 'v1.unlock.';
  private static readonly NONCE_PATTERN: RegExp = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

  /** 为合法解锁导航生成唯一短期请求，不接收任何车辆、密钥或指令参数。 */
  static create(action: Object | undefined, issuedAt: number, nonce: string): string {
    if (typeof action !== 'string' || action !== 'unlock' || !Number.isSafeInteger(issuedAt) || issuedAt <= 0 ||
      typeof nonce !== 'string' || !VehicleShortcutPolicy.NONCE_PATTERN.test(nonce)) return '';
    return `${VehicleShortcutPolicy.PREFIX}${issuedAt}.${nonce}`;
  }

  /** 解析仍有效的本机导航请求；未来时间和超过五分钟的请求均作废。 */
  static parse(request: string, now: number): VehicleShortcutRequest | undefined {
    if (typeof request !== 'string' || request.length > 96 || !Number.isSafeInteger(now) || now <= 0) return undefined;
    const match = /^v1\.unlock\.([1-9][0-9]{0,15})\.([0-9a-f-]{36})$/.exec(request);
    if (match === null || !VehicleShortcutPolicy.NONCE_PATTERN.test(match[2])) return undefined;
    const issuedAt = Number(match[1]);
    // 本请求只能导航；仍限制有效期，避免登录取消后很久再次误开解锁流程。
    if (!Number.isSafeInteger(issuedAt) || issuedAt > now || now - issuedAt > VehicleShortcutPolicy.MAX_AGE_MS) return undefined;
    return { id: request, action: 'unlock', issuedAt };
  }

  /** 判断请求是否仍可用于界面导航，调用方消费后应清除内存中的原值。 */
  static isCurrent(request: string, now: number): boolean {
    return VehicleShortcutPolicy.parse(request, now) !== undefined;
  }
}
