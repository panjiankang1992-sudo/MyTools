import { AppMarketDetail, AppMarketItem, AppMarketPage } from './AppMarketModels';

/**
 * 将应用市场服务端响应投影为只读、有界且不含执行字段的展示模型。
 */
export class AppMarketResponseNormalizer {
  /** 将新应用目录列表投影为旧页面使用的分页模型。 */
  catalogPage(value: Object | undefined, requestedPage: number, requestedPageSize: number,
    name: string): AppMarketPage {
    if (!Array.isArray(value) || value.length > 10000) throw new Error('应用目录响应无效');
    const query = name.trim().toLocaleLowerCase();
    const all = value.map((entry: Object): AppMarketItem => this.catalogItem(this.requiredRecord(entry,
      '应用目录条目无效'))).filter((item: AppMarketItem): boolean =>
        query.length === 0 || item.name.toLocaleLowerCase().includes(query));
    const start = (requestedPage - 1) * requestedPageSize;
    return { items: all.slice(start, start + requestedPageSize), total: all.length,
      page: requestedPage, pageSize: requestedPageSize };
  }

  /** 从新目录列表中构造只读详情。 */
  catalogDetail(value: Object | undefined, id: string): AppMarketDetail {
    if (!Array.isArray(value)) throw new Error('应用目录响应无效');
    const entry = value.map((item: Object): Record<string, Object> => this.requiredRecord(item,
      '应用目录条目无效')).find((item: Record<string, Object>): boolean => `${item['id'] ?? ''}` === id);
    if (entry === undefined) throw new Error('应用已不在发布目录中');
    const item = this.catalogItem(entry);
    return { id: item.id, name: item.name, type: item.type, version: item.version, summary: item.summary,
      status: item.status, author: item.author, updatedAt: item.updatedAt,
      content: '', fileName: '', fileSize: 0, fileType: '' };
  }
  /** 解析分页目录并逐项隔离损坏应用。 */
  page(value: Object | undefined, requestedPage: number, requestedPageSize: number): AppMarketPage {
    const record = this.requiredRecord(value, '应用市场响应缺少分页数据');
    const list = record['list'];
    if (!Array.isArray(list)) throw new Error('应用市场分页列表无效');
    const total = this.integer(record['total'], 10000000, '应用市场总数无效');
    const page = this.integer(record['page'], 1000000, '应用市场页码无效', 1);
    const pageSize = this.integer(record['pageSize'], 100, '应用市场分页大小无效', 1);
    if (page !== requestedPage || pageSize !== requestedPageSize || list.length > 200) {
      throw new Error('应用市场分页边界与请求不一致');
    }
    const selected = new Map<string, AppMarketItem>();
    list.forEach((value: Object) => {
      const item = this.item(this.record(value));
      if (item !== undefined && !selected.has(item.id) && selected.size < 100) selected.set(item.id, item);
    });
    return { items: Array.from(selected.values()), total: total, page: page, pageSize: pageSize };
  }

  /** 解析应用详情，只保留展示字段并忽略安装命令与下载地址。 */
  detail(value: Object | undefined): AppMarketDetail {
    const record = this.requiredRecord(value, '应用详情响应无效');
    const item = this.item(record);
    const content = this.optionalText(record['content'], 200000, true);
    const fileName = this.optionalText(record['fileName'], 255);
    const fileSize = record['fileSize'] === undefined || record['fileSize'] === null ? 0 :
      this.integer(record['fileSize'], Number.MAX_SAFE_INTEGER, '应用文件大小无效');
    const fileType = this.optionalText(record['fileType'], 128);
    if (item === undefined || content === undefined || fileName === undefined || fileType === undefined) {
      throw new Error('应用详情响应无效');
    }
    return { id: item.id, name: item.name, type: item.type, version: item.version,
      summary: item.summary, status: item.status, author: item.author, updatedAt: item.updatedAt,
      content: this.plainText(content), fileName: fileName, fileSize: fileSize, fileType: fileType };
  }

  private item(record: Record<string, Object> | undefined): AppMarketItem | undefined {
    if (record === undefined) return undefined;
    const id = this.text(record['id'], 100, false);
    const name = this.text(record['name'], 200, false);
    const type = this.optionalText(record['type'], 50);
    const version = this.optionalText(record['version'], 100);
    const preview = this.optionalText(record['contentPreview'], 4000, true);
    const status = this.optionalText(record['status'], 50);
    const author = this.optionalText(record['userName'], 100);
    const updatedAt = this.optionalText(record['updateTime'], 64);
    if (id === undefined || name === undefined || type === undefined || version === undefined || preview === undefined ||
      status === undefined || author === undefined || updatedAt === undefined) return undefined;
    return { id: id, name: name, type: type.length > 0 ? type : 'app',
      version: version.length > 0 ? version : '-', summary: this.plainText(preview), status: status,
      author: author.length > 0 ? author : '未知作者', updatedAt: updatedAt };
  }

  private catalogItem(record: Record<string, Object>): AppMarketItem {
    const id = this.text(record['id'], 100, false);
    const name = this.text(record['name'], 200, false);
    const type = this.optionalText(record['appType'], 50);
    const version = this.optionalText(record['currentVersion'], 100);
    const status = this.optionalText(record['status'], 50);
    if (id === undefined || name === undefined || type === undefined || version === undefined || status === undefined) {
      throw new Error('应用目录条目无效');
    }
    return { id, name, type: type.length > 0 ? type : 'app', version: version.length > 0 ? version : '-',
      summary: '', status, author: '-', updatedAt: '' };
  }

  private plainText(value: string): string {
    let output = '';
    let insideTag = false;
    for (let index = 0; index < value.length; index += 1) {
      const character = value[index];
      if (character === '<') {
        insideTag = true;
        output += ' ';
      } else if (character === '>') {
        insideTag = false;
      } else if (!insideTag) {
        output += character;
      }
    }
    return output.replace(/\s+/g, ' ').trim();
  }

  private record(value: Object | undefined): Record<string, Object> | undefined {
    return value !== undefined && value !== null && typeof value === 'object' && !Array.isArray(value) ?
      value as Record<string, Object> : undefined;
  }

  private requiredRecord(value: Object | undefined, message: string): Record<string, Object> {
    const record = this.record(value);
    if (record === undefined) throw new Error(message);
    return record;
  }

  private text(value: Object | undefined, maximum: number, allowEmpty: boolean,
    allowLineBreaks: boolean = false): string | undefined {
    if (typeof value !== 'string' || value.length > maximum || (!allowEmpty && value.length === 0) ||
      (allowLineBreaks ? /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/ : /[\u0000-\u001F\u007F]/).test(value)) {
      return undefined;
    }
    return value;
  }

  private optionalText(value: Object | undefined, maximum: number, allowLineBreaks: boolean = false): string | undefined {
    return value === undefined || value === null ? '' : this.text(value, maximum, true, allowLineBreaks);
  }

  private integer(value: Object | undefined, maximum: number, message: string, minimum: number = 0): number {
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum || value > maximum) {
      throw new Error(message);
    }
    return value;
  }
}
