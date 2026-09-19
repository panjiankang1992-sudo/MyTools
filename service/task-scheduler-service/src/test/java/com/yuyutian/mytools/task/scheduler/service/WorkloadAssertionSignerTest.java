package com.yuyutian.mytools.task.scheduler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.scheduler.config.WorkloadAuthorizationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 密钥文件仅在 JUnit 临时目录随机生成，测试后由框架清理，不引入仓库私钥。 */
class WorkloadAssertionSignerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir private Path directory;

    @Test
    void shouldLoadOwnerOnlyKeyringAndPublishOnlyPublicRotationKeys() throws Exception {
        KeyPair current = key();
        KeyPair previous = key();
        Path path = file(Map.of("activeKeyId", "new", "keys", List.of(encoded("new", current, true), encoded("old", previous, false))));
        var signer = new WorkloadAssertionSigner(properties(path), mapper);
        String publicJson = mapper.writeValueAsString(signer.jwks());
        assertThat(publicJson).contains("\"kid\":\"new\"", "\"kid\":\"old\"", "\"alg\":\"Ed25519\"")
                .doesNotContain("privateKey", Base64.getEncoder().encodeToString(current.getPrivate().getEncoded()), "\"d\"");
    }

    @Test
    void shouldRejectGroupReadableAndSymlinkSigningFiles() throws Exception {
        Path path = file(Map.of("activeKeyId", "fixture", "keys", List.of(encoded("fixture", key(), true))));
        Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));
        expectUnavailable(path);
        Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ));
        Path link = directory.resolve("keyring-link");
        Files.createSymbolicLink(link, path);
        expectUnavailable(link);
    }

    @Test
    void shouldRejectDuplicateTrailingUnknownAndOversizedConfiguration() throws Exception {
        Path path = file(Map.of("activeKeyId", "fixture", "keys", List.of(encoded("fixture", key(), true))));
        String valid = Files.readString(path);
        for (String invalid : List.of(valid + " {}", valid.replace("{", "{\"activeKeyId\":\"wrong\","),
                "{\"activeKeyId\":\"fixture\",\"keys\":[],\"secret\":\"sentinel-private-content\"}", " ".repeat(32769), "null")) {
            Files.writeString(path, invalid);
            expectUnavailable(path);
        }
    }

    @Test
    void shouldRejectMissingActivePrivateKeyWrongPairAndUnsupportedCurve() throws Exception {
        KeyPair first = key();
        KeyPair second = key();
        assertThatThrownBy(() -> new WorkloadAssertionSigner("first", Map.of("first", new KeyPair(first.getPublic(), null)), mapper))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkloadAssertionSigner("first", Map.of("first", new KeyPair(first.getPublic(), second.getPrivate())), mapper))
                .isInstanceOf(IllegalArgumentException.class);
        var otherCurve = KeyPairGenerator.getInstance("Ed448").generateKeyPair();
        assertThatThrownBy(() -> new WorkloadAssertionSigner("first", Map.of("first", otherCurve), mapper))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectAnyAdditionalOrMissingClaimBeforeSigning() throws Exception {
        var signer = new WorkloadAssertionSigner("fixture", Map.of("fixture", key()), mapper);
        assertThatThrownBy(() -> signer.sign(Map.of("ownerId", 41, "intent", "sentinel-private-content")))
                .isInstanceOf(com.yuyutian.mytools.task.scheduler.common.SchedulerException.class)
                .hasMessage("Workload signing is unavailable");
    }

    @Test
    void shouldDisableSigningWithoutLoadingAnyUnconfiguredFile() {
        var properties = new WorkloadAuthorizationProperties(false, "", "mytools-task-scheduler", 60, 5, Map.of(), Set.of());
        var signer = new WorkloadAssertionSigner(properties, mapper);
        assertThatThrownBy(signer::jwks).isInstanceOf(com.yuyutian.mytools.task.scheduler.common.SchedulerException.class);
    }

    private static KeyPair key() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static Map<String, String> encoded(String kid, KeyPair pair, boolean includePrivate) {
        if (includePrivate) {
            return Map.of("kid", kid, "publicKey", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                    "privateKey", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        }
        return Map.of("kid", kid, "publicKey", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    }

    private Path file(Map<String, Object> value) throws Exception {
        Path path = directory.resolve("keyring.json");
        Files.writeString(path, mapper.writeValueAsString(value));
        Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        return path;
    }

    private static WorkloadAuthorizationProperties properties(Path path) {
        return new WorkloadAuthorizationProperties(true, path.toString(), "mytools-task-scheduler", 60, 5,
                Map.of("fixture-node", "spiffe://fixture.test/executor"), Set.of("spiffe://fixture.test/reader"));
    }

    private void expectUnavailable(Path path) {
        assertThatThrownBy(() -> new WorkloadAssertionSigner(properties(path), mapper))
                .isInstanceOf(IllegalStateException.class).hasMessage("Workload signing keyring is unavailable")
                .hasNoCause();
    }
}
