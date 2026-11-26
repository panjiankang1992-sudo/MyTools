import { VehicleShortcutPolicy } from './VehicleShortcutPolicy';

/** 桌面快捷入口的登录门禁仅决定页面导航，不生成车辆操作。 */
export class VehicleShortcutRoutePolicy {
  /** 按请求有效期、会话恢复和登录状态决定下一步，保持冷启动与热启动一致。 */
  static resolve(request: string, now: number, sessionReady: boolean, authenticated: boolean): string {
    if (!VehicleShortcutPolicy.isCurrent(request, now)) return 'IGNORE';
    if (!sessionReady) return 'WAIT';
    return authenticated ? 'OPEN' : 'LOGIN';
  }
}
