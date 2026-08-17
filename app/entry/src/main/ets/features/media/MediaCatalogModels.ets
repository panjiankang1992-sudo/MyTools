export type MediaCatalogKind = 'VIDEO' | 'IMAGE' | 'AUDIO' | 'WEB' | 'TEXT' | 'OTHER';

export interface MediaDirectoryFilter {
  directoryId: string;
  name: string;
  fileCount: number;
  latestModifiedAt: string;
}

export interface MediaTagFilter {
  name: string;
  fileCount: number;
}

export interface MediaCatalogFilters {
  directories: MediaDirectoryFilter[];
  tags: MediaTagFilter[];
}

export interface MediaCatalogItem {
  itemId: string;
  name: string;
  kind: MediaCatalogKind;
  mimeType: string;
  sizeBytes: number;
  modifiedAt: string;
  thumbnailUrl: string;
  tags: string[];
  directoryId: string;
  directoryName: string;
}

export interface MediaGalleryPage {
  items: MediaCatalogItem[];
  total: number;
  page: number;
  pageSize: number;
}

export interface MediaVideoDirectory {
  directoryId: string;
  name: string;
  fileCount: number;
  totalSizeBytes: number;
  latestModifiedAt: string;
  topItems: MediaCatalogItem[];
}

export interface MediaStoryboardFrame {
  sequence: number;
  timestampMs: number;
  imageUrl: string;
}

export interface MediaVideoDetail {
  videoId: string;
  name: string;
  sizeBytes: number;
  format: string;
  videoCodec: string;
  audioCodec: string;
  durationMs: number;
  width: number;
  height: number;
  directoryName: string;
  summary: string;
  description: string;
  descriptionStatus: string;
  thumbnailUrl: string;
  storyboard: MediaStoryboardFrame[];
}
