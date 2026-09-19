package com.yuyutian.mytools.reader.service.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderShelfChapterProperties;
import com.yuyutian.mytools.reader.model.adaptation.SourceLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceLocatorSecurityTest {
    @TempDir
    Path directory;

    @Test
    void shouldAuthenticateOwnerBindingRevisionPurposeAndPayloadAcrossKeyRotation() {
        byte[] firstKey = key();
        byte[] secondKey = key();
        var first = new SourceLocatorCipher("first", Map.of("first", firstKey));
        var scope = new SourceLocator.Scope(41, UUID.randomUUID(), 1, 3, "CHAPTER");
        var address = new SourceLocator.Address("https://example.invalid/chapter?signature=private", "https", "example.invalid",
                AdaptationText.sha256("https://example.invalid/chapter?signature=private"));
        var encrypted = first.seal(scope, address);
        assertThat(encrypted.ciphertext()).doesNotContain("signature", "example.invalid", "private");
        assertThat(first.seal(scope, address).ciphertext()).isNotEqualTo(encrypted.ciphertext());
        var rotated = new SourceLocatorCipher("second", Map.of("first", firstKey, "second", secondKey));
        assertThat(rotated.open(scope, encrypted)).isEqualTo(address.value());
        assertThatThrownBy(() -> rotated.open(new SourceLocator.Scope(42, scope.bindingId(), 1, 3, "CHAPTER"), encrypted))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> rotated.open(new SourceLocator.Scope(41, scope.bindingId(), 2, 3, "CHAPTER"), encrypted))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> rotated.open(new SourceLocator.Scope(41, scope.bindingId(), 1, 3, "BOOK"), encrypted))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> rotated.open(scope, new SourceLocator.Sealed(encrypted.ciphertext(), "b".repeat(64), "https", "example.invalid")))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> new SourceLocatorCipher("second", Map.of("second", secondKey)).open(scope, encrypted))
                .isInstanceOf(ChapterAdaptationException.class);
    }

    @Test
    void shouldRejectForgedCursorAndSupportOldKeyDuringRotation() {
        byte[] firstKey = key();
        var first = new SourceLocatorCipher("first", Map.of("first", firstKey));
        String cursor = first.signCursor("41:shelf:1:2:3:9999999999");
        var rotated = new SourceLocatorCipher("second", Map.of("first", firstKey, "second", key()));
        assertThat(rotated.verifyCursor(cursor)).isEqualTo("41:shelf:1:2:3:9999999999");
        String changed = cursor.substring(0, cursor.length() - 5) + "other";
        assertThatThrownBy(() -> rotated.verifyCursor(changed)).isInstanceOf(ChapterAdaptationException.class);
    }

    @Test
    void shouldLoadOnlyOwnerReadableKeyringAndStayUnconfiguredByDefault() throws Exception {
        Path path = directory.resolve("keyring.json");
        var mapper = new ObjectMapper();
        Files.writeString(path, mapper.writeValueAsString(Map.of("activeKeyId", "key1",
                "keys", Map.of("key1", Base64.getEncoder().encodeToString(key())))));
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        var properties = new ReaderShelfChapterProperties(true, true, path.toString(), 180, 3, 2);
        var loaded = new SourceLocatorCipher(properties, mapper);
        assertThat(loaded.verifyCursor(loaded.signCursor("fixture"))).isEqualTo("fixture");
        var historyOnly = new SourceLocatorCipher(new ReaderShelfChapterProperties(false, false, path.toString(), 180, 3, 2), mapper);
        assertThat(historyOnly.verifyCursor(loaded.signCursor("history-fixture"))).isEqualTo("history-fixture");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> new SourceLocatorCipher(properties, mapper))
                .isInstanceOf(IllegalStateException.class).hasMessage("Locator keyring is unavailable");
        var disabled = new SourceLocatorCipher(new ReaderShelfChapterProperties(false, false, "", 180, 3, 2), mapper);
        assertThatThrownBy(() -> disabled.signCursor("fixture")).isInstanceOf(ChapterAdaptationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.1", "169.254.169.254", "100.64.0.1",
            "198.18.0.1", "192.0.2.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "::1", "fc00::1", "fe80::1", "2001:db8::1"})
    void shouldRejectNonPublicResolvedAddressEvenForPlausibleHostname(String address) throws Exception {
        var policy = resolved(InetAddress.getByName(address));
        assertThatThrownBy(() -> policy.validate("https://public-looking.example.invalid/chapter"))
                .isInstanceOf(ChapterAdaptationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///book", "https://user:password@example.invalid/book", "https://example.invalid:8443/book",
            "https://localhost/book", "https://host.local/book", "https://example.invalid/book#fragment", "http://2130706433/book"})
    void shouldRejectUncontrolledLocatorSyntax(String locator) throws Exception {
        var policy = resolved(InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34}));
        assertThatThrownBy(() -> policy.validate(locator)).isInstanceOf(ChapterAdaptationException.class);
    }

    @Test
    void shouldNormalizeAuthorityButKeepSignedQueryBytesAndRejectMixedDnsAnswers() throws Exception {
        var publicAddress = InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34});
        var policy = resolved(publicAddress);
        assertThat(policy.validate("HTTPS://Example.Invalid.:443/a%2Fb?token=a%2Bb&order=2").value())
                .isEqualTo("https://example.invalid/a%2Fb?token=a%2Bb&order=2");
        var mixed = resolved(publicAddress, InetAddress.getByAddress(new byte[]{10, 0, 0, 1}));
        assertThatThrownBy(() -> mixed.validate("https://example.invalid/book")).isInstanceOf(ChapterAdaptationException.class);
    }

    private SourceLocatorPolicy resolved(InetAddress... addresses) {
        return new SourceLocatorPolicy() {
            /** 使用固定解析结果，测试不访问真实 DNS。 */
            @Override
            protected InetAddress[] resolve(String host) throws UnknownHostException {
                return addresses;
            }
        };
    }

    private byte[] key() {
        byte[] value = new byte[32];
        new java.security.SecureRandom().nextBytes(value);
        return value;
    }
}
