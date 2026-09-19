package com.yuyutian.mytools.reader.service.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderShelfChapterProperties;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.SourceLocator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 使用独立凭据挂载中的轮换密钥环保护定位符，缺少密钥时关闭新能力。 */
@Component
public final class SourceLocatorCipher {
    private final Map<String, SecretKeySpec> keys;
    private final String activeKeyId;
    private final SecureRandom random = new SecureRandom();

    /** 加载受限密钥文件，失败只给出固定配置诊断。 */
    @Autowired
    public SourceLocatorCipher(ReaderShelfChapterProperties properties, ObjectMapper mapper) {
        Map<String, byte[]> loaded = new HashMap<>();
        String active = "";
        // 停止准备新章节后，已有历史分页仍需验证旧游标，不能随准备开关丢弃密钥。
        boolean keyringRequired = properties.enabled()
                || (properties.locatorKeyringFile() != null && !properties.locatorKeyringFile().isBlank());
        if (keyringRequired) {
            try {
                Path path = Path.of(properties.locatorKeyringFile());
                if (!path.isAbsolute() || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16384) {
                    throw new IllegalArgumentException();
                }
                var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
                if (permissions.contains(PosixFilePermission.GROUP_READ) || permissions.contains(PosixFilePermission.GROUP_WRITE)
                        || permissions.contains(PosixFilePermission.OTHERS_READ) || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                    throw new IllegalArgumentException();
                }
                try (var stream = Files.newInputStream(path)) {
                    byte[] bytes = stream.readNBytes(16385);
                    if (bytes.length > 16384) {
                        throw new IllegalArgumentException();
                    }
                    var node = mapper.readTree(bytes);
                    active = node.path("activeKeyId").asText("");
                    var fields = node.path("keys").fields();
                    while (fields.hasNext()) {
                        var entry = fields.next();
                        loaded.put(entry.getKey(), Base64.getDecoder().decode(entry.getValue().asText()));
                    }
                }
            } catch (Exception exception) {
                // 不保留可能携带文件路径或密钥片段的外部异常链。
                throw new IllegalStateException("Locator keyring is unavailable");
            }
        }
        this.keys = validatedKeys(active, loaded, keyringRequired);
        this.activeKeyId = active;
    }

    /** 从显式内存密钥创建实例，供隔离夹具和受控依赖注入使用。 */
    public SourceLocatorCipher(String activeKeyId, Map<String, byte[]> keys) {
        this.keys = validatedKeys(activeKeyId, keys, true);
        this.activeKeyId = activeKeyId;
    }

    /** 对定位符加密并将租户、绑定修订和用途纳入 GCM 关联数据。 */
    public SourceLocator.Sealed seal(SourceLocator.Scope scope, SourceLocator.Address address) {
        if (!keys.containsKey(activeKeyId)) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_UNAVAILABLE);
        }
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(scope));
            String ciphertext = "v1." + activeKeyId + "." + encode(nonce) + "."
                    + encode(cipher.doFinal(address.value().getBytes(StandardCharsets.UTF_8)));
            return new SourceLocator.Sealed(ciphertext, address.sha256(), address.scheme(), address.host());
        } catch (GeneralSecurityException exception) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_UNAVAILABLE);
        }
    }

    /** 解密旧或当前密钥版本，并校验摘要，拒绝错租户、重绑或密文篡改。 */
    public String open(SourceLocator.Scope scope, SourceLocator.Sealed sealed) {
        try {
            if (sealed.ciphertext().length() > 2048) {
                throw new IllegalArgumentException();
            }
            String[] parts = sealed.ciphertext().split("\\.", -1);
            if (parts.length != 4 || !"v1".equals(parts[0]) || !keys.containsKey(parts[1])) {
                throw new IllegalArgumentException();
            }
            byte[] nonce = Base64.getUrlDecoder().decode(parts[2]);
            if (nonce.length != 12) {
                throw new IllegalArgumentException();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.get(parts[1]), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(scope));
            String value = new String(cipher.doFinal(Base64.getUrlDecoder().decode(parts[3])), StandardCharsets.UTF_8);
            if (!AdaptationText.sha256(value).equals(sealed.sha256())) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new ChapterAdaptationException(ErrorCode.CHAPTER_SOURCE_CHANGED);
        }
    }

    /** 为有限目录游标签名，签名密钥从主密钥按独立用途派生。 */
    public String signCursor(String payload) {
        if (!keys.containsKey(activeKeyId) || payload == null || payload.length() > 512) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_UNAVAILABLE);
        }
        String encoded = encode(payload.getBytes(StandardCharsets.US_ASCII));
        String signed = "cursor-v1." + activeKeyId + "." + encoded;
        return signed + "." + encode(cursorMac(activeKeyId, signed));
    }

    /** 验证游标签名，业务层还须核对 owner、书架、修订号与期限。 */
    public String verifyCursor(String token) {
        try {
            if (token == null || token.length() > 1024) {
                throw new IllegalArgumentException();
            }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 4 || !"cursor-v1".equals(parts[0]) || !keys.containsKey(parts[1])) {
                throw new IllegalArgumentException();
            }
            String signed = String.join(".", parts[0], parts[1], parts[2]);
            if (!MessageDigest.isEqual(cursorMac(parts[1], signed), Base64.getUrlDecoder().decode(parts[3]))) {
                throw new IllegalArgumentException();
            }
            return new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException exception) {
            throw new ChapterAdaptationException(ErrorCode.CHAPTER_CATALOG_STALE);
        }
    }

    private byte[] cursorMac(String keyId, String value) {
        try {
            Mac derive = Mac.getInstance("HmacSHA256");
            derive.init(new SecretKeySpec(keys.get(keyId).getEncoded(), "HmacSHA256"));
            byte[] key = derive.doFinal("reader-catalog-cursor-key-v1".getBytes(StandardCharsets.US_ASCII));
            Mac signature = Mac.getInstance("HmacSHA256");
            signature.init(new SecretKeySpec(key, "HmacSHA256"));
            return signature.doFinal(value.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_UNAVAILABLE);
        }
    }

    private static byte[] aad(SourceLocator.Scope scope) {
        return AdaptationText.fingerprint("locator-aad-v1", List.of(Long.toString(scope.ownerId()),
                scope.bindingId().toString(), Long.toString(scope.bindingRevision()),
                Integer.toString(scope.sourceVersion()), scope.role())).getBytes(StandardCharsets.US_ASCII);
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Map<String, SecretKeySpec> validatedKeys(String active, Map<String, byte[]> source, boolean required) {
        Map<String, SecretKeySpec> result = new HashMap<>();
        if (source == null || source.size() > 3 || (required && !source.containsKey(active))) {
            throw new IllegalStateException("Invalid locator keyring");
        }
        source.forEach((id, bytes) -> {
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,32}") || bytes == null || bytes.length != 32) {
                throw new IllegalStateException("Invalid locator keyring");
            }
            result.put(id, new SecretKeySpec(bytes, "AES"));
        });
        return Map.copyOf(result);
    }
}
