import picker from '@ohos.file.picker';
import fileShare from '@ohos.fileshare';
import fs from '@ohos.file.fs';
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
    await this.persistReadPermission(uris);
    return this.importPolicy.normalize(uris);
  }

  /**
   * 激活已持久化的本地图书读取权限。
   */
  async activate(uri: string): Promise<void> {
    const policy: fileShare.PolicyInfo = {
      uri: uri,
      operationMode: fileShare.OperationMode.READ_MODE
    };
    try {
      await fileShare.activatePermission([policy]);
    } catch (_) {
      throw new Error('本地图书授权已失效，请返回本地书架，点击添加书籍并重新选择该文件');
    }
  }

  /** 删除用户通过系统文件选择器授权的本地图书。 */
  async delete(uri: string): Promise<void> {
    const policy: fileShare.PolicyInfo = {
      uri: uri,
      operationMode: fileShare.OperationMode.READ_MODE | fileShare.OperationMode.WRITE_MODE
    };
    try {
      await fileShare.activatePermission([policy]);
      fs.unlinkSync(uri);
    } catch (_) {
      throw new Error('无法删除本地图书文件，请确认文件仍存在且已授予修改权限');
    }
  }

  private async persistReadPermission(uris: string[]): Promise<void> {
    if (uris.length === 0) return;
    const writablePolicies: fileShare.PolicyInfo[] = uris.map((uri: string): fileShare.PolicyInfo => ({
      uri: uri,
      operationMode: fileShare.OperationMode.READ_MODE | fileShare.OperationMode.WRITE_MODE
    }));
    try {
      await fileShare.persistPermission(writablePolicies);
      await fileShare.activatePermission(writablePolicies);
      return;
    } catch (_) {
      // 部分文档提供方只授予读取权限，导入仍可继续，但后续删除会明确提示权限不足。
    }
    const readablePolicies: fileShare.PolicyInfo[] = uris.map((uri: string): fileShare.PolicyInfo => ({
      uri: uri,
      operationMode: fileShare.OperationMode.READ_MODE
    }));
    try {
      await fileShare.persistPermission(readablePolicies);
      await fileShare.activatePermission(readablePolicies);
    } catch (_) {
      throw new Error('无法保存本地图书读取权限，请重新选择文件');
    }
  }
}
