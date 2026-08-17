import cryptoFramework from '@ohos.security.cryptoFramework';
import { util } from '@kit.ArkTS';
import { LanBootstrap, LanChallenge } from './ConnectivityModels';

/**
 * 使用可信公网接口交付的短期密钥验证局域网服务证明。
 */
export class LanProofVerifier {
  /** 验证挑战内容、服务实例和HMAC证明。 */
  verify(bootstrap: LanBootstrap, challenge: LanChallenge, nonce: string): boolean {
    if (challenge.instanceId !== bootstrap.instanceId || challenge.apiVersion !== bootstrap.apiVersion ||
      challenge.nonce !== nonce || challenge.expiresAt !== bootstrap.expiresAt || challenge.expiresAt <= Date.now()) {
      return false;
    }
    let key: cryptoFramework.SymKey | undefined = undefined;
    try {
      const helper = new util.Base64Helper();
      const keyBytes = helper.decodeSync(bootstrap.proofKey, util.Type.BASIC_URL_SAFE);
      key = cryptoFramework.createSymKeyGenerator('HMAC').convertKeySync({ data: keyBytes });
      const mac = cryptoFramework.createMac('SHA256');
      mac.initSync(key);
      const message = `${challenge.instanceId}\n${nonce}\n${challenge.apiVersion}\n${challenge.expiresAt}`;
      mac.updateSync({ data: new util.TextEncoder().encodeInto(message) });
      const expected = helper.encodeToStringSync(mac.doFinalSync().data, util.Type.BASIC_URL_SAFE).replace(/=+$/, '');
      return this.constantTimeEquals(expected, challenge.proof);
    } catch (_) {
      return false;
    } finally {
      key?.clearMem();
    }
  }

  private constantTimeEquals(left: string, right: string): boolean {
    if (left.length !== right.length) return false;
    let difference = 0;
    for (let index = 0; index < left.length; index++) difference |= left.charCodeAt(index) ^ right.charCodeAt(index);
    return difference === 0;
  }
}
