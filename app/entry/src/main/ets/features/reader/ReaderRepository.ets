import preferences from '@ohos.data.preferences';
import common from '@ohos.app.ability.common';
import { Book, BookSource, BookSourceTombstone, ReaderAnnotation, ReaderBookmark, ReadingProgress, ReaderSettings,
  ReaderMarkerTombstone, ReadingProgressTombstone, ReadingStatistic, ShelfBookTombstone,
  defaultReaderSettings } from './ReaderModels';
import { BookSourceSnapshotNormalizer } from './BookSourceSnapshotNormalizer';
import { ReaderSnapshotNormalizer } from './ReaderSnapshotNormalizer';

export interface ReaderSnapshot {
  books: Book[];
  shelfTombstones: ShelfBookTombstone[];
  sources: BookSource[];
  sourceTombstones: BookSourceTombstone[];
  progress: ReadingProgress[];
  progressTombstones: ReadingProgressTombstone[];
  bookmarks: ReaderBookmark[];
  annotations: ReaderAnnotation[];
  markerTombstones: ReaderMarkerTombstone[];
  statistics: ReadingStatistic[];
  settings: ReaderSettings;
}

/**
 * 保存书架、书源、进度与阅读偏好。
 *
 * 当前使用 Preferences 保存轻量元数据，正文和大文件不会写入其中。
 */
export class ReaderRepository {
  private static readonly STORE_NAME: string = 'mytools_reader';
  private static readonly LEGACY_SNAPSHOT_KEY: string = 'reader_snapshot_v1';
  private static readonly GUEST_SCOPE: string = 'device:v2:guest';
  private store?: preferences.Preferences;
  private snapshotKey: string = '';
  private readonly sourceNormalizer = new BookSourceSnapshotNormalizer();
  private readonly snapshotNormalizer = new ReaderSnapshotNormalizer();

  /**
   * 初始化存储并读取当前快照。
   */
  restore(context: common.UIAbilityContext, scope: string = ReaderRepository.GUEST_SCOPE): ReaderSnapshot {
    try {
      if (scope !== ReaderRepository.GUEST_SCOPE && !/^account:v2:[a-f0-9]{64}$/.test(scope)) {
        throw new Error('阅读数据账户作用域无效');
      }
      this.store = preferences.getPreferencesSync(context, { name: ReaderRepository.STORE_NAME });
      this.snapshotKey = `reader_snapshot_v2_${scope}`;
      let raw = this.store.getSync(this.snapshotKey, '');
      if ((typeof raw !== 'string' || raw.length === 0) && scope === ReaderRepository.GUEST_SCOPE) {
        raw = this.store.getSync(ReaderRepository.LEGACY_SNAPSHOT_KEY, '');
        if (typeof raw === 'string' && raw.length > 0 && raw.length <= 80 * 1024 * 1024) {
          // 旧快照无法证明云账户归属，只允许迁入设备访客区。
          this.store.putSync(this.snapshotKey, raw);
          this.store.flush();
        }
      }
      if (typeof raw !== 'string' || raw.length === 0) return this.emptySnapshot();
      if (raw.length > 80 * 1024 * 1024) return this.emptySnapshot();
      const parsed = JSON.parse(raw) as Record<string, Object>;
      const bookmarks = this.snapshotNormalizer.normalizeBookmarks(parsed['bookmarks']);
      const annotations = this.snapshotNormalizer.normalizeAnnotations(parsed['annotations'], 10000 - bookmarks.length);
      const markerTombstones = this.snapshotNormalizer.normalizeMarkerTombstones(parsed['markerTombstones'],
        10000 - bookmarks.length - annotations.length);
      return {
        books: this.snapshotNormalizer.normalizeBooks(parsed['books']),
        shelfTombstones: this.snapshotNormalizer.normalizeShelfTombstones(parsed['shelfTombstones']),
        sources: Array.isArray(parsed['sources']) ? this.sourceNormalizer.normalize(parsed['sources'] as Object[]) : [],
        sourceTombstones: this.normalizeSourceTombstones(parsed['sourceTombstones']),
        progress: this.snapshotNormalizer.normalizeProgress(parsed['progress']),
        progressTombstones: this.snapshotNormalizer.normalizeProgressTombstones(parsed['progressTombstones']),
        bookmarks, annotations, markerTombstones,
        statistics: this.snapshotNormalizer.normalizeStatistics(parsed['statistics']),
        settings: this.snapshotNormalizer.normalizeSettings(parsed['settings'])
      };
    } catch (_) {
      return this.emptySnapshot();
    }
  }

  /**
   * 原子更新轻量阅读元数据快照。
   */
  save(books: Book[], shelfTombstones: ShelfBookTombstone[], sources: BookSource[],
    sourceTombstones: BookSourceTombstone[], progress: ReadingProgress[],
    progressTombstones: ReadingProgressTombstone[], bookmarks: ReaderBookmark[],
    annotations: ReaderAnnotation[], markerTombstones: ReaderMarkerTombstone[], statistics: ReadingStatistic[],
    settings: ReaderSettings): void {
    if (this.store === undefined || this.snapshotKey.length === 0) return;
    try {
      const snapshot: ReaderSnapshot = { books, shelfTombstones, sources, sourceTombstones, progress, progressTombstones,
        bookmarks, annotations,
        markerTombstones,
        statistics, settings };
      this.store.putSync(this.snapshotKey, JSON.stringify(snapshot));
      this.store.flush();
    } catch (error) {
      console.error('Failed to save reader metadata');
    }
  }

  private emptySnapshot(): ReaderSnapshot {
    return { books: [], shelfTombstones: [], sources: [], sourceTombstones: [], progress: [], progressTombstones: [], bookmarks: [], annotations: [],
      markerTombstones: [], statistics: [], settings: defaultReaderSettings() };
  }

  private normalizeSourceTombstones(value: Object | undefined): BookSourceTombstone[] {
    if (!Array.isArray(value)) return [];
    const tombstones = new Map<string, BookSourceTombstone>();
    value.slice(0, 1000).forEach((item: Object) => {
      if (item === null || typeof item !== 'object' || Array.isArray(item)) return;
      const record = item as Record<string, Object>;
      const sourceUrl = record['sourceUrl'];
      const snapshotJson = record['snapshotJson'];
      if (typeof sourceUrl !== 'string' || sourceUrl.length === 0 || sourceUrl.length > 4096 ||
        typeof snapshotJson !== 'string' || snapshotJson.length === 0 || snapshotJson.length > 512 * 1024 ||
        /[\u0000-\u001F\u007F]/.test(sourceUrl)) return;
      try {
        const restored = this.sourceNormalizer.normalize([JSON.parse(snapshotJson) as Object]);
        if (restored.length !== 1 || restored[0].bookSourceUrl !== sourceUrl) return;
      } catch (_) {
        return;
      }
      const updatedAt = typeof record['updatedAt'] === 'number' && Number.isFinite(record['updatedAt']) ?
        Math.min(9007199254740991, Math.max(0, Math.floor(record['updatedAt'] as number))) : 0;
      const revision = typeof record['revision'] === 'number' && Number.isFinite(record['revision']) ?
        Math.min(9007199254740991, Math.max(0, Math.floor(record['revision'] as number))) : 0;
      tombstones.set(sourceUrl, { sourceUrl, snapshotJson, updatedAt, revision });
    });
    return Array.from(tombstones.values()).slice(-500);
  }

}
