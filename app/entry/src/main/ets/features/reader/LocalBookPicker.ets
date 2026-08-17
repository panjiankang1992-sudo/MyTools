import picker from '@ohos.file.picker';
import common from '@ohos.app.ability.common';
import { Book } from './ReaderModels';
import { LocalBookImportPolicy } from './LocalBookImportPolicy';

/**
 * 仅通过系统文件选择器导入用户主动授权的电子书。
 */
export class LocalBookPicker {
  private readonly importPolicy: LocalBookImportPolicy = new LocalBookImportPolicy();
  /**
   * 选择一个或多个本地电子书，不扫描设备目录。
   */
  async select(context: common.UIAbilityContext): Promise<Book[]> {
    const options = new picker.DocumentSelectOptions();
    options.maxSelectNumber = 50;
    options.fileSuffixFilters = ['电子书|txt,epub,pdf,mobi,azw3,cbz,zip'];
    const documentPicker = new picker.DocumentViewPicker(context);
    const uris = await documentPicker.select(options);
    return this.importPolicy.normalize(uris);
  }
}
