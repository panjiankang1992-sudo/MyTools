/**
 * 确定性随机工具：同一 seed 与同一输入顺序必须得到完全一致的结果。
 * 算法版本与 seed 一起写入题单快照，保证预览、导出与答案页一致。
 */

/** 稳定的随机算法版本号，改变实现时必须同时更新。 */
export const SHUFFLE_ALGORITHM = 'seeded-shuffle-v1';

/** 归一化空白与 Unicode 组合字符，保持字母大小写与读音。 */
export function normalizeText(value: string): string {
  return value.normalize('NFC').trim().replace(/\s+/gu, ' ');
}

/**
 * 由字符串 seed 生成 32 位随机源。
 * 使用 FNV-1a 播种与 xorshift 混合，保证跨设备结果一致。
 */
export function createSeededRandom(seed: string): () => number {
  let state = 2166136261;
  for (const character of seed) {
    state = Math.imul(state ^ (character.codePointAt(0) ?? 0), 16777619) >>> 0;
  }
  return () => {
    state += 0x6d2b79f5;
    let value = Math.imul(state ^ (state >>> 15), 1 | state);
    value ^= value + Math.imul(value ^ (value >>> 7), 61 | value);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
}

/** 使用给定随机源进行无放回洗牌，返回新数组，不修改入参。 */
export function shuffleWith<T>(items: readonly T[], random: () => number): T[] {
  const result = items.slice();
  for (let index = result.length - 1; index > 0; index -= 1) {
    const target = Math.floor(random() * (index + 1));
    const temporary = result[index]!;
    result[index] = result[target]!;
    result[target] = temporary;
  }
  return result;
}

/** 为分组派生稳定 seed，使同一主 seed 下各组抽样互不相同且可复现。 */
export function deriveSeed(seed: string, scope: string): string {
  return `${seed}::${scope}`;
}

/**
 * 计算均衡抽取的确定性配额。
 * 先按非空组均分，容量不足的组释放配额，再按组顺序把剩余配额分给仍有余量的组。
 */
export function balancedQuota(capacities: readonly number[], requested: number): number[] {
  const groups = capacities.length;
  if (!groups || requested <= 0) return capacities.map(() => 0);
  const total = capacities.reduce((sum, value) => sum + value, 0);
  let remaining = Math.min(Math.floor(requested), total);
  const quota = new Array<number>(groups).fill(0);
  const base = Math.floor(remaining / groups);
  for (let index = 0; index < groups; index += 1) {
    // 先分配均分额度，容量不足的组只能拿到自身容量。
    quota[index] = Math.min(base, capacities[index]!);
    remaining -= quota[index]!;
  }
  // 剩余额度按组顺序轮流分配，避免余数总落到同一组。
  let guard = remaining + groups;
  while (remaining > 0 && guard > 0) {
    let progressed = false;
    for (let index = 0; index < groups && remaining > 0; index += 1) {
      if (quota[index]! < capacities[index]!) {
        quota[index] = quota[index]! + 1;
        remaining -= 1;
        progressed = true;
      }
    }
    if (!progressed) break;
    guard -= 1;
  }
  return quota;
}
