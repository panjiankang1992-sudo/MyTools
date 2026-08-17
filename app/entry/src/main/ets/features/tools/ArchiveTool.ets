import common from '@ohos.app.ability.common';
import fs from '@ohos.file.fs';
import zlib from '@ohos.zlib';
import { ArchiveToolPolicy } from './ArchiveToolPolicy';
import { ReaderContentLoader } from '../reader/ReaderContentLoader';

export interface ArchiveCreateResult {
  fileCount: number;
  inputBytes: number;
  outputBytes: number;
}

export interface ArchiveExtractedItem {
  relativePath: string;
  size: number;
}

/**
 * 将用户授权的普通文件安全暂存并创建ZIP。
 */
export class ArchiveTool {
  private readonly policy: ArchiveToolPolicy = new ArchiveToolPolicy();
  private readonly zipValidator: ReaderContentLoader = new ReaderContentLoader();
  private extractionDirectory: string = '';
  private extractedItems: ArchiveExtractedItem[] = [];

  /**
   * 创建ZIP并写入系统保存窗口返回的目标URI。
   */
  async createZip(context: common.UIAbilityContext, uris: string[], destinationUri: string): Promise<ArchiveCreateResult> {
    this.policy.validateSelectionCount(uris.length);
    const workspace = `${context.cacheDir}/archive-tool-${Date.now()}`;
    const staging = `${workspace}/files`;
    const archive = `${workspace}/archive.zip`;
    let inputBytes = 0;
    try {
      fs.mkdirSync(staging, true);
      for (let index = 0; index < uris.length; index++) {
        const stat = fs.statSync(uris[index]);
        if (stat.isDirectory()) throw new Error('暂不支持压缩目录，请选择普通文件');
        inputBytes = this.policy.addFileSize(stat.size, inputBytes);
        await fs.copyFile(uris[index], `${staging}/${this.policy.safeFileName(uris[index], index)}`);
      }
      await zlib.compressFile(staging, archive, {});
      const outputBytes = fs.statSync(archive).size;
      if (outputBytes <= 0 || outputBytes > ArchiveToolPolicy.MAX_TOTAL_BYTES) throw new Error('ZIP输出为空或超过500 MB');
      await fs.copyFile(archive, destinationUri);
      return { fileCount: uris.length, inputBytes, outputBytes };
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('系统无法创建ZIP文件');
    } finally {
      try {
        fs.rmdirSync(workspace);
      } catch (_) {
      }
    }
  }

  /**
   * 校验并解压用户选择的ZIP到应用临时目录。
   */
  async extractZip(context: common.UIAbilityContext, uri: string): Promise<ArchiveExtractedItem[]> {
    this.cleanupExtraction();
    const workspace = `${context.cacheDir}/archive-extract-${Date.now()}`;
    const archive = `${workspace}/archive.zip`;
    const output = `${workspace}/content`;
    try {
      fs.mkdirSync(output, true);
      await fs.copyFile(uri, archive);
      const expectedFiles = this.zipValidator.validateZipArchive(archive);
      await zlib.decompressFile(archive, output, {});
      const items: ArchiveExtractedItem[] = [];
      this.collectExtractedFiles(output, output, items, 0, expectedFiles);
      if (expectedFiles.size > 0) throw new Error('ZIP声明的文件未被完整解压');
      if (items.length === 0) throw new Error('ZIP中没有可导出的普通文件');
      this.extractionDirectory = output;
      this.extractedItems = items;
      try {
        fs.unlinkSync(archive);
      } catch (_) {
      }
      return items.slice();
    } catch (error) {
      try {
        fs.rmdirSync(workspace);
      } catch (_) {
      }
      if (error instanceof Error) throw error;
      throw new Error('系统无法安全解压ZIP');
    }
  }

  /**
   * 将已校验解压结果中的单个文件导出到系统授权目标。
   */
  async exportExtractedFile(relativePath: string, destinationUri: string): Promise<void> {
    if (!this.extractedItems.some((item: ArchiveExtractedItem) => item.relativePath === relativePath) ||
      this.extractionDirectory.length === 0) throw new Error('导出目标不在当前解压结果中');
    const source = `${this.extractionDirectory}/${relativePath}`;
    if (!source.startsWith(`${this.extractionDirectory}/`)) throw new Error('导出路径越界');
    try {
      const expectedSize = this.extractedItems.find((item: ArchiveExtractedItem) =>
        item.relativePath === relativePath)?.size;
      const stat = fs.lstatSync(source);
      if (expectedSize === undefined || !stat.isFile() || stat.isSymbolicLink() || stat.size !== expectedSize) {
        throw new Error('待导出文件已发生变化');
      }
      await fs.copyFile(source, destinationUri);
    } catch (error) {
      if (error instanceof Error && error.message === '待导出文件已发生变化') throw error;
      throw new Error('无法写入系统授权的目标文件');
    }
  }

  /**
   * 清理当前ZIP解压临时目录。
   */
  cleanupExtraction(): void {
    if (this.extractionDirectory.length > 0) {
      const workspace = this.extractionDirectory.substring(0, this.extractionDirectory.lastIndexOf('/'));
      try {
        fs.rmdirSync(workspace);
      } catch (_) {
      }
    }
    this.extractionDirectory = '';
    this.extractedItems = [];
  }

  private collectExtractedFiles(root: string, directory: string, items: ArchiveExtractedItem[], totalBytes: number,
    expectedFiles: Map<string, number>): number {
    try {
      const names = fs.listFileSync(directory);
      let total = totalBytes;
      for (let index = 0; index < names.length; index++) {
        const path = `${directory}/${names[index]}`;
        if (!path.startsWith(`${root}/`)) throw new Error('ZIP解压路径越界');
        const stat = fs.lstatSync(path);
        if (stat.isSymbolicLink()) throw new Error('ZIP解压内容包含符号链接');
        if (stat.isDirectory()) {
          total = this.collectExtractedFiles(root, path, items, total, expectedFiles);
        } else if (stat.isFile()) {
          const relativePath = path.substring(root.length + 1);
          const expectedSize = expectedFiles.get(relativePath);
          if (expectedSize === undefined) throw new Error('ZIP解压产生未声明文件');
          if (stat.size !== expectedSize) throw new Error('ZIP解压文件尺寸与声明不一致');
          expectedFiles.delete(relativePath);
          items.push({ relativePath: relativePath, size: stat.size });
          if (items.length > 5000) throw new Error('ZIP解压文件数量超过5000');
          total += stat.size;
          if (!Number.isSafeInteger(total) || total > 300 * 1024 * 1024) {
            throw new Error('ZIP实际展开内容超过300 MB');
          }
        } else {
          throw new Error('ZIP解压内容包含不支持的文件类型');
        }
      }
      return total;
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error('无法检查ZIP实际展开内容');
    }
  }
}
