package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuyutian.mytools.toolbox.config.AdminProperties;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminTokenCipher;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 上游令牌加密的契约测试。
 * 覆盖：往返一致、每次密文不同、未配置密钥时失败关闭、密钥长度非法时失败关闭。
 */
class AdminTokenCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    /** 构造只指定密钥的配置。 */
    private static AdminProperties properties(String key) {
        return new AdminProperties(true, "http://127.0.0.1:1", "t", 1000L, 30L, 8L, "cookie", "login-cookie", false, "X-Csrf", key, 4194304L, 10L, 300L);
    }

    @Test
    @DisplayName("同一明文可往返，且两次密文不同（随机 IV）")
    void roundTripUsesRandomIv() {
        AdminTokenCipher cipher = new AdminTokenCipher(properties(KEY));
        String first = cipher.encrypt("upstream-access-token");
        String second = cipher.encrypt("upstream-access-token");
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("upstream-access-token");
        assertThat(cipher.decrypt(second)).isEqualTo("upstream-access-token");
    }

    @Test
    @DisplayName("空令牌返回 null，不写入占位密文")
    void emptyTokenStaysNull() {
        AdminTokenCipher cipher = new AdminTokenCipher(properties(KEY));
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.encrypt("")).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    @DisplayName("未配置密钥时不可用，且加密失败关闭而不是明文落库")
    void missingKeyFailsClosed() {
        AdminTokenCipher cipher = new AdminTokenCipher(properties(""));
        assertThat(cipher.isConfigured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("secret-token")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("密钥长度非法时按未配置处理")
    void invalidKeyLengthFailsClosed() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[8]);
        AdminTokenCipher cipher = new AdminTokenCipher(properties(shortKey));
        assertThat(cipher.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("密文被篡改时解密失败，不返回部分明文")
    void tamperedCiphertextIsRejected() {
        AdminTokenCipher cipher = new AdminTokenCipher(properties(KEY));
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt("original-token"));
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }
}
