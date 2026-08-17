export type DriveItemKind = 'DIRECTORY' | 'VIDEO' | 'IMAGE' | 'AUDIO' | 'WEB' | 'TEXT' | 'OTHER';

export interface DriveAccount {
  driveId: string;
  name: string;
  readOnly: boolean;
  status: string;
}

export interface DriveItem {
  itemId: string;
  name: string;
  kind: DriveItemKind;
  mimeType: string;
  sizeBytes: number;
  modifiedAt: string;
}

export interface DriveDirectory {
  driveId: string;
  currentItemId: string;
  name: string;
  itemCount: number;
  totalSizeBytes: number;
  items: DriveItem[];
}

export interface DriveBreadcrumb {
  itemId: string;
  name: string;
}

export interface DriveOpenDescriptor {
  url: string;
  ticket: string;
  expiresAt: number;
  name: string;
  mimeType: string;
  sizeBytes: number;
}
