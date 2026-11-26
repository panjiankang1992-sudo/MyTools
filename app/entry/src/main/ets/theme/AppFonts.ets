import { UIContext } from '@ohos.arkui.UIContext';
import { AppTheme } from './AppTheme';

/** 统一注册随包分发的完整中文字形和真实字重。 */
export class AppFonts {
  private static registered: boolean = false;

  /** 在首屏构建前注册字体，重复调用保持幂等。 */
  static register(uiContext: UIContext): void {
    // 字体属于本地资源，注册不能依赖网络或用户主题字体。
    if (AppFonts.registered) {
      return;
    }
    const manager = uiContext.getFont();
    manager.registerFont({ familyName: AppTheme.fontFamily,
      familySrc: $rawfile('fonts/NotoSansSC-Regular.ttf') });
    manager.registerFont({ familyName: AppTheme.fontMedium,
      familySrc: $rawfile('fonts/NotoSansSC-Medium.ttf') });
    manager.registerFont({ familyName: AppTheme.fontSemibold,
      familySrc: $rawfile('fonts/NotoSansSC-Semibold.ttf') });
    AppFonts.registered = true;
  }
}
