import UIAbility from '@ohos.app.ability.UIAbility';
import { Configuration } from '@ohos.app.ability.Configuration';
import window from '@ohos.window';

export default class EntryAbility extends UIAbility {
  /**
   * 创建主窗口并加载应用入口页。
   */
  onWindowStageCreate(windowStage: window.WindowStage): void {
    // 在页面加载前发布当前系统字号倍率，保证首屏排版和分页一致。
    AppStorage.setOrCreate('systemFontScale', this.context.config.fontSizeScale ?? 1);
    windowStage.loadContent('pages/Index', (error) => {
      if (error.code) {
        console.error('Failed to load MyTools entry page');
      }
    });
  }

  /** 系统显示配置变化时把字号倍率同步给阅读页。 */
  onConfigurationUpdate(newConfig: Configuration): void {
    AppStorage.setOrCreate('systemFontScale', newConfig.fontSizeScale ?? 1);
  }
}
