import { DriveAccount, DriveDirectory, DriveItem, DriveItemKind, DriveOpenDescriptor } from './DriveModels';

/**
 * 将统一网盘响应限制为 App 使用的稳定字段。
 */
export class DriveResponseNormalizer {
  /** 校验网盘列表。 */
  normalizeAccounts(value: Object | undefined): DriveAccount[] {
    if (!Array.isArray(value) || value.length > 100) throw new Error('网盘列表格式无效');
    return value.map((entry: Object): DriveAccount => {
      const data = this.record(entry);
      return {
        driveId: this.uuid(data, 'id'),
        name: this.text(data, 'displayName', 255),
        readOnly: data['readOnly'] === true,
        status: data['enabled'] === true ? 'ACTIVE' : 'DISABLED'
      };
    });
  }

  /** 将新 Drive 索引列表投影为 App 目录。 */
  normalizeIndexedDirectory(value: Object | undefined, driveId: string, parentPath: string,
    keyword: string, sort: string, direction: string): DriveDirectory {
    if (!Array.isArray(value) || value.length > 10000) throw new Error('网盘目录格式无效');
    const query = keyword.trim().toLocaleLowerCase().substring(0, 100);
    let items = value.map((entry: Object): DriveItem => this.indexedItem(entry))
      .filter((item: DriveItem): boolean => query.length === 0 || item.name.toLocaleLowerCase().includes(query));
    items.sort((left: DriveItem, right: DriveItem): number => {
      const factor = direction === 'desc' ? -1 : 1;
      if (sort === 'size') return factor * (left.sizeBytes - right.sizeBytes);
      if (sort === 'modified') return factor * left.modifiedAt.localeCompare(right.modifiedAt);
      return factor * left.name.localeCompare(right.name);
    });
    return {
      driveId,
      currentItemId: parentPath,
      name: parentPath.length === 0 ? '/' : parentPath,
      itemCount: items.length,
      totalSizeBytes: items.reduce((total: number, item: DriveItem): number => total + item.sizeBytes, 0),
      items
    };
  }

  /** 校验目录统计和文件条目。 */
  normalizeDirectory(value: Object | undefined): DriveDirectory {
    const data = this.record(value);
    const rawItems = data['items'];
    if (!Array.isArray(rawItems) || rawItems.length > 10000) throw new Error('网盘目录格式无效');
    return {
      driveId: this.id(data, 'driveId'),
      currentItemId: this.optionalId(data, 'currentItemId'),
      name: this.text(data, 'name', 512),
      itemCount: this.nonNegativeNumber(data, 'itemCount'),
      totalSizeBytes: this.nonNegativeNumber(data, 'totalSizeBytes'),
      items: rawItems.map((entry: Object): DriveItem => this.item(entry))
    };
  }

  /** 校验文件打开票据并拼接同源地址。 */
  normalizeOpenDescriptor(value: Object | undefined, baseUrl: string): DriveOpenDescriptor {
    const data = this.record(value);
    const streamPath = this.text(data, 'streamPath', 256);
    if (!/^\/api\/app\/v1\/drive-tickets\/[a-f0-9]{32}$/.test(streamPath)) {
      throw new Error('网盘打开地址无效');
    }
    const expiresAt = Date.parse(this.text(data, 'expiresAt', 64));
    if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) throw new Error('网盘打开票据已过期');
    return {
      url: `${baseUrl}${streamPath}`,
      ticket: this.text(data, 'ticket', 32),
      expiresAt: expiresAt,
      name: this.text(data, 'name', 512),
      mimeType: this.optionalText(data, 'mimeType', 255),
      sizeBytes: this.nonNegativeNumber(data, 'sizeBytes')
    };
  }

  private item(value: Object): DriveItem {
    const data = this.record(value);
    const kind = this.text(data, 'kind', 16) as DriveItemKind;
    if (!['DIRECTORY', 'VIDEO', 'IMAGE', 'AUDIO', 'WEB', 'TEXT', 'OTHER'].includes(kind)) {
      throw new Error('网盘文件类型无效');
    }
    return {
      itemId: this.id(data, 'itemId'),
      name: this.text(data, 'name', 512),
      kind: kind,
      mimeType: this.optionalText(data, 'mimeType', 255),
      sizeBytes: this.nonNegativeNumber(data, 'sizeBytes'),
      modifiedAt: this.optionalText(data, 'modifiedAt', 64)
    };
  }

  private indexedItem(value: Object): DriveItem {
    const data = this.record(value);
    const mimeType = this.optionalText(data, 'mimeType', 255);
    const directory = data['directory'] === true;
    return {
      itemId: this.text(data, 'remotePath', 2048),
      name: this.text(data, 'displayName', 512),
      kind: directory ? 'DIRECTORY' : this.kind(mimeType),
      mimeType,
      sizeBytes: this.nonNegativeNumber(data, 'sizeBytes'),
      modifiedAt: this.optionalText(data, 'modifiedAt', 64)
    };
  }

  private kind(mimeType: string): DriveItemKind {
    if (mimeType.startsWith('video/')) return 'VIDEO';
    if (mimeType.startsWith('image/')) return 'IMAGE';
    if (mimeType.startsWith('audio/')) return 'AUDIO';
    if (mimeType.startsWith('text/')) return 'TEXT';
    return 'OTHER';
  }

  private record(value: Object | undefined): Record<string, Object> {
    if (value === undefined || value === null || Array.isArray(value) || typeof value !== 'object') {
      throw new Error('网盘响应格式无效');
    }
    return value as Record<string, Object>;
  }

  private id(data: Record<string, Object>, key: string): string {
    const value = `${data[key] ?? ''}`;
    if (!/^[1-9]\d{0,18}$/.test(value)) throw new Error(`网盘字段${key}无效`);
    return value;
  }

  private uuid(data: Record<string, Object>, key: string): string {
    const value = `${data[key] ?? ''}`;
    if (!/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$/.test(value)) {
      throw new Error(`网盘字段${key}无效`);
    }
    return value.toLowerCase();
  }

  private optionalId(data: Record<string, Object>, key: string): string {
    const value = `${data[key] ?? ''}`;
    if (value.length > 0 && !/^[1-9]\d{0,18}$/.test(value)) throw new Error(`网盘字段${key}无效`);
    return value;
  }

  private text(data: Record<string, Object>, key: string, limit: number): string {
    const value = data[key];
    if (typeof value !== 'string' || value.length === 0 || value.length > limit ||
      /[\u0000-\u001F\u007F]/.test(value)) throw new Error(`网盘字段${key}无效`);
    return value;
  }

  private optionalText(data: Record<string, Object>, key: string, limit: number): string {
    const value = data[key];
    if (value === undefined || value === null) return '';
    if (typeof value !== 'string' || value.length > limit || /[\u0000-\u001F\u007F]/.test(value)) {
      throw new Error(`网盘字段${key}无效`);
    }
    return value;
  }

  private nonNegativeNumber(data: Record<string, Object>, key: string): number {
    const value = Number(data[key]);
    if (!Number.isSafeInteger(value) || value < 0) throw new Error(`网盘字段${key}无效`);
    return value;
  }
}
