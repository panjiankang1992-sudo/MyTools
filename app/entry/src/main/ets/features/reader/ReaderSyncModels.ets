import { ReadingProgress } from './ReaderModels';

export interface ProgressSyncResult {
  accepted: boolean;
  progress?: ReadingProgress;
}

export interface BookSourceSyncItem {
  sourceUrl: string;
  snapshotJson: string;
  updatedAt: number;
  deleted: boolean;
  revision: number;
}

export interface BookSourceSyncResult {
  accepted: boolean;
  source?: BookSourceSyncItem;
}

export interface ReaderMarkerSyncItem {
  id: string;
  kind: 'BOOKMARK' | 'ANNOTATION';
  bookId: string;
  chapterTitle: string;
  locator: number;
  note: string;
  createdAt: number;
  updatedAt: number;
  deleted: boolean;
  revision: number;
}

export interface ReaderMarkerSyncResult {
  accepted: boolean;
  marker?: ReaderMarkerSyncItem;
}

export interface ReaderDataSummary {
  shelfRecords: number;
  sourceRecords: number;
  progressRecords: number;
  markerRecords: number;
}
