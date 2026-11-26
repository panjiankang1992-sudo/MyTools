import { ArchivePassword } from './ArchiveModels';

/** 历史密码只进行有限的候选匹配，不生成或猜测新密码。 */
export class ArchivePasswordPolicy {
  /** 按关联成功、最近成功和其他历史排序，并保留原始空格。 */
  static candidates(records: ArchivePassword[], manual: string, fingerprint: string): ArchivePassword[] {
    const ordered = records.slice().sort((a: ArchivePassword, b: ArchivePassword) => {
      const rankA = a.successes > 0 ? (a.fingerprint === fingerprint ? 2 : 1) : 0;
      const rankB = b.successes > 0 ? (b.fingerprint === fingerprint ? 2 : 1) : 0;
      return rankB - rankA || b.lastUsedAt - a.lastUsedAt;
    });
    if (manual.length > 0) {
      const existing = records.find((item: ArchivePassword) => item.value === manual);
      ordered.unshift(existing ?? { id: '', value: manual, createdAt: 0, lastUsedAt: 0, attempts: 0,
        successes: 0, fingerprint: '' });
    }
    const seen = new Set<string>();
    return ordered.filter((item: ArchivePassword) => {
      // 同一候选在一次匹配队列中最多出现一次。
      if (item.value.length === 0 || seen.has(item.value)) return false;
      seen.add(item.value);
      return true;
    });
  }
}
