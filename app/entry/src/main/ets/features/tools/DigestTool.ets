import cryptoFramework from '@ohos.security.cryptoFramework';
import { util } from '@kit.ArkTS';

/**
 * 使用系统密码框架计算本地文本摘要。
 */
export class DigestTool {
  /**
   * 计算UTF-8文本的SHA-256或SHA-512十六进制摘要。
   */
  async digestText(input: string, algorithm: string): Promise<string> {
    if (algorithm !== 'SHA256' && algorithm !== 'SHA512') throw new Error('不支持的摘要算法');
    const bytes = new util.TextEncoder().encodeInto(input);
    try {
      const digest = cryptoFramework.createMd(algorithm);
      await digest.update({ data: bytes });
      const result = await digest.digest();
      return this.toHex(result.data);
    } catch (_) {
      throw new Error('系统密码框架无法完成摘要计算');
    }
  }

  private toHex(bytes: Uint8Array): string {
    let value = '';
    for (let index = 0; index < bytes.length; index++) value += bytes[index].toString(16).padStart(2, '0');
    return value;
  }
}
