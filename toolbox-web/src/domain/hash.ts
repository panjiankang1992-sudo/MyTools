/**
 * 一致性哈希工具。
 * contentHash/renderHash 仅用于浏览器内判断结果是否对应当前设置，不作为公开统计标识，
 * 因此使用稳定的快速哈希；产物完整性使用标准 SHA-256。
 */

/** 计算稳定的 64 位 FNV-1a 风格十六进制摘要，同一输入必须得到同一结果。 */
export function stableHash(input: string): string {
  let low = 0x811c9dc5;
  let high = 0x811c9dc5;
  for (let index = 0; index < input.length; index += 1) {
    const code = input.charCodeAt(index);
    low = Math.imul(low ^ code, 16777619) >>> 0;
    high = Math.imul(high ^ (code + index), 16777619) >>> 0;
  }
  return `${low.toString(16).padStart(8, '0')}${high.toString(16).padStart(8, '0')}`;
}

/** 计算标准 SHA-256 十六进制摘要；运行环境缺少 WebCrypto 时抛出明确错误。 */
export async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const subtle = globalThis.crypto?.subtle;
  if (!subtle) throw new Error('CRYPTO_UNAVAILABLE');
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  const digest = await subtle.digest('SHA-256', copy);
  return [...new Uint8Array(digest)].map(value => value.toString(16).padStart(2, '0')).join('');
}
