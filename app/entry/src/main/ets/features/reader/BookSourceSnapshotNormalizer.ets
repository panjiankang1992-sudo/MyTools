import { BookSource, BookSourceHealthSample } from './ReaderModels';
import { BookSourceImporter } from './BookSourceImporter';

/**
 * 使用当前导入策略迁移和校验持久化书源快照。
 */
export class BookSourceSnapshotNormalizer {
  private readonly importer = new BookSourceImporter();

  /**
   * 恢复后端允许的合法来源，并安全继承有界健康状态。
   */
  normalize(values: Object[]): BookSource[] {
    const sources = new Map<string, BookSource>();
    values.slice(0, 5000).forEach((value: Object) => {
      if (value === null || typeof value !== 'object' || Array.isArray(value)) return;
      try {
        const imported = this.importer.importJson(JSON.stringify(value)).sources[0];
        if (imported === undefined) return;
        this.restoreHealth(imported, value as Record<string, Object>);
        imported.syncUpdatedAt = this.boundedInteger((value as Record<string, Object>)['syncUpdatedAt'], 0,
          9007199254740991);
        imported.syncRevision = this.boundedInteger((value as Record<string, Object>)['syncRevision'], 0,
          9007199254740991);
        sources.set(imported.bookSourceUrl, imported);
      } catch (_) {
        // 单个损坏快照不会阻断其他书源、书架或阅读记录恢复。
      }
    });
    return Array.from(sources.values());
  }

  private restoreHealth(source: BookSource, record: Record<string, Object>): void {
    const status = `${record['healthStatus'] ?? ''}`;
    if (status === 'available' || status === 'partial' || status === 'unavailable' || status === 'unsupported' ||
      status === 'unknown') source.healthStatus = status;
    source.healthLatencyMs = this.boundedInteger(record['healthLatencyMs'], 0, 3600000);
    source.healthCheckedAt = this.boundedInteger(record['healthCheckedAt'], 0, 9007199254740991);
    source.healthMessage = typeof record['healthMessage'] === 'string' ?
      (record['healthMessage'] as string).substring(0, 200) : '';
    source.healthHistory = this.healthHistory(record['healthHistory']);
    source.healthSuccessRate = this.boundedNumber(record['healthSuccessRate'], 0, 1);
    source.healthAverageLatencyMs = this.boundedInteger(record['healthAverageLatencyMs'], 0, 3600000);
    source.healthConsecutiveFailures = this.boundedInteger(record['healthConsecutiveFailures'], 0, 1000000);
    source.healthDegraded = record['healthDegraded'] === true;
  }

  private healthHistory(value: Object | undefined): BookSourceHealthSample[] {
    if (!Array.isArray(value)) return [];
    const samples: BookSourceHealthSample[] = [];
    value.slice(-20).forEach((item: Object) => {
      if (item === null || typeof item !== 'object' || Array.isArray(item)) return;
      const record = item as Record<string, Object>;
      const status = `${record['status'] ?? ''}`;
      if (status !== 'available' && status !== 'partial' && status !== 'unavailable' && status !== 'unsupported') return;
      samples.push({
        status: status,
        latencyMs: this.boundedInteger(record['latencyMs'], 0, 3600000),
        checkedAt: this.boundedInteger(record['checkedAt'], 0, 9007199254740991)
      });
    });
    return samples;
  }

  private boundedInteger(value: Object | undefined, minimum: number, maximum: number): number {
    if (typeof value !== 'number' || !Number.isFinite(value)) return minimum;
    return Math.min(maximum, Math.max(minimum, Math.floor(value)));
  }

  private boundedNumber(value: Object | undefined, minimum: number, maximum: number): number {
    if (typeof value !== 'number' || !Number.isFinite(value)) return minimum;
    return Math.min(maximum, Math.max(minimum, value));
  }
}
