import { ArchiveEntry } from './ArchiveModels';

export interface ArchiveTreeRow {
  path: string;
  name: string;
  depth: number;
  directory: boolean;
  ids: number[];
  size: number;
  encrypted: boolean;
}

/** 根据真实条目补齐隐式文件夹，统一树选择与导出路径规则。 */
export class ArchiveTree {
  /** 补齐父目录并按目录层级排序，不改变原始条目。 */
  static build(entries: ArchiveEntry[]): ArchiveTreeRow[] {
    const rows = new Map<string, ArchiveTreeRow>();
    for (const entry of entries) {
      const parts = entry.path.split('/');
      for (let index = 0; index < parts.length; index++) {
        const path = parts.slice(0, index + 1).join('/');
        let row = rows.get(path);
        if (!row) {
          row = { path, name: parts[index], depth: index, directory: index < parts.length - 1 || entry.directory,
            ids: [], size: 0, encrypted: false };
          rows.set(path, row);
        }
        row.ids.push(entry.id);
        row.size += entry.directory ? 0 : entry.size;
        row.encrypted = row.encrypted || entry.encrypted;
      }
    }
    return Array.from(rows.values()).sort((left: ArchiveTreeRow, right: ArchiveTreeRow) => {
      const a = left.path.split('/'); const b = right.path.split('/');
      for (let i = 0; i < Math.min(a.length, b.length); i++) {
        if (a[i] !== b[i]) return a[i].localeCompare(b[i]);
      }
      return a.length - b.length;
    });
  }

  /** 搜索时显示命中路径及祖先，平时仅显示已展开的分支。 */
  static visible(rows: ArchiveTreeRow[], expanded: string[], query: string): ArchiveTreeRow[] {
    const term = query.toLowerCase();
    const matches = rows.filter((row: ArchiveTreeRow) => row.path.toLowerCase().includes(term));
    const visible = new Set<string>();
    if (term.length > 0) {
      for (const row of matches) {
        const parts = row.path.split('/');
        for (let i = 1; i <= parts.length; i++) visible.add(parts.slice(0, i).join('/'));
      }
    }
    return rows.filter((row: ArchiveTreeRow) => {
      if (term.length > 0) return visible.has(row.path);
      const parts = row.path.split('/');
      for (let i = 1; i < parts.length; i++) if (!expanded.includes(parts.slice(0, i).join('/'))) return false;
      return true;
    });
  }

  /** 文件夹选择作用于完整分支，搜索不会造成隐藏文件被遗漏。 */
  static select(selected: number[], row: ArchiveTreeRow, checked: boolean): number[] {
    const result = new Set<number>(selected);
    for (const id of row.ids) { if (checked) result.add(id); else result.delete(id); }
    return Array.from(result);
  }

  /** 校验持久结果中的相对路径，拒绝路径逃逸。 */
  static safePath(path: string): string {
    if (path.length === 0 || path.length > 2048 || /[\\:\u0000-\u001f]/.test(path) ||
      path.split('/').some((part: string) => part.length === 0 || part === '.' || part === '..')) throw new Error('ARCHIVE_009');
    return path;
  }

  /** 手机批量导出使用唯一序号和短文件名，避免跨目录同名覆盖。 */
  static exportNames(paths: string[]): string[] {
    return paths.map((path: string, index: number) => {
      const name = ArchiveTree.safePath(path).split('/').pop() || 'file';
      const cleaned = name.replace(/[?*"<>|]/g, '_');
      const dot = cleaned.lastIndexOf('.');
      const suffix = dot > 0 ? cleaned.substring(dot).slice(0, 16) : '';
      const stem = dot > 0 ? cleaned.substring(0, dot) : cleaned;
      return `${index + 1}-${Array.from(stem).slice(0, 45).join('')}${suffix}`;
    });
  }
}
