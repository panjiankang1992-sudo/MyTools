export interface ArchiveEntry {
  id: number;
  path: string;
  size: number;
  directory: boolean;
  encrypted: boolean;
}

export interface ArchiveResult {
  code: string;
  format: string;
  entries: ArchiveEntry[];
  bytes: number;
  passwordUsed: boolean;
}

export interface ArchiveProgress {
  bytes: number;
  total: number;
  running: boolean;
}

export interface ArchivePassword {
  id: string;
  value: string;
  createdAt: number;
  lastUsedAt: number;
  attempts: number;
  successes: number;
  fingerprint: string;
}

export interface ArchivePasswordSummary {
  id: string;
  lastUsedAt: number;
  attempts: number;
  successes: number;
}

/** 解压错误码与界面资源之间的映射。 */
export class ArchiveErrors {
  /** 将引擎错误转换为本地化资源名称。 */
  static resource(code: string): string {
    const supported = ['ARCHIVE_001', 'ARCHIVE_002', 'ARCHIVE_003', 'ARCHIVE_004', 'ARCHIVE_005',
      'ARCHIVE_006', 'ARCHIVE_007', 'ARCHIVE_008', 'ARCHIVE_009', 'ARCHIVE_010', 'ARCHIVE_011', 'ARCHIVE_012'];
    return supported.includes(code) ? code.toLowerCase() : 'archive_011';
  }
}
