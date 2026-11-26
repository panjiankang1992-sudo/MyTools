import Want from '@ohos.app.ability.Want';
import UIAbility from '@ohos.app.ability.UIAbility';
import { Configuration } from '@ohos.app.ability.Configuration';
import ConfigurationConstant from '@ohos.app.ability.ConfigurationConstant';
import window from '@ohos.window';
import { AppFonts } from '../theme/AppFonts';
import { util } from '@kit.ArkTS';
import { VehicleShortcutPolicy } from '../features/tools/vehicle/VehicleShortcutPolicy';

/** 应用入口与主窗口生命周期管理。 */
export default class EntryAbility extends UIAbility {
  /** 接收系统文件入口或桌面车辆快捷方式，不在入口中执行车辆操作。 */
  onCreate(want: Want): void { this.receiveArchive(want); this.receiveVehicleShortcut(want); }

  /** 已运行时处理文件打开和快捷方式的独立导航请求。 */
  onNewWant(want: Want): void { this.receiveArchive(want); this.receiveVehicleShortcut(want); }

  private receiveVehicleShortcut(want: Want): void {
    // 文件入口优先；公开 Want 只读取动作白名单，绝不接受车辆身份或控制参数。
    if ((want.uri ?? '').length > 0) return;
    const action = want.parameters?.[VehicleShortcutPolicy.ACTION_PARAMETER];
    if (typeof action !== 'string' || action !== 'unlock') return;
    try {
      const request = VehicleShortcutPolicy.create(action, Date.now(), util.generateRandomUUID());
      if (request.length > 0) {
        // 最新有效快捷方式取代尚未消费的旧文件导航，但不改变文件本身或归档任务。
        AppStorage.setOrCreate('incomingArchiveUri', '');
        AppStorage.setOrCreate(VehicleShortcutPolicy.STORAGE_KEY, request);
      }
    } catch (_) {
      // 快捷方式材料生成失败不影响普通启动，也不回退为未经校验的车辆动作。
    }
  }

  private receiveArchive(want: Want): void {
    const uri = want.uri ?? '';
    // 只接收系统授予访问的本地 URI，不从外部指令下载链接或自动解压。
    if (uri.startsWith('file://') && uri.length <= 4096 && /\.(zip|7z|rar|tar|gz|tgz)$/i.test(uri)) {
      // 新文件入口优先于尚未完成登录的旧车辆导航意图。
      AppStorage.setOrCreate(VehicleShortcutPolicy.STORAGE_KEY, '');
      AppStorage.setOrCreate('incomingArchiveUri', uri);
    }
  }

  /**
   * 创建主窗口并加载应用入口页。
   */
  onWindowStageCreate(windowStage: window.WindowStage): void {
    // 在页面加载前发布当前系统字号倍率，保证首屏排版和分页一致。
    AppStorage.setOrCreate('systemFontScale', this.context.config.fontSizeScale ?? 1);
    AppStorage.setOrCreate('systemColorMode', this.context.config.colorMode ?? ConfigurationConstant.ColorMode.COLOR_MODE_LIGHT);
    windowStage.loadContent('pages/Index', (error) => {
      if (error.code) {
        console.error('Failed to load MyTools entry page');
        return;
      }
      // 页面已创建时补充窗口级注册，主入口也会在首build前幂等注册。
      AppFonts.register(windowStage.getMainWindowSync().getUIContext());
    });
  }

  /** 发布前台状态，允许当前工具页读取用户近期复制的链接。 */
  onForeground(): void {
    AppStorage.setOrCreate('appForeground', true);
  }

  /** 进入后台立即暂停工具页的剪贴板读取。 */
  onBackground(): void {
    AppStorage.setOrCreate('appForeground', false);
  }

  /** 系统显示配置变化时把字号倍率同步给阅读页。 */
  onConfigurationUpdate(newConfig: Configuration): void {
    AppStorage.setOrCreate('systemFontScale', newConfig.fontSizeScale ?? 1);
    AppStorage.setOrCreate('systemColorMode', newConfig.colorMode ?? ConfigurationConstant.ColorMode.COLOR_MODE_LIGHT);
  }
}
