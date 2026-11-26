package com.yuyutian.mytools.messaging.utils.outbound;

import com.yuyutian.mytools.messaging.config.OutboundProperties;
import com.yuyutian.mytools.messaging.model.ErrorCode;
import com.yuyutian.mytools.messaging.model.OutboundModels.Content;
import com.yuyutian.mytools.messaging.service.OutboundException;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

/** 凭据分域、目的地密文和跨服务可复算的内容摘要。 */
@Component
public class OutboundSecurity {
    private final OutboundProperties properties;
    private final SecureRandom random = new SecureRandom();
    /** 注入服务端凭据配置。 */
    public OutboundSecurity(OutboundProperties properties) { this.properties = properties; }

    /** 三个令牌必须独立且至少三十二字符，关闭状态下也不接受空凭据。 */
    public void authorize(String authorization, String role) {
        var tokens = java.util.List.of(properties.getClientToken(), properties.getWorkerToken(), properties.getAdminToken());
        if (tokens.stream().anyMatch(t -> t.length() < 32) || Set.copyOf(tokens).size() != 3) {
            throw new OutboundException(ErrorCode.OUTBOUND_DISABLED);
        }
        String expected = switch (role) {
            case "client" -> tokens.get(0);
            case "worker" -> tokens.get(1);
            case "admin" -> tokens.get(2);
            default -> "";
        };
        if (authorization == null || expected.isEmpty() || !MessageDigest.isEqual(
                ("Bearer " + expected).getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8))) {
            throw new OutboundException(ErrorCode.INTERNAL_UNAUTHORIZED);
        }
    }

    /** AES-GCM 的附加数据绑定目的地和所有者，数据库换行不能转移凭据。 */
    public String encrypt(String plain, String context) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] cipher = crypt(Cipher.ENCRYPT_MODE, plain.getBytes(StandardCharsets.UTF_8), nonce, context);
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + cipher.length).put(nonce).put(cipher).array());
    }

    /** 解密失败时只返回稳定配置错误。 */
    public String decrypt(String encoded, String context) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length < 29) throw new IllegalArgumentException();
            return new String(crypt(Cipher.DECRYPT_MODE, java.util.Arrays.copyOfRange(bytes, 12, bytes.length),
                    java.util.Arrays.copyOf(bytes, 12), context), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) { throw new OutboundException(ErrorCode.OUTBOUND_DISABLED); }
    }

    private byte[] crypt(int mode, byte[] value, byte[] nonce, String context) {
        try {
            byte[] key = Base64.getDecoder().decode(properties.getEncryptionKey());
            if (key.length != 32) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(value);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new OutboundException(ErrorCode.OUTBOUND_DISABLED);
        }
    }

    /** 另存严格幂等摘要，正文释放后仍区分空 HTML 与缺省 HTML。 */
    public static String identityHash(Content content) { return identityHash(hash(content),content.html()!=null); }

    /** 从冻结摘要和 HTML 存在位生成同一严格身份，支持不再持有正文的取消。 */
    public static String identityHash(String payloadHash,boolean htmlPresent) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            digest.update("outbound-content-identity-v1".getBytes(StandardCharsets.UTF_8));
            digest.update((byte)(htmlPresent?1:0));digest.update(HexFormat.of().parseHex(payloadHash));
            return HexFormat.of().formatHex(digest.digest());
        } catch(GeneralSecurityException exception) {throw new IllegalStateException("Outbound identity hash failed");}
    }

    /** 对三个 UTF-8 字段依次添加四字节大端长度后计算 SHA-256，空 HTML 按空字符串计算。 */
    public static String hash(Content content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : java.util.List.of(content.subject(), content.text(), content.html() == null ? "" : content.html())) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (GeneralSecurityException exception) { throw new IllegalStateException(exception); }
    }
}
