import { BookFormat } from './ReaderModels';

export interface ShelfSyncItem {
  id: string;
  name: string;
  author: string;
  origin: 'source' | 'remote';
  format: BookFormat;
  resourceUri: string;
  sourceId: string;
  remoteCoverUrl: string;
  updatedAt: number;
  deleted: boolean;
  revision: number;
}

export interface ShelfSyncResult {
  accepted: boolean;
  book?: ShelfSyncItem;
}
