export type RemoteMediaKind = 'directory' | 'image' | 'audio' | 'video' | 'other';

export interface RemoteMediaTag {
  name: string;
  type: string;
  confidence: number;
}

export interface RemoteMediaSource {
  id: string;
  name: string;
  type: string;
  url: string;
  isDefault: boolean;
  isActive: boolean;
  localDirectoryType?: string;
}

export interface RemoteMediaItem {
  id?: string;
  name: string;
  path: string;
  directoryName?: string;
  kind: RemoteMediaKind;
  contentType: string;
  size: number;
  lastModified: string;
  tags?: RemoteMediaTag[];
}

export interface RemoteDirectory {
  path: string;
  items: RemoteMediaItem[];
  total?: number;
  page?: number;
  pageSize?: number;
}

export interface RemoteMediaFilterOptions {
  directories: string[];
  tags: string[];
}

export interface RemotePlaybackDescriptor {
  url: string;
  ticket: string;
  expiresAt: number;
  sourceType: string;
  supportsRange: boolean;
}

export interface RemotePlaybackMetrics {
  transferredBytes: number;
  activeStreams: number;
  lastTransferTime: number;
}
